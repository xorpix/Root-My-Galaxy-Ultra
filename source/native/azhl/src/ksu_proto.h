#ifndef KSU_PROTO_H
#define KSU_PROTO_H

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define KSU_SOCK_PATH "/data/local/tmp/temp_su.sock"
#define KSU_SOCK_NAME "azhl-root-control"
#define KSU_LOADER_PATH "/data/local/tmp/ksud-s25u-kdp"
#define KSU_STAGE_PATH "/data/local/tmp/.ksud-stage"
#define KSU_ADB_DIR "/data/adb"
#define KSU_LOGCAT_PATH "/system/bin/logcat"
#define KSU_KMI "android16-6.12"

#define KSU_PROTO_VERSION 1U

struct ksu_get_info_cmd {
  uint32_t version;
  uint32_t flags;
  uint32_t features;
  uint32_t uapi_version;
};
#define KSU_VERSION_EXPECTED 32525U
#define KSU_FLAG_SU_ALLOW 1U
#define KSU_FLAG_ENABLE 4U

#define KSU_LD_PROTO_MISMATCH (-2)

/* Use a Linux abstract AF_UNIX name so the kernel-launched daemon's
 * u:r:kernel:s0 file label cannot block the adb shell client. */
static inline socklen_t ksu_socket_address(struct sockaddr_un* sun) {
  memset(sun, 0, sizeof(*sun));
  sun->sun_family = AF_UNIX;
  sun->sun_path[0] = '\0';
  size_t name_len = strlen(KSU_SOCK_NAME);
  memcpy(sun->sun_path + 1, KSU_SOCK_NAME, name_len);
  return (socklen_t)sizeof(*sun);
}

enum {
  KSU_LD_NS_FAIL = 10,
  KSU_LD_BIND_FAIL = 11,
  KSU_LD_EXEC_FAIL = 12,
  KSU_LD_VERIFY_FAIL = 13,
};

static inline int proto_read_exact(int fd, void* buf, size_t len) {
  char* p = (char*)buf;
  while (len) {
    ssize_t n = read(fd, p, len);
    if (n < 0 && errno == EINTR) {
      continue;
    }
    if (n <= 0) {
      return 0;
    }
    p += n;
    len -= (size_t)n;
  }
  return 1;
}

static inline int proto_write_all(int fd, const void* buf, size_t len) {
  const char* p = (const char*)buf;
  while (len) {
    ssize_t n = write(fd, p, len);
    if (n < 0 && errno == EINTR) {
      continue;
    }
    if (n <= 0) {
      return 0;
    }
    p += n;
    len -= (size_t)n;
  }
  return 1;
}

#endif
