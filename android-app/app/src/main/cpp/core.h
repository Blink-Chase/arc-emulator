#ifndef ARC_CORE_H
#define ARC_CORE_H

#include "arc_common.h"

// Lifecycle
bool LoadCore(const char *libPath);
void UnloadCore();

// Emulation Thread
void EmuThreadFunc();

// pthread entry point for the emulation thread (created with a large stack by
// nativeLoadGame) - clears g_emuThreadActive when the thread is done.
void *EmuThreadEntry(void *arg);

// True while the emulation loop is still servicing work. Goes false when the
// core is blocked inside a call of its own (a hung game), which is exactly the
// state in which calling reset/unload would crash the process.
bool EmuThreadResponsive();

// How long the emu thread has been continuously inside the current core call (ms).
int64_t EmuCallBlockedMs();

#endif
