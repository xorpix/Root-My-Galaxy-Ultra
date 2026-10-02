#include <assert.h>
#include "azhl_identity.h"
#include "azhl_backend.h"

int main(void) {
    const char *kernel = "6.12.30-android16-5-pd30ff70-abogkiS948BXXS4AZHL-4k";
    assert(azhl_identity_matches("SM-S948B", "m3q", "S948BXXS4AZHL", kernel, "36", "aarch64", 4096, 2000));
    assert(!azhl_identity_matches("SM-S948U", "m3q", "S948BXXS4AZHL", kernel, "36", "aarch64", 4096, 2000));
    assert(!azhl_identity_matches("SM-S948B", "other", "S948BXXS4AZHL", kernel, "36", "aarch64", 4096, 2000));
    assert(!azhl_identity_matches("SM-S948B", "m3q", "new", kernel, "36", "aarch64", 4096, 2000));
    assert(!azhl_identity_matches("SM-S948B", "m3q", "S948BXXS4AZHL", "other", "36", "aarch64", 4096, 2000));
    assert(!azhl_identity_matches("SM-S948B", "m3q", "S948BXXS4AZHL", kernel, "36", "aarch64", 16384, 2000));
    assert(!azhl_identity_matches("SM-S948B", "m3q", "S948BXXS4AZHL", kernel, "36", "aarch64", 4096, 0));
    assert(!azhl_identity_matches("SM-S948B", "m3q", "S948BXXS4AZHL", kernel, "36", "aarch64", 4096, 10234));
    for (unsigned i=0; i<3; ++i) {
        for (unsigned j=0; j<3; ++j)
            assert((azhl_backend_find(azhl_backends[i].id, azhl_backends[j].version_text) != NULL) == (i == j));
    }
    assert(!azhl_backend_find(NULL, "32653"));
    assert(!azhl_backend_find("unknown", "32653"));
    assert(!azhl_backend_find("kernelsu", "032653"));
    assert(!azhl_backend_find("kernelsu", "32653-extra"));
    assert(azhl_disable_valid("0") && azhl_disable_valid("1"));
    assert(!azhl_disable_valid(NULL) && !azhl_disable_valid("2") && !azhl_disable_valid(""));
    return 0;
}
