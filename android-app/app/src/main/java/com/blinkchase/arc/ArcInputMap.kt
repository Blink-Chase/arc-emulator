package com.blinkchase.arc

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Helper for mapping Android KeyCodes/Axis IDs to Standard Controller Identifiers
 * and providing localized labels for UI display.
 */
object ArcInputMap {

    // Standard Controller Identifiers
    const val BTN_A = "btn_a"
    const val BTN_B = "btn_b"
    const val BTN_X = "btn_x"
    const val BTN_Y = "btn_y"
    const val BTN_L1 = "btn_l1"
    const val BTN_R1 = "btn_r1"
    const val BTN_L2 = "btn_l2"
    const val BTN_R2 = "btn_r2"
    const val BTN_L3 = "btn_l3"
    const val BTN_R3 = "btn_r3"
    const val BTN_START = "btn_start"
    const val BTN_SELECT = "btn_select"
    const val BTN_UP = "btn_up"
    const val BTN_DOWN = "btn_down"
    const val BTN_LEFT = "btn_left"
    const val BTN_RIGHT = "btn_right"

    const val AXIS_LEFT_X = "axis_left_x"
    const val AXIS_LEFT_Y = "axis_left_y"
    const val AXIS_RIGHT_X = "axis_right_x"
    const val AXIS_RIGHT_Y = "axis_right_y"
    const val AXIS_L2 = "axis_l2"
    const val AXIS_R2 = "axis_r2"

    /**
     * Maps an Android KeyCode to a Standard Controller Identifier.
     */
    fun fromKeyCode(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> BTN_A
        KeyEvent.KEYCODE_BUTTON_B -> BTN_B
        KeyEvent.KEYCODE_BUTTON_X -> BTN_X
        KeyEvent.KEYCODE_BUTTON_Y -> BTN_Y
        KeyEvent.KEYCODE_BUTTON_L1 -> BTN_L1
        KeyEvent.KEYCODE_BUTTON_R1 -> BTN_R1
        KeyEvent.KEYCODE_BUTTON_L2 -> BTN_L2
        KeyEvent.KEYCODE_BUTTON_R2 -> BTN_R2
        KeyEvent.KEYCODE_BUTTON_THUMBL -> BTN_L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> BTN_R3
        KeyEvent.KEYCODE_BUTTON_START -> BTN_START
        KeyEvent.KEYCODE_BUTTON_SELECT -> BTN_SELECT
        KeyEvent.KEYCODE_DPAD_UP -> BTN_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> BTN_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> BTN_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> BTN_RIGHT
        else -> null
    }

    /**
     * Returns a human-readable label for a button based on the controller model.
     * Maps Libretro BTN_IDs to their hardware-specific names.
     */
    fun getButtonLabel(libretroBtnId: Int, model: ControllerModel): String {
        return when (model) {
            ControllerModel.PLAYSTATION -> when (libretroBtnId) {
                MainActivity.BTN_A -> "Cross"
                MainActivity.BTN_B -> "Circle"
                MainActivity.BTN_X -> "Square"
                MainActivity.BTN_Y -> "Triangle"
                MainActivity.BTN_L -> "L1"
                MainActivity.BTN_R -> "R1"
                MainActivity.BTN_L2 -> "L2"
                MainActivity.BTN_R2 -> "R2"
                MainActivity.BTN_L3 -> "L3"
                MainActivity.BTN_R3 -> "R3"
                MainActivity.BTN_START -> "Options"
                MainActivity.BTN_SELECT -> "Share"
                MainActivity.BTN_UP -> "Up"
                MainActivity.BTN_DOWN -> "Down"
                MainActivity.BTN_LEFT -> "Left"
                MainActivity.BTN_RIGHT -> "Right"
                else -> "Button $libretroBtnId"
            }
            ControllerModel.XBOX -> when (libretroBtnId) {
                MainActivity.BTN_A -> "A"
                MainActivity.BTN_B -> "B"
                MainActivity.BTN_X -> "X"
                MainActivity.BTN_Y -> "Y"
                MainActivity.BTN_L -> "LB"
                MainActivity.BTN_R -> "RB"
                MainActivity.BTN_L2 -> "LT"
                MainActivity.BTN_R2 -> "RT"
                MainActivity.BTN_L3 -> "LS Click"
                MainActivity.BTN_R3 -> "RS Click"
                MainActivity.BTN_START -> "Menu"
                MainActivity.BTN_SELECT -> "View"
                MainActivity.BTN_UP -> "Up"
                MainActivity.BTN_DOWN -> "Down"
                MainActivity.BTN_LEFT -> "Left"
                MainActivity.BTN_RIGHT -> "Right"
                else -> "Button $libretroBtnId"
            }
            ControllerModel.N64 -> when (libretroBtnId) {
                MainActivity.BTN_A -> "A"
                MainActivity.BTN_B -> "B"
                MainActivity.BTN_X -> "C-Up"
                MainActivity.BTN_Y -> "C-Left"
                MainActivity.BTN_L -> "L"
                MainActivity.BTN_R -> "R"
                MainActivity.BTN_L2 -> "Z"
                MainActivity.BTN_R2 -> "C-Right"
                MainActivity.BTN_L3 -> "Stick Click"
                MainActivity.BTN_R3 -> "C-Down"
                MainActivity.BTN_START -> "Start"
                MainActivity.BTN_SELECT -> "Map/Mode"
                MainActivity.BTN_UP -> "D-Up"
                MainActivity.BTN_DOWN -> "D-Down"
                MainActivity.BTN_LEFT -> "D-Left"
                MainActivity.BTN_RIGHT -> "D-Right"
                else -> "Button $libretroBtnId"
            }
            ControllerModel.GAMECUBE -> when (libretroBtnId) {
                MainActivity.BTN_A -> "A (Green)"
                MainActivity.BTN_B -> "B (Red)"
                MainActivity.BTN_X -> "X"
                MainActivity.BTN_Y -> "Y"
                MainActivity.BTN_L -> "L Trigger"
                MainActivity.BTN_R -> "R Trigger"
                MainActivity.BTN_L2 -> "Z Button"
                MainActivity.BTN_R2 -> "C-Stick"
                MainActivity.BTN_L3 -> "Main Stick Click"
                MainActivity.BTN_R3 -> "C-Stick Click"
                MainActivity.BTN_START -> "Start/Pause"
                MainActivity.BTN_SELECT -> "Select"
                MainActivity.BTN_UP -> "D-Pad Up"
                MainActivity.BTN_DOWN -> "D-Pad Down"
                MainActivity.BTN_LEFT -> "D-Pad Left"
                MainActivity.BTN_RIGHT -> "D-Pad Right"
                else -> "Button $libretroBtnId"
            }
            ControllerModel.WII -> when (libretroBtnId) {
                MainActivity.BTN_A -> "A (Wiimote)"
                MainActivity.BTN_B -> "B (Trigger)"
                MainActivity.BTN_X -> "Button 1"
                MainActivity.BTN_Y -> "Button 2"
                MainActivity.BTN_L -> "C (Nunchuk)"
                MainActivity.BTN_R -> "Z (Nunchuk)"
                MainActivity.BTN_L2 -> "- (Minus)"
                MainActivity.BTN_R2 -> "+ (Plus)"
                MainActivity.BTN_L3 -> "Nunchuk Stick Click"
                MainActivity.BTN_R3 -> "IR Pointer Center"
                MainActivity.BTN_START -> "Home"
                MainActivity.BTN_SELECT -> "Select/Mode"
                MainActivity.BTN_UP -> "D-Pad Up"
                MainActivity.BTN_DOWN -> "D-Pad Down"
                MainActivity.BTN_LEFT -> "D-Pad Left"
                MainActivity.BTN_RIGHT -> "D-Pad Right"
                else -> "Button $libretroBtnId"
            }
            ControllerModel.GENERIC_ABXY -> when (libretroBtnId) {
                MainActivity.BTN_A -> "A"
                MainActivity.BTN_B -> "B"
                MainActivity.BTN_X -> "X"
                MainActivity.BTN_Y -> "Y"
                MainActivity.BTN_L -> "L1"
                MainActivity.BTN_R -> "R1"
                MainActivity.BTN_L2 -> "L2"
                MainActivity.BTN_R2 -> "R2"
                MainActivity.BTN_L3 -> "L3"
                MainActivity.BTN_R3 -> "R3"
                MainActivity.BTN_START -> "Start"
                MainActivity.BTN_SELECT -> "Select"
                MainActivity.BTN_UP -> "Up"
                MainActivity.BTN_DOWN -> "Down"
                MainActivity.BTN_LEFT -> "Left"
                MainActivity.BTN_RIGHT -> "Right"
                else -> "Button $libretroBtnId"
            }
        }
    }
}
