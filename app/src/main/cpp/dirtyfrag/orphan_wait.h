#pragma once
#include <errno.h>
#include <stdint.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

static inline int64_t df_orphan_now_ms(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now)) return -1;
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static inline void df_orphan_sleep_ms(int64_t millis) {
    struct timespec remaining = {.tv_sec = millis / 1000, .tv_nsec = (millis % 1000) * 1000000};
    while (nanosleep(&remaining, &remaining) && errno == EINTR) {}
}

/** Wait for init adoption, with the original one-second delay as a bounded fallback. */
static inline int df_wait_for_adopter(pid_t adopter) {
    const int64_t limit = 1000;
    int64_t start = df_orphan_now_ms(), elapsed = 0;
    if (start < 0) {
        df_orphan_sleep_ms(limit);
        return 0;
    }
    while (1) {
        int64_t now = df_orphan_now_ms();
        if (now < start + elapsed) {
            df_orphan_sleep_ms(limit - elapsed);
            return 0;
        }
        elapsed = now - start;
        if (getppid() == adopter) return 1;
        if (elapsed >= limit) return 0;
        int64_t pause = limit - elapsed;
        if (pause > 2) pause = 2;
        df_orphan_sleep_ms(pause);
    }
}
