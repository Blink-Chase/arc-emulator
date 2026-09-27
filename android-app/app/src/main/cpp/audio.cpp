#include "audio.h"

void AudioSampleCallback(int16_t left, int16_t right) {
  int16_t stereo[2] = {left, right};
  AudioSampleBatchCallback(stereo, 1);
}

size_t AudioSampleBatchCallback(const int16_t *data, size_t frames) {
  // Buffer stats every 60 batches is ~10x/second and drowns out everything else
  // in logcat. Once every 5 seconds is enough to spot a drift or an overflow.
  static auto lastUsageLog = std::chrono::steady_clock::now();
  const auto nowLog = std::chrono::steady_clock::now();
  if (std::chrono::duration_cast<std::chrono::seconds>(nowLog - lastUsageLog)
          .count() >= 5) {
    lastUsageLog = nowLog;
    int wp = g_audioWritePos.load();
    int rp = g_audioReadPos.load();
    int usage = (wp >= rp) ? (wp - rp) : (AUDIO_BUFFER_SIZE - rp + wp);
    LOGI("AUDIO: Buffer Usage: %d / %d samples (%.1f%%)", usage,
         AUDIO_BUFFER_SIZE, (float)usage * 100.0f / AUDIO_BUFFER_SIZE);
  }

  if (g_fastForward.load() || !data || frames == 0)
    return 0;

  int samples = frames * 2;
  int writePos = g_audioWritePos.load();
  int readPos = g_audioReadPos.load();

  for (int i = 0; i < samples; i++) {
    int nextWrite = (writePos + 1) % AUDIO_BUFFER_SIZE;
    if (nextWrite == readPos) {
      g_audioOverflows++;
      // The consumer owns readPos; stop this batch rather than corrupting the
      // single-producer/single-consumer ring during a sustained burst.
      break;
    }
    g_audioRingBuffer[writePos] = data[i];
    writePos = nextWrite;
  }

  g_audioWritePos.store(writePos);

  int64_t startTime = g_audioStartTime.load();
  if (startTime == 0) {
    g_audioStartTime.store(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count());
  }
  g_audioSamplesTotal += samples;
  return frames;
}

void TriggerAudioPull() {
  // Pull audio trigger for frontend if needed
}
