package org.dattapool.capture

import android.view.KeyEvent
import org.dattapool.capture.input.DualSenseInputHandler
import org.dattapool.capture.xr.AdjustmentStepMode
import org.dattapool.capture.xr.EyeVisibilityMode
import org.junit.Assert.*
import org.junit.Test

class DualSenseInputHandlerTest {

    @Test
    fun testDpadLeftRightAdjustment() {
        var adjustedDelta = 0f
        val handler = DualSenseInputHandler(
            onAdjustSelectedParam = { delta -> adjustedDelta = delta },
            onNextParam = {},
            onPreviousParam = {},
            onResetCurrentParam = {},
            onToggleDisplaySource = {},
            onToggleStepMode = {},
            onSetEyeVisibility = {},
            onToggleFlickerMode = {},
            onSaveCalibration = {},
            onExitCalibration = {},
            onTouchpadClick = {}
        )

        val handledLeft = handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_DPAD_LEFT
        )
        assertTrue(handledLeft)
        assertEquals(-1f, adjustedDelta, 0.0001f)

        val handledRight = handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT
        )
        assertTrue(handledRight)
        assertEquals(1f, adjustedDelta, 0.0001f)
    }

    @Test
    fun testDpadUpDownParameterNavigation() {
        var nextCount = 0
        var prevCount = 0
        val handler = DualSenseInputHandler(
            onAdjustSelectedParam = {},
            onNextParam = { nextCount++ },
            onPreviousParam = { prevCount++ },
            onResetCurrentParam = {},
            onToggleDisplaySource = {},
            onToggleStepMode = {},
            onSetEyeVisibility = {},
            onToggleFlickerMode = {},
            onSaveCalibration = {},
            onExitCalibration = {},
            onTouchpadClick = {}
        )

        handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_DPAD_UP
        )
        assertEquals(1, prevCount)
        assertEquals(0, nextCount)

        handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_DPAD_DOWN
        )
        assertEquals(1, prevCount)
        assertEquals(1, nextCount)
    }

    @Test
    fun testStepModeButtons() {
        var recordedMode: AdjustmentStepMode? = null
        val handler = DualSenseInputHandler(
            onAdjustSelectedParam = {},
            onNextParam = {},
            onPreviousParam = {},
            onResetCurrentParam = {},
            onToggleDisplaySource = {},
            onToggleStepMode = { recordedMode = it },
            onSetEyeVisibility = {},
            onToggleFlickerMode = {},
            onSaveCalibration = {},
            onExitCalibration = {},
            onTouchpadClick = {}
        )

        handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BUTTON_L1
        )
        assertEquals(AdjustmentStepMode.FINE, recordedMode)

        handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BUTTON_R1
        )
        assertEquals(AdjustmentStepMode.COARSE, recordedMode)
    }

    @Test
    fun testEyeVisibilityButtons() {
        var recordedVisibility: EyeVisibilityMode? = null
        var flickerToggled = false
        val handler = DualSenseInputHandler(
            onAdjustSelectedParam = {},
            onNextParam = {},
            onPreviousParam = {},
            onResetCurrentParam = {},
            onToggleDisplaySource = {},
            onToggleStepMode = {},
            onSetEyeVisibility = { recordedVisibility = it },
            onToggleFlickerMode = { flickerToggled = true },
            onSaveCalibration = {},
            onExitCalibration = {},
            onTouchpadClick = {}
        )

        handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BUTTON_L2
        )
        assertEquals(EyeVisibilityMode.LEFT_ONLY, recordedVisibility)

        handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BUTTON_R2
        )
        assertEquals(EyeVisibilityMode.RIGHT_ONLY, recordedVisibility)

        handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BUTTON_A
        )
        assertEquals(EyeVisibilityMode.BOTH, recordedVisibility)

        handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BUTTON_X
        )
        assertTrue(flickerToggled)
    }

    @Test
    fun testSourceAndSaveButtons() {
        var sourceToggled = false
        var saved = false
        var exited = false
        val handler = DualSenseInputHandler(
            onAdjustSelectedParam = {},
            onNextParam = {},
            onPreviousParam = {},
            onResetCurrentParam = {},
            onToggleDisplaySource = { sourceToggled = true },
            onToggleStepMode = {},
            onSetEyeVisibility = {},
            onToggleFlickerMode = {},
            onSaveCalibration = { saved = true },
            onExitCalibration = { exited = true },
            onTouchpadClick = {}
        )

        // Triangle -> Toggle Source
        handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BUTTON_Y
        )
        assertTrue(sourceToggled)

        // Options -> Save
        handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BUTTON_START
        )
        assertTrue(saved)

        // Circle -> Exit
        handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BUTTON_B
        )
        assertTrue(exited)
    }

    @Test
    fun testTouchpadClickDebouncing() {
        var clickCount = 0
        var simulatedTime = 1000L
        val handler = DualSenseInputHandler(
            onAdjustSelectedParam = {},
            onNextParam = {},
            onPreviousParam = {},
            onResetCurrentParam = {},
            onToggleDisplaySource = {},
            onToggleStepMode = {},
            onSetEyeVisibility = {},
            onToggleFlickerMode = {},
            onSaveCalibration = {},
            onExitCalibration = {},
            onTouchpadClick = { clickCount++ },
            timeProvider = { simulatedTime }
        )

        // First click
        handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER
        )
        assertEquals(1, clickCount)

        // Click within debounce window (< 250ms)
        simulatedTime = 1100L
        handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER
        )
        assertEquals(1, clickCount)

        // Click after debounce window (>= 250ms)
        simulatedTime = 1300L
        handler.handleKeyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER
        )
        assertEquals(2, clickCount)
    }

    @Test
    fun testGenericMotionEventHatAxes() {
        var adjustedDelta = 0f
        var nextCount = 0
        val handler = DualSenseInputHandler(
            onAdjustSelectedParam = { delta -> adjustedDelta = delta },
            onNextParam = { nextCount++ },
            onPreviousParam = {},
            onResetCurrentParam = {},
            onToggleDisplaySource = {},
            onToggleStepMode = {},
            onSetEyeVisibility = {},
            onToggleFlickerMode = {},
            onSaveCalibration = {},
            onExitCalibration = {},
            onTouchpadClick = {}
        )

        // Hat X = 1.0 (Right)
        handler.handleGenericMotionEvent(
            actionMasked = 0,
            buttonState = 0,
            hatX = 1.0f,
            hatY = 0f
        )
        assertEquals(1f, adjustedDelta, 0.0001f)

        // Hat Y = 1.0 (Down)
        handler.handleGenericMotionEvent(
            actionMasked = 0,
            buttonState = 0,
            hatX = 0f,
            hatY = 1.0f
        )
        assertEquals(1, nextCount)
    }

    @Test
    fun testL3R3ResetAllCalibration() {
        var resetAllCount = 0
        val handler = DualSenseInputHandler(
            onAdjustSelectedParam = {},
            onNextParam = {},
            onPreviousParam = {},
            onResetCurrentParam = {},
            onResetAllCalibration = { resetAllCount++ },
            onToggleDisplaySource = {},
            onToggleStepMode = {},
            onSetEyeVisibility = {},
            onToggleFlickerMode = {},
            onSaveCalibration = {},
            onExitCalibration = {},
            onTouchpadClick = {}
        )

        // 1. Press L3 alone (ACTION_DOWN) -> No reset
        handler.handleKeyEvent(action = KeyEvent.ACTION_DOWN, keyCode = KeyEvent.KEYCODE_BUTTON_THUMBL)
        assertEquals(0, resetAllCount)

        // 2. Press R3 while L3 is held -> Reset triggered!
        handler.handleKeyEvent(action = KeyEvent.ACTION_DOWN, keyCode = KeyEvent.KEYCODE_BUTTON_THUMBR)
        assertEquals(1, resetAllCount)

        // 3. Release L3 and R3
        handler.handleKeyEvent(action = KeyEvent.ACTION_UP, keyCode = KeyEvent.KEYCODE_BUTTON_THUMBL)
        handler.handleKeyEvent(action = KeyEvent.ACTION_UP, keyCode = KeyEvent.KEYCODE_BUTTON_THUMBR)

        // 4. Press R3 alone -> No reset
        handler.handleKeyEvent(action = KeyEvent.ACTION_DOWN, keyCode = KeyEvent.KEYCODE_BUTTON_THUMBR)
        assertEquals(1, resetAllCount)

        // 5. Press L3 while R3 is held -> Reset triggered again!
        handler.handleKeyEvent(action = KeyEvent.ACTION_DOWN, keyCode = KeyEvent.KEYCODE_BUTTON_THUMBL)
        assertEquals(2, resetAllCount)
    }
}
