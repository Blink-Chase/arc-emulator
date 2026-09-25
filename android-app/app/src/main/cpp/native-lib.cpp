#include <unistd.h>
#include "audio.h"
#include "core.h"
#include "environment.h"
#include "input.h"
#include "arc_common.h"
#include "video.h"
#include "vulkan_bridge.h"

extern "C" {

// Stopping the emulation thread is the only safe way to end a session: that
// thread closes the core itself (UnloadCore() at the end of EmuThreadFunc), so
// nothing else may dlclose() or deinit the core while it might still be inside
// a core call. Bounded wait: a core that has hung inside its own code (melonDS
// can wedge in its DS wireless path) must not be able to freeze the app, and
// re-entering such a core (reset/unload) is what used to crash the process.
// Returns false when the thread had to be abandoned - the caller must then not
// start another core session in this process.
static bool StopEmuThread(unsigned timeoutMs) {
  g_isRunning.store(false);
  g_resetRequested.store(false);

  const int64_t deadline = ArcNowMs() + (int64_t)timeoutMs;
  while (g_emuThreadActive.load() && ArcNowMs() < deadline)
    std::this_thread::sleep_for(std::chrono::milliseconds(5));

  if (g_emuThreadActive.load()) {
    LOGW("CORE: emulation thread still running after %u ms - abandoning it "
         "(core wedged, no further core calls in this process)",
         timeoutMs);
    g_coreWedged.store(true);
    if (g_emuPthread) {
      pthread_detach(g_emuPthread); // never joinable again; freed when it exits
      g_emuPthread = 0;
    }
    return false;
  }

  if (g_emuPthread) {
    pthread_join(g_emuPthread, nullptr);
    g_emuPthread = 0;
  }
  return true;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  g_vm = vm;
  LOGI("ArcNative frontend build: dolphin-lifecycle-v4");
  return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_blinkchase_arc_MainActivity_updateNativeActivity(JNIEnv *env,
                                                           jobject thiz) {
  std::lock_guard<std::mutex> lock(g_activityMutex);
  if (g_activity)
    env->DeleteGlobalRef(g_activity);
  g_activity = env->NewGlobalRef(thiz);
}

JNIEXPORT void JNICALL
Java_com_blinkchase_arc_MainActivity_setSystemDirectories(JNIEnv *env,
                                                           jobject thiz,
                                                           jstring systemDir,
                                                           jstring saveDir) {
  const char *sysPath = env->GetStringUTFChars(systemDir, 0);
  const char *savePath = env->GetStringUTFChars(saveDir, 0);
  g_systemDir = sysPath;
  g_saveDir = savePath;
  env->ReleaseStringUTFChars(systemDir, sysPath);
  env->ReleaseStringUTFChars(saveDir, savePath);
}

JNIEXPORT jstring JNICALL Java_com_blinkchase_arc_MainActivity_loadCore(
    JNIEnv *env, jobject thiz, jstring corePath) {
  // A core whose emulation thread is still stuck inside a previous call must
  // never be dlopen()ed/re-armed: that thread owns the instance state.
  if (g_coreWedged.load()) {
    LOGE("CORE: refusing LoadCore - a previous emulation session is wedged");
    return env->NewStringUTF(
        "The previous emulation session stopped responding. Restart Arc to play again.");
  }
  const char *path = env->GetStringUTFChars(corePath, 0);
  bool success = LoadCore(path);
  env->ReleaseStringUTFChars(corePath, path);
  return success ? nullptr : env->NewStringUTF("Failed to load core");
}

JNIEXPORT jboolean JNICALL Java_com_blinkchase_arc_MainActivity_nativeLoadGame(
    JNIEnv *env, jobject thiz, jstring romPath) {
  if (g_coreWedged.load()) {
    LOGE("CORE: refusing to load a game - the previous session is wedged; "
         "restart Arc");
    return JNI_FALSE;
  }
  if (g_emuThreadActive.load() || g_isRunning.load()) {
    if (!StopEmuThread(4000)) {
      LOGE("CORE: previous emulation thread will not stop - refusing to load");
      return JNI_FALSE;
    }
  }

  // Reset State
  g_isPaused.store(false);
  g_forceOneRun.store(false);
  g_resetRequested.store(false);
  g_surfaceInvalidated.store(false);
  g_gameLoadComplete.store(false);
  g_gameLoadResult.store(false);
  g_audioWritePos = 0;
  g_audioReadPos = 0;
  g_currentFps = 0;
  g_audioSamplesTotal = 0;
  g_audioStartTime = 0;
  g_analogX = 0;
  g_analogY = 0;
  g_analogRightX = 0;
  g_analogRightY = 0;
  g_touchX = 0;
  g_touchY = 0;
  g_touchPressed.store(false);
  g_resetDebugCounters.store(true);
  g_variablesUpdated.store(true);
  g_videoRefreshCount.store(0);

  const char *path = env->GetStringUTFChars(romPath, 0);
  g_romPath = path;
  env->ReleaseStringUTFChars(romPath, path);

  g_isRunning.store(true);
  g_loadRequested.store(true);
  g_lastEmuHeartbeatMs.store(ArcNowMs());

  // The emulation thread runs the core with a large stack. bionic's default
  // pthread stack is 1 MB, which deep core paths can overflow - melonDS's DS
  // wireless stack when a game opens local multiplayer is one example - and an
  // overflow surfaces as a SIGSEGV inside the core .so. RetroArch runs cores on
  // an 8 MB main thread for the same reason.
  pthread_attr_t threadAttr;
  pthread_attr_init(&threadAttr);
  pthread_attr_setstacksize(&threadAttr, 8 * 1024 * 1024);
  g_emuThreadActive.store(true);
  const int createResult =
      pthread_create(&g_emuPthread, &threadAttr, EmuThreadEntry, nullptr);
  pthread_attr_destroy(&threadAttr);
  if (createResult != 0) {
    LOGE("CORE: pthread_create failed: %d", createResult);
    g_emuThreadActive.store(false);
    g_emuPthread = 0;
    g_isRunning.store(false);
    return JNI_FALSE;
  }

  for (int i = 0; i < 15000; i++) {
    if (g_gameLoadComplete.load())
      return g_gameLoadResult.load() ? JNI_TRUE : JNI_FALSE;
    if (!g_isRunning.load())
      return JNI_FALSE;
    std::this_thread::sleep_for(std::chrono::milliseconds(2));
  }
  LOGE("CORE: timed out waiting for retro_load_game");
  return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_blinkchase_arc_MainActivity_nativePauseGame(
    JNIEnv *env, jobject thiz) {
  // A pause dialog can swallow the ACTION_UP that would normally release the
  // stylus, which would leave the DS holding a press for as long as the dialog
  // stays open.
  g_touchPressed.store(false);
  g_isPaused.store(true);
}

JNIEXPORT void JNICALL Java_com_blinkchase_arc_MainActivity_nativeForceNextFrame(
    JNIEnv *env, jobject thiz) {
  g_forceOneRun.store(true);
}

JNIEXPORT void JNICALL Java_com_blinkchase_arc_MainActivity_nativeResumeGame(
    JNIEnv *env, jobject thiz) {
  g_isPaused.store(false);
}

JNIEXPORT jint JNICALL
Java_com_blinkchase_arc_MainActivity_resetGame(JNIEnv *env, jobject thiz) {
  if (!g_isRunning.load() || !core_reset)
    return -1; // nothing to reset

  // core_reset() is executed by the emulation thread. If that thread is stuck
  // inside a core call the core's state is already inconsistent, and the reset
  // (or the unload that follows it) is exactly what crashed the process before.
  // Refuse instead and let the UI tell the user to leave the game safely.
  // EmuCallBlockedMs() is authoritative here: it is only non-zero while the
  // emulation thread is physically inside retro_run()/retro_reset(), which is
  // precisely when letting another reset in is fatal. A merely stale heartbeat
  // just means the loop hasn't ticked (pause, surface loss); that is safe.
  const int64_t thresholdMs = g_isDolphinCore.load() ? 15000 : 5000;
  if (g_coreWedged.load() || EmuCallBlockedMs() > thresholdMs) {
    LOGW("CORE: reset refused - core is inside a call (blocked %lld ms)",
         (long long)EmuCallBlockedMs());
    return 0; // core unresponsive
  }

  // retro_reset must run on the emulation thread. Calling it from Compose's
  // UI thread races Dolphin's CPU/renderer threads and can crash the core.
  g_resetRequested.store(true);
  g_isPaused.store(false);
  return 1; // queued
}

JNIEXPORT void JNICALL Java_com_blinkchase_arc_MainActivity_nativeQuitGame(
    JNIEnv *env, jobject thiz) {
  // Bounded, recoverable shutdown: the emulation thread unloads the core and
  // exits on its own, and if it never does (core wedged inside a call of its
  // own) it is abandoned rather than joined, so the Quit button cannot hang the
  // app or crash it by re-entering a wedged core.
  StopEmuThread(4000);
}

void toggleDsScreenLayout() {
  if (g_dsScreenLayout.empty() || g_dsScreenLayout == "Top/Bottom") {
    g_dsScreenLayout = "Bottom/Top";
  } else {
    g_dsScreenLayout = "Top/Bottom";
  }
  auto it = g_coreVariables.find("melonds_screen_layout");
  if (it != g_coreVariables.end()) it->second = g_dsScreenLayout;
  auto it2 = g_coreVariables.find("desmume_screens_layout");
  if (it2 != g_coreVariables.end()) it2->second = g_dsScreenLayout;
  // Same contract as nativeSetDsScreenLayout(): the blit path performs the
  // visible flip, the core stays pinned to "Top/Bottom", and swapping screens
  // must not reset the game or re-announce the option.
  g_dsSwapScreens.store(g_dsScreenLayout == "Bottom/Top");
  LOGI("DS Screen Layout toggled to: %s (swap=%d)", g_dsScreenLayout.c_str(),
       g_dsSwapScreens.load() ? 1 : 0);
}

JNIEXPORT void JNICALL Java_com_blinkchase_arc_MainActivity_sendInput(
    JNIEnv *env, jobject thiz, jint buttonId, jint value) {
  if (buttonId == 16 && value == 1) { // BTN_SCREEN_SWAP
    toggleDsScreenLayout();
    return;
  }
  uint16_t bits = g_joypadBits.load();
  if (value)
    bits |= (1 << buttonId);
  else
    bits &= ~(1 << buttonId);
  g_joypadBits.store(bits);
}

JNIEXPORT void JNICALL Java_com_blinkchase_arc_MainActivity_setControllerType(
    JNIEnv *env, jobject thiz, jint port, jint type) {
  // Controller changes are consumed by the emulation thread after the core
  // has finished loading. Calling Dolphin from the UI thread during startup
  // races its initialization and can cause a native crash.
  if (port == 0)
    g_pendingControllerType.store((int)type);
}

JNIEXPORT void JNICALL Java_com_blinkchase_arc_MainActivity_setAnalogInput(
    JNIEnv *env, jobject thiz, jint x, jint y) {
  g_analogX.store((int16_t)x);
  g_analogY.store((int16_t)y);
}

JNIEXPORT void JNICALL Java_com_blinkchase_arc_MainActivity_setRightAnalogInput(
    JNIEnv *env, jobject thiz, jint x, jint y) {
  g_analogRightX.store((int16_t)x);
  g_analogRightY.store((int16_t)y);
}

JNIEXPORT void JNICALL Java_com_blinkchase_arc_MainActivity_setFastForward(
    JNIEnv *env, jobject thiz, jboolean enabled) {
  g_fastForward.store(enabled);
}

JNIEXPORT jint JNICALL Java_com_blinkchase_arc_MainActivity_getAudioSamples(
    JNIEnv *env, jobject thiz, jshortArray buffer, jint maxSamples) {
  int writePos = g_audioWritePos.load();
  int readPos = g_audioReadPos.load();
  int available = (writePos >= readPos)
                      ? (writePos - readPos)
                      : (AUDIO_BUFFER_SIZE - readPos + writePos);

  int toRead = available > maxSamples ? maxSamples : available;
  if (toRead <= 0)
    return 0;

  jshort *nativeBuffer = env->GetShortArrayElements(buffer, nullptr);
  if (!nativeBuffer)
    return 0;

  for (int i = 0; i < toRead; i++) {
    nativeBuffer[i] = g_audioRingBuffer[readPos];
    readPos = (readPos + 1) % AUDIO_BUFFER_SIZE;
  }

  g_audioReadPos.store(readPos);
  env->ReleaseShortArrayElements(buffer, nativeBuffer, 0);
  return toRead;
}

JNIEXPORT jboolean JNICALL Java_com_blinkchase_arc_MainActivity_saveState(
    JNIEnv *env, jobject thiz, jstring filePath) {
  const char *path = env->GetStringUTFChars(filePath, 0);
  {
      std::lock_guard<std::mutex> lock(g_stateMutex);
      g_stateFilePath = path;
  }
  env->ReleaseStringUTFChars(filePath, path);

  g_stateOperationSuccess.store(false);
  g_saveStateRequested.store(true);

  // Wait for emu thread to handle it
  for (int i = 0; i < 5000; i++) {
      if (!g_saveStateRequested.load()) {
          return g_stateOperationSuccess.load() ? JNI_TRUE : JNI_FALSE;
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(2));
  }
  return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_blinkchase_arc_MainActivity_loadState(
    JNIEnv *env, jobject thiz, jstring filePath) {
  const char *path = env->GetStringUTFChars(filePath, 0);
  {
      std::lock_guard<std::mutex> lock(g_stateMutex);
      g_stateFilePath = path;
  }
  env->ReleaseStringUTFChars(filePath, path);

  g_stateOperationSuccess.store(false);
  g_loadStateRequested.store(true);

  // Wait for emu thread to handle it
  for (int i = 0; i < 5000; i++) {
      if (!g_loadStateRequested.load()) {
          return g_stateOperationSuccess.load() ? JNI_TRUE : JNI_FALSE;
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(2));
  }

  return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_blinkchase_arc_MainActivity_getNativeFps(JNIEnv *env, jobject thiz) {
  return g_currentFps.load();
}

JNIEXPORT jdouble JNICALL
Java_com_blinkchase_arc_MainActivity_getGameSampleRate(JNIEnv *env,
                                                                jobject thiz) {
  if (g_avInfo.timing.sample_rate > 0)
    return g_avInfo.timing.sample_rate;
  return g_isDolphinCore.load() ? 48000.0 : 44100.0;
}

JNIEXPORT jdouble JNICALL
Java_com_blinkchase_arc_MainActivity_getGameFrameRate(JNIEnv *env,
                                                       jobject thiz) {
  return g_avInfo.timing.fps > 0.0 ? g_avInfo.timing.fps : 60.0;
}

JNIEXPORT jint JNICALL
Java_com_blinkchase_arc_MainActivity_getAudioBufferOccupancy(JNIEnv *env,
                                                              jobject thiz) {
  int wp = g_audioWritePos.load();
  int rp = g_audioReadPos.load();
  return (wp >= rp) ? (wp - rp) : (AUDIO_BUFFER_SIZE - rp + wp);
}

JNIEXPORT void JNICALL
Java_com_blinkchase_arc_MainActivity_nativeOnSurfaceCreated(JNIEnv *env,
                                                             jobject thiz,
                                                             jobject surface) {
  std::lock_guard<std::mutex> lock(g_windowMutex);
  if (surface) {
    g_nativeWindow = ANativeWindow_fromSurface(env, surface);
    g_prevWidth = 0;
    g_prevHeight = 0;
    g_prevFormat = 0;
    LOGI("Surface created: %p", g_nativeWindow);
  }
}

JNIEXPORT void JNICALL
Java_com_blinkchase_arc_MainActivity_nativeOnSurfaceDestroyed(JNIEnv *env,
                                                               jobject thiz) {
  std::lock_guard<std::mutex> lock(g_windowMutex);
  LOGI("Surface destroyed");
  // The touch listener goes away with the surface; drop any held press so the
  // next surface does not start with a stuck stylus.
  g_touchPressed.store(false);
  // Let the emulation thread destroy the EGL surface/context. The UI thread
  // must not invalidate EGL while Dolphin is rendering.
  g_surfaceInvalidated.store(true);
  if (g_nativeWindow) {
    ANativeWindow_release(g_nativeWindow);
    g_nativeWindow = nullptr;
  }
}

JNIEXPORT void JNICALL
Java_com_blinkchase_arc_MainActivity_nativeOnSurfaceChanged(JNIEnv *env,
                                                              jobject thiz,
                                                              jobject surface,
                                                              jint width,
                                                              jint height) {
  std::lock_guard<std::mutex> lock(g_windowMutex);
  if (g_nativeWindow) {
      g_prevWidth = 0; // Force geometry re-measurement in VideoRefreshCallback
      LOGI("Surface changed: %dx%d", width, height);
  }
}

JNIEXPORT void JNICALL
Java_com_blinkchase_arc_MainActivity_setCheat(JNIEnv *env,
                                                jobject thiz,
                                                jint index,
                                                jboolean enabled,
                                                jstring code) {
  // Stub
}

// PS2 Vulkan renderer preference (persisted by the Kotlin settings UI).
// vulkanSetRequested() is declared in vulkan_bridge.h, which is included above
// outside this extern "C" block so the C++ linkage matches the definition.
JNIEXPORT void JNICALL
Java_com_blinkchase_arc_MainActivity_nativeSetVulkanRequested(JNIEnv *env,
                                                             jobject thiz,
                                                             jboolean enabled) {
  vulkanSetRequested(enabled == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_blinkchase_arc_MainActivity_nativeIsVulkanRequested(JNIEnv *env,
                                                             jobject thiz) {
  return vulkanIsRequested() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_blinkchase_arc_MainActivity_nativeIsVulkanActive(JNIEnv *env,
                                                            jobject thiz) {
  return vulkanIsActive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_blinkchase_arc_MainActivity_nativeCoreSupportsVulkan(JNIEnv *env,
                                                              jobject thiz) {
  return vulkanCoreSupportsVulkan() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_blinkchase_arc_MainActivity_setTouchInput(
    JNIEnv *env, jobject thiz, jint x, jint y, jboolean pressed) {
  // The core is pinned to "Top/Bottom" (environment.cpp), so it always believes
  // the touch screen is the BOTTOM half of the frame it renders. When the user
  // swaps screens the frontend mirrors the halves, which puts the touch screen
  // on top - so the Y the user pressed has to be inverted to land on the same
  // physical spot inside the core's own frame. Without this, touch would work
  // but would be mirrored vertically after a swap.
  const int16_t outY =
      g_dsSwapScreens.load() ? static_cast<int16_t>(-y) : static_cast<int16_t>(y);
  g_touchX.store((int16_t)x);
  g_touchY.store(outY);
  g_touchPressed.store(pressed == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_blinkchase_arc_MainActivity_nativeSetDsScreenLayout(JNIEnv *env, jobject thiz, jstring layout) {
  const char *chars = env->GetStringUTFChars(layout, nullptr);
  if (chars) {
    g_dsScreenLayout = chars;
    env->ReleaseStringUTFChars(layout, chars);
  }
  auto it = g_coreVariables.find("melonds_screen_layout");
  if (it != g_coreVariables.end()) it->second = g_dsScreenLayout;
  auto it2 = g_coreVariables.find("desmume_screens_layout");
  if (it2 != g_coreVariables.end()) it2->second = g_dsScreenLayout;
  // The blit path (video.cpp) owns the visible flip. No g_resetRequested here:
  // a screen swap is not a game reset. g_variablesUpdated is deliberately NOT
  // raised either - the core is pinned to "Top/Bottom" in the environment
  // callback, so signalling an update would only invite it to re-apply a second
  // flip and cancel ours (one flipped frame, then a snap back).
  g_dsSwapScreens.store(g_dsScreenLayout == "Bottom/Top");
  LOGI("DS Screen Layout set to: %s (swap=%d)", g_dsScreenLayout.c_str(),
       g_dsSwapScreens.load() ? 1 : 0);
}

// Milliseconds since the emulation loop last made progress, or -1 when there is
// no running session to judge. The UI watchdog uses this to notice a game that
// hung inside the core (the state that made Reset/Quit crash the app).
JNIEXPORT jint JNICALL
Java_com_blinkchase_arc_MainActivity_nativeEmuHeartbeatAgeMs(JNIEnv *env,
                                                             jobject thiz) {
  if (!g_emuThreadActive.load() || !g_isRunning.load() || !g_gameLoadComplete.load())
    return -1;
  const int64_t last = g_lastEmuHeartbeatMs.load();
  if (last == 0)
    return -1;
  return (jint)(ArcNowMs() - last);
}
}
