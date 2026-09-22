#include "vulkan_bridge.h"
#include <vulkan/vulkan_android.h>
#include <vulkan/vulkan_symbol_wrapper.h>
#include <vector>
#include <algorithm>
#include <dlfcn.h>

// Vulkan Core Handles
static void* g_vulkanLib = nullptr;
static VkInstance g_vkInstance = VK_NULL_HANDLE;
static VkPhysicalDevice g_vkGpu = VK_NULL_HANDLE;
static VkDevice g_vkDevice = VK_NULL_HANDLE;
static VkQueue g_vkQueue = VK_NULL_HANDLE;
static uint32_t g_vkQueueIndex = 0;
static VkSurfaceKHR g_vkSurface = VK_NULL_HANDLE;

static struct retro_vulkan_context g_vkContext = {};

static VkInstance createInstanceWrapper(void *, const VkInstanceCreateInfo *createInfo) {
    return vkCreateInstance(createInfo, nullptr, &g_vkInstance) == VK_SUCCESS
        ? g_vkInstance
        : VK_NULL_HANDLE;
}

static VkDevice createDeviceWrapper(VkPhysicalDevice gpu, void *,
                                    const VkDeviceCreateInfo *createInfo) {
    return vkCreateDevice(gpu, createInfo, nullptr, &g_vkDevice) == VK_SUCCESS
        ? g_vkDevice
        : VK_NULL_HANDLE;
}

bool initVulkan(struct retro_hw_render_context_negotiation_interface_vulkan *iface) {
    if (g_vulkanInitialized) return true;

    LOGI("VULKAN: Initializing ARM64 Native Bridge...");

    // 0. Manually resolve vkGetInstanceProcAddr from system library
    g_vulkanLib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!g_vulkanLib) {
        LOGE("VULKAN: Failed to open libvulkan.so");
        return false;
    }

    PFN_vkGetInstanceProcAddr get_instance_proc_addr =
        (PFN_vkGetInstanceProcAddr)dlsym(g_vulkanLib, "vkGetInstanceProcAddr");

    if (!get_instance_proc_addr) {
        LOGE("VULKAN: Failed to find vkGetInstanceProcAddr symbol");
        dlclose(g_vulkanLib);
        g_vulkanLib = nullptr;
        return false;
    }

    // 1. Initialize Symbol Wrapper
    vulkan_symbol_wrapper_init(get_instance_proc_addr);
    if (!vulkan_symbol_wrapper_load_global_symbols()) {
        LOGE("VULKAN: Failed to load global symbols");
        dlclose(g_vulkanLib);
        g_vulkanLib = nullptr;
        return false;
    }

    // 2. Create Instance
    const VkApplicationInfo *appInfo = nullptr;
    if (iface && iface->get_application_info) {
        appInfo = iface->get_application_info();
    }

    VkApplicationInfo defaultAppInfo = {};
    if (!appInfo) {
        defaultAppInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        defaultAppInfo.pApplicationName = "Arc Emulator";
        defaultAppInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
        defaultAppInfo.pEngineName = "Arc Engine";
        defaultAppInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
        defaultAppInfo.apiVersion = VK_API_VERSION_1_1;
        appInfo = &defaultAppInfo;
    }

    const char *extensions[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
    };

    VkInstanceCreateInfo instInfo = {};
    instInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instInfo.pApplicationInfo = appInfo;
    instInfo.enabledExtensionCount = 2;
    instInfo.ppEnabledExtensionNames = extensions;

    if (iface && iface->interface_version >= 2 && iface->create_instance) {
        g_vkInstance = iface->create_instance(
            get_instance_proc_addr, appInfo, createInstanceWrapper, nullptr);
    } else if (vkCreateInstance(&instInfo, nullptr, &g_vkInstance) != VK_SUCCESS) {
        g_vkInstance = VK_NULL_HANDLE;
    }
    if (g_vkInstance == VK_NULL_HANDLE) {
        LOGE("VULKAN: Failed to create instance");
        dlclose(g_vulkanLib);
        g_vulkanLib = nullptr;
        return false;
    }
    if (!vulkan_symbol_wrapper_load_core_instance_symbols(g_vkInstance)) {
        LOGE("VULKAN: Failed to load instance symbols");
        vkDestroyInstance(g_vkInstance, nullptr);
        g_vkInstance = VK_NULL_HANDLE;
        dlclose(g_vulkanLib);
        g_vulkanLib = nullptr;
        return false;
    }

    // 3. Create Surface (If window exists)
    if (g_nativeWindow) {
        VkAndroidSurfaceCreateInfoKHR surfInfo = {};
        surfInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        surfInfo.window = g_nativeWindow;

        PFN_vkCreateAndroidSurfaceKHR create_surface =
            (PFN_vkCreateAndroidSurfaceKHR)get_instance_proc_addr(g_vkInstance, "vkCreateAndroidSurfaceKHR");

        if (create_surface) {
            if (create_surface(g_vkInstance, &surfInfo, nullptr, &g_vkSurface) != VK_SUCCESS) {
                LOGE("VULKAN: Failed to create Android surface");
            }
        } else {
            LOGE("VULKAN: vkCreateAndroidSurfaceKHR symbol not found");
        }
    }

    // 4. Select GPU
    uint32_t gpuCount = 0;
    vkEnumeratePhysicalDevices(g_vkInstance, &gpuCount, nullptr);
    if (gpuCount == 0) {
        LOGE("VULKAN: No GPUs found");
        return false;
    }
    std::vector<VkPhysicalDevice> gpus(gpuCount);
    vkEnumeratePhysicalDevices(g_vkInstance, &gpuCount, gpus.data());

    g_vkGpu = gpus[0];
    for (auto gpu : gpus) {
        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(gpu, &props);
        if (strstr(props.deviceName, "Adreno")) {
            g_vkGpu = gpu;
            LOGI("VULKAN: Selected GPU: %s", props.deviceName);
            break;
        }
    }

    // The core owns device and queue creation through the negotiation callbacks.
    bool deviceCreated = false;
    if (iface && iface->interface_version >= 2 && iface->create_device2) {
        deviceCreated = iface->create_device2(
            &g_vkContext, g_vkInstance, g_vkGpu, g_vkSurface,
            get_instance_proc_addr, createDeviceWrapper, nullptr);
    } else if (iface && iface->create_device) {
        deviceCreated = iface->create_device(
            &g_vkContext, g_vkInstance, g_vkGpu, g_vkSurface,
            get_instance_proc_addr, nullptr, 0, nullptr, 0, nullptr);
    }
    if (!deviceCreated || g_vkContext.device == VK_NULL_HANDLE ||
        g_vkContext.queue == VK_NULL_HANDLE) {
        LOGE("VULKAN: Core failed to create a valid device and queue");
        return false;
    }

    g_vkDevice = g_vkContext.device;
    g_vkGpu = g_vkContext.gpu;
    g_vkQueue = g_vkContext.queue;
    g_vkQueueIndex = g_vkContext.queue_family_index;
    if (!vulkan_symbol_wrapper_load_core_device_symbols(g_vkDevice)) {
        LOGE("VULKAN: Failed to load device symbols");
        return false;
    }

    // 6. Setup Libretro Context
    g_vkContext.gpu = g_vkGpu;
    g_vkContext.device = g_vkDevice;
    g_vkContext.queue = g_vkQueue;
    g_vkContext.queue_family_index = g_vkQueueIndex;
    g_vkContext.presentation_queue = g_vkQueue;
    g_vkContext.presentation_queue_family_index = g_vkQueueIndex;

    g_vulkanInterface.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
    g_vulkanInterface.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
    g_vulkanInterface.instance = g_vkInstance;
    g_vulkanInterface.gpu = g_vkGpu;
    g_vulkanInterface.device = g_vkDevice;
    g_vulkanInterface.queue = g_vkQueue;
    g_vulkanInterface.queue_index = g_vkQueueIndex;
    g_vulkanInterface.get_device_proc_addr = vkGetDeviceProcAddr;
    g_vulkanInterface.get_instance_proc_addr = get_instance_proc_addr;
    g_vulkanInterface.set_image = vulkan_set_image;
    g_vulkanInterface.get_sync_index = vulkan_get_sync_index;
    g_vulkanInterface.get_sync_index_mask = vulkan_get_sync_index_mask;
    g_vulkanInterface.wait_sync_index = vulkan_wait_sync_index;
    g_vulkanInterface.lock_queue = vulkan_lock_queue;
    g_vulkanInterface.unlock_queue = vulkan_unlock_queue;
    g_vulkanInterface.set_command_buffers = vulkan_set_command_buffers;

    g_vulkanInitialized = true;
    LOGI("VULKAN: Bridge ready (Surface bound: %s)",
         g_vkSurface != VK_NULL_HANDLE ? "yes" : "no");
    return true;
}

void deinitVulkan() {
    if (!g_vulkanInitialized) return;
    LOGI("VULKAN: Shutting down...");
    if (g_vkDevice != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(g_vkDevice);
        vkDestroyDevice(g_vkDevice, nullptr);
    }
    if (g_vkSurface != VK_NULL_HANDLE) {
        PFN_vkDestroySurfaceKHR destroy_surface =
            (PFN_vkDestroySurfaceKHR)vulkan_symbol_wrapper_instance_proc_addr()(g_vkInstance, "vkDestroySurfaceKHR");
        if (destroy_surface) {
            destroy_surface(g_vkInstance, g_vkSurface, nullptr);
        }
    }
    if (g_vkInstance != VK_NULL_HANDLE) {
        vkDestroyInstance(g_vkInstance, nullptr);
    }
    if (g_vulkanLib) {
        dlclose(g_vulkanLib);
        g_vulkanLib = nullptr;
    }
    g_vkInstance = VK_NULL_HANDLE;
    g_vkDevice = VK_NULL_HANDLE;
    g_vkSurface = VK_NULL_HANDLE;
    g_vulkanInitialized = false;
}

void vulkan_set_image(void *handle, const struct retro_vulkan_image *image, uint32_t num_semaphores, const VkSemaphore *semaphores, uint32_t src_queue_family) {
    // This is the presentation hook. In Vulkan mode, the core renders to its internal buffer.
    // If g_vkSurface is provided to the core, PCSX2 will handle the swapchain internally
    // and present directly to the screen.
}

#include "vulkan_bridge.h"

// Forward declare GetProcAddress from environment.cpp
extern retro_proc_address_t GetProcAddress(const char *sym);

uint32_t vulkan_get_sync_index(void *handle) { return 0; }
uint32_t vulkan_get_sync_index_mask(void *handle) { return 1; }
void vulkan_wait_sync_index(void *handle) {}
void vulkan_lock_queue(void *handle) {}
void vulkan_unlock_queue(void *handle) {}
void vulkan_set_command_buffers(void *handle, uint32_t num_cmd, const VkCommandBuffer *cmd) {}

// Helper for vulkan GetInstanceProcAddr: ignores VkInstance and forwards to our GetProcAddress
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL VulkanGetInstanceProcAddr(VkInstance instance, const char *pName) {
   (void)instance; // unused
   return reinterpret_cast<PFN_vkVoidFunction>(GetProcAddress(pName));
}
