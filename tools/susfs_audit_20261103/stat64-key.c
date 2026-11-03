/* SPDX-License-Identifier: GPL-2.0-only */
#include "tests/feature_hooks/host_kernel.h"
#include <stddef.h>
#include <sys/types.h>
#include "kernel/susfs_abi.h"
#define __user
#define DEFINE_MUTEX(n) int n
#define DEFINE_SPINLOCK(n) int n
#define spin_lock_irqsave(p,f) do { (void)(p); (f)=0; } while(0)
#define spin_unlock_irqrestore(p,f) do { (void)(p); (void)(f); } while(0)
static bool susfs_is_current_proc_umounted_app(void) { return true; }
static unsigned char buffer[104];
static void check_range(const void *p,size_t size) {
    assert((uintptr_t)p>=(uintptr_t)buffer && size<=sizeof(buffer) &&
        (uintptr_t)p-(uintptr_t)buffer<=sizeof(buffer)-size);
}
static int copy_from_user(void *to,const void *from,size_t size) {check_range(from,size);memcpy(to,from,size);return 0;}
static int copy_to_user(void *to,const void *from,size_t size) {check_range(to,size);memcpy(to,from,size);return 0;}
#include PRODUCTION
int main(int argc,char **argv) {
    assert(argc==2);
    unsigned long ino=!strcmp(argv[1],"high-inode")?0x10000002aUL:42;
    unsigned long long full=ino,device=65105;
    unsigned int truncated=(unsigned int)ino;
    /* Linux/ACK cp_new_stat64 fills BOTH fields, not only the legacy field. */
    memcpy(buffer+STAT64_ST_DEV_OFF,&device,sizeof(device));
    memcpy(buffer+STAT64_ST_BROKEN_INO_OFF,&truncated,sizeof(truncated));
    memcpy(buffer+STAT64_ST_INO_OFF,&full,sizeof(full));
    nkstat=1;
    kstat_entries[0]=(struct sus_kstat_entry){.target_ino=ino,.target_dev=device,
        .spoofed_ino=99,.flags=KSTAT_SPOOF_INO};
    struct kstat_call_counters cnt={0};
    struct kstat_call_state state={.cnt=&cnt,.compat=true};
    assert(susfs_kstat_spoof_compat_statbuf(&state,(unsigned long)buffer) &&
        "stat64 failed to match the full 64-bit inode key");
    memcpy(&full,buffer+STAT64_ST_INO_OFF,sizeof(full));assert(full==99);
    puts("PASS stat64 full inode key");return 0;
}
