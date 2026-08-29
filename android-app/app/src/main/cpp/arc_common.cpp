#include "arc_common.h"

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
std::atomic<int> g_pixelFormat{RETRO_PIXEL_FORMAT_RGB565};

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
std::atomic<bool> g_saveStateRequested{false};
std::atomic<bool> g_loadStateRequested{false};
std::atomic<bool> g_stateOperationSuccess{false};
std::vector<uint8_t> g_stateBuffer(32 * 1024 * 1024); // 32MB Shared Buffer
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

  // Deduplication to prevent log spam
  static char lastLog[4012] = {0};
  static int dupCount = 0;

  if (strncmp(buf, lastLog, sizeof(lastLog)) == 0) {
    dupCount++;
    if (dupCount == 10) {
      LOGI("... (previous message repeating)");
    }
    if (dupCount >= 10)
      return;
  } else {
    dupCount = 0;
    strncpy(lastLog, buf, sizeof(lastLog) - 1);
  }

  switch (level) {
  case RETRO_LOG_DEBUG:
    LOGI("CORE DEBUG: %s", buf);
    break;
  case RETRO_LOG_INFO:
    LOGI("CORE INFO: %s", buf);
    break;
  case RETRO_LOG_WARN:
    LOGI("CORE WARN: %s", buf);
    break;
  case RETRO_LOG_ERROR:
    LOGE("CORE ERROR: %s", buf);
    break;
  default:
    LOGI("CORE: %s", buf);
    break;
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
