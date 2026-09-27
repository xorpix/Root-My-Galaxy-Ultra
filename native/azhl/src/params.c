#define _GNU_SOURCE
#include "params.h"
#include "azhl_identity.h"
#include <stdio.h>
#include <sys/system_properties.h>
#include <sys/utsname.h>
#include <unistd.h>
const struct kernel_line *target;
struct device_identity g_ident={.line_flag=LINE_FLAG_NONE,.label="unknown"};
extern const struct kernel_line kernel_lines[];
extern const size_t kernel_lines_count;
extern const struct device_line_map device_map[];
extern const size_t device_map_count;
const char *params_label(void){return g_ident.label;}
uint32_t params_line_flag(void){return g_ident.line_flag;}
int params_resolve(void){
 char model[PROP_VALUE_MAX]={0},dev[PROP_VALUE_MAX]={0},inc[PROP_VALUE_MAX]={0},sdk[PROP_VALUE_MAX]={0};
 struct utsname u;
 __system_property_get("ro.product.model",model);
 __system_property_get("ro.product.device",dev);
 __system_property_get("ro.build.version.incremental",inc);
 __system_property_get("ro.build.version.sdk",sdk);
 if(uname(&u)||!azhl_identity_matches(model,dev,inc,u.release,sdk,u.machine,sysconf(_SC_PAGESIZE),getuid())||geteuid()!=2000){
  fprintf(stderr,"AZHL identity or shell UID mismatch; refusing native execution\n");return -1;
 }
 const struct device_line_map *hit=NULL;
 for(size_t i=0;i<device_map_count;i++)
  if(!strcmp(inc,device_map[i].build_id)&&device_map[i].device&&!strcmp(dev,device_map[i].device)){hit=&device_map[i];break;}
 if(!hit||strcmp(hit->line_id,"intl"))return -1;
 for(size_t i=0;i<kernel_lines_count;i++)if(!strcmp(kernel_lines[i].line_id,hit->line_id)){
  target=&kernel_lines[i];
  snprintf(g_ident.device_buf,sizeof(g_ident.device_buf),"%s",dev);
  snprintf(g_ident.build_id_buf,sizeof(g_ident.build_id_buf),"%s",inc);
  g_ident.device=g_ident.device_buf;g_ident.build_id=g_ident.build_id_buf;
  g_ident.line_id=hit->line_id;g_ident.line_flag=LINE_FLAG_INTL;
  snprintf(g_ident.label,sizeof(g_ident.label),"%s-%s:intl",dev,inc);return 0;
 }
 return -1;
}
