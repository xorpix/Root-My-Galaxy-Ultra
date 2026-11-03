/* SPDX-License-Identifier: GPL-2.0-only */
#include <assert.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
typedef unsigned short umode_t;
struct inode { umode_t i_mode; struct { uid_t val; } i_uid; };
struct sus_path_entry { struct inode *inode; umode_t orig_mode; };
#define READ_ONCE(x) (x)
#define WRITE_ONCE(x,v) ((x)=(v))
static struct { uid_t val; } caller = {2000};
#define current_uid() caller
bool susfs_ksu_umount_gate_off = true;
bool susfs_ksu_umount_ask(uid_t uid) { (void)uid; return true; }
#include "kernel/ksu_umount_gate.h"
static int hide_from_apps = 1;
#include PRODUCTION
int main(int argc,char **argv) {
    assert(argc==2);
    struct inode inode={.i_mode=S_IFREG|0640,.i_uid={0}};
    if(!strcmp(argv[1],"foreign-setid-clear"))inode.i_mode|=S_ISUID;
    struct sus_path_entry entry={.inode=&inode};
    sus_path_relax_mode(&entry);
    assert((inode.i_mode&0777)==0777);
    if (!strcmp(argv[1],"exempt-shell-dac")) {
        assert(!sus_path_gate_ok(&inode));
        assert(!(inode.i_mode & S_IWOTH) && "hiding granted DAC write access to an exempt shell caller");
        return 0;
    }
    umode_t wanted=entry.orig_mode;
    if(!strcmp(argv[1],"foreign-chmod"))wanted=inode.i_mode=S_IFREG|0600;
    if(!strcmp(argv[1],"foreign-setid-clear"))wanted=inode.i_mode=S_IFREG|0750;
    sus_path_restore_mode(&entry);
    assert(inode.i_mode==wanted && "cleanup overwrote a foreign chmod/setid-clear");
    puts("PASS permission ownership");return 0;
}
