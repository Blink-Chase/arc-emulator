#ifndef ARC_INPUT_H
#define ARC_INPUT_H

#include "arc_common.h"

// Libretro Callbacks
void InputPollCallback();
int16_t InputStateCallback(unsigned port, unsigned device, unsigned index,
                           unsigned id);

// Rumble Interface
bool set_rumble_state(unsigned port, enum retro_rumble_effect effect,
                      uint16_t strength);

#endif
