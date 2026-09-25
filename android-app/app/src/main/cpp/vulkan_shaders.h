
#ifndef ARC_VULKAN_SHADERS_H
#define ARC_VULKAN_SHADERS_H

// SPIR-V blobs for the frontend's presentation pass. The core hands us a
// VkImageView (never a VkImage), so presentation cannot use vkCmdBlitImage or
// vkCmdCopyImage - we have to sample the core's view with a fullscreen quad.
// The blobs are compiled offline with:
//   glslang -V --target-env vulkan1.0 --spirv-val quad.vert
// and embedded as uint32_t arrays by tools/spv2h.ps1 (see arc_quad_*_spv.h).
//
// Regenerate: edit android-app/tools/shaders/quad.vert / quad.frag, then
//   powershell -File android-app/tools/spv2h.ps1 ...
#include <cstddef>
#include <cstdint>

#include "arc_quad_frag_spv.h"
#include "arc_quad_vert_spv.h"

#endif // ARC_VULKAN_SHADERS_H
