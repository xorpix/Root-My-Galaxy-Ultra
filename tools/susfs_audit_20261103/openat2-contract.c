/* SPDX-License-Identifier: GPL-2.0-only */
/* Reuse the pinned source tree's kernel-API model, retaining the production
 * wrapper and adding independent assertions about the descriptor and scope. */
#ifndef E2BIG
#define E2BIG 7
#endif
#ifndef EOPNOTSUPP
#define EOPNOTSUPP 95
#endif
#define main existing_entry_test_main
#include "tests/redirect_entry/entry_test.c"
#undef main
int main(int argc,char **argv) {
    assert(argc==2);
    g_match="/outside/visible-file";wire();
    struct pt_regs regs={0};
    int kind=SUSFS_OR_ENTRY_OPENAT2;
    if (!strcmp(argv[1],"compat-openat2-cloexec"))kind=SUSFS_OR_ENTRY_COMPAT_OPENAT2;
    regs.regs[0]=42;regs.regs[1]=0x1000;
    if (!strcmp(argv[1],"openat-cloexec")) {
        kind=SUSFS_OR_ENTRY_OPENAT;regs.regs[2]=O_RDONLY|O_CLOEXEC;
    } else {
        g_how=(struct susfs_open_how){.flags=O_RDONLY|O_CLOEXEC};
        if (!strcmp(argv[1],"resolve-beneath"))g_how.resolve=SUSFS_RESOLVE_BENEATH;
        if (!strcmp(argv[1],"resolve-in-root"))g_how.resolve=SUSFS_RESOLVE_IN_ROOT;
        regs.regs[2]=(unsigned long)&g_how;regs.regs[3]=sizeof(g_how);
        if (!strcmp(argv[1],"oversize-how"))regs.regs[3]=4097;
    }
    long result=or_entry_redirect(&regs,kind);
    if (!strcmp(argv[1],"oversize-how")) {
        assert((result==SUSFS_OR_ENTRY_PASS || result==-E2BIG) && !g_install_called &&
            "openat2 accepted open_how larger than PAGE_SIZE");
    } else if (g_how.resolve) {
        /* The source lookup succeeds under the requested dirfd scope in
         * the model. Opening an absolute redirect via global filp_open()
         * cannot preserve BENEATH/IN_ROOT. With no scoped destination API,
         * the wrapper must defer to the native syscall or reject safely. */
        assert((result==SUSFS_OR_ENTRY_PASS || result<0) && !g_install_called &&
            "openat2 installed an unscoped absolute redirect descriptor");
    } else {
        assert(result==7 && g_install_called);
        assert((g_getfd_flags&O_CLOEXEC) && "openat2 lost close-on-exec on its descriptor");
        assert(!(g_filp_flags&O_CLOEXEC));
    }
    puts("PASS openat2 descriptor/scope contract");return 0;
}
