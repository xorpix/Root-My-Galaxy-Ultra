/* SPDX-License-Identifier: GPL-2.0-only */
#include <assert.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#define WRITE_ONCE(p,v) ((p)=(v))
#define rcu_assign_pointer(p,v) ((p)=(v))
struct prio_uid_rules { unsigned int count; bool automatic; };
static struct prio_uid_rules *prio_rules,*first_rules;
static unsigned int prio_count;
static unsigned long prio_generation;
static bool prio_enabled;
static pthread_mutex_t prio_config_lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t schedule_lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed=PTHREAD_COND_INITIALIZER;
static bool first_unlocked,first_freed;
static _Thread_local unsigned int role;
static void wait_flag(bool *flag) {
    struct timespec limit;assert(!clock_gettime(CLOCK_REALTIME,&limit));limit.tv_sec+=5;
    while(!*flag)assert(!pthread_cond_timedwait(&changed,&schedule_lock,&limit));
}
static void mutex_unlock(pthread_mutex_t *lock) {
    assert(!pthread_mutex_unlock(lock));
    if(role!=1)return;
    assert(!pthread_mutex_lock(&schedule_lock));
    first_unlocked=true;pthread_cond_broadcast(&changed);
    wait_flag(&first_freed);
    assert(!pthread_mutex_unlock(&schedule_lock));
}
static void synchronize_rcu(void) { /* No read-side critical sections in this schedule. */ }
static void kfree(void *p) {
    bool retired=p && p==first_rules;
    free(p);
    if(!retired)return;
    assert(!pthread_mutex_lock(&schedule_lock));
    first_freed=true;pthread_cond_broadcast(&changed);
    assert(!pthread_mutex_unlock(&schedule_lock));
}
#include PRODUCTION
static void *first_writer(void *unused) {
    (void)unused;role=1;assert(!pthread_mutex_lock(&prio_config_lock));
    prio_commit_locked(first_rules,NULL);return NULL;
}
int main(void) {
    first_rules=calloc(1,sizeof(*first_rules));assert(first_rules);first_rules->count=1;
    pthread_t first;assert(!pthread_create(&first,NULL,first_writer,NULL));
    assert(!pthread_mutex_lock(&schedule_lock));wait_flag(&first_unlocked);
    assert(!pthread_mutex_unlock(&schedule_lock));
    role=2;
    struct prio_uid_rules *second=calloc(1,sizeof(*second));assert(second);second->count=1;
    assert(!pthread_mutex_lock(&prio_config_lock));
    assert(prio_rules==first_rules);prio_commit_locked(second,first_rules);
    assert(!pthread_join(first,NULL));assert(prio_rules==second&&prio_count==1&&prio_enabled);
    free(second);puts("PASS two-writer publication lifetime");return 0;
}
