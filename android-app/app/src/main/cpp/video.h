#ifndef ARC_VIDEO_H
#define ARC_VIDEO_H

#include "arc_common.h"

// Lifecycle
void InitBlitter();
bool setupEGL();
void deinitEGL();

// Libretro Callbacks
void VideoRefreshCallback(const void *data, unsigned width, unsigned height,
                          size_t pitch);

#endif
