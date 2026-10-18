#define _GNU_SOURCE
#include <assert.h>
#include <poll.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include "orphan_wait.h"

struct proof { pid_t child, parent; int adopted; int64_t elapsed; };

static void reparent_case(int delay_ms) {
    pid_t adopter = getpid();
    int release[2], result[2];
    assert(pipe(release) == 0 && pipe(result) == 0);
    pid_t intermediate = fork();
    assert(intermediate >= 0);
    if (intermediate == 0) {
        pid_t child = fork();
        if (child < 0) _exit(2);
        if (child == 0) {
            close(release[0]); close(release[1]); close(result[0]);
            int64_t start = df_orphan_now_ms();
            int adopted = df_wait_for_adopter(adopter);
            struct proof p = {getpid(), getppid(), adopted, df_orphan_now_ms() - start};
            if (write(result[1], &p, sizeof(p)) != sizeof(p)) _exit(3);
            _exit(0);
        }
        close(result[0]); close(result[1]); close(release[1]);
        char byte;
        if (read(release[0], &byte, 1) != 1) _exit(4);
        _exit(0);
    }
    close(release[0]); close(result[1]);
    df_orphan_sleep_ms(delay_ms);
    assert(write(release[1], "x", 1) == 1);
    close(release[1]);
    int status;
    assert(waitpid(intermediate, &status, 0) == intermediate && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    struct pollfd fd = {.fd = result[0], .events = POLLIN};
    assert(poll(&fd, 1, 1500) > 0);
    struct proof p;
    assert(read(result[0], &p, sizeof(p)) == sizeof(p));
    close(result[0]);
    assert(p.adopted && p.parent == adopter && p.elapsed < 1000);
    assert(waitpid(p.child, &status, 0) == p.child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

int main(void) {
    assert(prctl(PR_SET_CHILD_SUBREAPER, 1) == 0);
    reparent_case(0);
    reparent_case(40);
    reparent_case(100);
    int64_t start = df_orphan_now_ms();
    assert(!df_wait_for_adopter(getpid()));
    assert(df_orphan_now_ms() - start >= 1000);
    puts("Orphan handoff: PASS (3 real reparenting cases and one-second fallback)");
}
