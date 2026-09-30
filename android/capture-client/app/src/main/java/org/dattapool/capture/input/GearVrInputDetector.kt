package org.dattapool.capture.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

object GearVrInputDetector {

    // Known Samsung / Oculus Vendor IDs
    const val VENDOR_SAMSUNG = 0x04E8
    const val VENDOR_OCULUS = 0x2833

    // Standard keycodes often emitted by VR touchpads, remotes, controllers, and mouse devices
    val CAPTURE_KEYCODES = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_1,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_CAMERA,
        KeyEvent.KEYCODE_MEDIA_RECORD,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_NAVIGATE_IN
    )

    fun isGearVrDevice(device: InputDevice?): Boolean {
        if (device == null) return false

        // Check vendor ID if available
        if (device.vendorId == VENDOR_SAMSUNG || device.vendorId == VENDOR_OCULUS) {
            return true
        }

        // Check name patterns for Gear VR headsets, touchpad nodes, or controllers
        val name = device.name.lowercase()
        return name.contains("gear") || 
               name.contains("vr") || 
               name.contains("oculus") || 
               name.contains("sm-r") || 
               name.contains("et-yo") ||
               name.contains("sec_touchpad") ||
               name.contains("touchpad") ||
               name.contains("trackpad")
    }

    fun isExternalCaptureDevice(device: InputDevice?): Boolean {
        if (device == null) return false
        if (isGearVrDevice(device)) return true
        
        // External non-virtual devices with mouse, gamepad, joystick, or key inputs
        if (device.isExternal) {
            val sources = device.sources
            val hasPointerOrGamepad = (sources and InputDevice.SOURCE_GAMEPAD) != 0 ||
                    (sources and InputDevice.SOURCE_JOYSTICK) != 0 ||
                    (sources and InputDevice.SOURCE_TOUCHPAD) != 0 ||
                    (sources and InputDevice.SOURCE_MOUSE) != 0 ||
                    (sources and InputDevice.SOURCE_KEYBOARD) != 0
            if (hasPointerOrGamepad) return true
        }

        return false
    }

    fun isCaptureKeyCode(keyCode: Int): Boolean {
        // Exclude system keys that should NOT trigger capture
        if (keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE ||
            keyCode == KeyEvent.KEYCODE_POWER ||
            keyCode == KeyEvent.KEYCODE_HOME ||
            keyCode == KeyEvent.KEYCODE_APP_SWITCH
        ) {
            return false
        }

        // Check if key is a known capture key
        return CAPTURE_KEYCODES.contains(keyCode)
    }

    fun isCaptureKey(event: KeyEvent): Boolean {
        return isCaptureKeyCode(event.keyCode)
    }

    fun isCaptureMotionEvent(
        actionMasked: Int,
        buttonState: Int,
        source: Int,
        isGearVrOrExternal: Boolean = true
    ): Boolean {
        val isPointerOrMouse = (source and InputDevice.SOURCE_MOUSE) != 0 ||
                (source and InputDevice.SOURCE_TOUCHPAD) != 0 ||
                (source and InputDevice.SOURCE_TRACKBALL) != 0 ||
                (source and InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                (source and InputDevice.SOURCE_JOYSTICK) != 0 ||
                isGearVrOrExternal

        if (isPointerOrMouse) {
            if (actionMasked == MotionEvent.ACTION_DOWN ||
                actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                (buttonState and MotionEvent.BUTTON_PRIMARY) != 0) {
                return true
            }
        }
        return false
    }

    fun isCaptureMotion(event: MotionEvent): Boolean {
        val device = InputDevice.getDevice(event.deviceId)
        val isGearVr = isGearVrDevice(device)
        val isExternal = isExternalCaptureDevice(device)

        val isMouseOrTrackpad = (event.source and InputDevice.SOURCE_MOUSE) != 0 ||
                (event.source and InputDevice.SOURCE_TOUCHPAD) != 0 ||
                (event.source and InputDevice.SOURCE_TRACKBALL) != 0 ||
                (event.source and InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                (event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0 ||
                isGearVr || isExternal

        if (isMouseOrTrackpad) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                (event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0) {
                return true
            }
        }

        return false
    }
}
