#ifndef AZHL_BACKEND_H
#define AZHL_BACKEND_H
#include <stdint.h>
#include <string.h>
struct azhl_backend { const char *id, *version_text, *manager; uint32_t version; };
static const struct azhl_backend azhl_backends[] = {
  {"kernelsu", "32661", "me.weishu.kernelsu", 32661},
  {"kernelsu-next", "33319", "com.rifsxd.ksunext", 33319},
  {"resukisu", "35212", "org.bakasu.bakasu", 35212},
};
static inline const struct azhl_backend *azhl_backend_find(const char *id, const char *version) {
  if (!id || !version) return NULL;
  for (unsigned i=0; i<sizeof(azhl_backends)/sizeof(azhl_backends[0]); i++)
    if (!strcmp(id,azhl_backends[i].id) && !strcmp(version,azhl_backends[i].version_text))
      return &azhl_backends[i];
  return NULL;
}
static inline int azhl_disable_valid(const char *value) {
  return value && (!strcmp(value,"0") || !strcmp(value,"1"));
}
#endif
