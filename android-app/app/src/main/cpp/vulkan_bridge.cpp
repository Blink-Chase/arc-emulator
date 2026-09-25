// Arc Emulator - libretro Vulkan presentation backend for the PS2 (LRPS2) core.
//
// Contract with the core (libretro/ps2: GSDeviceVK.cpp + libretro_vulkan.h):
//   * SET_HW_RENDER(VULKAN) only returns true via vulkanAcceptHwRenderRequest();
//     the real bring-up happens in vulkanBeginNegotiation(), and
//     GET_HW_RENDER_INTERFACE only ever returns a fully populated interface.
//   * The core creates its device through our create_device_wrapper() so we can
//     append VK_KHR_swapchain without touching anything the core asked for.
//   * set_image() hands over an image *view* (no VkImage), so presentation must
//     sample it with a fullscreen quad - blits/copies are impossible. The core
//     guarantees a sampleable layout and keeps the image alive until
//     wait_sync_index() returns for that sync index.
//   * The core never calls set_command_buffers()/set_signal_semaphore() today;
//     both are implemented defensively anyway.

#include "vulkan_bridge.h"
#include "vulkan_shaders.h"

#include <vulkan/vulkan_android.h>

#include <cstring>
#include <mutex>
#include <vector>

// Frames in flight. Must agree with get_sync_index_mask(): the core keeps one
// image alive per set bit of the mask.
constexpr uint32_t ARC_VK_SLOTS = 3;
constexpr uint32_t ARC_VK_SYNC_MASK = (1u << ARC_VK_SLOTS) - 1u;
// 1 = flip vertically in the quad shader; PCSX2 scanout needs no flip.
constexpr float ARC_VK_FLIP_Y = 0.0f;
constexpr uint64_t ARC_VK_FENCE_TIMEOUT_NS = 500ull * 1000ull * 1000ull;

// Preference / capability state (declared in vulkan_bridge.h).
std::atomic<bool> g_coreSupportsVulkan{false};
std::atomic<bool> g_vulkanRequested{false};
std::atomic<bool> g_vulkanFailed{false};

namespace {

// One slot per frame in flight. The retro_vulkan_image pointer passed to
// set_image() is only valid until retro_video_refresh returns, so the fields
// we need are copied into the slot at set_image() time.
struct ArcVkSlot {
  VkCommandBuffer cmd = VK_NULL_HANDLE;
  VkSemaphore acquire = VK_NULL_HANDLE;    // image acquired from swapchain
  VkSemaphore renderDone = VK_NULL_HANDLE; // signalled when our draw finishes
  VkFence fence = VK_NULL_HANDLE;
  VkDescriptorSet descriptor = VK_NULL_HANDLE;
  bool fenceSignalled = false;
  std::vector<VkCommandBuffer> coreCmds; // from set_command_buffers()
  bool hasImage = false;
  VkImageView imageView = VK_NULL_HANDLE;
  VkImageLayout imageLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  // Core-provided sync (contract: wait on them before sampling the image).
  std::vector<VkSemaphore> waitSemaphores;
  uint32_t srcQueueFamily = VK_QUEUE_FAMILY_IGNORED;
};

struct ArcVkState {
  // --- loader ---
  void *lib = nullptr;
  PFN_vkGetInstanceProcAddr gipa = nullptr;
  PFN_vkGetDeviceProcAddr gdpa = nullptr;
  PFN_vkCreateAndroidSurfaceKHR createAndroidSurface = nullptr;

  // --- instance / device ---
  VkInstance instance = VK_NULL_HANDLE;
  bool ownsInstance = false;
  VkPhysicalDevice gpu = VK_NULL_HANDLE;
  VkDevice device = VK_NULL_HANDLE;
  VkQueue queue = VK_NULL_HANDLE;
  uint32_t queueFamily = 0;
  VkSurfaceKHR surface = VK_NULL_HANDLE;

  // --- swapchain ---
  VkSwapchainKHR swapchain = VK_NULL_HANDLE;
  std::vector<VkImage> swapImages;
  std::vector<VkImageView> swapViews;
  std::vector<VkFramebuffer> framebuffers;
  VkFormat swapFormat = VK_FORMAT_B8G8R8A8_UNORM;
  VkExtent2D swapExtent = {0, 0};
  uint32_t imageIndex = 0;

  // --- present pipeline (fullscreen quad sampling the core's view) ---
  VkRenderPass renderPass = VK_NULL_HANDLE;
  VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
  VkPipeline pipeline = VK_NULL_HANDLE;
  VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
  VkDescriptorPool dpool = VK_NULL_HANDLE;
  VkSampler sampler = VK_NULL_HANDLE;
  VkCommandPool cmdPool = VK_NULL_HANDLE;

  // --- frames in flight ---
  ArcVkSlot slots[ARC_VK_SLOTS];
  uint32_t syncIndex = 0;

  // --- core-supplied ---
  const retro_hw_render_context_negotiation_interface_vulkan *neg = nullptr;
  VkSemaphore coreSignalSema = VK_NULL_HANDLE;
  bool coreSignalPending = false;

  // --- state flags ---
  bool ready = false;         // instance+device+interface built
  bool swapchainReady = false;
  bool failed = false;
  bool contextActive = false; // core context has been reset, destroy pending
  bool surfaceLost = false;
  std::atomic<uint64_t> presentedFrames{0};

  std::recursive_mutex presentMutex; // serializes vulkanPresent + teardown
  std::recursive_mutex queueMutex;   // lock_queue/unlock_queue + our submits
};

ArcVkState g_vk;

// VkResult -> readable name for diagnostics.
const char *arcVkResultName(VkResult r) {
  switch (r) {
  case VK_SUCCESS: return "VK_SUCCESS";
  case VK_NOT_READY: return "VK_NOT_READY";
  case VK_TIMEOUT: return "VK_TIMEOUT";
  case VK_SUBOPTIMAL_KHR: return "VK_SUBOPTIMAL_KHR";
  case VK_ERROR_OUT_OF_DATE_KHR: return "VK_ERROR_OUT_OF_DATE_KHR";
  case VK_ERROR_SURFACE_LOST_KHR: return "VK_ERROR_SURFACE_LOST_KHR";
  case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
  case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
  case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
  case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
  case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
  case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
  case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
  default: return "VK_ERROR_<other>";
  }
}

// Sampleable layouts the core is allowed to hand us. Anything else means the
// core broke the contract; sampling it would be undefined behaviour.
bool arcVkLayoutIsSampleable(VkImageLayout l) {
  return l == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL ||
         l == VK_IMAGE_LAYOUT_GENERAL;
}

} // namespace

#include <dlfcn.h>

namespace {

// --- resolved Vulkan entry points (loader-anchored, no symbol-wrapper risk) ---
PFN_vkDestroyInstance p_vkDestroyInstance;
PFN_vkEnumeratePhysicalDevices p_vkEnumeratePhysicalDevices;
PFN_vkGetPhysicalDeviceQueueFamilyProperties p_vkGetPhysicalDeviceQueueFamilyProperties;
PFN_vkGetPhysicalDeviceProperties p_vkGetPhysicalDeviceProperties;
PFN_vkGetPhysicalDeviceSurfaceSupportKHR p_vkGetPhysicalDeviceSurfaceSupportKHR;
PFN_vkGetPhysicalDeviceSurfaceFormatsKHR p_vkGetPhysicalDeviceSurfaceFormatsKHR;
PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR p_vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
PFN_vkGetPhysicalDeviceSurfacePresentModesKHR p_vkGetPhysicalDeviceSurfacePresentModesKHR;
PFN_vkCreateAndroidSurfaceKHR p_vkCreateAndroidSurfaceKHR;
PFN_vkDestroySurfaceKHR p_vkDestroySurfaceKHR;
PFN_vkCreateDevice p_vkCreateDevice;
PFN_vkEnumerateDeviceExtensionProperties p_vkEnumerateDeviceExtensionProperties;
PFN_vkGetDeviceQueue p_vkGetDeviceQueue;
PFN_vkDestroyDevice p_vkDestroyDevice;
PFN_vkCreateSwapchainKHR p_vkCreateSwapchainKHR;
PFN_vkDestroySwapchainKHR p_vkDestroySwapchainKHR;
PFN_vkGetSwapchainImagesKHR p_vkGetSwapchainImagesKHR;
PFN_vkAcquireNextImageKHR p_vkAcquireNextImageKHR;
PFN_vkQueuePresentKHR p_vkQueuePresentKHR;
PFN_vkQueueSubmit p_vkQueueSubmit;
PFN_vkQueueWaitIdle p_vkQueueWaitIdle;
PFN_vkDeviceWaitIdle p_vkDeviceWaitIdle;
PFN_vkCreateSemaphore p_vkCreateSemaphore;
PFN_vkDestroySemaphore p_vkDestroySemaphore;
PFN_vkCreateFence p_vkCreateFence;
PFN_vkDestroyFence p_vkDestroyFence;
PFN_vkWaitForFences p_vkWaitForFences;
PFN_vkResetFences p_vkResetFences;
PFN_vkCreateCommandPool p_vkCreateCommandPool;
PFN_vkDestroyCommandPool p_vkDestroyCommandPool;
PFN_vkAllocateCommandBuffers p_vkAllocateCommandBuffers;
PFN_vkResetCommandBuffer p_vkResetCommandBuffer;
PFN_vkBeginCommandBuffer p_vkBeginCommandBuffer;
PFN_vkEndCommandBuffer p_vkEndCommandBuffer;
PFN_vkCreateRenderPass p_vkCreateRenderPass;
PFN_vkDestroyRenderPass p_vkDestroyRenderPass;
PFN_vkCreateFramebuffer p_vkCreateFramebuffer;
PFN_vkDestroyFramebuffer p_vkDestroyFramebuffer;
PFN_vkCreateShaderModule p_vkCreateShaderModule;
PFN_vkDestroyShaderModule p_vkDestroyShaderModule;
PFN_vkCreatePipelineLayout p_vkCreatePipelineLayout;
PFN_vkDestroyPipelineLayout p_vkDestroyPipelineLayout;
PFN_vkCreateGraphicsPipelines p_vkCreateGraphicsPipelines;
PFN_vkDestroyPipeline p_vkDestroyPipeline;
PFN_vkCreateDescriptorSetLayout p_vkCreateDescriptorSetLayout;
PFN_vkDestroyDescriptorSetLayout p_vkDestroyDescriptorSetLayout;
PFN_vkCreateDescriptorPool p_vkCreateDescriptorPool;
PFN_vkDestroyDescriptorPool p_vkDestroyDescriptorPool;
PFN_vkAllocateDescriptorSets p_vkAllocateDescriptorSets;
PFN_vkUpdateDescriptorSets p_vkUpdateDescriptorSets;
PFN_vkCreateSampler p_vkCreateSampler;
PFN_vkDestroySampler p_vkDestroySampler;
PFN_vkCreateImageView p_vkCreateImageView;
PFN_vkDestroyImageView p_vkDestroyImageView;
PFN_vkCmdBeginRenderPass p_vkCmdBeginRenderPass;
PFN_vkCmdEndRenderPass p_vkCmdEndRenderPass;
PFN_vkCmdBindPipeline p_vkCmdBindPipeline;
PFN_vkCmdBindDescriptorSets p_vkCmdBindDescriptorSets;
PFN_vkCmdDraw p_vkCmdDraw;
PFN_vkCmdPushConstants p_vkCmdPushConstants;
PFN_vkCmdSetViewport p_vkCmdSetViewport;
PFN_vkCmdSetScissor p_vkCmdSetScissor;
PFN_vkCmdPipelineBarrier p_vkCmdPipelineBarrier;

// The interface's get_instance_proc_addr is the loader entry point itself
// (g_vk.gipa), assigned in arcVkFillInterface(). It must not be wrapped: on
// 32-bit ARM the pcs("aapcs-vfp") attribute on PFN_vkGetInstanceProcAddr makes
// any plain wrapper function type-incompatible.

} // namespace

// ---------------------------------------------------------------------------
// Capability + preference
// ---------------------------------------------------------------------------
void vulkanSetCoreSupported(bool supported) {
  if (g_coreSupportsVulkan.exchange(supported) != supported)
    LOGI("VULKAN: core %s Vulkan rendering",
         supported ? "advertises" : "does not advertise");
}

bool vulkanCoreSupportsVulkan() { return g_coreSupportsVulkan.load(); }

void vulkanSetRequested(bool requested) {
  if (g_vulkanRequested.exchange(requested) != requested)
    LOGI("VULKAN: renderer preference set to %s",
         requested ? "Vulkan (hardware)" : "Software (SW)");
}

bool vulkanRequested() { return g_vulkanRequested.load(); }

bool vulkanShouldFallBack() {
  if (!g_vulkanRequested.load())
    return false;
  if (!g_coreSupportsVulkan.load())
    return true;
  return g_vulkanFailed.load();
}

bool vulkanFailed() { return g_vulkanFailed.load(); }

bool vulkanBackendReady() { return g_vk.ready && !g_vk.failed; }

bool vulkanHasPresentedFrame() { return g_vk.presentedFrames.load() > 0; }

uint64_t vulkanPresentedFrameCount() { return g_vk.presentedFrames.load(); }

bool vulkanSurfaceLost() { return g_vk.surfaceLost; }

void vulkanNotifySurfaceLost() { g_vk.surfaceLost = true; }

bool vulkanContextActive() { return g_vk.contextActive; }

struct retro_hw_render_interface_vulkan *vulkanInterface() {
  return vulkanBackendReady() ? &g_vulkanInterface : nullptr;
}

// Cheap gate for RETRO_ENVIRONMENT_SET_HW_RENDER. Never does heavy work, the
// real bring-up happens in vulkanBeginNegotiation().
bool vulkanAcceptHwRenderRequest() {
  if (!g_vulkanRequested.load())
    return false;
  if (!g_coreSupportsVulkan.load()) {
    LOGW("VULKAN: requested but this core build has no Vulkan renderer");
    return false;
  }
  if (g_vulkanFailed.load())
    return false;
  if (!g_nativeWindow) {
    LOGW("VULKAN: requested but no native window is bound yet");
    return false;
  }
  return true;
}

// ---------------------------------------------------------------------------
// Loader, symbol resolution and the v2 negotiation wrappers handed to the core
// ---------------------------------------------------------------------------
#define ARC_I(name)                                                          \
  do {                                                                       \
    p_##name = reinterpret_cast<PFN_##name>(g_vk.gipa(inst, #name));         \
    if (!p_##name) { LOGE("VULKAN: missing instance fn " #name); return false; } \
  } while (0)

#define ARC_D(name)                                                          \
  do {                                                                       \
    p_##name =                                                               \
        reinterpret_cast<PFN_##name>(g_vk.gdpa(g_vk.device, #name));         \
    if (!p_##name) { LOGE("VULKAN: missing device fn " #name); return false; } \
  } while (0)

namespace {

bool arcVkLoadLibrary() {
  if (g_vk.gipa)
    return true;
  g_vk.lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
  if (!g_vk.lib) {
    LOGE("VULKAN: dlopen(libvulkan.so) failed: %s", dlerror());
    return false;
  }
  g_vk.gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
      dlsym(g_vk.lib, "vkGetInstanceProcAddr"));
  g_vk.gdpa = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
      dlsym(g_vk.lib, "vkGetDeviceProcAddr"));
  if (!g_vk.gipa || !g_vk.gdpa) {
    LOGE("VULKAN: loader is missing vkGetInstanceProcAddr/vkGetDeviceProcAddr");
    return false;
  }
  return true;
}

bool arcVkLoadInstanceSymbols(VkInstance inst) {
  ARC_I(vkDestroyInstance);
  ARC_I(vkEnumeratePhysicalDevices);
  ARC_I(vkGetPhysicalDeviceQueueFamilyProperties);
  ARC_I(vkGetPhysicalDeviceProperties);
  ARC_I(vkGetPhysicalDeviceSurfaceSupportKHR);
  ARC_I(vkGetPhysicalDeviceSurfaceFormatsKHR);
  ARC_I(vkGetPhysicalDeviceSurfaceCapabilitiesKHR);
  ARC_I(vkGetPhysicalDeviceSurfacePresentModesKHR);
  ARC_I(vkCreateAndroidSurfaceKHR);
  ARC_I(vkDestroySurfaceKHR);
  ARC_I(vkCreateDevice);
  ARC_I(vkEnumerateDeviceExtensionProperties);
  return true;
}

bool arcVkLoadDeviceSymbols() {
  ARC_D(vkGetDeviceQueue);
  ARC_D(vkDestroyDevice);
  ARC_D(vkCreateSwapchainKHR);
  ARC_D(vkDestroySwapchainKHR);
  ARC_D(vkGetSwapchainImagesKHR);
  ARC_D(vkAcquireNextImageKHR);
  ARC_D(vkQueuePresentKHR);
  ARC_D(vkQueueSubmit);
  ARC_D(vkQueueWaitIdle);
  ARC_D(vkDeviceWaitIdle);
  ARC_D(vkCreateSemaphore);
  ARC_D(vkDestroySemaphore);
  ARC_D(vkCreateFence);
  ARC_D(vkDestroyFence);
  ARC_D(vkWaitForFences);
  ARC_D(vkResetFences);
  ARC_D(vkCreateCommandPool);
  ARC_D(vkDestroyCommandPool);
  ARC_D(vkAllocateCommandBuffers);
  ARC_D(vkResetCommandBuffer);
  ARC_D(vkBeginCommandBuffer);
  ARC_D(vkEndCommandBuffer);
  ARC_D(vkCreateRenderPass);
  ARC_D(vkDestroyRenderPass);
  ARC_D(vkCreateFramebuffer);
  ARC_D(vkDestroyFramebuffer);
  ARC_D(vkCreateShaderModule);
  ARC_D(vkDestroyShaderModule);
  ARC_D(vkCreatePipelineLayout);
  ARC_D(vkDestroyPipelineLayout);
  ARC_D(vkCreateGraphicsPipelines);
  ARC_D(vkDestroyPipeline);
  ARC_D(vkCreateDescriptorSetLayout);
  ARC_D(vkDestroyDescriptorSetLayout);
  ARC_D(vkCreateDescriptorPool);
  ARC_D(vkDestroyDescriptorPool);
  ARC_D(vkAllocateDescriptorSets);
  ARC_D(vkUpdateDescriptorSets);
  ARC_D(vkCreateSampler);
  ARC_D(vkDestroySampler);
  ARC_D(vkCreateImageView);
  ARC_D(vkDestroyImageView);
  ARC_D(vkCmdBeginRenderPass);
  ARC_D(vkCmdEndRenderPass);
  ARC_D(vkCmdBindPipeline);
  ARC_D(vkCmdBindDescriptorSets);
  ARC_D(vkCmdDraw);
  ARC_D(vkCmdPushConstants);
  ARC_D(vkCmdSetViewport);
  ARC_D(vkCmdSetScissor);
  ARC_D(vkCmdPipelineBarrier);
  return true;
}

} // namespace

// v2 create_instance wrapper: appends exactly the surface extensions the
// frontend needs for presentation, changes nothing else the core requested.
VkInstance arcVkCreateInstanceWrapper(void *opaque,
                                      const VkInstanceCreateInfo *ci) {
  (void)opaque;
  if (!ci)
    return VK_NULL_HANDLE;
  std::vector<const char *> exts(ci->enabledExtensionCount);
  for (uint32_t i = 0; i < ci->enabledExtensionCount; ++i)
    exts[i] = ci->ppEnabledExtensionNames[i];
  auto add = [&](const char *e) {
    for (const char *x : exts)
      if (!strcmp(x, e))
        return;
    exts.push_back(e);
  };
  add(VK_KHR_SURFACE_EXTENSION_NAME);
  add(VK_KHR_ANDROID_SURFACE_EXTENSION_NAME);
  VkInstanceCreateInfo info = *ci;
  info.enabledExtensionCount = static_cast<uint32_t>(exts.size());
  info.ppEnabledExtensionNames = exts.data();
  auto createI = reinterpret_cast<PFN_vkCreateInstance>(
      g_vk.gipa(VK_NULL_HANDLE, "vkCreateInstance"));
  if (!createI) {
    LOGE("VULKAN: loader has no vkCreateInstance");
    return VK_NULL_HANDLE;
  }
  VkResult r = createI(&info, nullptr, &g_vk.instance);
  if (r != VK_SUCCESS) {
    LOGE("VULKAN: vkCreateInstance failed: %s", arcVkResultName(r));
    return VK_NULL_HANDLE;
  }
  g_vk.ownsInstance = true;
  return g_vk.instance;
}

// v2 create_device_wrapper: appends VK_KHR_swapchain (the frontend presents
// from this device), keeps every extension/feature the core asked for.
VkDevice arcVkCreateDeviceWrapper(VkPhysicalDevice gpu, void *opaque,
                                  const VkDeviceCreateInfo *ci) {
  (void)opaque;
  if (!ci)
    return VK_NULL_HANDLE;
  std::vector<const char *> exts(ci->enabledExtensionCount);
  for (uint32_t i = 0; i < ci->enabledExtensionCount; ++i)
    exts[i] = ci->ppEnabledExtensionNames[i];
  auto add = [&](const char *e) {
    for (const char *x : exts)
      if (!strcmp(x, e))
        return;
    exts.push_back(e);
  };
  add(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
  VkDeviceCreateInfo info = *ci;
  info.enabledExtensionCount = static_cast<uint32_t>(exts.size());
  info.ppEnabledExtensionNames = exts.data();
  VkResult r = p_vkCreateDevice(gpu, &info, nullptr, &g_vk.device);
  if (r != VK_SUCCESS) {
    LOGE("VULKAN: vkCreateDevice failed: %s", arcVkResultName(r));
    return VK_NULL_HANDLE;
  }
  return g_vk.device;
}

// --- resource helpers (defined below) ---------------------------------------
static bool arcVkCreateSwapchainResources();
static void arcVkDestroySwapchainResources();
static bool arcVkCreatePresentPipeline();

// Interface callbacks bound by arcVkFillInterface() (defined at the end of
// this file; declared here so the binding compiles before their definitions).
void vulkanSetImage(void *handle, const struct retro_vulkan_image *image,
                    uint32_t num_semaphores, const VkSemaphore *semaphores,
                    uint32_t src_queue_family);
uint32_t vulkanGetSyncIndex(void *handle);
uint32_t vulkanGetSyncIndexMask(void *handle);
void vulkanSetCommandBuffers(void *handle, uint32_t num_cmd,
                             const VkCommandBuffer *cmd);
void vulkanWaitSyncIndex(void *handle);
void vulkanLockQueue(void *handle);
void vulkanUnlockQueue(void *handle);
void vulkanSetSignalSemaphore(void *handle, VkSemaphore semaphore);

// Fill the interface the core fetches through GET_HW_RENDER_INTERFACE. The
// core dereferences this immediately (GSDeviceVK), so it is only populated
// once every object it points at actually exists.
void arcVkFillInterface() {
  g_vulkanInterface.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
  g_vulkanInterface.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
  g_vulkanInterface.handle = nullptr;
  g_vulkanInterface.instance = g_vk.instance;
  g_vulkanInterface.gpu = g_vk.gpu;
  g_vulkanInterface.device = g_vk.device;
  g_vulkanInterface.get_device_proc_addr = g_vk.gdpa;
  // Hand the core the real loader entry point: it resolves its own Vulkan calls
  // through this (eglGetProcAddress would hand it GLES symbols or NULL).
  g_vulkanInterface.get_instance_proc_addr = g_vk.gipa;
  g_vulkanInterface.queue = g_vk.queue;
  g_vulkanInterface.queue_index = g_vk.queueFamily;
  g_vulkanInterface.set_image = vulkanSetImage;
  g_vulkanInterface.get_sync_index = vulkanGetSyncIndex;
  g_vulkanInterface.get_sync_index_mask = vulkanGetSyncIndexMask;
  g_vulkanInterface.set_command_buffers = vulkanSetCommandBuffers;
  g_vulkanInterface.wait_sync_index = vulkanWaitSyncIndex;
  g_vulkanInterface.lock_queue = vulkanLockQueue;
  g_vulkanInterface.unlock_queue = vulkanUnlockQueue;
  g_vulkanInterface.set_signal_semaphore = vulkanSetSignalSemaphore;
}

bool vulkanBeginNegotiation(
    const retro_hw_render_context_negotiation_interface_vulkan *iface) {
  if (!iface) {
    LOGE("VULKAN: negotiation without interface");
    g_vulkanFailed.store(true);
    return false;
  }
  g_vk.neg = iface;
  if (g_vk.ready)
    return true;
  if (!arcVkLoadLibrary()) {
    g_vulkanFailed.store(true);
    return false;
  }

  // --- instance: prefer the core's create_instance (v2) so it can enable its
  // own instance-level extensions; our wrapper appends the surface ones. ---
  static VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO,
                                  nullptr, "ArcEmulator", 1, "ArcEmulator", 1,
                                  VK_MAKE_VERSION(1, 1, 0)};
  const VkApplicationInfo *appInfo =
      iface->get_application_info ? iface->get_application_info() : &app;
  if (!appInfo)
    appInfo = &app;
  if (iface->create_instance) {
    g_vk.instance = iface->create_instance(
        g_vk.gipa, appInfo, arcVkCreateInstanceWrapper, nullptr);
  } else {
    const char *exts[] = {VK_KHR_SURFACE_EXTENSION_NAME,
                          VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo ci = {};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = appInfo;
    ci.enabledExtensionCount = 2;
    ci.ppEnabledExtensionNames = exts;
    auto createI = reinterpret_cast<PFN_vkCreateInstance>(
        g_vk.gipa(VK_NULL_HANDLE, "vkCreateInstance"));
    if (!createI) {
      LOGE("VULKAN: loader has no vkCreateInstance");
      g_vulkanFailed.store(true);
      return false;
    }
    VkResult r = createI(&ci, nullptr, &g_vk.instance);
    if (r != VK_SUCCESS) {
      LOGE("VULKAN: vkCreateInstance failed: %s", arcVkResultName(r));
      g_vulkanFailed.store(true);
      return false;
    }
    g_vk.ownsInstance = true;
  }
  if (!g_vk.instance || !arcVkLoadInstanceSymbols(g_vk.instance)) {
    LOGE("VULKAN: instance creation/symbol load failed");
    g_vulkanFailed.store(true);
    return false;
  }
  // --- Android surface: copy the window under the lock, never hold the mutex
  // across Vulkan calls or core callbacks. ---
  ANativeWindow *win = nullptr;
  {
    std::lock_guard<std::mutex> lk(g_windowMutex);
    win = g_nativeWindow;
  }
  if (!win) {
    LOGE("VULKAN: no native window for surface");
    g_vulkanFailed.store(true);
    return false;
  }
  VkAndroidSurfaceCreateInfoKHR asci = {};
  asci.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
  asci.window = win;
  VkResult sr =
      p_vkCreateAndroidSurfaceKHR(g_vk.instance, &asci, nullptr, &g_vk.surface);
  if (sr != VK_SUCCESS) {
    LOGE("VULKAN: surface creation failed: %s", arcVkResultName(sr));
    g_vulkanFailed.store(true);
    return false;
  }

  // --- GPU: graphics+compute+present (LRPS2 requires compute on its queue) ---
  uint32_t gpuCount = 0;
  p_vkEnumeratePhysicalDevices(g_vk.instance, &gpuCount, nullptr);
  if (!gpuCount) {
    LOGE("VULKAN: no physical devices");
    g_vulkanFailed.store(true);
    return false;
  }
  std::vector<VkPhysicalDevice> gpus(gpuCount);
  p_vkEnumeratePhysicalDevices(g_vk.instance, &gpuCount, gpus.data());
  g_vk.gpu = VK_NULL_HANDLE;
  for (uint32_t i = 0; i < gpuCount && !g_vk.gpu; ++i) {
    uint32_t n = 0;
    p_vkGetPhysicalDeviceQueueFamilyProperties(gpus[i], &n, nullptr);
    std::vector<VkQueueFamilyProperties> props(n);
    p_vkGetPhysicalDeviceQueueFamilyProperties(gpus[i], &n, props.data());
    for (uint32_t f = 0; f < n; ++f) {
      if (!(props[f].queueFlags & VK_QUEUE_GRAPHICS_BIT) ||
          !(props[f].queueFlags & VK_QUEUE_COMPUTE_BIT))
        continue;
      VkBool32 canPresent = VK_FALSE;
      p_vkGetPhysicalDeviceSurfaceSupportKHR(gpus[i], f, g_vk.surface,
                                             &canPresent);
      if (canPresent) {
        g_vk.gpu = gpus[i];
        g_vk.queueFamily = f;
        break;
      }
    }
  }
  if (!g_vk.gpu) {
    LOGE("VULKAN: no GPU with graphics+compute+present");
    g_vulkanFailed.store(true);
    return false;
  }
  VkPhysicalDeviceProperties gpuProps = {};
  p_vkGetPhysicalDeviceProperties(g_vk.gpu, &gpuProps);
  LOGI("VULKAN: using GPU %s", gpuProps.deviceName);

  // --- device: created by the core through our wrapper (adds swapchain ext) ---
  struct retro_vulkan_context ctx = {};
  bool ok = false;
  if (iface->create_device2) {
    ok = iface->create_device2(&ctx, g_vk.instance, g_vk.gpu, g_vk.surface,
                               g_vk.gipa, arcVkCreateDeviceWrapper, nullptr);
  } else if (iface->create_device) {
    const char *req = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
    ok = iface->create_device(&ctx, g_vk.instance, g_vk.gpu, g_vk.surface,
                              g_vk.gipa, &req, 1, nullptr, 0, nullptr);
  }
  if (!ok || !ctx.device) {
    LOGE("VULKAN: core device creation failed");
    g_vulkanFailed.store(true);
    return false;
  }
  g_vk.device = ctx.device;
  g_vk.queue = ctx.queue;
  g_vk.queueFamily = ctx.queue_family_index;
  if (!arcVkLoadDeviceSymbols()) {
    g_vulkanFailed.store(true);
    return false;
  }
  LOGI("VULKAN: device ready (queue family %u)", g_vk.queueFamily);

  // --- frontend-owned swapchain + presentation pipeline (the core never sees
  // these: it renders to its own image and hands us a view). ---
  // Swapchain first: it picks the surface format/extent that the render pass,
  // framebuffers and pipeline are built from.
  if (!arcVkCreateSwapchainResources() || !arcVkCreatePresentPipeline()) {
    LOGE("VULKAN: swapchain/pipeline creation failed");
    g_vulkanFailed.store(true);
    return false;
  }
  arcVkFillInterface();
  g_vk.ready = true;
  g_vulkanInitialized = true; // makes UnloadCore call deinitVulkan()
  LOGI("VULKAN: backend ready (%ux%u, %u swapchain images)",
       g_vk.swapExtent.width, g_vk.swapExtent.height,
       (uint32_t)g_vk.swapImages.size());
  return true;
}

// Render pass + fullscreen-quad pipeline + sampler + descriptor pool. The quad
// samples the core's image view into a swapchain image - a blit is impossible
// because set_image() never hands us a VkImage.
static bool arcVkCreatePresentPipeline() {
  VkAttachmentDescription color = {};
  color.format = g_vk.swapFormat;
  color.samples = VK_SAMPLE_COUNT_1_BIT;
  color.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
  color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
  color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
  color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
  color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  color.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
  VkAttachmentReference colorRef = {0,
                                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
  VkSubpassDescription sub = {};
  sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
  sub.colorAttachmentCount = 1;
  sub.pColorAttachments = &colorRef;
  VkSubpassDependency dep = {};
  dep.srcSubpass = VK_SUBPASS_EXTERNAL;
  dep.dstSubpass = 0;
  dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
  dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
  dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
  VkRenderPassCreateInfo rp = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
                               nullptr, 0, 1, &color, 1, &sub, 1, &dep};
  if (p_vkCreateRenderPass(g_vk.device, &rp, nullptr, &g_vk.renderPass) !=
      VK_SUCCESS) {
    LOGE("VULKAN: render pass creation failed");
    return false;
  }

  // set 0 binding 0: combined image sampler, fragment stage (quad.frag).
  VkDescriptorSetLayoutBinding samp = {};
  samp.binding = 0;
  samp.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
  samp.descriptorCount = 1;
  samp.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
  VkDescriptorSetLayoutCreateInfo dsl = {
      VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, nullptr, 0, 1,
      &samp};
  if (p_vkCreateDescriptorSetLayout(g_vk.device, &dsl, nullptr, &g_vk.dsl) !=
      VK_SUCCESS) {
    LOGE("VULKAN: descriptor set layout failed");
    return false;
  }

  // pipeline layout: one 4-byte push constant (flip_y) read by the VS.
  VkPushConstantRange pcr = {VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(float)};
  VkPipelineLayoutCreateInfo pl = {
      VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, nullptr, 0, 1, &g_vk.dsl,
      1, &pcr};
  if (p_vkCreatePipelineLayout(g_vk.device, &pl, nullptr,
                               &g_vk.pipelineLayout) != VK_SUCCESS) {
    LOGE("VULKAN: pipeline layout failed");
    return false;
  }
    VkShaderModuleCreateInfo vsInfo = {
      VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, nullptr, 0,
      arc_quad_vert_spv_size, arc_quad_vert_spv};
  VkShaderModuleCreateInfo fsInfo = {
      VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, nullptr, 0,
      arc_quad_frag_spv_size, arc_quad_frag_spv};
  VkShaderModule vs = VK_NULL_HANDLE, fs = VK_NULL_HANDLE;
  if (p_vkCreateShaderModule(g_vk.device, &vsInfo, nullptr, &vs) != VK_SUCCESS ||
      p_vkCreateShaderModule(g_vk.device, &fsInfo, nullptr, &fs) != VK_SUCCESS) {
    LOGE("VULKAN: shader module creation failed");
    return false;
  }
  VkPipelineShaderStageCreateInfo stages[2] = {};
  stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
  stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
  stages[0].module = vs;
  stages[0].pName = "main";
  stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
  stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
  stages[1].module = fs;
  stages[1].pName = "main";

  VkPipelineVertexInputStateCreateInfo vi = {
      VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
  VkPipelineInputAssemblyStateCreateInfo ia = {
      VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO, nullptr, 0,
      VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST};
  VkViewport dummyVp = {0, 0, 1, 1, 0, 1};
  VkRect2D dummySc = {{0, 0}, {1, 1}};
  VkPipelineViewportStateCreateInfo vps = {
      VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, nullptr, 0, 1,
      &dummyVp, 1, &dummySc};
  VkPipelineRasterizationStateCreateInfo rs = {
      VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
      nullptr, 0, VK_FALSE, VK_FALSE, VK_POLYGON_MODE_FILL, VK_CULL_MODE_NONE,
      VK_FRONT_FACE_COUNTER_CLOCKWISE};
  rs.lineWidth = 1.0f;
  VkPipelineMultisampleStateCreateInfo ms = {
      VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO, nullptr, 0,
      VK_SAMPLE_COUNT_1_BIT};
  VkPipelineDepthStencilStateCreateInfo ds = {
      VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO, nullptr, 0,
      VK_FALSE, VK_FALSE};
  VkPipelineColorBlendAttachmentState blendAtt = {};
  blendAtt.colorWriteMask = 0xF;
  VkPipelineColorBlendStateCreateInfo cb = {
      VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO, nullptr, 0,
      VK_FALSE, VK_LOGIC_OP_COPY, 1, &blendAtt};
  VkDynamicState dyn[2] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
  VkPipelineDynamicStateCreateInfo dynInfo = {
      VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO, nullptr, 0, 2, dyn};
  VkGraphicsPipelineCreateInfo gp = {
      VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
  gp.stageCount = 2;
  gp.pStages = stages;
  gp.pVertexInputState = &vi;
  gp.pInputAssemblyState = &ia;
  gp.pViewportState = &vps;
  gp.pRasterizationState = &rs;
  gp.pMultisampleState = &ms;
  gp.pDepthStencilState = &ds;
  gp.pColorBlendState = &cb;
  gp.pDynamicState = &dynInfo;
  gp.layout = g_vk.pipelineLayout;
  gp.renderPass = g_vk.renderPass;
  gp.subpass = 0;
  VkResult pr = p_vkCreateGraphicsPipelines(g_vk.device, VK_NULL_HANDLE, 1, &gp,
                                            nullptr, &g_vk.pipeline);
  p_vkDestroyShaderModule(g_vk.device, vs, nullptr);
  p_vkDestroyShaderModule(g_vk.device, fs, nullptr);
  if (pr != VK_SUCCESS) {
    LOGE("VULKAN: graphics pipeline failed: %s", arcVkResultName(pr));
    return false;
  }
  VkSamplerCreateInfo sc = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
                            nullptr, 0, VK_FILTER_LINEAR, VK_FILTER_LINEAR,
                            VK_SAMPLER_MIPMAP_MODE_NEAREST,
                            VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                            VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                            VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                            0.0f, VK_FALSE, 1.0f, VK_FALSE, VK_COMPARE_OP_ALWAYS,
                            0.0f, 0.0f, VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK,
                            VK_FALSE};
  if (p_vkCreateSampler(g_vk.device, &sc, nullptr, &g_vk.sampler) != VK_SUCCESS) {
    LOGE("VULKAN: sampler creation failed");
    return false;
  }

  VkDescriptorPoolSize ps = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                             ARC_VK_SLOTS};
  VkDescriptorPoolCreateInfo dpci = {
      VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, nullptr, 0, ARC_VK_SLOTS,
      1, &ps};
  if (p_vkCreateDescriptorPool(g_vk.device, &dpci, nullptr, &g_vk.dpool) !=
      VK_SUCCESS) {
    LOGE("VULKAN: descriptor pool failed");
    return false;
  }

  // Device-level presentation objects: created once, survive swapchain rebuilds.
  if (g_vk.cmdPool == VK_NULL_HANDLE) {
    VkCommandPoolCreateInfo cp = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cp.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cp.queueFamilyIndex = g_vk.queueFamily;
    if (p_vkCreateCommandPool(g_vk.device, &cp, nullptr, &g_vk.cmdPool) !=
        VK_SUCCESS) {
      LOGE("VULKAN: command pool failed");
      return false;
    }
    VkCommandBufferAllocateInfo cba = {
        VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cba.commandPool = g_vk.cmdPool;
    cba.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cba.commandBufferCount = ARC_VK_SLOTS;
    VkCommandBuffer bufs[ARC_VK_SLOTS] = {VK_NULL_HANDLE, VK_NULL_HANDLE,
                                          VK_NULL_HANDLE};
    if (p_vkAllocateCommandBuffers(g_vk.device, &cba, bufs) != VK_SUCCESS) {
      LOGE("VULKAN: command buffer allocation failed");
      return false;
    }
    VkSemaphoreCreateInfo semInfo = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VkFenceCreateInfo fenceInfo = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    for (uint32_t i = 0; i < ARC_VK_SLOTS; ++i) {
      ArcVkSlot &s = g_vk.slots[i];
      s.cmd = bufs[i];
      s.fenceSignalled = false;
      if (p_vkCreateSemaphore(g_vk.device, &semInfo, nullptr, &s.acquire) !=
              VK_SUCCESS ||
          p_vkCreateSemaphore(g_vk.device, &semInfo, nullptr, &s.renderDone) !=
              VK_SUCCESS ||
          p_vkCreateFence(g_vk.device, &fenceInfo, nullptr, &s.fence) !=
              VK_SUCCESS) {
        LOGE("VULKAN: per-slot sync objects failed");
        return false;
      }
    }
  }

  // One descriptor set per slot (re-allocated whenever the pool is recreated).
  VkDescriptorSetLayout layouts[ARC_VK_SLOTS] = {g_vk.dsl, g_vk.dsl, g_vk.dsl};
  VkDescriptorSet sets[ARC_VK_SLOTS] = {VK_NULL_HANDLE, VK_NULL_HANDLE,
                                        VK_NULL_HANDLE};
  VkDescriptorSetAllocateInfo dsa = {
      VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
  dsa.descriptorPool = g_vk.dpool;
  dsa.descriptorSetCount = ARC_VK_SLOTS;
  dsa.pSetLayouts = layouts;
  if (p_vkAllocateDescriptorSets(g_vk.device, &dsa, sets) != VK_SUCCESS) {
    LOGE("VULKAN: descriptor set allocation failed");
    return false;
  }
  for (uint32_t i = 0; i < ARC_VK_SLOTS; ++i)
    g_vk.slots[i].descriptor = sets[i];

  // One framebuffer per swapchain image (needs render pass + swap views).
  g_vk.framebuffers.assign(g_vk.swapViews.size(), VK_NULL_HANDLE);
  for (size_t i = 0; i < g_vk.swapViews.size(); ++i) {
    VkFramebufferCreateInfo fb = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fb.renderPass = g_vk.renderPass;
    fb.attachmentCount = 1;
    fb.pAttachments = &g_vk.swapViews[i];
    fb.width = g_vk.swapExtent.width;
    fb.height = g_vk.swapExtent.height;
    fb.layers = 1;
    if (p_vkCreateFramebuffer(g_vk.device, &fb, nullptr,
                              &g_vk.framebuffers[i]) != VK_SUCCESS) {
      LOGE("VULKAN: framebuffer creation failed");
      return false;
    }
  }
  LOGI("VULKAN: present pipeline ready (%ux%u, %d swap images)",
       g_vk.swapExtent.width, g_vk.swapExtent.height,
       (int)g_vk.swapViews.size());
  return true;
}

// Creates the swapchain + image views and records the surface format/extent
// that the render pass, framebuffers and pipeline are built from.
static bool arcVkCreateSwapchainResources() {
  VkSurfaceCapabilitiesKHR caps = {};
  if (p_vkGetPhysicalDeviceSurfaceCapabilitiesKHR(g_vk.gpu, g_vk.surface,
                                                  &caps) != VK_SUCCESS) {
    LOGE("VULKAN: surface capabilities query failed");
    return false;
  }
  uint32_t formatCount = 0;
  p_vkGetPhysicalDeviceSurfaceFormatsKHR(g_vk.gpu, g_vk.surface, &formatCount,
                                         nullptr);
  if (!formatCount) {
    LOGE("VULKAN: surface exposes no formats");
    return false;
  }
  std::vector<VkSurfaceFormatKHR> formats(formatCount);
  p_vkGetPhysicalDeviceSurfaceFormatsKHR(g_vk.gpu, g_vk.surface, &formatCount,
                                         formats.data());
  VkSurfaceFormatKHR chosen = formats[0];
  if (chosen.format == VK_FORMAT_UNDEFINED) {
    chosen.format = VK_FORMAT_B8G8R8A8_UNORM;
    chosen.colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
  }
  for (uint32_t i = 0; i < formatCount; ++i) {
    if (formats[i].format == VK_FORMAT_B8G8R8A8_UNORM &&
        formats[i].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
      chosen = formats[i];
      break;
    }
  }
  g_vk.swapFormat = chosen.format;

  // currentExtent is authoritative on Android unless it is 0xFFFFFFFF.
  uint32_t w = caps.currentExtent.width;
  uint32_t h = caps.currentExtent.height;
  if (w == 0xFFFFFFFFu || h == 0xFFFFFFFFu) {
    int nw = 0, nh = 0;
    {
      std::lock_guard<std::mutex> lk(g_windowMutex);
      if (g_nativeWindow) {
        nw = ANativeWindow_getWidth(g_nativeWindow);
        nh = ANativeWindow_getHeight(g_nativeWindow);
      }
    }
    w = nw > 0 ? (uint32_t)nw : 1280u;
    h = nh > 0 ? (uint32_t)nh : 720u;
  }
  if (w < caps.minImageExtent.width)
    w = caps.minImageExtent.width;
  if (h < caps.minImageExtent.height)
    h = caps.minImageExtent.height;
  if (w > caps.maxImageExtent.width)
    w = caps.maxImageExtent.width;
  if (h > caps.maxImageExtent.height)
    h = caps.maxImageExtent.height;
  g_vk.swapExtent.width = w;
  g_vk.swapExtent.height = h;

  uint32_t imageCount = caps.minImageCount + 1;
  if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount)
    imageCount = caps.maxImageCount;

  VkSwapchainCreateInfoKHR sci = {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
  sci.surface = g_vk.surface;
  sci.minImageCount = imageCount;
  sci.imageFormat = g_vk.swapFormat;
  sci.imageColorSpace = chosen.colorSpace;
  sci.imageExtent = g_vk.swapExtent;
  sci.imageArrayLayers = 1;
  sci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
  sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
  sci.preTransform = caps.currentTransform;
  sci.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
  // FIFO always exists and paces to the display; the emu loop already does its
  // own frame limiting, so this adds no extra latency.
  sci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
  sci.clipped = VK_TRUE;
  VkResult r =
      p_vkCreateSwapchainKHR(g_vk.device, &sci, nullptr, &g_vk.swapchain);
  if (r != VK_SUCCESS) {
    LOGE("VULKAN: swapchain creation failed: %s", arcVkResultName(r));
    return false;
  }

  uint32_t count = 0;
  p_vkGetSwapchainImagesKHR(g_vk.device, g_vk.swapchain, &count, nullptr);
  if (!count) {
    LOGE("VULKAN: swapchain has no images");
    return false;
  }
  g_vk.swapImages.assign(count, VK_NULL_HANDLE);
  p_vkGetSwapchainImagesKHR(g_vk.device, g_vk.swapchain, &count,
                            g_vk.swapImages.data());
  g_vk.swapViews.assign(count, VK_NULL_HANDLE);
  for (uint32_t i = 0; i < count; ++i) {
    VkImageViewCreateInfo vci = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = g_vk.swapImages[i];
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = g_vk.swapFormat;
    vci.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vci.subresourceRange.levelCount = 1;
    vci.subresourceRange.layerCount = 1;
    if (p_vkCreateImageView(g_vk.device, &vci, nullptr, &g_vk.swapViews[i]) !=
        VK_SUCCESS) {
      LOGE("VULKAN: swapchain image view failed");
      return false;
    }
  }
  g_vk.swapchainReady = true;
  LOGI("VULKAN: swapchain %ux%u, %u images, format %d, FIFO", w, h, count,
       (int)g_vk.swapFormat);
  return true;
}

// Tears down everything that depends on the surface/window so a new window
// (rotation, app resume) rebuilds cleanly. Device-level objects - the command
// pool and per-slot sync - deliberately survive.
static void arcVkDestroySwapchainResources() {
  std::lock_guard<std::recursive_mutex> q(g_vk.queueMutex);
  if (g_vk.device)
    p_vkDeviceWaitIdle(g_vk.device);
  for (VkFramebuffer fb : g_vk.framebuffers)
    if (fb && g_vk.device)
      p_vkDestroyFramebuffer(g_vk.device, fb, nullptr);
  for (VkImageView v : g_vk.swapViews)
    if (v && g_vk.device)
      p_vkDestroyImageView(g_vk.device, v, nullptr);
  for (ArcVkSlot &s : g_vk.slots) {
    s.hasImage = false;
    s.imageView = VK_NULL_HANDLE;
    s.waitSemaphores.clear();
    s.srcQueueFamily = VK_QUEUE_FAMILY_IGNORED;
  }
  if (g_vk.swapchain && g_vk.device)
    p_vkDestroySwapchainKHR(g_vk.device, g_vk.swapchain, nullptr);
  g_vk.swapchain = VK_NULL_HANDLE;
  g_vk.swapImages.clear();
  g_vk.swapViews.clear();
  g_vk.framebuffers.clear();
  // Pipeline objects depend on the swapchain format, so they go too and
  // arcVkCreatePresentPipeline() rebuilds them on the next reset.
  if (g_vk.pipeline && g_vk.device)
    p_vkDestroyPipeline(g_vk.device, g_vk.pipeline, nullptr);
  if (g_vk.renderPass && g_vk.device)
    p_vkDestroyRenderPass(g_vk.device, g_vk.renderPass, nullptr);
  if (g_vk.dpool && g_vk.device)
    p_vkDestroyDescriptorPool(g_vk.device, g_vk.dpool, nullptr);
  if (g_vk.dsl && g_vk.device)
    p_vkDestroyDescriptorSetLayout(g_vk.device, g_vk.dsl, nullptr);
  if (g_vk.pipelineLayout && g_vk.device)
    p_vkDestroyPipelineLayout(g_vk.device, g_vk.pipelineLayout, nullptr);
  if (g_vk.sampler && g_vk.device)
    p_vkDestroySampler(g_vk.device, g_vk.sampler, nullptr);
  g_vk.pipeline = VK_NULL_HANDLE;
  g_vk.renderPass = VK_NULL_HANDLE;
  g_vk.dpool = VK_NULL_HANDLE;
  g_vk.dsl = VK_NULL_HANDLE;
  g_vk.pipelineLayout = VK_NULL_HANDLE;
  g_vk.sampler = VK_NULL_HANDLE;
  for (ArcVkSlot &s : g_vk.slots)
    s.descriptor = VK_NULL_HANDLE;
  g_vk.swapchainReady = false;
}

// Presents the image the core handed us via set_image(). Runs on the emulation
// thread; returning false just drops a frame, the core keeps running.
bool vulkanPresent() {
  std::lock_guard<std::recursive_mutex> lock(g_vk.presentMutex);
  if (!g_vk.ready || g_vk.failed || !g_vk.contextActive || !g_vk.swapchainReady)
    return false;
  ArcVkSlot &slot = g_vk.slots[g_vk.syncIndex];
  if (!slot.hasImage || slot.imageView == VK_NULL_HANDLE)
    return false;
  if (!arcVkLayoutIsSampleable(slot.imageLayout)) {
    LOGW("VULKAN: core image layout %d not sampleable; dropping frame",
         (int)slot.imageLayout);
    return false;
  }

  // Wait for our previous use of this slot before touching its fence/cmd buffer.
  if (slot.fenceSignalled) {
    VkResult w = p_vkWaitForFences(g_vk.device, 1, &slot.fence, VK_TRUE,
                                   ARC_VK_FENCE_TIMEOUT_NS);
    if (w != VK_SUCCESS) {
      LOGW("VULKAN: slot fence wait -> %s", arcVkResultName(w));
      return false;
    }
    slot.fenceSignalled = false;
  }

  uint32_t imageIndex = 0;
  VkResult ar = p_vkAcquireNextImageKHR(g_vk.device, g_vk.swapchain, UINT64_MAX,
                                        slot.acquire, VK_NULL_HANDLE,
                                        &imageIndex);
  if (ar == VK_ERROR_OUT_OF_DATE_KHR || ar == VK_SUBOPTIMAL_KHR) {
    g_vk.swapchainReady = false; // rebuilt by vulkanContextReset()
    LOGW("VULKAN: swapchain out of date (%s)", arcVkResultName(ar));
    return false;
  }
  if (ar != VK_SUCCESS) {
    LOGE("VULKAN: acquire failed: %s", arcVkResultName(ar));
    return false;
  }
  g_vk.imageIndex = imageIndex;

  // Point this slot's descriptor at the frame's core view.
  VkDescriptorImageInfo di = {};
  di.sampler = g_vk.sampler;
  di.imageView = slot.imageView;
  di.imageLayout = (slot.imageLayout == VK_IMAGE_LAYOUT_GENERAL)
                       ? VK_IMAGE_LAYOUT_GENERAL
                       : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
  VkWriteDescriptorSet wds = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
  wds.dstSet = slot.descriptor;
  wds.dstBinding = 0;
  wds.descriptorCount = 1;
  wds.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
  wds.pImageInfo = &di;
  p_vkUpdateDescriptorSets(g_vk.device, 1, &wds, 0, nullptr);

  p_vkResetCommandBuffer(slot.cmd, 0);
  VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
  bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  if (p_vkBeginCommandBuffer(slot.cmd, &bi) != VK_SUCCESS)
    return false;

  // The core hands the image over without semaphores (and cannot transition it,
  // since we only ever receive a view), so the contract's prescribed pipeline
  // barrier makes its writes visible to our fragment reads.
  VkMemoryBarrier mb = {VK_STRUCTURE_TYPE_MEMORY_BARRIER};
  mb.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                     VK_ACCESS_SHADER_WRITE_BIT |
                     VK_ACCESS_TRANSFER_WRITE_BIT;
  mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
  p_vkCmdPipelineBarrier(slot.cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 1, &mb, 0,
                         nullptr, 0, nullptr);

  VkClearValue clearColor = {};
  clearColor.color.float32[3] = 1.0f;
  VkRenderPassBeginInfo rbi = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
  rbi.renderPass = g_vk.renderPass;
  rbi.framebuffer = g_vk.framebuffers[imageIndex];
  rbi.renderArea.extent = g_vk.swapExtent;
  rbi.clearValueCount = 1;
  rbi.pClearValues = &clearColor;
  p_vkCmdBeginRenderPass(slot.cmd, &rbi, VK_SUBPASS_CONTENTS_INLINE);
  VkViewport vp = {0.0f, 0.0f, (float)g_vk.swapExtent.width,
                   (float)g_vk.swapExtent.height, 0.0f, 1.0f};
  VkRect2D rc = {{0, 0}, g_vk.swapExtent};
  p_vkCmdSetViewport(slot.cmd, 0, 1, &vp);
  p_vkCmdSetScissor(slot.cmd, 0, 1, &rc);
  p_vkCmdBindPipeline(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g_vk.pipeline);
  p_vkCmdBindDescriptorSets(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                            g_vk.pipelineLayout, 0, 1, &slot.descriptor, 0,
                            nullptr);
  float flip = ARC_VK_FLIP_Y;
  p_vkCmdPushConstants(slot.cmd, g_vk.pipelineLayout,
                       VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(float), &flip);
  p_vkCmdDraw(slot.cmd, 3, 1, 0, 0);
  p_vkCmdEndRenderPass(slot.cmd);
  if (p_vkEndCommandBuffer(slot.cmd) != VK_SUCCESS)
    return false;
  // Wait on the acquired image plus any semaphores the core attached to it
  // (consumed once, per the contract).
  std::vector<VkSemaphore> waits;
  waits.reserve(1 + slot.waitSemaphores.size());
  waits.push_back(slot.acquire);
  for (VkSemaphore s : slot.waitSemaphores)
    if (s != VK_NULL_HANDLE)
      waits.push_back(s);
  slot.waitSemaphores.clear();
  std::vector<VkPipelineStageFlags> stages(
      waits.size(), VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
  std::vector<VkSemaphore> signals;
  signals.push_back(slot.renderDone);
  if (g_vk.coreSignalPending && g_vk.coreSignalSema != VK_NULL_HANDLE) {
    signals.push_back(g_vk.coreSignalSema);
    g_vk.coreSignalPending = false;
  }

  VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
  si.waitSemaphoreCount = (uint32_t)waits.size();
  si.pWaitSemaphores = waits.data();
  si.pWaitDstStageMask = stages.data();
  si.commandBufferCount = 1;
  si.pCommandBuffers = &slot.cmd;
  si.signalSemaphoreCount = (uint32_t)signals.size();
  si.pSignalSemaphores = signals.data();

  {
    std::lock_guard<std::recursive_mutex> q(g_vk.queueMutex);
    if (p_vkResetFences(g_vk.device, 1, &slot.fence) != VK_SUCCESS)
      return false;
    if (p_vkQueueSubmit(g_vk.queue, 1, &si, slot.fence) != VK_SUCCESS) {
      LOGE("VULKAN: queue submit failed");
      return false;
    }
    slot.fenceSignalled = true;

    VkPresentInfoKHR pi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &slot.renderDone;
    pi.swapchainCount = 1;
    pi.pSwapchains = &g_vk.swapchain;
    pi.pImageIndices = &imageIndex;
    VkResult pr = p_vkQueuePresentKHR(g_vk.queue, &pi);
    if (pr == VK_ERROR_OUT_OF_DATE_KHR || pr == VK_SUBOPTIMAL_KHR) {
      g_vk.swapchainReady = false;
      LOGW("VULKAN: present out of date (%s)", arcVkResultName(pr));
      return false;
    }
    if (pr != VK_SUCCESS) {
      LOGE("VULKAN: present failed: %s", arcVkResultName(pr));
      return false;
    }
  }

  g_vk.presentedFrames.fetch_add(1);
  const uint64_t n = g_vk.presentedFrames.load();
  if (n == 1 || n % 300 == 0)
    LOGI("VULKAN: presented %llu frames (%ux%u)", (unsigned long long)n,
         g_vk.swapExtent.width, g_vk.swapExtent.height);
  g_vk.syncIndex = (g_vk.syncIndex + 1) % ARC_VK_SLOTS;
  return true;
}

// ---------------------------------------------------------------------------
// retro_hw_render_interface_vulkan callbacks (invoked by the core)
// ---------------------------------------------------------------------------
// The core registers the image to present for the current sync index. The
// pointer is only valid until retro_video_refresh returns, so copy the fields.
void vulkanSetImage(void *handle, const struct retro_vulkan_image *image,
                    uint32_t num_semaphores, const VkSemaphore *semaphores,
                    uint32_t src_queue_family) {
  (void)handle;
  std::lock_guard<std::recursive_mutex> lock(g_vk.presentMutex);
  ArcVkSlot &slot = g_vk.slots[g_vk.syncIndex];
  if (!image) {
    // context_destroy retracts the image this way.
    slot.hasImage = false;
    slot.imageView = VK_NULL_HANDLE;
    slot.imageLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    slot.waitSemaphores.clear();
    slot.srcQueueFamily = VK_QUEUE_FAMILY_IGNORED;
    return;
  }
  slot.imageView = image->image_view;
  slot.imageLayout = image->image_layout;
  slot.hasImage = true;
  slot.srcQueueFamily = src_queue_family;
  slot.waitSemaphores.assign(semaphores, semaphores + num_semaphores);
}

uint32_t vulkanGetSyncIndex(void *handle) {
  (void)handle;
  return g_vk.syncIndex;
}

uint32_t vulkanGetSyncIndexMask(void *handle) {
  (void)handle;
  return ARC_VK_SYNC_MASK;
}

// Called before the core reuses an image from this sync index.
void vulkanWaitSyncIndex(void *handle) {
  (void)handle;
  ArcVkSlot &slot = g_vk.slots[g_vk.syncIndex];
  if (slot.fenceSignalled && g_vk.device)
    p_vkWaitForFences(g_vk.device, 1, &slot.fence, VK_TRUE,
                      ARC_VK_FENCE_TIMEOUT_NS);
}

void vulkanLockQueue(void *handle) {
  (void)handle;
  g_vk.queueMutex.lock();
}

void vulkanUnlockQueue(void *handle) {
  (void)handle;
  g_vk.queueMutex.unlock();
}

// LRPS2 does not use this today; stored so a core that does cannot break us.
void vulkanSetCommandBuffers(void *handle, uint32_t num_cmd,
                             const VkCommandBuffer *cmd) {
  (void)handle;
  std::lock_guard<std::recursive_mutex> lock(g_vk.presentMutex);
  ArcVkSlot &slot = g_vk.slots[g_vk.syncIndex];
  slot.coreCmds.assign(cmd, cmd + num_cmd);
}

void vulkanSetSignalSemaphore(void *handle, VkSemaphore semaphore) {
  (void)handle;
  g_vk.coreSignalSema = semaphore;
  g_vk.coreSignalPending = semaphore != VK_NULL_HANDLE;
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------
// Recreates the surface (after a window change), the swapchain and the pipeline,
// then tells the core its context is ready. Runs on the emulation thread each
// loop iteration until the context is active.
void vulkanContextReset() {
  bool tellCore = false;
  {
    std::lock_guard<std::recursive_mutex> lock(g_vk.presentMutex);
    if (g_vk.failed || !g_vk.ready)
      return;
    if (!g_vk.surface) {
      ANativeWindow *win = nullptr;
      {
        std::lock_guard<std::mutex> lk(g_windowMutex);
        win = g_nativeWindow;
      }
      if (!win)
        return; // no window yet: the emu loop retries
      VkAndroidSurfaceCreateInfoKHR asci = {};
      asci.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
      asci.window = win;
      if (p_vkCreateAndroidSurfaceKHR(g_vk.instance, &asci, nullptr,
                                      &g_vk.surface) != VK_SUCCESS) {
        LOGE("VULKAN: surface recreation failed");
        return;
      }
    }
    if (!g_vk.swapchainReady) {
      if (!arcVkCreateSwapchainResources() || !arcVkCreatePresentPipeline()) {
        LOGE("VULKAN: context rebuild failed; will retry");
        return;
      }
    }
    if (!g_vk.contextActive) {
      g_vk.contextActive = true;
      tellCore = true;
    }
  }
  if (tellCore && g_hwRender.context_reset) {
    LOGI("VULKAN: invoking core context_reset");
    g_hwRender.context_reset();
  }
}

// Surface gone (or session ending): let the core retract its image first, then
// drop everything surface-dependent. Device and instance survive.
void vulkanContextDestroy() {
  if (g_vk.contextActive && g_hwRender.context_destroy) {
    LOGI("VULKAN: invoking core context_destroy");
    g_hwRender.context_destroy();
  }
  std::lock_guard<std::recursive_mutex> lock(g_vk.presentMutex);
  g_vk.contextActive = false;
  arcVkDestroySwapchainResources();
  if (g_vk.surface && g_vk.instance) {
    p_vkDestroySurfaceKHR(g_vk.instance, g_vk.surface, nullptr);
    g_vk.surface = VK_NULL_HANDLE;
  }
  g_vk.surfaceLost = false;
}

// Full teardown. UnloadCore() calls this AFTER dlclose(), so it must never call
// back into the core: by then the core has released its own objects through
// retro_unload_game/retro_deinit.
void deinitVulkan() {
  std::lock_guard<std::recursive_mutex> lock(g_vk.presentMutex);
  if (g_vk.device) {
    p_vkDeviceWaitIdle(g_vk.device);
    arcVkDestroySwapchainResources();
    for (ArcVkSlot &s : g_vk.slots) {
      if (s.acquire)
        p_vkDestroySemaphore(g_vk.device, s.acquire, nullptr);
      if (s.renderDone)
        p_vkDestroySemaphore(g_vk.device, s.renderDone, nullptr);
      if (s.fence)
        p_vkDestroyFence(g_vk.device, s.fence, nullptr);
      s.acquire = VK_NULL_HANDLE;
      s.renderDone = VK_NULL_HANDLE;
      s.fence = VK_NULL_HANDLE;
      s.cmd = VK_NULL_HANDLE;
      s.fenceSignalled = false;
      s.coreCmds.clear();
    }
    if (g_vk.cmdPool)
      p_vkDestroyCommandPool(g_vk.device, g_vk.cmdPool, nullptr);
    if (g_vk.surface)
      p_vkDestroySurfaceKHR(g_vk.instance, g_vk.surface, nullptr);
    p_vkDestroyDevice(g_vk.device, nullptr);
  }
  if (g_vk.instance && g_vk.ownsInstance)
    p_vkDestroyInstance(g_vk.instance, nullptr);
  if (g_vk.lib)
    dlclose(g_vk.lib);

  g_vk.lib = nullptr;
  g_vk.gipa = nullptr;
  g_vk.gdpa = nullptr;
  g_vk.instance = VK_NULL_HANDLE;
  g_vk.ownsInstance = false;
  g_vk.gpu = VK_NULL_HANDLE;
  g_vk.device = VK_NULL_HANDLE;
  g_vk.queue = VK_NULL_HANDLE;
  g_vk.queueFamily = 0;
  g_vk.surface = VK_NULL_HANDLE;
  g_vk.swapchain = VK_NULL_HANDLE;
  g_vk.cmdPool = VK_NULL_HANDLE;
  g_vk.syncIndex = 0;
  g_vk.imageIndex = 0;
  g_vk.neg = nullptr;
  g_vk.coreSignalSema = VK_NULL_HANDLE;
  g_vk.coreSignalPending = false;
  g_vk.ready = false;
  g_vk.swapchainReady = false;
  g_vk.failed = false;
  g_vk.contextActive = false;
  g_vk.surfaceLost = false;
  g_vk.presentedFrames.store(0);
  g_vulkanInitialized = false;
  g_useVulkan = false;
  g_coreSupportsVulkan.store(false);
  LOGI("VULKAN: shut down");
}

bool vulkanIsActive() {
  return g_useVulkan && vulkanBackendReady();
}

bool vulkanIsRequested() {
  return g_vulkanRequested.load();
}