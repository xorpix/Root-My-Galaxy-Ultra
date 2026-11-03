/* SPDX-License-Identifier: GPL-2.0-only */
#include "tests/module_control/host_kernel.h"
#undef SUSFS_LOGI
#define SUSFS_LOGI(...) do { if (0) fprintf(stderr,__VA_ARGS__); } while (0)
#define mount_keep_lock hide_modules_lock
static bool mount_registered;
static int sus_mount_refresh(void) { return 0; }
#include PRODUCTION
int main(int argc,char **argv) {
    assert(argc==2);
    assert(!sus_mount_keep_command("/data/local/custom/",true));
    assert(n_mount_keep==1 && !strcmp(mount_keep[0],"/data/local/custom/"));
    if (!strcmp(argv[1],"live-setter")) {puts("PASS mount-prefix setter");return 0;}
    audit_mount_init_defaults();
    assert(n_mount_keep==1 && !strcmp(mount_keep[0],"/data/local/custom/") &&
        "module initialization discarded the supplied hide_mounts parameter");
    puts("PASS load-time mount-prefix preserved");return 0;
}
