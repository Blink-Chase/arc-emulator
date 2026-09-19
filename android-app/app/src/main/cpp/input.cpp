#include "input.h"

void InputPollCallback() {
  // Poll input if needed (Arc currently pulls from atomic vars)
}

int16_t InputStateCallback(unsigned port, unsigned device, unsigned index,
                           unsigned id) {
  if (port == 0) {
    // Cores may pass a device subclass (for example a GameCube controller)
    // rather than the base RetroPad value. Libretro requires the frontend to
    // handle the base device type in that case.
    const unsigned baseDevice = device & RETRO_DEVICE_MASK;
    if (baseDevice == RETRO_DEVICE_JOYPAD && index == 0) {
      const uint16_t buttons = g_joypadBits.load();
      if (id == RETRO_DEVICE_ID_JOYPAD_MASK)
        return static_cast<int16_t>(buttons);
      if (id < 16)
        return (buttons & (static_cast<uint16_t>(1u) << id)) ? 1 : 0;
      return 0;
    }
    if (baseDevice == RETRO_DEVICE_ANALOG) {
      if (index == RETRO_DEVICE_INDEX_ANALOG_LEFT &&
          id == RETRO_DEVICE_ID_ANALOG_X)
        return g_analogX.load();
      if (index == RETRO_DEVICE_INDEX_ANALOG_LEFT &&
          id == RETRO_DEVICE_ID_ANALOG_Y)
        return -g_analogY.load();
      if (index == RETRO_DEVICE_INDEX_ANALOG_RIGHT &&
          id == RETRO_DEVICE_ID_ANALOG_X)
        return g_analogRightX.load();
      if (index == RETRO_DEVICE_INDEX_ANALOG_RIGHT &&
          id == RETRO_DEVICE_ID_ANALOG_Y)
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
