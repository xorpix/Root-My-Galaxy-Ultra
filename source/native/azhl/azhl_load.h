/* Privileged backend preparation; included by the companion-protocol helper. */
#include <dirent.h>
#include <sys/xattr.h>
#include "src/azhl_backend.h"
#define AZHL_RECEIPT "/data/adb/azhl-last-backend"
static int azhl_disable_directory(const char *path) {
 int fd=open(path,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
 if(fd<0)return errno==ENOENT;
 DIR *dir=fdopendir(fd);
 if(!dir){close(fd);return 0;}
 int ok=1;
 struct dirent *entry;
 errno=0;
 while((entry=readdir(dir))){
  if(!strcmp(entry->d_name,".")||!strcmp(entry->d_name,".."))continue;
  struct stat st;
  if(fstatat(fd,entry->d_name,&st,AT_SYMLINK_NOFOLLOW)){ok=0;break;}
  if(S_ISLNK(st.st_mode)){ok=0;break;}
  if(!S_ISDIR(st.st_mode))continue;
  int module=openat(fd,entry->d_name,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
  if(module<0){ok=0;break;}
  int marker=openat(module,"disable",O_WRONLY|O_CREAT|O_NOFOLLOW|O_CLOEXEC,0644);
  if(marker<0){close(module);ok=0;break;}
  if(fsync(marker)){ok=0;}
  close(marker);close(module);
  if(!ok)break;
  errno=0;
 }
 if(errno)ok=0;
 closedir(dir);return ok;
}
static int azhl_prepare_load(const struct azhl_backend *backend,int disable) {
 int driver=-1;
 syscall(SYS_reboot,0xDEADBEEF,0xCAFEBABE,0,&driver);
 if(driver>=0){close(driver);dprintf(2,"Driver already active; full reboot required\n");return 0;}
 if(mkdir("/data/adb",0700)&&errno!=EEXIST)return 0;
 struct stat st;
 if(lstat("/data/adb",&st)||!S_ISDIR(st.st_mode))return 0;
 static const char context[]="u:object_r:system_data_root_file:s0";
 if(setxattr("/data/adb","security.selinux",context,sizeof(context),0))
  dprintf(2,"AZHL: /data/adb label: %s\n",strerror(errno));
 char boot[64]={0},claim[160];
 int fd=open("/proc/sys/kernel/random/boot_id",O_RDONLY|O_CLOEXEC);
 if(fd<0)return 0;
 ssize_t n=read(fd,boot,sizeof(boot)-1);close(fd);
 if(n<=0)return 0;
 boot[strcspn(boot,"\r\n")]=0;
 if(strlen(boot)!=36||strspn(boot,"0123456789abcdef-")!=36)return 0;
 snprintf(claim,sizeof(claim),"/data/adb/azhl-loader-%s.claim",boot);
 fd=open(claim,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
 if(fd<0){dprintf(2,"AZHL: backend load already attempted or claim unavailable; full reboot required\n");return 0;}
 int ok=write_full(fd,backend->id,strlen(backend->id))&&fsync(fd)==0;
 close(fd);if(!ok)return 0;
 char previous[64]={0};
 fd=open(AZHL_RECEIPT,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
 if(fd>=0){n=read(fd,previous,sizeof(previous)-1);close(fd);if(n<0)return 0;}
 else if(errno!=ENOENT)return 0;
 previous[strcspn(previous,"\r\n")]=0;
 if(disable||strcmp(previous,backend->id)){
  if(!azhl_disable_directory("/data/adb/modules")||!azhl_disable_directory("/data/adb/modules_update")){
   dprintf(2,"AZHL: could not disable existing modules before backend change\n");return 0;
  }
  dprintf(1,"Existing modules disabled; re-enable compatible modules in the selected manager\n");
 }
 return 1;
}
static int azhl_record_backend(const struct azhl_backend *backend){
 int fd=open(AZHL_RECEIPT ".tmp",O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC|O_NOFOLLOW,0600);
 if(fd<0)return 0;
 int ok=write_full(fd,backend->id,strlen(backend->id))&&fsync(fd)==0;
 close(fd);
 return ok&&rename(AZHL_RECEIPT ".tmp",AZHL_RECEIPT)==0;
}
