package com.shank1.port.input

import android.view.InputDevice

/**
 * The virtual gamepad the touch controls drive: an Xbox 360 pad's state, pushed into the port's native side
 * (src/android/android_pad.cpp) whenever it changes.
 *
 * The native side ORs the buttons and takes the larger of each axis, so a Bluetooth pad and the screen work at the
 * same time and neither has to be turned off (docs/android.md, "Controls").
 *
 * The button bits are the XInput ones, which is also what the recompiled game reads through XInputGetState.
 */
object VirtualPad {

    const val DPAD_UP = 0x0001
    const val DPAD_DOWN = 0x0002
    const val DPAD_LEFT = 0x0004
    const val DPAD_RIGHT = 0x0008
    const val START = 0x0010
    const val BACK = 0x0020
    const val LEFT_THUMB = 0x0040
    const val RIGHT_THUMB = 0x0080
    const val LB = 0x0100
    const val RB = 0x0200
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000

    private var buttons = 0
    private var lx = 0f
    private var ly = 0f
    private var rx = 0f
    private var ry = 0f
    private var lt = 0f
    private var rt = 0f
    private var pushedButtons = 0
    private var pushedLx = 0f
    private var pushedLy = 0f
    private var pushedRx = 0f
    private var pushedRy = 0f
    private var pushedLt = 0f
    private var pushedRt = 0f
    private var activePointers = 0

    private val lock = Any()

    fun bitOf(output: String): Int = when (output) {
        "a" -> A
        "b" -> B
        "x" -> X
        "y" -> Y
        "lb" -> LB
        "rb" -> RB
        "start" -> START
        "back" -> BACK
        "l3" -> LEFT_THUMB
        "r3" -> RIGHT_THUMB
        "up" -> DPAD_UP
        "down" -> DPAD_DOWN
        "left" -> DPAD_LEFT
        "right" -> DPAD_RIGHT
        else -> 0
    }

    /** A button or d-pad direction: [output] is one of [Outputs.BUTTONS] or a d-pad direction. */
    fun setButton(output: String, down: Boolean) {
        val bit = bitOf(output)
        if (bit == 0) return
        synchronized(lock) {
            buttons = if (down) buttons or bit else buttons and bit.inv()
        }
        push()
    }

    fun clearButtons() {
        synchronized(lock) { buttons = 0 }
        push()
    }

    /** A stick's position, -1..1 each way; [output] is "ls" or "rs". */
    fun setStick(output: String, x: Float, y: Float) {
        synchronized(lock) {
            if (output == "rs") {
                rx = x
                ry = y
            } else {
                lx = x
                ly = y
            }
        }
        push()
    }

    /** A trigger's pull, 0..1; [output] is "lt" or "rt". */
    fun setTrigger(output: String, value: Float) {
        synchronized(lock) {
            if (output == "rt") rt = value else lt = value
        }
        push()
    }

    /** How many fingers are on the overlay: the native side keeps the pad alive while any is, and lets a Bluetooth
     *  pad's own button press through as a real one. */
    fun setTouchPointers(count: Int) {
        synchronized(lock) { activePointers = count }
        push()
    }

    fun reset() {
        synchronized(lock) {
            buttons = 0
            lx = 0f; ly = 0f; rx = 0f; ry = 0f; lt = 0f; rt = 0f
        }
        push()
    }

    /** Sends the state if it changed since the last send: one JNI call per change, none when nothing moves. */
    fun push() {
        val mask: Int
        val ax: Float; val ay: Float; val bx: Float; val by: Float; val tl: Float; val tr: Float
        synchronized(lock) {
            if (buttons == pushedButtons && lx == pushedLx && ly == pushedLy && rx == pushedRx &&
                ry == pushedRy && lt == pushedLt && rt == pushedRt) {
                return
            }
            mask = buttons
            ax = lx; ay = ly; bx = rx; by = ry; tl = lt; tr = rt
            pushedButtons = mask
            pushedLx = ax; pushedLy = ay; pushedRx = bx; pushedRy = by; pushedLt = tl; pushedRt = tr
        }
        com.shank1.port.Native.setPadState(mask, ax, ay, bx, by, tl, tr, activePointers)
    }

    /** A short tick for a press, if the device has a vibrator and the player wants it. */
    var haptics = true

    /** True when a real gamepad is connected: the overlay hides itself then (the controls screen can keep it shown). */
    fun gamepadConnected(): Boolean {
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            val sources = device.sources
            if (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            ) {
                return true
            }
        }
        return false
    }
}
