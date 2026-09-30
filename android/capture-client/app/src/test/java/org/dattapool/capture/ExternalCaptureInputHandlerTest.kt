package org.dattapool.capture

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.dattapool.capture.input.CaptureTriggerSource
import org.dattapool.capture.input.ExternalCaptureInputHandler
import org.dattapool.capture.input.GearVrInputDetector
import org.junit.Assert.*
import org.junit.Test

class ExternalCaptureInputHandlerTest {

    @Test
    fun testIsCaptureKeyExcludesVolumeAndBack() {
        assertFalse(GearVrInputDetector.isCaptureKeyCode(KeyEvent.KEYCODE_BACK))
        assertFalse(GearVrInputDetector.isCaptureKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(GearVrInputDetector.isCaptureKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertFalse(GearVrInputDetector.isCaptureKeyCode(KeyEvent.KEYCODE_VOLUME_MUTE))
    }

    @Test
    fun testIsCaptureKeyIncludesStandardTriggers() {
        assertTrue(GearVrInputDetector.isCaptureKeyCode(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(GearVrInputDetector.isCaptureKeyCode(KeyEvent.KEYCODE_BUTTON_A))
        assertTrue(GearVrInputDetector.isCaptureKeyCode(KeyEvent.KEYCODE_BUTTON_1))
        assertTrue(GearVrInputDetector.isCaptureKeyCode(KeyEvent.KEYCODE_ENTER))
        assertTrue(GearVrInputDetector.isCaptureKeyCode(KeyEvent.KEYCODE_CAMERA))
    }

    @Test
    fun testDebounceSuppressesRapidEvents() {
        var triggerCount = 0
        var simulatedTime = 1000L

        val handler = ExternalCaptureInputHandler(
            onToggleCaptureRequested = { triggerCount++ },
            debounceMs = 500L,
            timeProvider = { simulatedTime }
        )

        // First tap at 1000ms -> should trigger
        val handled1 = handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BUTTON_A,
            repeatCount = 0,
            isGearVr = true,
            deviceName = "Samsung Gear VR"
        )
        assertTrue(handled1)
        assertEquals(1, triggerCount)

        // Rapid tap at 1200ms (200ms < 500ms debounce) -> should be suppressed
        simulatedTime = 1200L
        val handled2 = handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BUTTON_A,
            repeatCount = 0,
            isGearVr = true,
            deviceName = "Samsung Gear VR"
        )
        assertTrue(handled2) // Handled/consumed
        assertEquals(1, triggerCount) // Still 1

        // Tap at 1600ms (600ms >= 500ms debounce) -> should trigger second time
        simulatedTime = 1600L
        val handled3 = handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BUTTON_A,
            repeatCount = 0,
            isGearVr = true,
            deviceName = "Samsung Gear VR"
        )
        assertTrue(handled3)
        assertEquals(2, triggerCount)
    }

    @Test
    fun testKeyUpDoesNotTriggerCapture() {
        var triggerCount = 0
        val handler = ExternalCaptureInputHandler(
            onToggleCaptureRequested = { triggerCount++ }
        )

        val handled = handler.handleKeyEvent(
            action = KeyEvent.ACTION_UP,
            keyCode = KeyEvent.KEYCODE_BUTTON_A,
            repeatCount = 0,
            isGearVr = true
        )

        assertFalse(handled)
        assertEquals(0, triggerCount)
    }

    @Test
    fun testKeyRepeatDoesNotTriggerSecondCapture() {
        var triggerCount = 0
        var simulatedTime = 1000L

        val handler = ExternalCaptureInputHandler(
            onToggleCaptureRequested = { triggerCount++ },
            timeProvider = { simulatedTime }
        )

        // Initial DOWN (repeatCount = 0)
        val handledDown = handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BUTTON_A,
            repeatCount = 0,
            isGearVr = true
        )
        assertTrue(handledDown)
        assertEquals(1, triggerCount)

        // Held down repeat event (repeatCount = 1)
        simulatedTime = 1100L
        val handledRepeat1 = handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BUTTON_A,
            repeatCount = 1,
            isGearVr = true
        )
        assertTrue(handledRepeat1)
        assertEquals(1, triggerCount) // Still 1

        // Held down repeat event (repeatCount = 2)
        simulatedTime = 1200L
        val handledRepeat2 = handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BUTTON_A,
            repeatCount = 2,
            isGearVr = true
        )
        assertTrue(handledRepeat2)
        assertEquals(1, triggerCount) // Still 1
    }

    @Test
    fun testMotionEventTouchpadTapTriggersCapture() {
        var triggerCount = 0
        var simulatedTime = 1000L
        var reportedSource: CaptureTriggerSource? = null

        val handler = ExternalCaptureInputHandler(
            onToggleCaptureRequested = { source ->
                triggerCount++
                reportedSource = source
            },
            timeProvider = { simulatedTime }
        )

        val handledMotion = handler.handleGenericMotionEvent(
            actionMasked = MotionEvent.ACTION_DOWN,
            buttonState = MotionEvent.BUTTON_PRIMARY,
            source = InputDevice.SOURCE_TOUCHPAD,
            isGearVr = true,
            deviceName = "Samsung Gear VR"
        )

        assertTrue(handledMotion)
        assertEquals(1, triggerCount)
        assertEquals(CaptureTriggerSource.GEAR_VR, reportedSource)
    }
}
