#include "core.h"
#include "audio.h"
#include "environment.h"
#include "input.h"
#include "video.h"
#include "vulkan_bridge.h"
#include <dlfcn.h>
#include <fstream>
#include <string>
#include <malloc.h>
#include <csignal>
#include <cerrno>
#include <cstdint>
#include <sys/mman.h>
#include <unistd.h>

namespace {
bool g_coreGameLoaded = false;

// ---------------------------------------------------------------------------
// Scoped SIGSEGV "page fix-up" guard.
//
// Some cores (notably PCSX2 with fastmem enabled) write-protect guest RAM
// pages so their JIT can trap and backpatch stores. retro_unserialize() then
// restores main RAM with one large memmove, which hits a protected page and
// dies with SIGSEGV (SEGV_ACCERR) - exactly the crash we can see in tombstone
// #01 (core) <- #02 (EmuThreadFunc). While this guard is active, a SEGV_ACCERR
// on a mapped-but-read-only page is resolved by making the page writable and
// returning, so the core's memmove *resumes and completes*. Any other fault
// (or a fix-up failure) is chained to the previously installed handler so real
// crashes still surface normally.
// ---------------------------------------------------------------------------
struct sigaction g_prevSegvAction;
bool g_segvGuardActive = false;
bool g_segvGuardFaulted = false;

void SegvFixupHandler(int sig, siginfo_t *info, void *ucontext) {
  (void)ucontext;
  if (sig == SIGSEGV && info != nullptr &&
      (info->si_code == SEGV_ACCERR || info->si_code == SEGV_MAPERR)) {
    uintptr_t page = reinterpret_cast<uintptr_t>(info->si_addr);
    const uintptr_t pageSize = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
    page &= ~(pageSize - 1);
    if (mprotect(reinterpret_cast<void *>(page), pageSize,
                 PROT_READ | PROT_WRITE | PROT_EXEC) == 0) {
      g_segvGuardFaulted = true;
      LOGW("STATE: fixed up protected page %p (SEGV_ACCERR) during state op",
           info->si_addr);
      return; // Resume the faulting instruction (the core's memmove).
    }
    LOGE("STATE: mprotect fix-up failed for %p (errno=%d)", info->si_addr,
         errno);
  }
  // Not fixable here: restore the previous handler and re-raise so the fault
  // is handled (or reported) exactly as it would have been without the guard.
  sigaction(SIGSEGV, &g_prevSegvAction, nullptr);
  raise(sig);
}

class ScopedSigsegvFixup {
public:
  ScopedSigsegvFixup() {
    if (g_segvGuardActive)
      return; // already armed (non-nested); leave previous guard in place
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = SegvFixupHandler;
    action.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGSEGV, &action, &g_prevSegvAction) == 0) {
      g_segvGuardActive = true;
      armed = true;
    }
  }
  ~ScopedSigsegvFixup() {
    if (armed && g_segvGuardActive) {
      sigaction(SIGSEGV, &g_prevSegvAction, nullptr);
      g_segvGuardActive = false;
    }
  }
  bool armed = false;
};
} // namespace

bool LoadCore(const char *libPath) {
  UnloadCore();

  g_useHwRender = false;
  g_coreVariables.clear();
  g_coreHandle = dlopen(libPath, RTLD_NOW | RTLD_LOCAL);
  if (!g_coreHandle) {
    LOGE("Failed to load core: %s", dlerror());
    return false;
  }
  g_isDolphinCore.store(std::string(libPath).find("dolphin") != std::string::npos);
  g_isPcsx2Core.store(std::string(libPath).find("pcsx2") != std::string::npos);

  // Android invokes JNI_OnLoad automatically as part of dlopen. Calling it
  // again here double-initializes Dolphin and can crash during game startup.
  LOGI("Core opened; relying on Android JNI_OnLoad handling");

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
  core_set_controller_port_device = (retro_set_controller_port_device_t)dlsym(g_coreHandle, "retro_set_controller_port_device");

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
    LOGI("UNLOAD: starting core deinit");
    // Explicit hard pause before unloading to prevent background thread access
    g_isRunning.store(false);
    std::this_thread::sleep_for(std::chrono::milliseconds(200));

    if (g_coreGameLoaded && core_unload_game) {
        LOGI("UNLOAD: calling retro_unload_game");
        core_unload_game();
    }
    if (core_deinit) {
        LOGI("UNLOAD: calling retro_deinit");
        core_deinit();
    }
    LOGI("UNLOAD: dlclose core");
    dlclose(g_coreHandle);
    g_coreHandle = nullptr;
  }
  g_coreGameLoaded = false;
  g_isDolphinCore.store(false);
  g_isPcsx2Core.store(false);

  if (g_vulkanInitialized) {
      deinitVulkan();
  }

  // Free state buffer only after core is fully gone
  std::lock_guard<std::mutex> lock(g_stateMutex);
  if (g_stateBuffer) {
    LOGI("UNLOAD: freeing state buffer");
    free(g_stateBuffer);
    g_stateBuffer = nullptr;
    g_stateBufferCapacity = 0;
    g_stateBufferSize = 0;
  }
  LOGI("UNLOAD: complete");
}

void EmuThreadFunc() {
  LOGI("Emulation thread started");
  g_emuThreadId = std::this_thread::get_id();
  setpriority(PRIO_PROCESS, 0, -10);

  double targetFrameMs = 1000.0 / 60.0;
  auto lastFrameTime = std::chrono::steady_clock::now();
  auto lastFpsUpdate = lastFrameTime;
  int frameCount = 0;
  bool loggedFirstRun = false;
  bool dolphinStarted = false;
  bool dolphinControllerConfigured = false;
  bool eglInitialized = false;
  bool gameLoaded = false;

  while (g_isRunning.load()) {
    // 1. ASYNC STATE HANDLING (Works even when paused)
    if (g_saveStateRequested.load()) {
      bool success = false;
      if (core_serialize && core_serialize_size) {
        // Deep safety sync to ensure all core threads (VU, EE, GPU) are idle
        std::this_thread::sleep_for(std::chrono::milliseconds(200));

        std::lock_guard<std::recursive_mutex> coreLock(g_emuMutex);
        size_t size = core_serialize_size();
        if (size > 0) {
          if (size > g_stateBufferCapacity) {
             ResizeStateBuffer(size + (16 * 1024 * 1024)); // 16MB safety padding
          }

          std::lock_guard<std::mutex> stateLock(g_stateMutex);
          if (g_stateBuffer && core_serialize(g_stateBuffer, size)) {
            // Write to disk immediately on this thread
            FILE *f = fopen(g_stateFilePath.c_str(), "wb");
            if (f) {
                size_t written = fwrite(g_stateBuffer, 1, size, f);
                fclose(f);
                if (written == size) success = true;
            }
          }
        }
      }
      LOGI("STATE: save %s (size=%zu)", success ? "completed" : "failed", g_stateBufferSize);
      g_stateOperationSuccess.store(success);
      g_saveStateRequested.store(false);
    }

    if (g_loadStateRequested.load()) {
      bool success = false;
      if (core_unserialize) {
        // Deep safety sync to ensure all core threads (VU, EE, GPU) are idle
        std::this_thread::sleep_for(std::chrono::milliseconds(200));

        std::lock_guard<std::recursive_mutex> coreLock(g_emuMutex);

        // Read file on this thread
        FILE *f = fopen(g_stateFilePath.c_str(), "rb");
        if (f) {
            // Ask the core how many bytes it expects for its state (ignore any file padding/chunks)
            if (core_serialize_size) {
                size_t expectedSize = core_serialize_size();
                if (expectedSize > 0) {
                    if (expectedSize > g_stateBufferCapacity) {
                        ResizeStateBuffer(expectedSize);
                    }

                    std::lock_guard<std::mutex> stateLock(g_stateMutex);
                    if (g_stateBuffer) {
                        // Read *at most* expectedSize bytes from the file (ignore extra file padding)
                        size_t bytesRead = fread(g_stateBuffer, 1, expectedSize, f);
                        if (bytesRead == expectedSize) {
                            // Update shared size so other subsystems know the valid buffer length
                            g_stateBufferSize = bytesRead;
                            // Pass exactly what we read to the core. Wrap the call
                            // in a page fix-up guard so fastmem's write-protected
                            // guest RAM pages (SEGV_ACCERR) get made writable
                            // mid-restore instead of killing the process.
                            g_segvGuardFaulted = false;
                            ScopedSigsegvFixup segvGuard;
                            bool restored = core_unserialize(g_stateBuffer, bytesRead);
                            if (g_segvGuardFaulted) {
                                LOGW("STATE: load required %s page fix-up(s)",
                                     "one or more");
                            }
                            if (restored) {
                                success = true;
                                g_audioReadPos.store(0);
                                g_audioWritePos.store(0);
                                g_videoRefreshCount.store(0);
                                g_forceOneRun.store(true);
                            }
                        }
                    }
                }
            }
            fclose(f);
        }
      }
      LOGI("STATE: load %s", success ? "completed" : "failed");
      g_stateOperationSuccess.store(success);
      g_loadStateRequested.store(false);
    }

    if (g_resetRequested.exchange(false)) {
      if (core_reset) {
        LOGI("CORE: executing reset on emulation thread");
        std::lock_guard<std::recursive_mutex> lock(g_emuMutex);
        core_reset();
        LOGI("CORE: reset returned");
      }
      g_isPaused.store(false);
      g_forceOneRun.store(true);
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

      LOGI("CORE: calling retro_load_game");
      g_coreGameLoaded = false;
      if (core_load_game && core_load_game(&game_info)) {
        LOGI("CORE: retro_load_game returned successfully");
        g_coreGameLoaded = true;
        gameLoaded = true;
        // A lifecycle callback can leave the shared pause flag set while the
        // core is still completing its asynchronous boot. Dolphin must reach
        // its first retro_run before pause can be honored.
        if (g_isDolphinCore.load())
          g_isPaused.store(false);
        // Dolphin's libretro controller setter is not safe during its
        // asynchronous boot sequence. Its default port is already a
        // standard controller, so leave it untouched for Dolphin.
        if (core_set_controller_port_device && !g_isDolphinCore.load()) {
          core_set_controller_port_device(0, (unsigned)g_pendingControllerType.load());
        }
        if (core_get_system_av_info) {
          core_get_system_av_info(&g_avInfo);
          if (g_avInfo.timing.fps > 0.0)
            targetFrameMs = 1000.0 / g_avInfo.timing.fps;
        }
      } else {
        LOGE("CORE: retro_load_game failed");
        g_isRunning.store(false);
      }
      g_gameLoadResult.store(gameLoaded);
      g_gameLoadComplete.store(true);
      g_loadRequested.store(false);
      continue;
    }

    if (!gameLoaded) {
      std::this_thread::sleep_for(std::chrono::milliseconds(10));
      continue;
    }

    if (g_surfaceInvalidated.exchange(false)) {
      if (eglInitialized && g_useHwRender) {
        LOGI("CORE: retiring EGL window surface after surface destruction");
        cleanupSurfaceEGL();
        eglInitialized = false;
      }
    }

    // 3. WINDOW & EGL SETUP (PRIORITY)
    // We do this BEFORE the pause check so that if a new surface is bound while the
    // engine is paused, we pick it up and initialize the EGL context immediately.
    if (!g_nativeWindow) {
      std::this_thread::sleep_for(std::chrono::milliseconds(16));
      lastFrameTime = std::chrono::steady_clock::now();
      continue;
    }

    if (g_useHwRender && !eglInitialized) {
      if (setupEGL()) {
        eglInitialized = true;
        LOGI("CORE: EGL ready after game load");
      }
      else {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        continue;
      }
    }

    // 4. PAUSE HANDLING
    // If g_forceOneRun is true, we bypass the pause check ONCE to refresh the screen.
    if (g_isPaused.load() && !g_forceOneRun.load() &&
        (!g_isDolphinCore.load() || dolphinStarted)) {
      std::this_thread::sleep_for(std::chrono::milliseconds(16));
      lastFrameTime = std::chrono::steady_clock::now();
      continue;
    }

    // 5. CORE EXECUTION
    if (core_run) {
      if (!dolphinStarted) {
        LOGI("CORE: about to call first retro_run (paused=%d, surface=%p, egl=%d)",
             g_isPaused.load() ? 1 : 0, g_nativeWindow,
             eglInitialized ? 1 : 0);
      }
      if (!loggedFirstRun) {
        LOGI("CORE: entering first retro_run");
        loggedFirstRun = true;
      }
      std::lock_guard<std::recursive_mutex> lock(g_emuMutex);
      const auto runStarted = std::chrono::steady_clock::now();
      core_run();
      const auto runDurationMs =
          std::chrono::duration_cast<std::chrono::milliseconds>(
              std::chrono::steady_clock::now() - runStarted)
              .count();
      if (runDurationMs > 250) {
        LOGW("CORE: retro_run took %lld ms", static_cast<long long>(runDurationMs));
      }
      if (!dolphinStarted) {
        LOGI("CORE: first retro_run returned");
        dolphinStarted = true;
      }
      if (dolphinStarted && !dolphinControllerConfigured &&
          g_isDolphinCore.load() && core_set_controller_port_device) {
        // Dolphin's controller port must be configured after its asynchronous
        // boot. Doing this during retro_load_game races Dolphin's input setup.
        core_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
        dolphinControllerConfigured = true;
        LOGI("INPUT: Dolphin port 0 configured as RetroPad");
      }
    }
    frameCount++;

    // Clear the one-shot flag if it was set
    if (g_forceOneRun.load()) {
        g_forceOneRun.store(false);
    }

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

    const double fpsWindowSeconds =
        std::chrono::duration<double>(now - lastFpsUpdate).count();
    if (fpsWindowSeconds >= 0.5) {
      g_currentFps.store(static_cast<int>(frameCount / fpsWindowSeconds + 0.5));
      frameCount = 0;
      lastFpsUpdate = now;
    }
  }

  if (g_useHwRender && eglInitialized) deinitEGL();
  UnloadCore();
  LOGI("Emulation thread exiting");
}
