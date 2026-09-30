package org.dattapool.capture.xr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class XrCalibrationStore(private val context: Context) {

    companion object {
        const val PREFS_NAME = "dattapool_xr_calibration"
        const val DEFAULT_PROFILE = "gearvr_s20fe_v1"
        private const val TAG = "DATTA_XR_CAL"

        private const val KEY_LEFT_X = "left_offset_x"
        private const val KEY_LEFT_Y = "left_offset_y"
        private const val KEY_LEFT_SCALE = "left_scale"
        private const val KEY_LEFT_ROT = "left_rot"

        private const val KEY_RIGHT_X = "right_offset_x"
        private const val KEY_RIGHT_Y = "right_offset_y"
        private const val KEY_RIGHT_SCALE = "right_scale"
        private const val KEY_RIGHT_ROT = "right_rot"

        private const val KEY_EYE_SEP = "eye_separation"
        private const val KEY_VERT_OFF = "vert_offset"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadCalibration(profile: String = DEFAULT_PROFILE): XrEyeCalibration {
        val prefix = "${profile}_"
        val cal = XrEyeCalibration(
            leftOffsetXPx = prefs.getFloat("${prefix}$KEY_LEFT_X", 0f),
            leftOffsetYPx = prefs.getFloat("${prefix}$KEY_LEFT_Y", 0f),
            leftScale = prefs.getFloat("${prefix}$KEY_LEFT_SCALE", 1f),
            leftRotationDeg = prefs.getFloat("${prefix}$KEY_LEFT_ROT", 0f),

            rightOffsetXPx = prefs.getFloat("${prefix}$KEY_RIGHT_X", 0f),
            rightOffsetYPx = prefs.getFloat("${prefix}$KEY_RIGHT_Y", 0f),
            rightScale = prefs.getFloat("${prefix}$KEY_RIGHT_SCALE", 1f),
            rightRotationDeg = prefs.getFloat("${prefix}$KEY_RIGHT_ROT", 0f),

            globalEyeSeparationPx = prefs.getFloat("${prefix}$KEY_EYE_SEP", 0f),
            globalVerticalOffsetPx = prefs.getFloat("${prefix}$KEY_VERT_OFF", 0f)
        )
        Log.i(TAG, "Loaded calibration for profile=$profile")
        cal.logSummary(TAG, profile)
        return cal
    }

    fun saveCalibration(cal: XrEyeCalibration, profile: String = DEFAULT_PROFILE) {
        val prefix = "${profile}_"
        prefs.edit()
            .putFloat("${prefix}$KEY_LEFT_X", cal.leftOffsetXPx)
            .putFloat("${prefix}$KEY_LEFT_Y", cal.leftOffsetYPx)
            .putFloat("${prefix}$KEY_LEFT_SCALE", cal.leftScale)
            .putFloat("${prefix}$KEY_LEFT_ROT", cal.leftRotationDeg)
            .putFloat("${prefix}$KEY_RIGHT_X", cal.rightOffsetXPx)
            .putFloat("${prefix}$KEY_RIGHT_Y", cal.rightOffsetYPx)
            .putFloat("${prefix}$KEY_RIGHT_SCALE", cal.rightScale)
            .putFloat("${prefix}$KEY_RIGHT_ROT", cal.rightRotationDeg)
            .putFloat("${prefix}$KEY_EYE_SEP", cal.globalEyeSeparationPx)
            .putFloat("${prefix}$KEY_VERT_OFF", cal.globalVerticalOffsetPx)
            .apply()

        Log.i(TAG, "Saved calibration for profile=$profile")
        cal.logSummary(TAG, profile)
    }

    fun resetToDefaults(profile: String = DEFAULT_PROFILE): XrEyeCalibration {
        val prefix = "${profile}_"
        prefs.edit()
            .remove("${prefix}$KEY_LEFT_X")
            .remove("${prefix}$KEY_LEFT_Y")
            .remove("${prefix}$KEY_LEFT_SCALE")
            .remove("${prefix}$KEY_LEFT_ROT")
            .remove("${prefix}$KEY_RIGHT_X")
            .remove("${prefix}$KEY_RIGHT_Y")
            .remove("${prefix}$KEY_RIGHT_SCALE")
            .remove("${prefix}$KEY_RIGHT_ROT")
            .remove("${prefix}$KEY_EYE_SEP")
            .remove("${prefix}$KEY_VERT_OFF")
            .apply()

        val defaultCal = XrEyeCalibration()
        Log.i(TAG, "Reset calibration to defaults for profile=$profile")
        defaultCal.logSummary(TAG, profile)
        return defaultCal
    }
}
