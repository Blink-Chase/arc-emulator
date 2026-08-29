#ifndef ARC_COMMON_H
#define ARC_COMMON_H

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <jni.h>
#include <mutex>
#include <string>
#include <sys/resource.h>
#include <thread>
#include <unordered_map>

#include "libretro.h"

#ifndef EGL_OPENGL_ES3_BIT
#define EGL_OPENGL_ES3_BIT 0x00000040
#endif

#define TAG "ArcNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// Ring buffer size - enough for ~100ms of audio at 44100Hz stereo
#define AUDIO_BUFFER_SIZE 12288

// Shared Global Variables (extern)
extern int16_t g_audioRingBuffer[AUDIO_BUFFER_SIZE];
extern std::atomic<int> g_audioWritePos;
extern std::atomic<int> g_audioReadPos;
extern std::atomic<int> g_audioOverflows;

extern JavaVM *g_vm;
extern jobject g_activity;
extern void *g_coreHandle;

extern bool g_useHwRender;
extern struct retro_hw_render_callback g_hwRender;
extern EGLDisplay g_eglDisplay;
extern EGLContext g_eglContext;
extern EGLSurface g_eglSurface;
extern ANativeWindow *g_nativeWindow;

extern int32_t g_prevWidth;
extern int32_t g_prevHeight;
extern int32_t g_prevFormat;

extern std::atomic<bool> g_isRunning;
extern std::atomic<bool> g_isPaused;
extern std::atomic<bool> g_fastForward;
extern std::atomic<uint16_t> g_joypadBits;
extern std::atomic<int16_t> g_analogX;
extern std::atomic<int16_t> g_analogY;
extern std::atomic<int16_t> g_analogRightX;
extern std::atomic<int16_t> g_analogRightY;
extern std::atomic<int> g_pixelFormat;

extern std::thread g_emuThread;
extern std::mutex g_activityMutex;
extern std::mutex g_windowMutex;
extern std::recursive_mutex g_emuMutex; // New mutex for thread-safe core access

// Function pointer types for dynamically loaded core functions
typedef void (*retro_init_t)(void);
typedef bool (*retro_load_game_t)(const struct retro_game_info *game);
typedef void (*retro_run_t)(void);
typedef void (*retro_deinit_t)(void);
typedef void (*retro_unload_game_t)(void);
typedef void (*retro_reset_t)(void);
typedef size_t (*retro_serialize_size_t)(void);
typedef bool (*retro_serialize_t)(void *data, size_t size);
typedef bool (*retro_unserialize_t)(const void *data, size_t size);
typedef void (*retro_set_environment_t)(retro_environment_t cb);
typedef void (*retro_set_video_refresh_t)(retro_video_refresh_t cb);
typedef void (*retro_set_audio_sample_t)(retro_audio_sample_t cb);
typedef void (*retro_set_audio_sample_batch_t)(retro_audio_sample_batch_t cb);
typedef void (*retro_set_input_poll_t)(retro_input_poll_t cb);
typedef void (*retro_set_input_state_t)(retro_input_state_t cb);
typedef void (*retro_get_system_av_info_t)(struct retro_system_av_info *info);
typedef void (*retro_get_system_info_t)(struct retro_system_info *info);
typedef void (*retro_set_controller_port_device_t)(unsigned port, unsigned device);

// Core functions
extern retro_init_t core_init;
extern retro_load_game_t core_load_game;
extern retro_run_t core_run;
extern retro_deinit_t core_deinit;
extern retro_unload_game_t core_unload_game;
extern retro_reset_t core_reset;
extern retro_serialize_size_t core_serialize_size;
extern retro_serialize_t core_serialize;
extern retro_unserialize_t core_unserialize;

extern retro_set_environment_t core_set_environment;
extern retro_set_video_refresh_t core_set_video_refresh;
extern retro_set_audio_sample_t core_set_audio_sample;
extern retro_set_audio_sample_batch_t core_set_audio_sample_batch;
extern retro_set_input_poll_t core_set_input_poll;
extern retro_set_input_state_t core_set_input_state;
extern retro_get_system_av_info_t core_get_system_av_info;
extern retro_get_system_info_t core_get_system_info;
extern retro_set_controller_port_device_t core_set_controller_port_device;

extern struct retro_system_av_info g_avInfo;
extern std::string g_systemDir;
extern std::string g_saveDir;

extern std::atomic<int> g_currentFps;
extern std::atomic<int64_t> g_audioSamplesTotal;
extern std::atomic<int64_t> g_audioStartTime;
extern std::atomic<bool> g_resetDebugCounters;
extern std::atomic<int> g_videoRefreshCount;

extern std::string g_romPath;
extern std::atomic<bool> g_loadRequested;
extern std::atomic<bool> g_saveStateRequested;
extern std::atomic<bool> g_loadStateRequested;
extern std::atomic<bool> g_stateOperationSuccess; // New: Tell UI if it worked
extern std::vector<uint8_t> g_stateBuffer;
extern size_t g_stateBufferSize;
extern std::mutex g_stateMutex;
extern std::thread::id g_emuThreadId;
extern std::atomic<bool> g_variablesUpdated;
extern std::unordered_map<std::string, std::string> g_coreVariables;

// Shared Utility Functions
void LogCallback(enum retro_log_level level, const char *fmt, ...);
JNIEnv *GetJNIEnv();

// Shared Forward Declarations (Implemented in specific modules)
bool setupEGL();
bool set_rumble_state(unsigned port, enum retro_rumble_effect effect,
                      uint16_t strength);

#endif
