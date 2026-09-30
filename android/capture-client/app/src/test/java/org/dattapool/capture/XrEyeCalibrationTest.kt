package org.dattapool.capture

import org.dattapool.capture.xr.AdjustmentStepMode
import org.dattapool.capture.xr.CalibrationParam
import org.dattapool.capture.xr.XrEyeCalibration
import org.junit.Assert.*
import org.junit.Test

class XrEyeCalibrationTest {

    @Test
    fun testDefaultCalibrationValues() {
        val cal = XrEyeCalibration()
        assertEquals(0f, cal.leftOffsetXPx, 0.0001f)
        assertEquals(0f, cal.leftOffsetYPx, 0.0001f)
        assertEquals(1f, cal.leftScale, 0.0001f)
        assertEquals(0f, cal.leftRotationDeg, 0.0001f)

        assertEquals(0f, cal.rightOffsetXPx, 0.0001f)
        assertEquals(0f, cal.rightOffsetYPx, 0.0001f)
        assertEquals(1f, cal.rightScale, 0.0001f)
        assertEquals(0f, cal.rightRotationDeg, 0.0001f)

        assertEquals(0f, cal.globalEyeSeparationPx, 0.0001f)
        assertEquals(0f, cal.globalVerticalOffsetPx, 0.0001f)

        assertEquals(0f, cal.effectiveLeftX, 0.0001f)
        assertEquals(0f, cal.effectiveRightX, 0.0001f)
        assertEquals(0f, cal.effectiveLeftY, 0.0001f)
        assertEquals(0f, cal.effectiveRightY, 0.0001f)
    }

    @Test
    fun testAdjustTranslationFineAndCoarse() {
        var cal = XrEyeCalibration()

        // Fine step = 1 px
        val deltaFine = CalibrationParam.RIGHT_X.getDelta(AdjustmentStepMode.FINE, isPositive = true)
        assertEquals(1.0f, deltaFine, 0.0001f)
        cal = cal.adjust(CalibrationParam.RIGHT_X, deltaFine)
        assertEquals(1.0f, cal.rightOffsetXPx, 0.0001f)

        // Coarse step = 10 px
        val deltaCoarse = CalibrationParam.RIGHT_X.getDelta(AdjustmentStepMode.COARSE, isPositive = true)
        assertEquals(10.0f, deltaCoarse, 0.0001f)
        cal = cal.adjust(CalibrationParam.RIGHT_X, deltaCoarse)
        assertEquals(11.0f, cal.rightOffsetXPx, 0.0001f)

        // Negative fine step
        val deltaNeg = CalibrationParam.RIGHT_X.getDelta(AdjustmentStepMode.FINE, isPositive = false)
        assertEquals(-1.0f, deltaNeg, 0.0001f)
        cal = cal.adjust(CalibrationParam.RIGHT_X, deltaNeg)
        assertEquals(10.0f, cal.rightOffsetXPx, 0.0001f)
    }

    @Test
    fun testAdjustScaleFineAndCoarse() {
        var cal = XrEyeCalibration()

        // Fine step = 0.001
        val deltaFine = CalibrationParam.RIGHT_SCALE.getDelta(AdjustmentStepMode.FINE, isPositive = true)
        assertEquals(0.001f, deltaFine, 0.00001f)
        cal = cal.adjust(CalibrationParam.RIGHT_SCALE, deltaFine)
        assertEquals(1.001f, cal.rightScale, 0.00001f)

        // Coarse step = 0.010
        val deltaCoarse = CalibrationParam.RIGHT_SCALE.getDelta(AdjustmentStepMode.COARSE, isPositive = true)
        assertEquals(0.010f, deltaCoarse, 0.00001f)
        cal = cal.adjust(CalibrationParam.RIGHT_SCALE, deltaCoarse)
        assertEquals(1.011f, cal.rightScale, 0.00001f)
    }

    @Test
    fun testAdjustRotationFineAndCoarse() {
        var cal = XrEyeCalibration()

        // Fine step = 0.05 deg
        val deltaFine = CalibrationParam.RIGHT_ROTATION.getDelta(AdjustmentStepMode.FINE, isPositive = true)
        assertEquals(0.05f, deltaFine, 0.0001f)
        cal = cal.adjust(CalibrationParam.RIGHT_ROTATION, deltaFine)
        assertEquals(0.05f, cal.rightRotationDeg, 0.0001f)

        // Coarse step = 0.50 deg
        val deltaCoarse = CalibrationParam.RIGHT_ROTATION.getDelta(AdjustmentStepMode.COARSE, isPositive = true)
        assertEquals(0.50f, deltaCoarse, 0.0001f)
        cal = cal.adjust(CalibrationParam.RIGHT_ROTATION, deltaCoarse)
        assertEquals(0.55f, cal.rightRotationDeg, 0.0001f)
    }

    @Test
    fun testEffectiveEyeOffsetsWithSeparationAndVerticalOffset() {
        val cal = XrEyeCalibration(
            leftOffsetXPx = 5f,
            leftOffsetYPx = -2f,
            rightOffsetXPx = -18f,
            rightOffsetYPx = 4f,
            globalEyeSeparationPx = 20f,
            globalVerticalOffsetPx = 10f
        )

        // Left effective X = 5 - (20 / 2) = -5
        assertEquals(-5f, cal.effectiveLeftX, 0.0001f)
        // Right effective X = -18 + (20 / 2) = -8
        assertEquals(-8f, cal.effectiveRightX, 0.0001f)

        // Left effective Y = -2 + 10 = 8
        assertEquals(8f, cal.effectiveLeftY, 0.0001f)
        // Right effective Y = 4 + 10 = 14
        assertEquals(14f, cal.effectiveRightY, 0.0001f)
    }

    @Test
    fun testParameterNavigationCycle() {
        var param = CalibrationParam.RIGHT_X
        assertEquals(CalibrationParam.RIGHT_Y, param.next())
        param = param.next() // RIGHT_Y
        assertEquals(CalibrationParam.RIGHT_SCALE, param.next())
        param = param.next() // RIGHT_SCALE
        assertEquals(CalibrationParam.RIGHT_ROTATION, param.next())
        param = param.next() // RIGHT_ROTATION
        assertEquals(CalibrationParam.LEFT_X, param.next())

        // Previous from first wraps to last
        assertEquals(CalibrationParam.GLOBAL_VERTICAL, CalibrationParam.RIGHT_X.previous())
        // Next from last wraps to first
        assertEquals(CalibrationParam.RIGHT_X, CalibrationParam.GLOBAL_VERTICAL.next())
    }

    @Test
    fun testResetIndividualParameter() {
        var cal = XrEyeCalibration(
            rightOffsetXPx = -18f,
            rightOffsetYPx = 5f,
            rightScale = 0.996f,
            rightRotationDeg = 1.25f
        )

        cal = cal.resetParam(CalibrationParam.RIGHT_X)
        assertEquals(0f, cal.rightOffsetXPx, 0.0001f)
        assertEquals(5f, cal.rightOffsetYPx, 0.0001f)
        assertEquals(0.996f, cal.rightScale, 0.0001f)

        cal = cal.resetParam(CalibrationParam.RIGHT_SCALE)
        assertEquals(1.0f, cal.rightScale, 0.0001f)
    }

    @Test
    fun testFormattingValues() {
        val cal = XrEyeCalibration(
            rightOffsetXPx = -18f,
            rightOffsetYPx = 5f,
            rightScale = 0.996f,
            rightRotationDeg = 0.0f
        )

        assertEquals("-18 px", cal.formatValue(CalibrationParam.RIGHT_X))
        assertEquals("+5 px", cal.formatValue(CalibrationParam.RIGHT_Y))
        assertEquals("0.996", cal.formatValue(CalibrationParam.RIGHT_SCALE))
        assertEquals("+0.00°", cal.formatValue(CalibrationParam.RIGHT_ROTATION))
    }
}
