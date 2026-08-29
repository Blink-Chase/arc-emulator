package com.blinkchase.arc

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Centralized manager for handling physical controller inputs and touch overlays.
 * In v1.5.0, this handles dynamic mapping profiles.
 */
class InputManager(private val activity: MainActivity) {
    @Volatile private var activeProfile: ControllerProfile? = null
    private val prefs = activity.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    data class AxisState(val lsX: Float = 0f, val lsY: Float = 0f, val rsX: Float = 0f, val rsY: Float = 0f)
    private val _axisState = MutableStateFlow(AxisState())
    val axisState = _axisState.asStateFlow()

    private val _buttonState = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val buttonState = _buttonState.asStateFlow()

    private var currentPlatform: Platform = Platform.UNKNOWN
    private var currentGamePath: String = ""

    fun hasActiveController(): Boolean = activeProfile != null
    fun getActiveModel(): ControllerModel = activeProfile?.model ?: ControllerModel.GENERIC_ABXY

    fun getActiveProfile(): ControllerProfile? = activeProfile

    fun setContext(platform: Platform, gamePath: String) {
        currentPlatform = platform
        currentGamePath = gamePath
        clearAll() // Reset state for new game
    }

    /**
     * Forcefully clears all currently held button states.
     * Prevents "stuck" buttons during screen transitions.
     */
    fun clearAll() {
        _buttonState.value = emptyMap()
        _axisState.value = AxisState()
        // We can't easily tell the native engine to clear everything without a JNI call,
        // but resetting the local state prevents logic loops.
    }

    private fun applyDeadzone(value: Float): Float {
        val deadzone = prefs.getFloat(MainActivity.KEY_CONTROLLER_DEADZONE, 0.15f)
        return if (kotlin.math.abs(value) < deadzone) 0f else value
    }

    fun setActiveProfile(profile: ControllerProfile?) {
        activeProfile = profile
    }

    /**
     * Resolves the best profile for the given device using hierarchical lookup.
     * Force reload ensures we fetch the latest from DB after mapping changes.
     */
    suspend fun resolveProfile(deviceName: String, forceReload: Boolean = false) {
        val dao = activity.inputDao
        
        // 1. Try Game-specific profile
        var profile = dao.getProfile(deviceName, currentPlatform.name, currentGamePath)
        
        // 2. Fallback to Platform-specific profile
        if (profile == null && currentPlatform != Platform.UNKNOWN) {
            profile = dao.getProfile(deviceName, currentPlatform.name, "")
        }
        
        // 3. Fallback to Global profile
        if (profile == null) {
            profile = dao.getProfile(deviceName, "", "")
        }
        
        activeProfile = profile
    }

    /**
     * Triggers a refresh of the currently active controller's profile.
     * Call this when returning from the Mapping screen.
     */
    fun refreshCurrentProfile() {
        val deviceName = activity.lastDeviceName ?: return
        activity.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            resolveProfile(deviceName, true)
        }
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isGamepad(event)) return false
        
        // CRITICAL: Ignore repeat events to prevent log spam and engine flooding.
        // This stops the "ViewPostIme" spam from stuck buttons like R2.
        if (event.repeatCount > 0) return true

        val current = _buttonState.value.toMutableMap()
        current[keyCode] = true
        _buttonState.value = current

        // 1. Try active profile mapping
        activeProfile?.buttonMap?.get(keyCode)?.let { btnId ->
            activity.sendInput(btnId, 1)
            return true
        }

        // 2. Fallback to default Android mapping
        val mapped = mapKeyCodeToButton(keyCode)
        if (mapped != -1) {
            activity.sendInput(mapped, 1)
            return true
        }
        return false
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isGamepad(event)) return false

        val current = _buttonState.value.toMutableMap()
        current[keyCode] = false
        _buttonState.value = current

        // 1. Try active profile mapping
        activeProfile?.buttonMap?.get(keyCode)?.let { btnId ->
            activity.sendInput(btnId, 0)
            return true
        }

        // 2. Fallback to default Android mapping
        val mapped = mapKeyCodeToButton(keyCode)
        if (mapped != -1) {
            activity.sendInput(mapped, 0)
            return true
        }
        return false
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false

        // TODO: In v1.5.0 Phase 2, we will make these axis mappings dynamic too
        
        // Left Stick
        val lsX = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X))
        val lsY = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y))
        activity.setAnalogInput((lsX * 32767).toInt(), (lsY * 32767).toInt())

        // Right Stick
        val rsX = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Z))
        val rsY = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_RZ))
        activity.setRightAnalogInput((rsX * 32767).toInt(), (rsY * 32767).toInt())

        _axisState.value = AxisState(lsX, lsY, rsX, rsY)
        
        // Update virtual buttons for Right Stick (Useful for mapping C-Buttons)
        updateVirtualButton(-20, rsY < -0.5f) // RS Up
        updateVirtualButton(-21, rsY > 0.5f)  // RS Down
        updateVirtualButton(-22, rsX < -0.5f) // RS Left
        updateVirtualButton(-23, rsX > 0.5f)  // RS Right

        // DPAD as HAT Axes
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        
        updateHatButton(MainActivity.BTN_LEFT, hatX < -0.5f)
        updateHatButton(MainActivity.BTN_RIGHT, hatX > 0.5f)
        updateHatButton(MainActivity.BTN_UP, hatY < -0.5f)
        updateHatButton(MainActivity.BTN_DOWN, hatY > 0.5f)

        return true
    }

    private fun updateVirtualButton(virtualKeyCode: Int, pressed: Boolean) {
        val current = _buttonState.value.toMutableMap()
        if (current[virtualKeyCode] != pressed) {
            current[virtualKeyCode] = pressed
            _buttonState.value = current
        }
    }

    private fun updateHatButton(btnId: Int, pressed: Boolean) {
        activity.sendInput(btnId, if (pressed) 1 else 0)
        
        // Update diagnostics flow (using a virtual negative keycode for DPAD axes to avoid collisions)
        val virtualKeyCode = when(btnId) {
            MainActivity.BTN_UP -> -10
            MainActivity.BTN_DOWN -> -11
            MainActivity.BTN_LEFT -> -12
            MainActivity.BTN_RIGHT -> -13
            else -> 0
        }
        
        updateVirtualButton(virtualKeyCode, pressed)
    }

    private fun isGamepad(event: KeyEvent): Boolean {
        val source = event.source
        val isGamepadSource = (source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
               (source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
        
        // Some controllers report as keyboard but have specific gamepad keycodes
        val isGamepadKey = event.keyCode in listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, 
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT
        )

        return isGamepadSource || isGamepadKey
    }

    private fun mapKeyCodeToButton(keyCode: Int): Int {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> MainActivity.BTN_A
            KeyEvent.KEYCODE_BUTTON_B -> MainActivity.BTN_B
            KeyEvent.KEYCODE_BUTTON_X -> MainActivity.BTN_X
            KeyEvent.KEYCODE_BUTTON_Y -> MainActivity.BTN_Y
            KeyEvent.KEYCODE_BUTTON_L1 -> MainActivity.BTN_L
            KeyEvent.KEYCODE_BUTTON_R1 -> MainActivity.BTN_R
            KeyEvent.KEYCODE_BUTTON_L2 -> MainActivity.BTN_L2
            KeyEvent.KEYCODE_BUTTON_R2 -> MainActivity.BTN_R2
            KeyEvent.KEYCODE_BUTTON_THUMBL -> MainActivity.BTN_L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> MainActivity.BTN_R3
            KeyEvent.KEYCODE_BUTTON_START -> MainActivity.BTN_START
            KeyEvent.KEYCODE_BUTTON_SELECT -> MainActivity.BTN_SELECT
            KeyEvent.KEYCODE_DPAD_UP -> MainActivity.BTN_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> MainActivity.BTN_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> MainActivity.BTN_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> MainActivity.BTN_RIGHT
            else -> -1
        }
    }
}
