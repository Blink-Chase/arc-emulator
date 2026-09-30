#include "input.h"
#include "environment.h"

namespace {

// TEMPORARY DIAGNOSTIC (remove once PS1 input is confirmed working).
// SwanStation is not answering input and the frontend had no visibility into
// what the core actually asks for. This records every distinct
// (device, index, id) triple the core queries, plus a trace of button
// transitions, so one logcat answers "is the core polling, and what is it
// polling?". The query census is bounded so it cannot flood the log.
constexpr int kMaxLoggedQueries = 64;
constexpr int kMaxQueryKeySpace = 4096; // 256 devices * 16 indexes * ids
int g_querySeen[kMaxQueryKeySpace] = {0};
int g_queryLogged = 0;
uint16_t g_lastLoggedBits = 0xFFFF; // forces a first-time log of "no buttons"

void LogInputDiagnostic(unsigned port, unsigned device, unsigned index,
                        unsigned id, int16_t value) {
    // Button transitions: one line per press/release, not per query.
    const uint16_t bits = g_joypadBits.load();
    if (bits != g_lastLoggedBits) {
        LOGI("INPUT_DIAG: bits 0x%04X -> 0x%04X", g_lastLoggedBits, bits);
        g_lastLoggedBits = bits;
    }

    const int key = ((int)(device & 0xFF) * 16 + (int)(index & 0xF)) * 32 +
                    (int)(id & 31);
    if (key < 0 || key >= kMaxQueryKeySpace)
        return;
    if (g_querySeen[key]++ == 0 && g_queryLogged < kMaxLoggedQueries) {
        g_queryLogged++;
        LOGI("INPUT_DIAG: core queries port=%u device=%u index=%u id=%u -> %d",
             port, device, index, id, value);
    }
}

// Digit lookup shared by the JOYPAD and ANALOG paths. g_joypadBits uses the
// standard libretro bit order, so a query for id N is just a single bit test.
int16_t DigitalButton(unsigned id) {
    const uint16_t buttons = g_joypadBits.load();
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK)
        return static_cast<int16_t>(buttons);
    if (id < 16)
        return (buttons & (static_cast<uint16_t>(1u) << id)) ? 1 : 0;
    return 0;
}

// True when the given RetroPad id currently has a digital bit asserted.
bool DigitalPressed(unsigned id) {
    if (id >= 16)
        return false;
    return (g_joypadBits.load() & (static_cast<uint16_t>(1u) << id)) != 0;
}

} // namespace

void InputPollCallback() {
    // Poll input if needed (Arc pulls from atomic vars).
    // DIAG: retro_set_input_poll is registered, so a core that polls at all
    // will call this once per frame. Logging the first few calls distinguishes
    // "core never polls" from "core polls but never asks for button state".
    static int pollCount = 0;
    if (pollCount < 5) {
        pollCount++;
        LOGI("INPUT_DIAG: retro_input_poll called (count=%d)", pollCount);
    }
}

// Real implementation; InputStateCallback wraps it purely to run the diagnostic
// on every exit path.
static int16_t ResolveInputState(unsigned port, unsigned device, unsigned index,
                                 unsigned id) {
    if (port != 0) return 0;

    const unsigned baseDevice = device & RETRO_DEVICE_MASK;

    // 1. ANALOG STICKS (PS1 DualShock, PS2, N64)
    //
    // Libretro disambiguates axes and pressure-sensitive buttons with index:
    // left/right sticks use indexes 0/1, while analog buttons use index 2.
    // The id values intentionally overlap, so they must never be inferred from
    // the id alone.
    if (baseDevice == RETRO_DEVICE_ANALOG) {
        if (index == RETRO_DEVICE_INDEX_ANALOG_LEFT ||
            index == RETRO_DEVICE_INDEX_ANALOG_RIGHT) {
            const bool left = (index == RETRO_DEVICE_INDEX_ANALOG_LEFT);
            const int16_t x = left ? g_analogX.load() : g_analogRightX.load();
            const int16_t y = left ? g_analogY.load() : g_analogRightY.load();
            if (id == RETRO_DEVICE_ID_ANALOG_X)
                return x;
            if (id == RETRO_DEVICE_ID_ANALOG_Y)
                return static_cast<int16_t>(-y);
            return 0;
        }

        // Analog buttons are pressure values, not 1/0 digital values.
        if (index == RETRO_DEVICE_INDEX_ANALOG_BUTTON)
            return DigitalPressed(id) ? 0x7FFF : 0;
        return 0;
    }

    // 2. DIGITAL BUTTONS (Standard Joypad)
    if (baseDevice == RETRO_DEVICE_JOYPAD) {
        if (index == 0)
            return DigitalButton(id);
    }

    // 3. TOUCH / POINTER (DS & DOLPHIN ONLY)
    if (baseDevice == RETRO_DEVICE_POINTER) {
        if (!g_isDsCore.load() && !g_isDolphinCore.load()) {
            return 0;
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

    return 0;
}

int16_t InputStateCallback(unsigned port, unsigned device, unsigned index,
                           unsigned id) {
    const int16_t value = ResolveInputState(port, device, index, id);
    LogInputDiagnostic(port, device, index, id, value);
    return value;
}

bool set_rumble_state(unsigned port, enum retro_rumble_effect effect,
        uint16_t strength) {
    return true;
}
