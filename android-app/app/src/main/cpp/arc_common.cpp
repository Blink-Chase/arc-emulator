#include "arc_common.h"
#include <cstdlib>
#include <sys/mman.h>
#include <unistd.h>

// Global variable instances
int16_t g_audioRingBuffer[AUDIO_BUFFER_SIZE];
std::atomic<int> g_audioWritePos{0};
std::atomic<int> g_audioReadPos{0};
std::atomic<int> g_audioOverflows{0};

JavaVM *g_vm = nullptr;
jobject g_activity = nullptr;
void *g_coreHandle = nullptr;

bool g_useHwRender = false;
struct retro_hw_render_callback g_hwRender;
EGLDisplay g_eglDisplay = EGL_NO_DISPLAY;
EGLContext g_eglContext = EGL_NO_CONTEXT;
EGLSurface g_eglSurface = EGL_NO_SURFACE;
ANativeWindow *g_nativeWindow = nullptr;

int32_t g_prevWidth = 0;
int32_t g_prevHeight = 0;
int32_t g_prevFormat = 0;

std::atomic<bool> g_isRunning{false};
std::atomic<bool> g_isPaused{false};
std::atomic<bool> g_fastForward{false};
std::atomic<uint16_t> g_joypadBits{0};
std::atomic<int16_t> g_analogX{0};
std::atomic<int16_t> g_analogY{0};
std::atomic<int16_t> g_analogRightX{0};
std::atomic<int16_t> g_analogRightY{0};
std::atomic<int> g_pendingControllerType{1};
std::atomic<bool> g_isDolphinCore{false};
std::atomic<bool> g_isPcsx2Core{false};
std::atomic<int> g_pixelFormat{RETRO_PIXEL_FORMAT_RGB565};

// Vulkan Globals
struct retro_hw_render_interface_vulkan g_vulkanInterface = {};
bool g_vulkanInitialized = false;

std::thread g_emuThread;
std::mutex g_activityMutex;
std::mutex g_windowMutex;
std::recursive_mutex g_emuMutex;

// Core functions
retro_init_t core_init = nullptr;
retro_load_game_t core_load_game = nullptr;
retro_run_t core_run = nullptr;
retro_deinit_t core_deinit = nullptr;
retro_unload_game_t core_unload_game = nullptr;
retro_reset_t core_reset = nullptr;
retro_serialize_size_t core_serialize_size = nullptr;
retro_serialize_t core_serialize = nullptr;
retro_unserialize_t core_unserialize = nullptr;

retro_set_environment_t core_set_environment = nullptr;
retro_set_video_refresh_t core_set_video_refresh = nullptr;
retro_set_audio_sample_t core_set_audio_sample = nullptr;
retro_set_audio_sample_batch_t core_set_audio_sample_batch = nullptr;
retro_set_input_poll_t core_set_input_poll = nullptr;
retro_set_input_state_t core_set_input_state = nullptr;
retro_get_system_av_info_t core_get_system_av_info = nullptr;
retro_get_system_info_t core_get_system_info = nullptr;
retro_set_controller_port_device_t core_set_controller_port_device = nullptr;

struct retro_system_av_info g_avInfo;
std::string g_systemDir;
std::string g_saveDir;

std::atomic<int> g_currentFps{60};
std::atomic<int64_t> g_audioSamplesTotal{0};
std::atomic<int64_t> g_audioStartTime{0};
std::atomic<bool> g_resetDebugCounters{false};
std::atomic<int> g_videoRefreshCount{0};

std::string g_romPath;
std::atomic<bool> g_loadRequested{false};
std::atomic<bool> g_gameLoadComplete{false};
std::atomic<bool> g_gameLoadResult{false};
std::atomic<bool> g_forceOneRun{false};
std::atomic<bool> g_resetRequested{false};
std::atomic<bool> g_surfaceInvalidated{false};
std::atomic<bool> g_saveStateRequested{false};
std::atomic<bool> g_loadStateRequested{false};
std::atomic<bool> g_stateOperationSuccess{false};
std::string g_stateFilePath; // New: Path to read/write from emu thread
bool g_useVulkan = false;

uint8_t* g_stateBuffer = nullptr;
size_t g_stateBufferCapacity = 0;
size_t g_stateBufferSize = 0;
std::mutex g_stateMutex;
std::thread::id g_emuThreadId;
std::atomic<bool> g_variablesUpdated{false};
std::unordered_map<std::string, std::string> g_coreVariables;

void LogCallback(enum retro_log_level level, const char *fmt, ...) {
  va_list va;
  va_start(va, fmt);
  char buf[4012];
  vsnprintf(buf, sizeof(buf), fmt, va);
  va_end(va);

  // Deduplication logic: Suppress repeats until the message changes
  static char lastLog[4012] = {0};
  static int dupCount = 0;
  static int lastLevel = -1;
  static auto lastFlushTime = std::chrono::steady_clock::now();

  auto now = std::chrono::steady_clock::now();
  bool isRepeat = (strncmp(buf, lastLog, sizeof(lastLog)) == 0 && (int)level == lastLevel);
  bool shouldFlush = !isRepeat || std::chrono::duration_cast<std::chrono::seconds>(now - lastFlushTime).count() >= 5;

  if (shouldFlush) {
    if (dupCount > 1) {
      LOGI("!!! CORE REPEAT x%d: %s", dupCount, lastLog);
    }
    dupCount = 1;
    lastLevel = (int)level;
    strncpy(lastLog, buf, sizeof(lastLog) - 1);
    lastFlushTime = now;

    // Print the first instance immediately
    switch (level) {
    case RETRO_LOG_DEBUG: LOGI("CORE DEBUG: %s", buf); break;
    case RETRO_LOG_INFO:  LOGI("CORE INFO: %s", buf);  break;
    case RETRO_LOG_WARN:  LOGI("CORE WARN: %s", buf);  break;
    case RETRO_LOG_ERROR: LOGE("CORE ERROR: %s", buf); break;
    default:              LOGI("CORE: %s", buf);       break;
    }
  } else {
    dupCount++;
  }
}

JNIEnv *GetJNIEnv() {
  if (!g_vm)
    return nullptr;
  JNIEnv *env = nullptr;
  int status = g_vm->GetEnv((void **)&env, JNI_VERSION_1_6);
  if (status == JNI_EDETACHED) {
    if (g_vm->AttachCurrentThread(&env, nullptr) != 0)
      return nullptr;
  } else if (status != JNI_OK) {
    return nullptr;
  }
  return env;
}

void ResizeStateBuffer(size_t newCapacity) {
  std::lock_guard<std::mutex> lock(g_stateMutex);
  if (newCapacity <= g_stateBufferCapacity && g_stateBuffer != nullptr)
    return;

  // SYSTEM LEVEL FIX: Use aligned allocation for ALL state buffers.
  // Android 11+ uses "tagged" heap memory which breaks PCSX2/Dolphin's
  // bit-masking on pointers, causing SIGSEGV (ACCERR). Aligned allocation
  // provides untagged memory that works universally with libretro cores.
  //
  // NOTE: aligned_alloc() is only declared in bionic headers for API 28+,
  // but this project targets minSdk = 24. posix_memalign() is available
  // since API 17 and provides equivalent alignment guarantees without
  // raising the minSdk requirement.
  size_t alignment = 16;
  void *ptr = nullptr;
  if (posix_memalign(&ptr, alignment, newCapacity) != 0) {
    ptr = nullptr;
  }

  if (ptr == nullptr) {
    LOGE("STATE: Failed to allocate memory of size %zu", newCapacity);
    return;
  }

  // Ensure fresh memory is clean
  memset(ptr, 0, newCapacity);

  if (g_stateBuffer) {
    if (g_stateBufferSize > 0 && g_stateBufferSize <= newCapacity) {
      memcpy(ptr, g_stateBuffer, g_stateBufferSize);
    }
    free(g_stateBuffer);
  }

  g_stateBuffer = static_cast<uint8_t *>(ptr);
  g_stateBufferCapacity = newCapacity;
  g_stateBufferSize = 0; // Reset to actual written size
  LOGI("STATE: Aligned allocation resized to %zu bytes", newCapacity);
}