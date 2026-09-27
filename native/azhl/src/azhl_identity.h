#ifndef AZHL_IDENTITY_H
#define AZHL_IDENTITY_H
#include <string.h>
static inline int azhl_identity_matches(const char *model, const char *device,
    const char *build, const char *kernel, const char *sdk, const char *machine,
    long pagesize, unsigned uid) {
  return model && device && build && kernel && sdk && machine &&
    !strcmp(model,"SM-S948B") && !strcmp(device,"m3q") &&
    !strcmp(build,"S948BXXS4AZHL") &&
    !strcmp(kernel,"6.12.30-android16-5-pd30ff70-abogkiS948BXXS4AZHL-4k") &&
    !strcmp(sdk,"36") && !strcmp(machine,"aarch64") && pagesize==4096 && uid==2000;
}
#endif
