#include <jni.h>

#include <fcntl.h>
#include <string.h>
#include <unistd.h>

JNIEXPORT jboolean JNICALL
Java_dev_busung_s25uroot_NativeProbe_isKernelSuActive(JNIEnv *env,
                                                       jobject thiz) {
  (void)env;
  (void)thiz;

  // NOTE: do NOT probe the driver with the magic reboot(2) request here.
  // That call (SYS_reboot, arm64 syscall 142) is issued in the APP process,
  // and Android's app seccomp-BPF kills any app issuing reboot() with
  // SIGSYS no matter the arguments — tombstone "Fatal signal 31 (SIGSYS),
  // code 1 (SYS_SECCOMP), syscall 142", ~2s after launch. runCatching
  // cannot catch a signal, so this function can never return through that
  // path in-app. Driver presence in-app is therefore read from /sys/module
  // and /proc/modules only (false negatives tolerated); the authoritative
  // check runs post-root through su/Shizuku, and the magic-reboot probe
  // stays in the loader/helper/shell processes where it belongs.
  //
  // Removed: syscall(SYS_reboot, 0xDEADBEEF, 0xCAFEBABE, 0, &control_fd).

  if (access("/sys/module/kernelsu", F_OK) == 0) {
    return JNI_TRUE;
  }

  int fd = open("/proc/modules", O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    return JNI_FALSE;
  }

  char modules[65536];
  ssize_t count = read(fd, modules, sizeof(modules) - 1);
  close(fd);
  if (count <= 0) {
    return JNI_FALSE;
  }
  modules[count] = '\0';

  const char *line = modules;
  while (line != NULL && *line != '\0') {
    if (strncmp(line, "kernelsu ", sizeof("kernelsu ") - 1) == 0) {
      return JNI_TRUE;
    }
    line = strchr(line, '\n');
    if (line != NULL) {
      ++line;
    }
  }
  return JNI_FALSE;
}
