#include "audio.h"

void AudioSampleCallback(int16_t left, int16_t right) {
  int16_t stereo[2] = {left, right};
  AudioSampleBatchCallback(stereo, 1);
}

size_t AudioSampleBatchCallback(const int16_t *data, size_t frames) {
  static int logCounter = 0;
  if (logCounter++ > 60) {
    int wp = g_audioWritePos.load();
    int rp = g_audioReadPos.load();
    int usage = (wp >= rp) ? (wp - rp) : (AUDIO_BUFFER_SIZE - rp + wp);
    LOGI("AUDIO: Buffer Usage: %d / %d samples (%.1f%%)", usage,
         AUDIO_BUFFER_SIZE, (float)usage * 100.0f / AUDIO_BUFFER_SIZE);
    logCounter = 0;
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
