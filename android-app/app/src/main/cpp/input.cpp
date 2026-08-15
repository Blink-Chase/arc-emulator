#include "input.h"

void InputPollCallback() {
  // Poll input if needed (Arc currently pulls from atomic vars)
}

int16_t InputStateCallback(unsigned port, unsigned device, unsigned index,
                           unsigned id) {
  if (port == 0) {
    if (device == RETRO_DEVICE_JOYPAD && index == 0) {
      return (g_joypadBits.load() & (1 << id)) ? 1 : 0;
    }
    if (device == RETRO_DEVICE_ANALOG && index == 0) {
      if (id == RETRO_DEVICE_ID_ANALOG_X)
        return g_analogX.load();
      if (id == RETRO_DEVICE_ID_ANALOG_Y)
        return -g_analogY.load();
    }
    if (device == RETRO_DEVICE_ANALOG && index == RETRO_DEVICE_INDEX_ANALOG_RIGHT) {
      if (id == RETRO_DEVICE_ID_ANALOG_X)
        return g_analogRightX.load();
      if (id == RETRO_DEVICE_ID_ANALOG_Y)
        return -g_analogRightY.load();
    }
  }
  return 0;
}

bool set_rumble_state(unsigned port, enum retro_rumble_effect effect,
                      uint16_t strength) {
  // Implementation for rumble if hardware supports it
  return true;
}
