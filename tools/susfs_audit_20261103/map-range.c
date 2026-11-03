/* SPDX-License-Identifier: GPL-2.0-only */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
struct super_block { unsigned int s_dev; };
struct inode { unsigned long i_ino; struct super_block *i_sb; };
struct file { struct inode *inode; };
struct vm_area_struct { struct file *vm_file; unsigned long vm_start,vm_end; };
struct mm_struct { struct vm_area_struct *vmas; unsigned int n; bool locked; };
struct pt_regs { unsigned long regs[31],pc; };
struct kprobe { const char *symbol_name; int (*pre_handler)(struct kprobe *,struct pt_regs *); };
typedef int atomic_t;
#define ATOMIC_INIT(v) (v)
#define smp_load_acquire(p) (*(p))
static int nmap=1;
static bool sus_map_gate_ok(void) { return true; }
static bool sus_map_lookup(unsigned long ino,unsigned int dev) { return ino==22&&dev==7; }
static void atomic_inc(atomic_t *p) { ++*p; }
static void regs_set_return_value(struct pt_regs *p,long rc) { p->regs[0]=(unsigned long)rc; }
static struct inode *file_inode(struct file *p) { return p->inode; }
static struct vm_area_struct *find_vma(struct mm_struct *mm,unsigned long addr) {
    assert(mm->locked);
    for(unsigned int i=0;i<mm->n;i++)if(addr<mm->vmas[i].vm_end)return &mm->vmas[i];
    return NULL;
}
#include PRODUCTION
int main(int argc,char **argv) {
    assert(argc==2);
    struct super_block sb={7};
    struct inode inodes[]={{11,&sb},{22,&sb}};
    struct file files[]={{&inodes[0]},{&inodes[1]}};
    struct vm_area_struct vmas[]={{&files[0],0x1000,0x2000},{&files[1],0x2000,0x4000}};
    struct mm_struct mm={vmas,2,true};
    bool crossing=strstr(argv[1],"cross")!=NULL;
    struct pt_regs regs={.regs={[0]=(unsigned long)&mm,[1]=crossing?0x1000:0x2000,
        [2]=crossing?2:1,[30]=0x1234},.pc=0x4321};
    struct kprobe *kp=strstr(argv[1],"pin")?&kp_pin_remote:&kp_gup_remote;
    assert(sus_map_vm_access_pre(kp,&regs)==1 && "multi-page request admitted a later hidden mapping");
    assert((long)regs.regs[0]==-EFAULT && regs.pc==0x1234);
    puts("PASS hidden remote-page range rejected");return 0;
}
