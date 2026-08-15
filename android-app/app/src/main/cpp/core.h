#ifndef ARC_CORE_H
#define ARC_CORE_H

#include "arc_common.h"

// Lifecycle
bool LoadCore(const char *libPath);
void UnloadCore();

// Emulation Thread
void EmuThreadFunc();

#endif
