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
#include <csetjmp>
#include <cerrno>
#include <cstdint>
#include <sys/mman.h>
#include <unistd.h>

int64_t ArcSteadyUs() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count();
}

int64_t EmuCallBlockedMs() {
    const int64_t start = g_coreCallStartUs.load();
    if (start == 0)
        return 0;
    return (ArcSteadyUs() - start) / 1000;
}

namespace {
    bool g_coreGameLoaded = false;

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
                LOGW("STATE: fixed up protected page %p during state op", info->si_addr);
                return;
            }
        }
        sigaction(SIGSEGV, &g_prevSegvAction, nullptr);
        raise(sig);
    }

    struct sigaction g_prevCoreFaultAction;
    struct sigaction g_prevCoreFaultBusAction;
    thread_local sigjmp_buf g_coreCallJmp;
    thread_local volatile bool g_coreCallGuardArmed = false;

    void CoreFaultHandler(int sig, siginfo_t *info, void *ucontext) {
        (void)ucontext;
        if ((sig == SIGSEGV || sig == SIGBUS) && g_coreCallGuardArmed) {
            g_coreCallGuardArmed = false;
            siglongjmp(g_coreCallJmp, sig == SIGBUS ? 2 : 1);
        }
        if (sig == SIGBUS) {
            sigaction(SIGBUS, &g_prevCoreFaultBusAction, nullptr);
        } else {
            sigaction(SIGSEGV, &g_prevCoreFaultAction, nullptr);
        }
        raise(sig);
    }

    template <typename Fn>
    bool InvokeGuardedCoreCall(int kind, Fn &&fn) {
        if (g_isDolphinCore.load() || g_isPcsx2Core.load()) {
            g_coreCallStartUs.store(ArcSteadyUs());
            fn();
            g_coreCallStartUs.store(0);
            return true;
        }
        struct sigaction action;
        memset(&action, 0, sizeof(action));
        action.sa_sigaction = CoreFaultHandler;
        action.sa_flags = SA_SIGINFO | SA_NODEFER;
        sigemptyset(&action.sa_mask);
        struct sigaction prevSegv, prevBus;
        if (sigaction(SIGSEGV, &action, &prevSegv) != 0 ||
                sigaction(SIGBUS, &action, &prevBus) != 0) {
            fn();
            return true;
        }
        g_prevCoreFaultAction = prevSegv;
        g_prevCoreFaultBusAction = prevBus;
        if (sigsetjmp(g_coreCallJmp, 1) != 0) {
            sigaction(SIGSEGV, &prevSegv, nullptr);
            sigaction(SIGBUS, &prevBus, nullptr);
            g_coreCallGuardArmed = false;
            g_coreCallStartUs.store(0);
            g_coreCallKind.store(0);
            return false;
        }
        g_coreCallGuardArmed = true;
        g_coreCallKind.store(kind);
        g_coreCallStartUs.store(ArcSteadyUs());
        fn();
        g_coreCallGuardArmed = false;
        g_coreCallStartUs.store(0);
        g_coreCallKind.store(0);
        sigaction(SIGSEGV, &prevSegv, nullptr);
        sigaction(SIGBUS, &prevBus, nullptr);
        return true;
    }

    class ScopedSigsegvFixup {
    public:
        ScopedSigsegvFixup() {
            if (g_segvGuardActive)
                return;
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
    g_isDsCore.store(std::string(libPath).find("melonds") != std::string::npos ||
            std::string(libPath).find("desmume") != std::string::npos);
    g_isN64Core.store(std::string(libPath).find("mupen64plus") != std::string::npos ||
            std::string(libPath).find("parallel_n64") != std::string::npos);

    LOGI("Core opened successfully");

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
        g_isRunning.store(false);
        std::this_thread::sleep_for(std::chrono::milliseconds(200));

        if (g_coreWedged.load()) {
            LOGW("UNLOAD: core is wedged - skipping unload_game/deinit/dlclose");
            g_coreHandle = nullptr;
        } else {
            if (g_coreGameLoaded && core_unload_game) {
                LOGI("UNLOAD: calling retro_unload_game");
                const bool unloadOk = InvokeGuardedCoreCall(3, [&]() {
                    core_unload_game();
                });
                if (!unloadOk) {
                    LOGE("COREFAULT: retro_unload_game() faulted");
                    g_coreWedged.store(true);
                    g_coreHandle = nullptr;
                }
            }
            if (g_coreHandle && core_deinit) {
                LOGI("UNLOAD: calling retro_deinit");
                const bool deinitOk = InvokeGuardedCoreCall(4, [&]() {
                    core_deinit();
                });
                if (!deinitOk) {
                    LOGE("COREFAULT: retro_deinit() faulted");
                    g_coreWedged.store(true);
                    g_coreHandle = nullptr;
                }
            }
            if (g_coreHandle) {
                LOGI("UNLOAD: dlclose core");
                dlclose(g_coreHandle);
                g_coreHandle = nullptr;
            }
        }
    }
    g_coreGameLoaded = false;
    g_isDolphinCore.store(false);
    g_isPcsx2Core.store(false);
    g_isDsCore.store(false);
    g_isN64Core.store(false);

    if (g_vulkanInitialized) {
        deinitVulkan();
    }

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
        g_lastEmuHeartbeatMs.store(ArcNowMs());

        // 1. ASYNC STATE HANDLING
        if (g_saveStateRequested.load()) {
            bool success = false;
            if (core_serialize && core_serialize_size) {
                std::this_thread::sleep_for(std::chrono::milliseconds(200));

                std::lock_guard<std::recursive_mutex> coreLock(g_emuMutex);
                size_t size = core_serialize_size();
                if (size > 0) {
                    if (size > g_stateBufferCapacity) {
                        ResizeStateBuffer(size + (16 * 1024 * 1024));
                    }

                    std::lock_guard<std::mutex> stateLock(g_stateMutex);
                    if (g_stateBuffer && core_serialize(g_stateBuffer, size)) {
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
                std::this_thread::sleep_for(std::chrono::milliseconds(200));

                std::lock_guard<std::recursive_mutex> coreLock(g_emuMutex);

                FILE *f = fopen(g_stateFilePath.c_str(), "rb");
                if (f) {
                    if (core_serialize_size) {
                        size_t expectedSize = core_serialize_size();
                        if (expectedSize > 0) {
                            if (expectedSize > g_stateBufferCapacity) {
                                ResizeStateBuffer(expectedSize);
                            }

                            std::lock_guard<std::mutex> stateLock(g_stateMutex);
                            if (g_stateBuffer) {
                                size_t bytesRead = fread(g_stateBuffer, 1, expectedSize, f);
                                if (bytesRead == expectedSize) {
                                    g_stateBufferSize = bytesRead;
                                    g_segvGuardFaulted = false;
                                    ScopedSigsegvFixup segvGuard;
                                    bool restored = core_unserialize(g_stateBuffer, bytesRead);
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
                const bool resetOk = InvokeGuardedCoreCall(2, [&]() {
                    core_reset();
                });
                if (!resetOk) {
                    LOGE("COREFAULT: retro_reset() faulted");
                    g_coreWedged.store(true);
                    g_isRunning.store(false);
                    g_loadRequested.store(false);
                    g_saveStateRequested.store(false);
                    g_loadStateRequested.store(false);
                    break;
                }
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
            bool loaded = core_load_game && core_load_game(&game_info);
            if (!loaded && vulkanRequested()) {
                LOGW("CORE: Vulkan attempt failed; retrying with SW");
                core_deinit();
                if (g_vulkanInitialized)
                    deinitVulkan();
                vulkanSetRequested(false);
                g_useVulkan = false;
                g_vulkanFailed.store(true);
                g_coreVariables["pcsx2_renderer"] = "Software (SW)";
                if (core_init)
                    core_init();
                loaded = core_load_game && core_load_game(&game_info);
            }
            if (loaded) {
                LOGI("CORE: retro_load_game returned successfully");
                g_coreGameLoaded = true;
                gameLoaded = true;

                // Setup EGL & invoke context_reset ONCE right after retro_load_game finishes
                if (g_useHwRender && !g_useVulkan && g_nativeWindow && !eglInitialized) {
                    std::lock_guard<std::recursive_mutex> lock(g_emuMutex);
                    if (setupEGL()) {
                        eglInitialized = true;
                        if (g_hwRender.context_reset) {
                            LOGI("VIDEO: Executing context_reset post retro_load_game");
                            InvokeGuardedCoreCall(5, [&]() {
                                g_hwRender.context_reset();
                            });
                        }
                    }
                }

                if (g_isDolphinCore.load())
                    g_isPaused.store(false);

                // PS1 Controller Type Auto-Detect (DualShock RETRO_DEVICE_ANALOG = 5)
                const bool isPs1 = (g_romPath.find(".cue") != std::string::npos ||
                        g_romPath.find(".chd") != std::string::npos ||
                        g_romPath.find(".pbp") != std::string::npos ||
                        g_romPath.find(".bin") != std::string::npos);

                if (core_set_controller_port_device) {
                    if (isPs1) {
                        core_set_controller_port_device(0, 5 /* RETRO_DEVICE_ANALOG */);
                        LOGI("INPUT: Set Port 0 to RETRO_DEVICE_ANALOG (5) for PS1");
                    } else {
                        core_set_controller_port_device(0, (unsigned)g_pendingControllerType.load());
                    }
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
            if (g_useVulkan) {
                LOGI("CORE: surface destroyed - tearing down Vulkan");
                vulkanContextDestroy();
            } else if (eglInitialized && g_useHwRender) {
                LOGI("CORE: retiring EGL window surface after surface destruction");
                std::lock_guard<std::recursive_mutex> lock(g_emuMutex);
                cleanupSurfaceEGL();
                eglInitialized = false;
            }
        }

        // 3. WINDOW & EGL SETUP FOR LATE-ATTACHED SURFACES
        if (!g_nativeWindow) {
            std::this_thread::sleep_for(std::chrono::milliseconds(16));
            lastFrameTime = std::chrono::steady_clock::now();
            continue;
        }

        if (g_useVulkan) {
            if (!vulkanContextActive() && !vulkanFailed())
                vulkanContextReset();
        } else if (g_useHwRender && !eglInitialized) {
            std::lock_guard<std::recursive_mutex> lock(g_emuMutex);
            if (setupEGL()) {
                eglInitialized = true;
                LOGI("CORE: EGL ready after late surface bind");
                if (g_hwRender.context_reset) {
                    LOGI("VIDEO: Executing context_reset on late EGL setup");
                    InvokeGuardedCoreCall(5, [&]() {
                        g_hwRender.context_reset();
                    });
                }
            } else {
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                continue;
            }
        }

        // 4. PAUSE HANDLING
        if (g_isPaused.load() && !g_forceOneRun.load() &&
                (!g_isDolphinCore.load() || dolphinStarted)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(16));
            lastFrameTime = std::chrono::steady_clock::now();
            continue;
        }

        // 5. CORE EXECUTION
        if (core_run) {
            if (!loggedFirstRun) {
                LOGI("CORE: entering first retro_run");
                loggedFirstRun = true;
            }
            std::lock_guard<std::recursive_mutex> lock(g_emuMutex);
            const auto runStarted = std::chrono::steady_clock::now();
            g_lastEmuHeartbeatMs.store(ArcNowMs());
            const bool runOk = InvokeGuardedCoreCall(1, [&]() {
                core_run();
            });
            if (!runOk) {
                LOGE("COREFAULT: retro_run() faulted");
                g_coreWedged.store(true);
                g_isRunning.store(false);
                break;
            }
            if (!dolphinStarted) {
                dolphinStarted = true;
            }
            if (dolphinStarted && !dolphinControllerConfigured &&
                    g_isDolphinCore.load() && core_set_controller_port_device) {
                unsigned device = (unsigned)g_pendingControllerType.load();
                if (!g_isWiiGame.load() && device >= 513)
                    device = RETRO_DEVICE_JOYPAD;
                if (g_isWiiGame.load() && device < 513)
                    device = 769;
                core_set_controller_port_device(0, device);
                dolphinControllerConfigured = true;
            }
        }
        frameCount++;

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

void *EmuThreadEntry(void *arg) {
    (void)arg;
    g_emuThreadActive.store(true);
    EmuThreadFunc();
    g_emuThreadActive.store(false);
    return nullptr;
}

bool EmuThreadResponsive() {
    if (!g_emuThreadActive.load())
        return true;
    const int64_t last = g_lastEmuHeartbeatMs.load();
    if (last == 0)
        return true;
    return (ArcNowMs() - last) <= 6000;
}