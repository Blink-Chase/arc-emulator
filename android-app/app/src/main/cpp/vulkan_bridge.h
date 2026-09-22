#ifndef ARC_VULKAN_BRIDGE_H
#define ARC_VULKAN_BRIDGE_H

#include "arc_common.h"
#include "libretro_vulkan.h"

bool initVulkan(struct retro_hw_render_context_negotiation_interface_vulkan *iface);
void deinitVulkan();

// Libretro Vulkan Interface Callbacks
void vulkan_set_image(void *handle, const struct retro_vulkan_image *image, uint32_t num_semaphores, const VkSemaphore *semaphores, uint32_t src_queue_family);
uint32_t vulkan_get_sync_index(void *handle);
uint32_t vulkan_get_sync_index_mask(void *handle);
void vulkan_wait_sync_index(void *handle);
void vulkan_lock_queue(void *handle);
void vulkan_unlock_queue(void *handle);
void vulkan_set_command_buffers(void *handle, uint32_t num_cmd, const VkCommandBuffer *cmd);

// Helper for vulkan GetInstanceProcAddr: ignores VkInstance and forwards to our GetProcAddress
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL VulkanGetInstanceProcAddr(VkInstance instance, const char *pName);

#endif