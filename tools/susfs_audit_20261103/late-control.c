/* SPDX-License-Identifier: GPL-2.0-only */
#include "tests/module_control/host_kernel.h"
#define pr_warn(...) ((void)0)
static bool hide_registered,m_show_registered;
static int kp_s_show,kp_m_show;
static void *hide_modules_entry,*modules_op_target,*kallsyms_op_target;
static atomic_t hide_enter_count,hide_hit_count,hide_layout_bad;
static bool path_layer_down;
static unsigned int added;
static void proc_remove(void *p) { (void)p; }
static void unregister_kprobe(int *p) { (void)p; }
static void susfs_kp_forget_addr(int *p) { (void)p; }
static int sus_path_add_self_hidden(const char *path) {
    (void)path;
    assert(!path_layer_down && "late sysfs command added a rule after path-layer teardown");
    added++;return 0;
}
static int sus_path_del_path(const char *path) { (void)path;return 1; }
#include PRODUCTION
int main(int argc,char **argv) {
    assert(argc==2);
    assert(!hide_modules_command("set kernelsu",false));assert(added==1);
    if (!strcmp(argv[1],"ordinary")) { puts("PASS live control command");return 0; }
    susfs_hide_syms_exit();
    /* Linux v6.12 removes module parameter attributes in free_module(),
     * after the complete module exit/layer-down call. A store can reach
     * this controller while lower layers have already been torn down. */
    path_layer_down=true;
    int rc=hide_modules_command("set kernelsu",false);
    assert(rc==-ENODEV && "late control command was not rejected");
    puts("PASS late control rejected");return 0;
}
