package com.abubadr.arribha

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.metallic.chiaki.lib.ControllerState

/** Turns Android gamepad events into PlayStation controller state (based on Chiaki's StreamInput). */
class GamepadInput(private val onChange: (ControllerState) -> Unit) {
    private val keyState = ControllerState()
    private val motionState = ControllerState()

    private fun state(): ControllerState {
        val s = keyState or motionState
        if (motionState.l2State > 0U) s.l2State = motionState.l2State
        if (motionState.r2State > 0U) s.r2State = motionState.r2State
        return s
    }

    companion object {
        fun isGamepad(event: KeyEvent): Boolean {
            val src = event.source
            return (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                    KeyEvent.isGamepadButton(event.keyCode)
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> { keyState.l2State = if (down) UByte.MAX_VALUE else 0U; onChange(state()); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { keyState.r2State = if (down) UByte.MAX_VALUE else 0U; onChange(state()); return true }
        }
        val mask: UInt = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> ControllerState.BUTTON_CROSS
            KeyEvent.KEYCODE_BUTTON_B -> ControllerState.BUTTON_MOON
            KeyEvent.KEYCODE_BUTTON_X -> ControllerState.BUTTON_BOX
            KeyEvent.KEYCODE_BUTTON_Y -> ControllerState.BUTTON_PYRAMID
            KeyEvent.KEYCODE_BUTTON_L1 -> ControllerState.BUTTON_L1
            KeyEvent.KEYCODE_BUTTON_R1 -> ControllerState.BUTTON_R1
            KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerState.BUTTON_L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerState.BUTTON_R3
            KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerState.BUTTON_SHARE
            KeyEvent.KEYCODE_BUTTON_START -> ControllerState.BUTTON_OPTIONS
            KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_MODE -> ControllerState.BUTTON_PS
            else -> return false
        }
        keyState.buttons = if (down) keyState.buttons or mask else keyState.buttons and mask.inv()
        onChange(state())
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK) return false
        fun Float.signed() = (this * Short.MAX_VALUE).toInt().toShort()
        fun Float.unsigned() = (this.coerceIn(0f, 1f) * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()
        motionState.leftX = event.getAxisValue(MotionEvent.AXIS_X).signed()
        motionState.leftY = event.getAxisValue(MotionEvent.AXIS_Y).signed()
        motionState.rightX = event.getAxisValue(MotionEvent.AXIS_Z).signed()
        motionState.rightY = event.getAxisValue(MotionEvent.AXIS_RZ).signed()
        motionState.l2State = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).unsigned()
        motionState.r2State = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).unsigned()
        val dx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val dy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val dpadAll = ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_LEFT or
                ControllerState.BUTTON_DPAD_DOWN or ControllerState.BUTTON_DPAD_UP
        val dpad = (if (dx > 0.5f) ControllerState.BUTTON_DPAD_RIGHT else 0U) or
                (if (dx < -0.5f) ControllerState.BUTTON_DPAD_LEFT else 0U) or
                (if (dy > 0.5f) ControllerState.BUTTON_DPAD_DOWN else 0U) or
                (if (dy < -0.5f) ControllerState.BUTTON_DPAD_UP else 0U)
        motionState.buttons = (motionState.buttons and dpadAll.inv()) or dpad
        onChange(state())
        return true
    }
}
