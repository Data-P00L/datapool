package org.dattapool.capture.input

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

class ExternalCaptureInputHandler(
    private val onToggleCaptureRequested: (CaptureTriggerSource) -> Unit,
    private val debounceMs: Long = CAPTURE_DEBOUNCE_MS,
    private val timeProvider: () -> Long = { SystemClock.elapsedRealtime() }
) {

    companion object {
        const val CAPTURE_DEBOUNCE_MS = 500L
        private const val TAG_GEARVR = "DATTA_GEARVR"
        private const val TAG_CAPTURE = "DATTA_CAPTURE"
    }

    private var lastCaptureTriggerMs = 0L

    fun handleKeyEvent(
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        isGearVr: Boolean = false,
        isExternal: Boolean = false,
        deviceName: String = "unknown"
    ): Boolean {
        if (action != KeyEvent.ACTION_DOWN) {
            return false
        }

        // Suppress repeating keys when held down
        if (repeatCount > 0) {
            Log.d(TAG_GEARVR, "Ignoring repeated key event keyCode=$keyCode repeat=$repeatCount")
            return true
        }

        val isCaptureKey = GearVrInputDetector.isCaptureKeyCode(keyCode)

        if ((isGearVr || isExternal || isCaptureKey) && isCaptureKey) {
            val source = if (isGearVr) CaptureTriggerSource.GEAR_VR else CaptureTriggerSource.HARDWARE_BUTTON
            return attemptTrigger(source, "KeyEvent keyCode=$keyCode device='$deviceName'")
        }

        return false
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val device = InputDevice.getDevice(event.deviceId)
        val isGearVr = GearVrInputDetector.isGearVrDevice(device)
        val isExternal = GearVrInputDetector.isExternalCaptureDevice(device)
        return handleKeyEvent(
            action = event.action,
            keyCode = event.keyCode,
            repeatCount = event.repeatCount,
            isGearVr = isGearVr,
            isExternal = isExternal,
            deviceName = device?.name ?: "unknown"
        )
    }

    fun handleGenericMotionEvent(
        actionMasked: Int,
        buttonState: Int,
        source: Int,
        isGearVr: Boolean = false,
        isExternal: Boolean = false,
        deviceName: String = "unknown"
    ): Boolean {
        if (GearVrInputDetector.isCaptureMotionEvent(actionMasked, buttonState, source, isGearVr || isExternal)) {
            val triggerSource = if (isGearVr) CaptureTriggerSource.GEAR_VR else CaptureTriggerSource.EXTERNAL_CONTROLLER
            return attemptTrigger(triggerSource, "GenericMotionEvent action=$actionMasked device='$deviceName'")
        }
        return false
    }

    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        val device = InputDevice.getDevice(event.deviceId)
        val isGearVr = GearVrInputDetector.isGearVrDevice(device)
        val isExternal = GearVrInputDetector.isExternalCaptureDevice(device)
        return handleGenericMotionEvent(
            actionMasked = event.actionMasked,
            buttonState = event.buttonState,
            source = event.source,
            isGearVr = isGearVr,
            isExternal = isExternal,
            deviceName = device?.name ?: "unknown"
        )
    }

    fun handleTouchEvent(event: MotionEvent): Boolean {
        val device = InputDevice.getDevice(event.deviceId)
        val isGearVr = GearVrInputDetector.isGearVrDevice(device)
        val isExternal = GearVrInputDetector.isExternalCaptureDevice(device)

        val isTouchscreen = (event.source and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
        val isMouseOrTrackpad = !isTouchscreen && (
                (event.source and InputDevice.SOURCE_MOUSE) != 0 ||
                (event.source and InputDevice.SOURCE_TOUCHPAD) != 0 ||
                (event.source and InputDevice.SOURCE_TRACKBALL) != 0 ||
                isGearVr || isExternal
        )

        // Only intercept if the event comes from external mouse/trackpad pointer (not direct finger on phone touchscreen)
        if (isMouseOrTrackpad) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                (event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0) {
                val source = if (isGearVr) CaptureTriggerSource.GEAR_VR else CaptureTriggerSource.EXTERNAL_CONTROLLER
                return attemptTrigger(source, "TouchEvent action=${event.actionMasked} device='${device?.name}' buttonState=${event.buttonState}")
            }
        }
        return false
    }

    private fun attemptTrigger(source: CaptureTriggerSource, details: String): Boolean {
        val now = timeProvider()
        val elapsed = now - lastCaptureTriggerMs

        if (lastCaptureTriggerMs > 0 && elapsed < debounceMs) {
            Log.d(TAG_GEARVR, "Debounce suppressed trigger (${elapsed}ms < ${debounceMs}ms): $details")
            return true
        }

        lastCaptureTriggerMs = now
        Log.i(TAG_GEARVR, "Physical trackpad capture trigger detected: $details -> source=$source")
        Log.i(TAG_CAPTURE, "Toggle capture requested from source=${source.sourceName}")
        onToggleCaptureRequested(source)
        return true
    }
}
