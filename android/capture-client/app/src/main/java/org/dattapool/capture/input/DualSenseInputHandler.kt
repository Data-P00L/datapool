package org.dattapool.capture.input

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.dattapool.capture.xr.AdjustmentStepMode
import org.dattapool.capture.xr.EyeVisibilityMode
import java.util.Locale

/**
 * Controller and Touchpad input handler for PS5 DualSense and Gear VR in XR Calibration Mode.
 */
class DualSenseInputHandler(
    private val onAdjustSelectedParam: (delta: Float) -> Unit,
    private val onNextParam: () -> Unit,
    private val onPreviousParam: () -> Unit,
    private val onResetCurrentParam: () -> Unit,
    private val onResetAllCalibration: () -> Unit = {},
    private val onToggleDisplaySource: () -> Unit,
    private val onToggleStepMode: (AdjustmentStepMode) -> Unit,
    private val onSetEyeVisibility: (EyeVisibilityMode) -> Unit,
    private val onToggleFlickerMode: () -> Unit,
    private val onSaveCalibration: () -> Unit,
    private val onExitCalibration: () -> Unit,
    private val onTouchpadClick: () -> Unit,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val scheduler: ((Runnable, Long) -> Unit)? = null,
    private val cancelScheduler: ((Runnable) -> Unit)? = null
) {

    companion object {
        private const val TAG_GAMEPAD = "DATTA_GAMEPAD"
        private const val TAG_XR_CAL = "DATTA_XR_CAL"

        // Sony DualSense Vendor and Product IDs
        const val SONY_VENDOR_ID = 0x054c
        const val DUALSENSE_PRODUCT_ID = 0x0ce6

        private const val TOUCHPAD_DEBOUNCE_MS = 250L
        private const val HELD_INITIAL_DELAY_MS = 300L
        private const val HELD_REPEAT_INTERVAL_MS = 75L
        private const val COMBO_WINDOW_MS = 600L
    }

    private var lastTouchpadClickMs = 0L
    private var lastL3DownMs = 0L
    private var lastR3DownMs = 0L
    private var heldAdjustmentRunnable: Runnable? = null
    private var isHoldingAdjustment = false

    // State tracking for analog stick clicks (L3 + R3)
    private var isL3Pressed = false
    private var isR3Pressed = false

    private val lazyHandler by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (e: Throwable) {
            null
        }
    }

    // Keep track of D-pad axis state to synthesize discrete presses
    private var lastDpadX = 0f
    private var lastDpadY = 0f

    fun logDeviceInfo(device: InputDevice?) {
        if (device == null) return
        try {
            Log.i(
                TAG_GAMEPAD,
                """
                === PS5 / GAMEPAD INPUT DEVICE DETECTED ===
                  name: '${device.name}'
                  id: ${device.id}
                  vendorId: 0x${Integer.toHexString(device.vendorId)} (${device.vendorId})
                  productId: 0x${Integer.toHexString(device.productId)} (${device.productId})
                  descriptor: ${device.descriptor}
                  sources: 0x${Integer.toHexString(device.sources)}
                  isExternal: ${device.isExternal}
                  controllerNumber: ${device.controllerNumber}
                ==========================================
                """.trimIndent()
            )
        } catch (e: Throwable) {
            // Safe fallback during testing
        }
    }

    fun isDualSenseDevice(device: InputDevice?): Boolean {
        if (device == null) return false
        try {
            if (device.vendorId == SONY_VENDOR_ID) return true
            val name = device.name.lowercase(Locale.US)
            return name.contains("dualsense") || name.contains("ps5") || name.contains("wireless controller")
        } catch (e: Throwable) {
            return false
        }
    }

    fun handleKeyEvent(
        action: Int,
        keyCode: Int,
        repeatCount: Int = 0,
        isDualSense: Boolean = false,
        deviceName: String = "unknown",
        isTouchpadDevice: Boolean = false
    ): Boolean {
        try {
            Log.d(
                TAG_GAMEPAD,
                "KeyEvent action=$action keyCode=$keyCode device='$deviceName' isDualSense=$isDualSense repeat=$repeatCount"
            )
        } catch (e: Throwable) {
            // Ignore in tests
        }

        if (action == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL) {
                isL3Pressed = false
            } else if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR) {
                isR3Pressed = false
            }
            stopHeldAdjustment()
            return true
        }

        if (action != KeyEvent.ACTION_DOWN) {
            return false
        }

        // Analog Stick Clicks: L3 + R3 -> Reset Entire Calibration Set
        if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL) {
            val now = timeProvider()
            isL3Pressed = true
            lastL3DownMs = now
            if (isR3Pressed || (lastR3DownMs > 0 && (now - lastR3DownMs) < COMBO_WINDOW_MS)) {
                try { Log.i(TAG_XR_CAL, "PS5 L3 + R3 pressed -> RESET ENTIRE CALIBRATION SET") } catch (e: Throwable) {}
                lastL3DownMs = 0L
                lastR3DownMs = 0L
                onResetAllCalibration()
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR) {
            val now = timeProvider()
            isR3Pressed = true
            lastR3DownMs = now
            if (isL3Pressed || (lastL3DownMs > 0 && (now - lastL3DownMs) < COMBO_WINDOW_MS)) {
                try { Log.i(TAG_XR_CAL, "PS5 L3 + R3 pressed -> RESET ENTIRE CALIBRATION SET") } catch (e: Throwable) {}
                lastL3DownMs = 0L
                lastR3DownMs = 0L
                onResetAllCalibration()
            }
            return true
        }

        // Suppress repeated key downs for single action buttons
        if (repeatCount > 0 &&
            keyCode != KeyEvent.KEYCODE_DPAD_LEFT &&
            keyCode != KeyEvent.KEYCODE_DPAD_RIGHT
        ) {
            return true
        }

        return when (keyCode) {
            // Parameter Navigation
            KeyEvent.KEYCODE_DPAD_UP -> {
                try { Log.i(TAG_XR_CAL, "D-PAD UP -> Previous Parameter") } catch (e: Throwable) {}
                onPreviousParam()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                try { Log.i(TAG_XR_CAL, "D-PAD DOWN -> Next Parameter") } catch (e: Throwable) {}
                onNextParam()
                true
            }

            // Value Adjustments (Left / Right)
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (repeatCount == 0) {
                    try { Log.i(TAG_XR_CAL, "D-PAD LEFT -> Decrease Value (single step)") } catch (e: Throwable) {}
                    onAdjustSelectedParam(-1f)
                    startHeldAdjustment(-1f)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (repeatCount == 0) {
                    try { Log.i(TAG_XR_CAL, "D-PAD RIGHT -> Increase Value (single step)") } catch (e: Throwable) {}
                    onAdjustSelectedParam(1f)
                    startHeldAdjustment(1f)
                }
                true
            }

            // Step Size Modes: L1 (Fine), R1 (Coarse)
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                try { Log.i(TAG_XR_CAL, "L1 -> Step Mode FINE") } catch (e: Throwable) {}
                onToggleStepMode(AdjustmentStepMode.FINE)
                true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                try { Log.i(TAG_XR_CAL, "R1 -> Step Mode COARSE") } catch (e: Throwable) {}
                onToggleStepMode(AdjustmentStepMode.COARSE)
                true
            }

            // Eye View Modes: L2 (Left Only), R2 (Right Only), Cross / A (Both), Square / X (Flicker)
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                try { Log.i(TAG_XR_CAL, "L2 -> LEFT ONLY Eye View") } catch (e: Throwable) {}
                onSetEyeVisibility(EyeVisibilityMode.LEFT_ONLY)
                true
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                try { Log.i(TAG_XR_CAL, "R2 -> RIGHT ONLY Eye View") } catch (e: Throwable) {}
                onSetEyeVisibility(EyeVisibilityMode.RIGHT_ONLY)
                true
            }
            KeyEvent.KEYCODE_BUTTON_A -> {
                try { Log.i(TAG_XR_CAL, "Cross (A) -> BOTH Eyes View") } catch (e: Throwable) {}
                onSetEyeVisibility(EyeVisibilityMode.BOTH)
                true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                try { Log.i(TAG_XR_CAL, "Square (X) -> Toggle Flicker / Alternate Eyes View") } catch (e: Throwable) {}
                onToggleFlickerMode()
                true
            }

            // Source Toggle: Triangle / Y
            KeyEvent.KEYCODE_BUTTON_Y -> {
                try { Log.i(TAG_XR_CAL, "Triangle (Y) -> Toggle Pattern / Live Passthrough Source") } catch (e: Throwable) {}
                onToggleDisplaySource()
                true
            }

            // Save Calibration Profile: Options / START
            KeyEvent.KEYCODE_BUTTON_START -> {
                try { Log.i(TAG_XR_CAL, "Options (START) -> Save Calibration Profile") } catch (e: Throwable) {}
                onSaveCalibration()
                true
            }

            // Reset Current Parameter: Create / Share / SELECT
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                if (isTouchpadDevice) {
                    attemptTouchpadClick("KeyEvent BUTTON_SELECT from touchpad")
                } else {
                    try { Log.i(TAG_XR_CAL, "Create (SELECT) -> Reset Current Parameter") } catch (e: Throwable) {}
                    onResetCurrentParam()
                }
                true
            }

            // Exit Calibration: Circle / B / Back
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                try { Log.i(TAG_XR_CAL, "Circle (B) / BACK -> Exit Calibration Mode") } catch (e: Throwable) {}
                onExitCalibration()
                true
            }

            // Touchpad Click or Center Confirm Keycodes
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_1,
            KeyEvent.KEYCODE_NAVIGATE_IN -> {
                attemptTouchpadClick("KeyEvent keyCode=$keyCode")
                true
            }

            else -> false
        }
    }

    /**
     * Handle KeyEvent dispatched to the Activity / Screen.
     * Returns true if the key event was consumed.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val device = try { InputDevice.getDevice(event.deviceId) } catch (e: Throwable) { null }
        val isDualSense = isDualSenseDevice(device)
        val isTouchpad = isTouchpadDeviceOrEvent(device, event.source)

        return handleKeyEvent(
            action = event.action,
            keyCode = event.keyCode,
            repeatCount = event.repeatCount,
            isDualSense = isDualSense,
            deviceName = device?.name ?: "unknown",
            isTouchpadDevice = isTouchpad
        )
    }

    /**
     * Handle MotionEvent (analogs, triggers, Hat axes, pointer trackpad events).
     */
    fun handleGenericMotionEvent(
        actionMasked: Int,
        buttonState: Int,
        hatX: Float,
        hatY: Float,
        deviceName: String = "unknown"
    ): Boolean {
        try {
            Log.d(
                TAG_GAMEPAD,
                "GenericMotionEvent action=$actionMasked hatX=$hatX hatY=$hatY buttonState=$buttonState device='$deviceName'"
            )
        } catch (e: Throwable) {}

        // Handle Touchpad physical click button state
        if ((buttonState and MotionEvent.BUTTON_PRIMARY) != 0 ||
            actionMasked == MotionEvent.ACTION_BUTTON_PRESS
        ) {
            return attemptTouchpadClick("GenericMotionEvent buttonState=$buttonState")
        }

        // Handle Hat D-pad axes if generated as motion events
        var handled = false
        if (hatX != lastDpadX) {
            if (hatX < -0.5f) {
                try { Log.i(TAG_XR_CAL, "Hat Axis D-PAD LEFT -> Decrease Value") } catch (e: Throwable) {}
                onAdjustSelectedParam(-1f)
                startHeldAdjustment(-1f)
                handled = true
            } else if (hatX > 0.5f) {
                try { Log.i(TAG_XR_CAL, "Hat Axis D-PAD RIGHT -> Increase Value") } catch (e: Throwable) {}
                onAdjustSelectedParam(1f)
                startHeldAdjustment(1f)
                handled = true
            } else {
                stopHeldAdjustment()
            }
            lastDpadX = hatX
        }

        if (hatY != lastDpadY) {
            if (hatY < -0.5f) {
                try { Log.i(TAG_XR_CAL, "Hat Axis D-PAD UP -> Previous Parameter") } catch (e: Throwable) {}
                onPreviousParam()
                handled = true
            } else if (hatY > 0.5f) {
                try { Log.i(TAG_XR_CAL, "Hat Axis D-PAD DOWN -> Next Parameter") } catch (e: Throwable) {}
                onNextParam()
                handled = true
            }
            lastDpadY = hatY
        }

        return handled
    }

    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        val device = try { InputDevice.getDevice(event.deviceId) } catch (e: Throwable) { null }
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        return handleGenericMotionEvent(
            actionMasked = event.actionMasked,
            buttonState = event.buttonState,
            hatX = hatX,
            hatY = hatY,
            deviceName = device?.name ?: "unknown"
        )
    }

    /**
     * Handle TouchEvent from Touchpad or screen.
     */
    fun handleTouchEvent(event: MotionEvent): Boolean {
        val device = try { InputDevice.getDevice(event.deviceId) } catch (e: Throwable) { null }
        val isExternalTouchpad = isTouchpadDeviceOrEvent(device, event.source)

        if (isExternalTouchpad) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                (event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
            ) {
                return attemptTouchpadClick("TouchEvent action=${event.actionMasked} device='${device?.name}'")
            }
        }
        return false
    }

    private fun isTouchpadDeviceOrEvent(device: InputDevice?, source: Int): Boolean {
        if (device == null) return false
        try {
            val name = device.name.lowercase(Locale.US)
            if (name.contains("touchpad") || name.contains("trackpad")) return true
            return (source and InputDevice.SOURCE_TOUCHPAD) != 0 ||
                   (source and InputDevice.SOURCE_MOUSE) != 0
        } catch (e: Throwable) {
            return false
        }
    }

    private fun attemptTouchpadClick(details: String): Boolean {
        val now = timeProvider()
        val elapsed = now - lastTouchpadClickMs

        if (lastTouchpadClickMs > 0 && elapsed < TOUCHPAD_DEBOUNCE_MS) {
            try { Log.d(TAG_XR_CAL, "Debounce suppressed touchpad click (${elapsed}ms < ${TOUCHPAD_DEBOUNCE_MS}ms): $details") } catch (e: Throwable) {}
            return true
        }

        lastTouchpadClickMs = now
        try { Log.i(TAG_XR_CAL, "PS5 Touchpad CLICK confirmed: $details") } catch (e: Throwable) {}
        onTouchpadClick()
        return true
    }

    private fun startHeldAdjustment(direction: Float) {
        stopHeldAdjustment()
        isHoldingAdjustment = true

        val runnable = object : Runnable {
            override fun run() {
                if (isHoldingAdjustment) {
                    onAdjustSelectedParam(direction)
                    scheduleRun(this, HELD_REPEAT_INTERVAL_MS)
                }
            }
        }
        heldAdjustmentRunnable = runnable
        scheduleRun(runnable, HELD_INITIAL_DELAY_MS)
    }

    private fun scheduleRun(runnable: Runnable, delayMs: Long) {
        if (scheduler != null) {
            scheduler.invoke(runnable, delayMs)
        } else {
            lazyHandler?.postDelayed(runnable, delayMs)
        }
    }

    private fun stopHeldAdjustment() {
        isHoldingAdjustment = false
        heldAdjustmentRunnable?.let { runnable ->
            if (cancelScheduler != null) {
                cancelScheduler.invoke(runnable)
            } else {
                lazyHandler?.removeCallbacks(runnable)
            }
        }
        heldAdjustmentRunnable = null
    }
}
