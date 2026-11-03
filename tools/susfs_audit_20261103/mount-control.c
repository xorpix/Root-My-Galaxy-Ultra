/* SPDX-License-Identifier: GPL-2.0-only */
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#define READ_ONCE(x) (x)
#define WRITE_ONCE(x,v) ((x)=(v))
#define __user
#define pr_warn(...) ((void)0)
#define SUSFS_LOGI(...) ((void)0)
#define KERNEL_VERSION(a,b,c) (((a)<<16)|((b)<<8)|(c))
#define LINUX_VERSION_CODE KERNEL_VERSION(6,12,0)
#define DEFINE_MUTEX(n) pthread_mutex_t n=PTHREAD_MUTEX_INITIALIZER
#define mutex_lock(p) assert(!pthread_mutex_lock(p))
#define mutex_unlock(p) assert(!pthread_mutex_unlock(p))
static DEFINE_MUTEX(sus_mount_config_lock);
static bool sus_mount_exiting;
struct kprobe { const char *symbol_name; void *addr; unsigned flags; bool live; };
struct kretprobe { struct kprobe kp; };
static struct kprobe kp_sb_down={.symbol_name="sb"},kp_mnt_free={.symbol_name="mnt"},
    kp_vfsstat={.symbol_name="vfsstat"},kp_mountinfo={.symbol_name="mountinfo"},kp_vfsmnt={.symbol_name="vfsmnt"};
static struct kretprobe kr_statx={.kp={.symbol_name="statx"}},kr_statx_do={.kp={.symbol_name="statxdo"}},
    kr_statfs={.kp={.symbol_name="statfs"}},kr_clone_ns={.kp={.symbol_name="clone"}},kr_newmnt={.kp={.symbol_name="newmnt"}};
static bool kp_sb_down_ok,kp_mnt_free_ok,kr_fdinfo_ok,kr_statx_ok,
    kr_statx_do_ok,kr_statfs_ok,kr_clone_ns_ok,kr_newmnt_ok,mount_registered;
#define SUS_MOUNT_SHOW_N 3
static struct kprobe *const sus_mount_show_probes[]={&kp_vfsstat,&kp_mountinfo,&kp_vfsmnt};
static const char *const sus_mount_show_names[]={"vfsstat","mountinfo","vfsmnt"};
static bool sus_mount_show_armed[SUS_MOUNT_SHOW_N];
static int n_show_probes,n_ident;
static char mount_ident[16];
static pthread_mutex_t model=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed=PTHREAD_COND_INITIALIZER;
static bool enable_paused,disable_started,disable_done,resume_enable;
static _Thread_local int role;
static struct timespec deadline(int seconds) {
    struct timespec t;assert(!clock_gettime(CLOCK_REALTIME,&t));t.tv_sec+=seconds;return t;
}
static void wait_for(bool *flag) {
    struct timespec t=deadline(3);
    while(!*flag)assert(!pthread_cond_timedwait(&changed,&model,&t));
}
static int register_kprobe(struct kprobe *probe) {
    assert(!probe->live);probe->live=true;probe->addr=probe;
    if(role==1 && probe==&kp_vfsstat) {
        assert(!pthread_mutex_lock(&model));enable_paused=true;pthread_cond_broadcast(&changed);
        while(!resume_enable)assert(!pthread_cond_wait(&changed,&model));
        assert(!pthread_mutex_unlock(&model));
    }
    return 0;
}
static void unregister_kprobe(struct kprobe *probe) {assert(probe->live);probe->live=false;}
static int register_kretprobe(struct kretprobe *probe) {return register_kprobe(&probe->kp);}
static void unregister_kretprobe(struct kretprobe *probe) {unregister_kprobe(&probe->kp);}
static int sus_mount_fdinfo_arm(void) {kr_fdinfo_ok=true;return 0;}
static void sus_mount_fdinfo_disarm(void) {assert(kr_fdinfo_ok);kr_fdinfo_ok=false;}
static void sus_mount_learn_stop(void) {assert(!kr_clone_ns.kp.live);}
static void sus_mount_idmap_clear(void) {}
static int sus_mount_refresh(void) {return 0;}
static int copy_from_user(void *to,const void *from,size_t n) {memcpy(to,from,n);return 0;}
static int copy_to_user(void *to,const void *from,size_t n) {memcpy(to,from,n);return 0;}
struct st_susfs_hide_sus_mnts_for_non_su_procs {int enabled,err;};
static void sus_mount_unregister(void);
#include PRODUCTION
static void *enable(void *unused) {
    (void)unused;role=1;
    struct st_susfs_hide_sus_mnts_for_non_su_procs info={.enabled=1};
    void *arg=&info;susfs_sus_mount_supercall(&arg);assert(!info.err);return NULL;
}
static void *disable(void *unused) {
    (void)unused;role=2;
    assert(!pthread_mutex_lock(&model));disable_started=true;pthread_cond_broadcast(&changed);
    assert(!pthread_mutex_unlock(&model));
    struct st_susfs_hide_sus_mnts_for_non_su_procs info={.enabled=0};
    void *arg=&info;susfs_sus_mount_supercall(&arg);assert(!info.err);
    assert(!pthread_mutex_lock(&model));disable_done=true;pthread_cond_broadcast(&changed);
    assert(!pthread_mutex_unlock(&model));return NULL;
}
int main(int argc,char **argv) {
    (void)argv;assert(argc==2);
    pthread_t first,second;assert(!pthread_create(&first,NULL,enable,NULL));
    assert(!pthread_mutex_lock(&model));wait_for(&enable_paused);
    assert(!pthread_mutex_unlock(&model));
    assert(!pthread_create(&second,NULL,disable,NULL));
    assert(!pthread_mutex_lock(&model));wait_for(&disable_started);
    struct timespec t=deadline(1);
    while(!disable_done) {int rc=pthread_cond_timedwait(&changed,&model,&t);if(rc==ETIMEDOUT)break;assert(!rc);}
    resume_enable=true;pthread_cond_broadcast(&changed);assert(!pthread_mutex_unlock(&model));
    assert(!pthread_join(first,NULL));assert(!pthread_join(second,NULL));
    if(mount_registered) {
        assert(kp_sb_down.live&&kp_mnt_free.live&&kp_sb_down_ok&&kp_mnt_free_ok &&
               "mount hiding enabled without its lifetime invalidators");
        assert(n_show_probes==3);
        for(int i=0;i<3;i++)assert(sus_mount_show_probes[i]->live&&sus_mount_show_armed[i]);
    } else {
        assert(!kp_sb_down.live&&!kp_mnt_free.live&&!n_show_probes);
        for(int i=0;i<3;i++)assert(!sus_mount_show_probes[i]->live&&!sus_mount_show_armed[i]);
    }
    puts("PASS serialized mount lifecycle");return 0;
}
