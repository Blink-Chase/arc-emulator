#ifndef ARC_ENVIRONMENT_H
#define ARC_ENVIRONMENT_H

#include "arc_common.h"

// Callback implemented by the frontend to handle core requests
bool EnvironmentCallback(unsigned cmd, void *data);

// Shared utilities for environment handling
uintptr_t GetCurrentFramebuffer();
retro_proc_address_t GetProcAddress(const char *sym);

#endif
