#ifndef ARC_AUDIO_H
#define ARC_AUDIO_H

#include "arc_common.h"

// Libretro Callbacks
void AudioSampleCallback(int16_t left, int16_t right);
size_t AudioSampleBatchCallback(const int16_t *data, size_t frames);

// Frontend Trigger
void TriggerAudioPull();

#endif
