#!/usr/bin/env python3
"""Compile the actual new Samsung LSM route from each reviewed patch."""
from pathlib import Path
import subprocess
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
PREFIX = r'''
#include <assert.h>
#include <stdbool.h>
#include <stddef.h>
#include <errno.h>
#define __nocfi
#define KERNEL_VERSION(a,b,c) (((a)<<16)+((b)<<8)+(c))
struct mnt_idmap { int unused; };
struct user_namespace { int unused; };
struct dentry { int unused; };
struct pt_regs { unsigned long pc; };
struct kprobe {
    void *addr;
    int (*pre_handler)(struct kprobe *, struct pt_regs *);
};
struct hook { void *original; };
static struct hook selinux_inode_setxattr_hook;
static bool ksu_selinux_hide_enabled, guard, teardown_during_call;
static int lsm_result, probe_result, probe_calls, unprobe_calls, unhook_calls, handler_calls;
static const void *original = (void *)0x123400;
static struct dentry entry;
static const char name[] = "security.selinux", value[] = "test";
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6,3,0)
static struct mnt_idmap ns;
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(5,12,0)
static struct user_namespace ns;
#endif
static int sel_hide_setxattr_pre(struct kprobe *, struct pt_regs *);
static void sel_hide_unroute_setxattr(void);
static bool sel_hide_guard_enter(void) { if (guard) return false; guard = true; return true; }
static void sel_hide_guard_exit(void) { assert(guard); guard = false; }
static void instruction_pointer_set(struct pt_regs *regs, unsigned long pc) { regs->pc = pc; }
static int ksu_lsm_hook(struct hook *h) { assert(h->original == original); return lsm_result; }
static void ksu_lsm_unhook(struct hook *h) { unhook_calls++; }
static int sel_hide_register_probe(struct kprobe *kp, void *target) {
    assert(target == original && selinux_inode_setxattr_hook.original == original);
    probe_calls++;
    kp->addr = probe_result ? NULL : target;
    return probe_result;
}
static void unregister_kprobe(struct kprobe *kp) { assert(kp->addr == original); unprobe_calls++; }
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6,3,0)
static int my_inode_setxattr(struct mnt_idmap *idmap, struct dentry *d, const char *n, const void *v, size_t s, int f) {
    assert(idmap == &ns);
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(5,12,0)
static int my_inode_setxattr(struct user_namespace *idmap, struct dentry *d, const char *n, const void *v, size_t s, int f) {
    assert(idmap == &ns);
#else
static int my_inode_setxattr(struct dentry *d, const char *n, const void *v, size_t s, int f) {
#endif
    struct pt_regs nested = {0};
    assert(d == &entry && n == name && v == value && s == sizeof(value) && f == 7);
    assert(guard && !sel_hide_setxattr_pre(NULL, &nested) && !nested.pc);
    if (teardown_during_call)
        sel_hide_unroute_setxattr();
    /* The real handler may reach call_orig after its probe was removed. */
    assert(selinux_inode_setxattr_hook.original == original);
    handler_calls++;
    return -EACCES;
}
'''
SUFFIX = r'''
int main(void) {
    struct pt_regs regs = {0};
    selinux_inode_setxattr_hook.original = (void *)original;
    assert(!sel_hide_setxattr_pre(NULL, &regs) && !guard && !regs.pc);
    ksu_selinux_hide_enabled = true;
    guard = true;
    assert(!sel_hide_setxattr_pre(NULL, &regs) && !regs.pc);
    guard = false;
    assert(sel_hide_setxattr_pre(NULL, &regs) == 1 && guard);
    assert(regs.pc == (unsigned long)sel_hide_setxattr_tramp);
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5,12,0)
    assert(sel_hide_setxattr_tramp(&ns, &entry, name, value, sizeof(value), 7) == -EACCES);
#else
    assert(sel_hide_setxattr_tramp(&entry, name, value, sizeof(value), 7) == -EACCES);
#endif
    assert(!guard && handler_calls == 1);
    assert(!sel_hide_route_setxattr() && !probe_calls);
    sel_hide_unroute_setxattr();
    assert(unhook_calls == 1 && !unprobe_calls);
    lsm_result = -EOPNOTSUPP;
    assert(!sel_hide_route_setxattr() && probe_calls == 1);
    teardown_during_call = true;
    assert(sel_hide_setxattr_pre(NULL, &regs) == 1 && guard);
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5,12,0)
    assert(sel_hide_setxattr_tramp(&ns, &entry, name, value, sizeof(value), 7) == -EACCES);
#else
    assert(sel_hide_setxattr_tramp(&entry, name, value, sizeof(value), 7) == -EACCES);
#endif
    assert(!guard && handler_calls == 2);
    assert(unprobe_calls == 1 && unhook_calls == 1);
    assert(!sel_hide_setxattr_kprobe.addr && selinux_inode_setxattr_hook.original == original);
    probe_result = -EINVAL;
    assert(sel_hide_route_setxattr() == -EINVAL && !sel_hide_setxattr_kprobe.addr);
    return 0;
}
'''


class SelinuxRouteTests(unittest.TestCase):
    def test_real_routes_preserve_arguments_guard_errors_and_cleanup(self):
        handlers = {
            'kernelsu': 'my_inode_setxattr',
            'kernelsu-next': 'my_inode_setxattr',
            'kowsu': 'my_inode_setxattr',
            'resukisu': 'ksu_handle_selinux_inode_setxattr',
        }
        for name, handler in handlers.items():
            patch = (HERE / f'{name}-compat.patch').read_text()
            added = '\n'.join(line[1:] for line in patch.splitlines() if line.startswith('+') and not line.startswith('+++'))
            start = added.index('/* Preserve the Samsung route')
            end = added.index('\n}\n', added.index('static void sel_hide_unroute_setxattr', start)) + 3
            source = PREFIX.replace('my_inode_setxattr', handler) + added[start:end] + SUFFIX
            for version in ((5, 10, 0), (5, 15, 0), (6, 12, 0)):
                with self.subTest(backend=name, kernel=version), tempfile.TemporaryDirectory() as tmp:
                    c = Path(tmp) / 'route.c'
                    binary = Path(tmp) / 'route'
                    c.write_text(source)
                    subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', '-Wno-unused-parameter',
                                    '-fsanitize=undefined', '-fno-sanitize-recover=all',
                                    f'-DLINUX_VERSION_CODE={version[0]*65536+version[1]*256+version[2]}',
                                    str(c), '-o', str(binary)], check=True)
                    subprocess.run([binary], check=True)


if __name__ == '__main__':
    unittest.main(verbosity=2)
