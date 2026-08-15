#include "core.h"
#include "audio.h"
#include "environment.h"
#include "input.h"
#include "video.h"
#include <dlfcn.h>
#include <fstream>

bool LoadCore(const char *libPath) {
  UnloadCore();

  g_useHwRender = false;
  g_coreHandle = dlopen(libPath, RTLD_NOW | RTLD_LOCAL);
  if (!g_coreHandle) {
    LOGE("Failed to load core: %s", dlerror());
    return false;
  }

  typedef jint (*jni_onload_t)(JavaVM *, void *);
  jni_onload_t core_jni_onload = (jni_onload_t)dlsym(g_coreHandle, "JNI_OnLoad");
  if (core_jni_onload) {
    LOGI("Found JNI_OnLoad in core, calling it...");
    core_jni_onload(g_vm, nullptr);
    JNIEnv *env = GetJNIEnv();
    if (env && env->ExceptionCheck()) {
      env->ExceptionClear();
    }
  }

  core_init = (retro_init_t)dlsym(g_coreHandle, "retro_init");
  core_load_game = (retro_load_game_t)dlsym(g_coreHandle, "retro_load_game");
  core_run = (retro_run_t)dlsym(g_coreHandle, "retro_run");
  core_deinit = (retro_deinit_t)dlsym(g_coreHandle, "retro_deinit");
  core_unload_game = (retro_unload_game_t)dlsym(g_coreHandle, "retro_unload_game");
  core_reset = (retro_reset_t)dlsym(g_coreHandle, "retro_reset");
  core_serialize_size = (retro_serialize_size_t)dlsym(g_coreHandle, "retro_serialize_size");
  core_serialize = (retro_serialize_t)dlsym(g_coreHandle, "retro_serialize");
  core_unserialize = (retro_unserialize_t)dlsym(g_coreHandle, "retro_unserialize");
  core_set_environment = (retro_set_environment_t)dlsym(g_coreHandle, "retro_set_environment");
  core_set_video_refresh = (retro_set_video_refresh_t)dlsym(g_coreHandle, "retro_set_video_refresh");
  core_set_audio_sample = (retro_set_audio_sample_t)dlsym(g_coreHandle, "retro_set_audio_sample");
  core_set_audio_sample_batch = (retro_set_audio_sample_batch_t)dlsym(g_coreHandle, "retro_set_audio_sample_batch");
  core_set_input_poll = (retro_set_input_poll_t)dlsym(g_coreHandle, "retro_set_input_poll");
  core_set_input_state = (retro_set_input_state_t)dlsym(g_coreHandle, "retro_set_input_state");
  core_get_system_av_info = (retro_get_system_av_info_t)dlsym(g_coreHandle, "retro_get_system_av_info");
  core_get_system_info = (retro_get_system_info_t)dlsym(g_coreHandle, "retro_get_system_info");

  if (core_set_environment) core_set_environment(EnvironmentCallback);
  if (core_set_video_refresh) core_set_video_refresh(VideoRefreshCallback);
  if (core_set_audio_sample) core_set_audio_sample(AudioSampleCallback);
  if (core_set_audio_sample_batch) core_set_audio_sample_batch(AudioSampleBatchCallback);
  if (core_set_input_poll) core_set_input_poll(InputPollCallback);
  if (core_set_input_state) core_set_input_state(InputStateCallback);

  LOGI("Core loaded: %s", libPath);
  return true;
}

void UnloadCore() {
  if (g_coreHandle) {
    if (core_deinit) core_deinit();
    dlclose(g_coreHandle);
    g_coreHandle = nullptr;
  }
}

void EmuThreadFunc() {
  LOGI("Emulation thread started");
  g_emuThreadId = std::this_thread::get_id();
  setpriority(PRIO_PROCESS, 0, -10);

  double targetFrameMs = 1000.0 / 60.0;
  auto lastFrameTime = std::chrono::steady_clock::now();
  auto lastFpsUpdate = lastFrameTime;
  int frameCount = 0;
  bool eglInitialized = false;
  bool gameLoaded = false;

  while (g_isRunning.load()) {
    // 1. ASYNC STATE HANDLING (Works even when paused)
    if (g_saveStateRequested.load()) {
      bool success = false;
      if (core_serialize && core_serialize_size) {
        size_t size = core_serialize_size();
        if (size > 0 && size <= g_stateBuffer.size()) {
          if (core_serialize(g_stateBuffer.data(), size)) {
            std::lock_guard<std::mutex> lock(g_stateMutex);
            g_stateBufferSize = size;
            success = true;

            // Ensure GPU is finished before reporting success (CRITICAL for screenshots)
            if (g_useHwRender) {
                glFinish();
            }
          }
        }
      }
      g_stateOperationSuccess.store(success);
      g_saveStateRequested.store(false);
    }

    if (g_loadStateRequested.load()) {
      bool success = false;
      if (core_unserialize && core_serialize_size) {
        size_t expectedSize = core_serialize_size();
        size_t actualSize = 0;
        {
          std::lock_guard<std::mutex> lock(g_stateMutex);
          actualSize = g_stateBufferSize;
        }
        if (actualSize >= expectedSize) {
          if (g_useHwRender) glFinish();
          if (core_unserialize(g_stateBuffer.data(), expectedSize)) {
            success = true;
          }
        }
      }
      g_stateOperationSuccess.store(success);
      g_loadStateRequested.store(false);
    }

    // 2. GAME LOADING
    if (g_loadRequested.load()) {
      if (core_init) core_init();
      struct retro_system_info system_info = {};
      if (core_get_system_info) core_get_system_info(&system_info);

      struct retro_game_info game_info = {};
      game_info.path = g_romPath.c_str();

      std::vector<char> romData;
      if (!system_info.need_fullpath) {
        std::ifstream file(g_romPath, std::ios::binary | std::ios::ate);
        if (file.is_open()) {
          std::streamsize size = file.tellg();
          file.seekg(0, std::ios::beg);
          if (size > 0) {
            romData.resize(size);
            file.read(romData.data(), size);
            game_info.data = (const void *)romData.data();
            game_info.size = size;
          }
        }
      }

      if (core_load_game && core_load_game(&game_info)) {
        gameLoaded = true;
        if (core_get_system_av_info) {
          core_get_system_av_info(&g_avInfo);
          if (g_avInfo.timing.fps > 0.0)
            targetFrameMs = 1000.0 / g_avInfo.timing.fps;
        }
      } else {
        g_isRunning.store(false);
      }
      g_loadRequested.store(false);
      continue;
    }

    if (!gameLoaded) {
      std::this_thread::sleep_for(std::chrono::milliseconds(10));
      continue;
    }

    // 3. PAUSE HANDLING
    if (g_isPaused.load()) {
      if (eglInitialized && g_useHwRender) {
        // Optional: you could deinit EGL here to save battery, but let's keep it simple
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(16));
      lastFrameTime = std::chrono::steady_clock::now();
      continue;
    }

    // 4. WINDOW & EGL SETUP
    if (!g_nativeWindow) {
      if (eglInitialized) {
        if (g_useHwRender) deinitEGL();
        eglInitialized = false;
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(16));
      lastFrameTime = std::chrono::steady_clock::now();
      continue;
    }

    if (g_useHwRender && !eglInitialized) {
      if (setupEGL()) eglInitialized = true;
      else {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        continue;
      }
    }

    // 5. CORE EXECUTION
    if (core_run) {
      std::lock_guard<std::recursive_mutex> lock(g_emuMutex);
      core_run();
    }
    frameCount++;

    // 6. TIMING & FPS
    auto now = std::chrono::steady_clock::now();
    if (!g_fastForward.load()) {
      double elapsed = std::chrono::duration<double, std::milli>(now - lastFrameTime).count();
      double remaining = targetFrameMs - elapsed;
      if (remaining > 1.0)
        std::this_thread::sleep_for(std::chrono::milliseconds((int)(remaining - 1.0)));
      while (std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - lastFrameTime).count() < targetFrameMs) {
        std::this_thread::yield();
      }
    }
    lastFrameTime = std::chrono::steady_clock::now();

    if (std::chrono::duration_cast<std::chrono::milliseconds>(now - lastFpsUpdate).count() >= 1000) {
      g_currentFps.store(frameCount);
      frameCount = 0;
      lastFpsUpdate = now;
    }
  }

  if (g_useHwRender && eglInitialized) deinitEGL();
  UnloadCore();
  LOGI("Emulation thread exiting");
}
