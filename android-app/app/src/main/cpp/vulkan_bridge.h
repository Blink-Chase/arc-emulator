// Arc Emulator - libretro Vulkan backend interface (PS2 / LRPS2 core).
//
// The frontend owns instance, device, swapchain and presentation; the core
// renders into its own image and hands us a VkImageView via set_image().
#pragma once

#include "arc_common.h"
#include "libretro_vulkan.h"

#include <atomic>
#include <cstdint>

// Set once from the core's own advertised option list (SET_CORE_OPTIONS):
// PCSX2 only lists a Vulkan renderer when it was built with ENABLE_VULKAN.
extern std::atomic<bool> g_coreSupportsVulkan;
// User preference (persisted from the Kotlin settings UI).
extern std::atomic<bool> g_vulkanRequested;
// Bring-up already failed. Drives the automatic software fallback.
extern std::atomic<bool> g_vulkanFailed;

// --- Capability / preference ---------------------------------------------
void vulkanSetCoreSupported(bool supported);
bool vulkanCoreSupportsVulkan();
void vulkanSetRequested(bool requested);
bool vulkanRequested();
bool vulkanIsRequested();
bool vulkanIsActive();
bool vulkanShouldFallBack(); // requested but core unsupported or failed
bool vulkanFailed();
bool vulkanBackendReady(); // instance+device+interface fully built
bool vulkanHasPresentedFrame();
uint64_t vulkanPresentedFrameCount();
bool vulkanSurfaceLost();
void vulkanNotifySurfaceLost();
bool vulkanContextActive();

// --- Environment hooks ----------------------------------------------------
// Cheap feasibility gate for SET_HW_RENDER. Never does heavy work; the real
// bring-up happens in vulkanBeginNegotiation().
bool vulkanAcceptHwRenderRequest();
// Called from SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE. Creates instance,
// surface and device (through the core's create_device2 + our
// create_device_wrapper), then fully populates g_vulkanInterface.
bool vulkanBeginNegotiation(
    const struct retro_hw_render_context_negotiation_interface_vulkan *iface);
// Payload for GET_HW_RENDER_INTERFACE; nullptr unless vulkanBackendReady().
struct retro_hw_render_interface_vulkan *vulkanInterface();

// --- Lifecycle -------------------------------------------------------------
// Rebuilds the swapchain (if needed), then invokes the core's context_reset.
// Must run on the emulation thread without g_windowMutex held.
void vulkanContextReset();
// Invokes the core's context_destroy first (the core retracts its image),
// waits for idle, then tears the swapchain down. Device/instance survive.
void vulkanContextDestroy();
// Presentation hook: called from VideoRefreshCallback when the core passes
// RETRO_HW_FRAME_BUFFER_VALID after set_image().
bool vulkanPresent();
// Full teardown (device, instance, loader) from UnloadCore.
void deinitVulkan();