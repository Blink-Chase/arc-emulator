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
    // Touch screens (Nintendo DS and friends).
    if (baseDevice == RETRO_DEVICE_POINTER) {
      const bool pressed = g_touchPressed.load();
      // X/Y are absolute and span the whole video frame in [-32768, 32767].
      // The core maps them onto its own screen layout and decides which part of
      // the frame is the touch screen, so no half-screen preselection happens
      // here (that broke swapped layouts and hybrid layouts entirely).
      if (id == RETRO_DEVICE_ID_POINTER_COUNT)
        return pressed ? 1 : 0;
      if (id == RETRO_DEVICE_ID_POINTER_IS_OFFSCREEN)
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
    // Mouse. RETRO_DEVICE_ID_MOUSE_X/Y are *relative* movements since the last
    // poll, not positions: aliasing the absolute touch surface onto them threw
    // any core running a mouse-driven touch mode (melonDS defaults its touch
    // mode to "Mouse") at the screen edges on every tap. Arc has no real mouse,
    // so report no movement at all; only the button state is meaningful.
    if (baseDevice == RETRO_DEVICE_MOUSE && index == 0) {
      if (id == RETRO_DEVICE_ID_MOUSE_LEFT)
        return g_touchPressed.load() ? 1 : 0;
      return 0;
    }
  }
  return 0;
}

bool set_rumble_state(unsigned port, enum retro_rumble_effect effect,
                      uint16_t strength) {
  // Implementation for rumble if hardware supports it
  return true;
}
