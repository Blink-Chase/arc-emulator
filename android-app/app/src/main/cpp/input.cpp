#include "input.h"
#include "environment.h"

void InputPollCallback() {
  // Poll input if needed (Arc pulls from atomic vars)
}

int16_t InputStateCallback(unsigned port, unsigned device, unsigned index,
        unsigned id) {
  if (port == 0) {
    const unsigned baseDevice = device & RETRO_DEVICE_MASK;

    // 1. DIGITAL BUTTONS (Handles standard Joypad AND DualShock digital buttons)
    if ((baseDevice == RETRO_DEVICE_JOYPAD || baseDevice == RETRO_DEVICE_ANALOG) && index == 0) {
      const uint16_t buttons = g_joypadBits.load();
      if (id == RETRO_DEVICE_ID_JOYPAD_MASK)
        return static_cast<int16_t>(buttons);
      if (id < 16)
        return (buttons & (static_cast<uint16_t>(1u) << id)) ? 1 : 0;
    }

    // 2. ANALOG STICKS (PS1 DualShock, PS2, N64)
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
      return 0;
    }

    // 3. TOUCH / POINTER (STRICTLY GUARDED FOR DS & DOLPHIN ONLY)
    if (baseDevice == RETRO_DEVICE_POINTER) {
      if (!g_isDsCore.load() && !g_isDolphinCore.load()) {
        return 0; // BLOCK pointer queries for PS1, PS2, N64!
      }

      const bool pressed = g_touchPressed.load();
      if (id == RETRO_DEVICE_ID_POINTER_COUNT)
        return pressed ? 1 : 0;
      if (id == RETRO_DEVICE_ID_POINTER_IS_OFFSCREEN || index == 15)
        return pressed ? 0 : 1;
      if (index == 0) {
        if (id == RETRO_DEVICE_ID_POINTER_X)
          return g_touchX.load();
        if (id == RETRO_DEVICE_ID_POINTER_Y)
          return g_touchY.load();
        if (id == RETRO_DEVICE_ID_POINTER_PRESSED)
          return pressed ? 1 : 0;
      }
      return 0;
    }

    // 4. MOUSE (DS ONLY)
    if (baseDevice == RETRO_DEVICE_MOUSE && index == 0) {
      if (g_isDsCore.load() && id == RETRO_DEVICE_ID_MOUSE_LEFT)
        return g_touchPressed.load() ? 1 : 0;
      return 0;
    }
  }
  return 0;
}

bool set_rumble_state(unsigned port, enum retro_rumble_effect effect,
        uint16_t strength) {
  return true;
}