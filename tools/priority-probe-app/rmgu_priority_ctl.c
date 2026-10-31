// SPDX-License-Identifier: GPL-2.0
/* Freestanding ARM64 priority-filter control and read-only verification client.
 * set <comma-list> | clear | status | query <uid> | probe <caller-uid> <target-uid>
 * Probe observes a running target, isolates its own mount namespace, drops all
 * IDs/groups, then issues reads and ONE deliberately invalid ioprio_set request.
 * It never sends a valid scheduling update and never changes another task's UID.
 */
typedef unsigned long usize;
typedef long sysarg;
struct priority_payload { char packages[65535]; int err; };
struct automatic_payload {unsigned int enabled,own_appid;int err;};
_Static_assert(sizeof(struct automatic_payload)==12,"automatic ABI size");
_Static_assert(__builtin_offsetof(struct automatic_payload,err)==8,"automatic ABI result");
_Static_assert(sizeof(struct priority_payload) == 65540, "priority ABI size");
_Static_assert(__builtin_offsetof(struct priority_payload, err) == 65536, "priority ABI error");

#ifdef PRIORITY_TOOL_HOSTTEST
extern sysarg priority_sys6(sysarg,sysarg,sysarg,sysarg,sysarg,sysarg,sysarg);
#else
static sysarg priority_sys6(sysarg n,sysarg a,sysarg b,sysarg c,sysarg d,sysarg e,sysarg f)
{
    register sysarg x8 __asm__("x8")=n;
    register sysarg x0 __asm__("x0")=a,x1 __asm__("x1")=b,x2 __asm__("x2")=c;
    register sysarg x3 __asm__("x3")=d,x4 __asm__("x4")=e,x5 __asm__("x5")=f;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8),"r"(x1),"r"(x2),"r"(x3),"r"(x4),"r"(x5) : "memory","cc");
    return x0;
}
#endif
#define SC(n,a,b,c,d,e,f) priority_sys6(n,(sysarg)(a),(sysarg)(b),(sysarg)(c),(sysarg)(d),(sysarg)(e),(sysarg)(f))
static struct priority_payload payload;
static char output[2048],status_text[512];
static usize used;
static usize len(const char *s) {usize n=0;while(s[n])n++;return n;}
static int eq(const char *a,const char *b) {while(*a&&*a==*b){a++;b++;}return *a==*b;}
static void put(const char *s) {while(*s&&used<sizeof(output))output[used++]=*s++;}
static void num(long v) {
    char d[24];int n=0;usize u=v<0?0UL-(usize)v:(usize)v;
    if(v<0)put("-");do{d[n++]=(char)('0'+u%10);u/=10;}while(u);
    while(n&&used<sizeof(output))output[used++]=d[--n];
}
static void string(const char *s) {
    put("\"");for(;*s;s++){
        if(*s=='\n')put("\\n");else if(*s=='\r')put("\\r");else if(*s=='\t')put("\\t");
        else {if(*s=='"'||*s=='\\')put("\\");if(used<sizeof(output))output[used++]=*s;}
    }put("\"");
}
static int flush(int code) {
    usize at=0;while(at<used){long n=SC(64,1,output+at,used-at,0,0,0);if(n<=0)return 2;at+=(usize)n;}return code;
}
static int fail(const char *message,long error) {
    put("{\"schema\":1,\"error\":");num(error);put(",\"detail\":");string(message);put("}\n");return flush(2);
}
static int decimal(const char *s,unsigned int *value) {
    usize v=0;if(!*s)return 0;for(;*s;s++){
        if(*s<'0'||*s>'9'||v>(2147483647UL-(usize)(*s-'0'))/10)return 0;
        v=v*10+(usize)(*s-'0');
    }*value=(unsigned int)v;return 1;
}
static int status_read(void) {
    long fd=SC(56,-100,"/sys/module/susfs_guard_lkm/parameters/priority_filter",0,0,0,0);
    if(fd<0)return (int)fd;
    long n=SC(63,fd,status_text,sizeof(status_text)-1,0,0,0);
    SC(57,fd,0,0,0,0,0);
    if(n<=0)return n<0?(int)n:-5;
    status_text[n]=0;return 0;
}
static int ready(void) {
    static const char prefix[]="ready=1 configured=";
    usize i=0;for(;prefix[i];i++)if(status_text[i]!=prefix[i])return 0;
    if(status_text[i]>='1'&&status_text[i]<='9')return 1;
    for(;status_text[i];i++)if(status_text[i]==' '){
        static const char automatic[]=" automatic=1 ";usize n=0;
        while(automatic[n]&&status_text[i+n]==automatic[n])n++;
        if(!automatic[n])return 1;
    }
    return 0;
}
static int configure(const char *packages) {
    usize n=len(packages);if(n>=sizeof(payload.packages))return fail("Package list is too long",-7);
    for(usize i=0;i<=n;i++)payload.packages[i]=packages[i];payload.err=126;
    long rc=SC(142,0xDEADBEEFUL,0xFAFAFAFAUL,0x60030,&payload,0,0);
    put("{\"schema\":1,\"syscall\":");num(rc);put(",\"error\":");num(payload.err);put("}\n");
    return flush(rc==0&&payload.err==0?0:1);
}
static int configure_automatic(unsigned int enabled,unsigned int appid) {
    if(enabled&&(appid<10000||appid>19999))return fail("Use the RMGU application ID",-22);
    struct automatic_payload info={enabled,appid,126};
    long rc=SC(142,0xDEADBEEFUL,0xFAFAFAFAUL,0x6f001,&info,0,0);
    put("{\"schema\":1,\"syscall\":");num(rc);put(",\"error\":");num(info.err);put("}\n");
    return flush(rc==0&&info.err==0?0:1);
}
static int probe(unsigned int caller,unsigned int target) {
    unsigned int app=caller%100000,target_app=target%100000;
    long rc,root_gp,root_io,gp,io,self_gp,self_io,invalid;
    if(app<10000||target_app<10000||target_app>19999||app==target_app)
        return fail("Use distinct application IDs for caller and target",-22);
    if(SC(174,0,0,0,0,0,0)!=0||SC(175,0,0,0,0,0,0)!=0)
        return fail("Probe must start as root",-1);
    rc=status_read();if(rc)return fail("Priority status is unavailable; fully reboot into the test module",rc);
    if(!ready())return fail("Priority filter is not ready or has no configured rules",-19);
    root_gp=SC(141,2,target,0,0,0,0);root_io=SC(31,3,target,0,0,0,0);
    if(root_gp<0||root_io<0)return fail("Target is not observable as root; open the target app and retry",-3);
    /* A KernelSU setuid hook may unmount modules. Isolate BEFORE changing IDs
     * and make mounts private, so that operation cannot affect the parent. */
    rc=SC(97,0x00020000,0,0,0,0,0);if(rc)return fail("Cannot isolate the probe mount namespace",rc);
    rc=SC(40,0,"/",0,0x4000|0x40000,0,0);if(rc)return fail("Cannot make probe mounts private",rc);
    rc=SC(167,8,0,0,0,0,0);if(rc)return fail("Cannot clear keep-caps",rc);
    rc=SC(167,38,1,0,0,0,0);if(rc)return fail("Cannot set no-new-privileges",rc);
    rc=SC(159,0,0,0,0,0,0);if(rc)return fail("Cannot clear supplementary groups",rc);
    rc=SC(149,caller,caller,caller,0,0,0);if(rc)return fail("Cannot drop group IDs",rc);
    rc=SC(147,caller,caller,caller,0,0,0);if(rc)return fail("Cannot drop user IDs",rc);
    if(SC(174,0,0,0,0,0,0)!=(long)caller||SC(175,0,0,0,0,0,0)!=(long)caller)
        return fail("Caller identity did not change",-1);
    gp=SC(141,2,target,0,0,0,0);io=SC(31,3,target,0,0,0,0);
    self_gp=SC(141,2,caller,0,0,0,0);self_io=SC(31,3,caller,0,0,0,0);
    /* Invalid class: the kernel must reject it before it can change scheduling. */
    invalid=SC(30,3,target,-1,0,0,0);
    int passed=gp==-3&&io==-3&&self_gp>=0&&self_io>=0&&invalid==-22;
    put("{\"schema\":1,\"caller_uid\":");num(caller);put(",\"target_uid\":");num(target);
    put(",\"root_getpriority\":");num(root_gp);put(",\"root_ioprio_get\":");num(root_io);
    put(",\"getpriority\":");num(gp);put(",\"ioprio_get\":");num(io);
    put(",\"self_getpriority\":");num(self_gp);put(",\"self_ioprio_get\":");num(self_io);
    put(",\"invalid_ioprio_set\":");num(invalid);put(",\"passed\":");put(passed?"true":"false");put("}\n");
    return flush(passed?0:1);
}
int priority_tool_main(int argc,char **argv) {
    unsigned int caller,target;used=0;
    if(argc==3&&eq(argv[1],"auto")&&decimal(argv[2],&caller))return configure_automatic(1,caller);
    if(argc==2&&eq(argv[1],"auto-off"))return configure_automatic(0,0);
    if(argc==2&&eq(argv[1],"clear"))return configure("");
    if(argc==3&&eq(argv[1],"set"))return configure(argv[2]);
    if(argc==2&&eq(argv[1],"status")){
        int rc=status_read();if(rc)return fail("Priority status is unavailable",rc);
        put("{\"schema\":1,\"status\":");string(status_text);put("}\n");return flush(0);
    }
    if(argc==3&&eq(argv[1],"query")&&decimal(argv[2],&target)){
        put("{\"schema\":1,\"query_only\":true,\"caller_uid\":");num(SC(174,0,0,0,0,0,0));
        put(",\"target_uid\":");num(target);
        put(",\"getpriority\":");num(SC(141,2,target,0,0,0,0));
        put(",\"ioprio_get\":");num(SC(31,3,target,0,0,0,0));put("}\n");return flush(0);
    }
    if(argc==4&&eq(argv[1],"probe")&&decimal(argv[2],&caller)&&decimal(argv[3],&target))return probe(caller,target);
    return fail("Usage: auto <appid> | auto-off | set <comma-list> | clear | status | query <uid> | probe <caller-uid> <target-uid>",-22);
}
#ifndef PRIORITY_TOOL_HOSTTEST
__attribute__((used)) static int start(usize *stack) {return priority_tool_main((int)stack[0],(char **)(stack+1));}
__asm__(".text\n.global _start\n.type _start,%function\n_start:\nmov x29,#0\nmov x0,sp\nbl start\nmov x8,#93\nsvc #0\n");
#endif
