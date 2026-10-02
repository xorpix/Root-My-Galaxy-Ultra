#include "common.h"
#include "ksu_proto.h"
static char claim_path[160];
int ghostlock_boot_claim(void){
 char uptime[64],boot[64],old[256];
 if(read_first_line("/proc/uptime",uptime,sizeof(uptime))||strtod(uptime,NULL)<180){
  pr_warning("Wait at least 180 seconds after a full reboot\n");return 1;
 }
 int kfd=-1;syscall(SYS_reboot,0xDEADBEEF,0xCAFEBABE,0,&kfd);
 if(kfd>=0){close(kfd);pr_warning("A driver is already active; reboot before loading\n");return 1;}
 if(read_first_line("/proc/sys/kernel/random/boot_id",boot,sizeof(boot))||strlen(boot)!=36||strspn(boot,"0123456789abcdef-")!=36){
  pr_warning("Cannot establish boot identity; refusing\n");return 1;
 }
 int prior=open(GHOSTLOCK_BOOT_STATE_PATH,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
 if(prior>=0){
  ssize_t n=read(prior,old,sizeof(old)-1);close(prior);
  if(n>0){old[n]=0;if(strstr(old,boot)){pr_warning("Original loader already attempted this boot; reboot\n");return 1;}}
 }else if(errno!=ENOENT){pr_warning("Cannot inspect previous loader receipt\n");return 1;}
 snprintf(claim_path,sizeof(claim_path),"/data/local/tmp/azhl-%s.claim",boot);
 int fd=open(claim_path,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW,0600);
 if(fd<0){pr_warning("AZHL boot already claimed or claim unavailable; reboot\n");return 1;}
 static const char pending[]="running\n";
 int ok=write(fd,pending,sizeof(pending)-1)==sizeof(pending)-1&&fsync(fd)==0;
 close(fd);return !ok;
}
void ghostlock_boot_mark(int status){
 if(!claim_path[0])return;
 int fd=open(claim_path,O_WRONLY|O_APPEND|O_CLOEXEC|O_NOFOLLOW);
 if(fd>=0){dprintf(fd,"status=%d\n",status);fsync(fd);close(fd);}
}
