#include <unistd.h>
#include "audio.h"
#include "core.h"
#include "environment.h"
#include "input.h"
#include "arc_common.h"
#include "video.h"

extern "C" {

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
  const char *path = env->GetStringUTFChars(corePath, 0);
  bool success = LoadCore(path);
  env->ReleaseStringUTFChars(corePath, path);
  return success ? nullptr : env->NewStringUTF("Failed to load core");
}

JNIEXPORT jboolean JNICALL Java_com_blinkchase_arc_MainActivity_nativeLoadGame(
    JNIEnv *env, jobject thiz, jstring romPath) {
  if (g_isRunning.load()) {
    g_isRunning.store(false);
    if (g_emuThread.joinable())
      g_emuThread.join();
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
  g_resetDebugCounters.store(true);
  g_variablesUpdated.store(true);
  g_videoRefreshCount.store(0);

  const char *path = env->GetStringUTFChars(romPath, 0);
  g_romPath = path;
  env->ReleaseStringUTFChars(romPath, path);

  g_isRunning.store(true);
  g_loadRequested.store(true);
  g_emuThread = std::thread(EmuThreadFunc);
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

JNIEXPORT void JNICALL
Java_com_blinkchase_arc_MainActivity_resetGame(JNIEnv *env, jobject thiz) {
  if (!g_isRunning.load() || !core_reset)
    return;
  // retro_reset must run on the emulation thread. Calling it from Compose's
  // UI thread races Dolphin's CPU/renderer threads and can crash the core.
  g_resetRequested.store(true);
  g_isPaused.store(false);
}

JNIEXPORT void JNICALL Java_com_blinkchase_arc_MainActivity_nativeQuitGame(
    JNIEnv *env, jobject thiz) {
  g_isRunning.store(false);
  g_resetRequested.store(false);
  if (g_emuThread.joinable())
    g_emuThread.join();
}

JNIEXPORT void JNICALL Java_com_blinkchase_arc_MainActivity_sendInput(
    JNIEnv *env, jobject thiz, jint buttonId, jint value) {
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
  std::string savePath = path;
  env->ReleaseStringUTFChars(filePath, path);

  // Signal emulation thread to serialize into g_stateBuffer
  g_stateOperationSuccess.store(false);
  g_saveStateRequested.store(true);

  // Dolphin serialization may briefly pause emulation while copying its state.
  // Allow enough time for large states on slower devices.
  for (int i = 0; i < 5000; i++) {
      if (!g_saveStateRequested.load()) {
          if (g_stateOperationSuccess.load()) {
              // Write the serialized buffer to disk
              FILE *f = fopen(savePath.c_str(), "wb");
              if (!f) return JNI_FALSE;

              size_t stateSize = 0;
              size_t written = 0;
              {
                  std::lock_guard<std::mutex> lock(g_stateMutex);
                  stateSize = g_stateBufferSize;
                  written = fwrite(g_stateBuffer.data(), 1, stateSize, f);
              }
              fclose(f);
              if (written != stateSize) {
                  LOGE("STATE: failed to write complete state (%zu/%zu bytes)",
                       written, stateSize);
                  return JNI_FALSE;
              }
              return JNI_TRUE;
          }
          return JNI_FALSE;
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(2));
  }
  return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_blinkchase_arc_MainActivity_loadState(
    JNIEnv *env, jobject thiz, jstring filePath) {
  const char *path = env->GetStringUTFChars(filePath, 0);
  std::string loadPath = path;
  env->ReleaseStringUTFChars(filePath, path);

  // Read file into buffer in this thread first (Very fast)
  FILE *f = fopen(loadPath.c_str(), "rb");
  if (!f) return JNI_FALSE;

  fseek(f, 0, SEEK_END);
  long fileSize = ftell(f);
  fseek(f, 0, SEEK_SET);

  if (fileSize <= 0 || fileSize > g_stateBuffer.size()) {
      fclose(f);
      return JNI_FALSE;
  }

  size_t bytesRead = 0;
  {
      std::lock_guard<std::mutex> lock(g_stateMutex);
      bytesRead = fread(g_stateBuffer.data(), 1, fileSize, f);
      if (bytesRead == static_cast<size_t>(fileSize))
        g_stateBufferSize = bytesRead;
  }
  fclose(f);
  if (bytesRead != static_cast<size_t>(fileSize)) {
      LOGE("STATE: failed to read complete state (%zu/%ld bytes)", bytesRead,
           fileSize);
      return JNI_FALSE;
  }

  // Signal emulation thread to pick up the buffer
  g_stateOperationSuccess.store(false);
  g_loadStateRequested.store(true);

  // Allow Dolphin time to restore a large state on the emulation thread.
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
}
