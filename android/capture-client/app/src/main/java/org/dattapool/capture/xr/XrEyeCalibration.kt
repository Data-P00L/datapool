package org.dattapool.capture.xr

import android.util.Log
import java.util.Locale

/**
 * Optical calibration data for stereo XR viewing in Gear VR.
 *
 * All offsets are in screen pixels. Scale is a multiplier (1.0 = normal).
 * Rotation is in degrees.
 */
data class XrEyeCalibration(
    val leftOffsetXPx: Float = 0f,
    val leftOffsetYPx: Float = 0f,
    val leftScale: Float = 1f,
    val leftRotationDeg: Float = 0f,

    val rightOffsetXPx: Float = 0f,
    val rightOffsetYPx: Float = 0f,
    val rightScale: Float = 1f,
    val rightRotationDeg: Float = 0f,

    val globalEyeSeparationPx: Float = 0f,
    val globalVerticalOffsetPx: Float = 0f
) {
    // Effective Left Eye values incorporating global parameters
    val effectiveLeftX: Float get() = leftOffsetXPx - (globalEyeSeparationPx / 2f)
    val effectiveLeftY: Float get() = leftOffsetYPx + globalVerticalOffsetPx
    val effectiveLeftScale: Float get() = leftScale
    val effectiveLeftRotation: Float get() = leftRotationDeg

    // Effective Right Eye values incorporating global parameters
    val effectiveRightX: Float get() = rightOffsetXPx + (globalEyeSeparationPx / 2f)
    val effectiveRightY: Float get() = rightOffsetYPx + globalVerticalOffsetPx
    val effectiveRightScale: Float get() = rightScale
    val effectiveRightRotation: Float get() = rightRotationDeg

    fun logSummary(tag: String = "DATTA_XR_CAL", profile: String = "gearvr_s20fe_v1") {
        Log.i(
            tag,
            String.format(
                Locale.US,
                "DATTA_XR_CAL profile=%s rightX=%.1f rightY=%.1f rightScale=%.3f rightRotation=%.2f leftX=%.1f leftY=%.1f leftScale=%.3f leftRotation=%.2f eyeSep=%.1f vertOff=%.1f",
                profile,
                rightOffsetXPx,
                rightOffsetYPx,
                rightScale,
                rightRotationDeg,
                leftOffsetXPx,
                leftOffsetYPx,
                leftScale,
                leftRotationDeg,
                globalEyeSeparationPx,
                globalVerticalOffsetPx
            )
        )
    }

    /**
     * Adjust a specific parameter by the specified delta.
     */
    fun adjust(param: CalibrationParam, delta: Float): XrEyeCalibration {
        return when (param) {
            CalibrationParam.RIGHT_X -> copy(rightOffsetXPx = rightOffsetXPx + delta)
            CalibrationParam.RIGHT_Y -> copy(rightOffsetYPx = rightOffsetYPx + delta)
            CalibrationParam.RIGHT_SCALE -> copy(rightScale = (rightScale + delta).coerceIn(0.5f, 2.0f))
            CalibrationParam.RIGHT_ROTATION -> copy(rightRotationDeg = (rightRotationDeg + delta).coerceIn(-45f, 45f))
            CalibrationParam.LEFT_X -> copy(leftOffsetXPx = leftOffsetXPx + delta)
            CalibrationParam.LEFT_Y -> copy(leftOffsetYPx = leftOffsetYPx + delta)
            CalibrationParam.LEFT_SCALE -> copy(leftScale = (leftScale + delta).coerceIn(0.5f, 2.0f))
            CalibrationParam.LEFT_ROTATION -> copy(leftRotationDeg = (leftRotationDeg + delta).coerceIn(-45f, 45f))
            CalibrationParam.GLOBAL_SEPARATION -> copy(globalEyeSeparationPx = globalEyeSeparationPx + delta)
            CalibrationParam.GLOBAL_VERTICAL -> copy(globalVerticalOffsetPx = globalVerticalOffsetPx + delta)
        }
    }

    /**
     * Reset a specific parameter to default value.
     */
    fun resetParam(param: CalibrationParam): XrEyeCalibration {
        return when (param) {
            CalibrationParam.RIGHT_X -> copy(rightOffsetXPx = 0f)
            CalibrationParam.RIGHT_Y -> copy(rightOffsetYPx = 0f)
            CalibrationParam.RIGHT_SCALE -> copy(rightScale = 1f)
            CalibrationParam.RIGHT_ROTATION -> copy(rightRotationDeg = 0f)
            CalibrationParam.LEFT_X -> copy(leftOffsetXPx = 0f)
            CalibrationParam.LEFT_Y -> copy(leftOffsetYPx = 0f)
            CalibrationParam.LEFT_SCALE -> copy(leftScale = 1f)
            CalibrationParam.LEFT_ROTATION -> copy(leftRotationDeg = 0f)
            CalibrationParam.GLOBAL_SEPARATION -> copy(globalEyeSeparationPx = 0f)
            CalibrationParam.GLOBAL_VERTICAL -> copy(globalVerticalOffsetPx = 0f)
        }
    }

    fun getValue(param: CalibrationParam): Float {
        return when (param) {
            CalibrationParam.RIGHT_X -> rightOffsetXPx
            CalibrationParam.RIGHT_Y -> rightOffsetYPx
            CalibrationParam.RIGHT_SCALE -> rightScale
            CalibrationParam.RIGHT_ROTATION -> rightRotationDeg
            CalibrationParam.LEFT_X -> leftOffsetXPx
            CalibrationParam.LEFT_Y -> leftOffsetYPx
            CalibrationParam.LEFT_SCALE -> leftScale
            CalibrationParam.LEFT_ROTATION -> leftRotationDeg
            CalibrationParam.GLOBAL_SEPARATION -> globalEyeSeparationPx
            CalibrationParam.GLOBAL_VERTICAL -> globalVerticalOffsetPx
        }
    }

    fun formatValue(param: CalibrationParam): String {
        return when (param) {
            CalibrationParam.RIGHT_X, CalibrationParam.LEFT_X,
            CalibrationParam.RIGHT_Y, CalibrationParam.LEFT_Y,
            CalibrationParam.GLOBAL_SEPARATION, CalibrationParam.GLOBAL_VERTICAL ->
                String.format(Locale.US, "%+d px", getValue(param).toInt())
            CalibrationParam.RIGHT_SCALE, CalibrationParam.LEFT_SCALE ->
                String.format(Locale.US, "%.3f", getValue(param))
            CalibrationParam.RIGHT_ROTATION, CalibrationParam.LEFT_ROTATION ->
                String.format(Locale.US, "%+.2f°", getValue(param))
        }
    }
}

/**
 * Ordered calibration parameters in recommended workflow order.
 */
enum class CalibrationParam(val displayName: String, val shortName: String, val unit: String) {
    RIGHT_X("RIGHT X", "R X", "px"),
    RIGHT_Y("RIGHT Y", "R Y", "px"),
    RIGHT_SCALE("RIGHT SCALE", "R SCALE", "x"),
    RIGHT_ROTATION("RIGHT ROTATION", "R ROT", "°"),
    LEFT_X("LEFT X", "L X", "px"),
    LEFT_Y("LEFT Y", "L Y", "px"),
    LEFT_SCALE("LEFT SCALE", "L SCALE", "x"),
    LEFT_ROTATION("LEFT ROTATION", "L ROT", "°"),
    GLOBAL_SEPARATION("GLOBAL EYE SEPARATION", "EYE SEP", "px"),
    GLOBAL_VERTICAL("GLOBAL VERTICAL OFFSET", "VERT OFF", "px");

    fun next(): CalibrationParam {
        val entries = entries
        val nextIdx = (ordinal + 1) % entries.size
        return entries[nextIdx]
    }

    fun previous(): CalibrationParam {
        val entries = entries
        val prevIdx = (ordinal - 1 + entries.size) % entries.size
        return entries[prevIdx]
    }

    fun getDelta(stepMode: AdjustmentStepMode, isPositive: Boolean): Float {
        val sign = if (isPositive) 1f else -1f
        return when (this) {
            RIGHT_X, LEFT_X, RIGHT_Y, LEFT_Y, GLOBAL_SEPARATION, GLOBAL_VERTICAL -> {
                when (stepMode) {
                    AdjustmentStepMode.FINE -> 1.0f * sign
                    AdjustmentStepMode.COARSE -> 10.0f * sign
                }
            }
            RIGHT_SCALE, LEFT_SCALE -> {
                when (stepMode) {
                    AdjustmentStepMode.FINE -> 0.001f * sign
                    AdjustmentStepMode.COARSE -> 0.010f * sign
                }
            }
            RIGHT_ROTATION, LEFT_ROTATION -> {
                when (stepMode) {
                    AdjustmentStepMode.FINE -> 0.05f * sign
                    AdjustmentStepMode.COARSE -> 0.50f * sign
                }
            }
        }
    }
}

enum class CalibrationDisplaySource {
    CALIBRATION_PATTERN,
    LIVE_PASSTHROUGH
}

enum class EyeVisibilityMode {
    BOTH,
    LEFT_ONLY,
    RIGHT_ONLY,
    ALTERNATE_FLICKER
}

enum class AdjustmentStepMode(val label: String) {
    FINE("FINE (1px / 0.001 / 0.05°)"),
    COARSE("COARSE (10px / 0.01 / 0.50°)")
}
