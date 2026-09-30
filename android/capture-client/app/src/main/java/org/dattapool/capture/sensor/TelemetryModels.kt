package org.dattapool.capture.sensor

import com.google.gson.annotations.SerializedName
import org.dattapool.capture.crypto.CanonicalJson
import org.dattapool.capture.crypto.KeyPair
import org.dattapool.capture.crypto.toHex

data class IMUReading(
    @SerializedName("linear_accel") val linearAccel: List<Double>,
    @SerializedName("angular_vel") val angularVel: List<Double>
)

data class JointState(
    @SerializedName("positions") val positions: List<Double> = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
    @SerializedName("velocities") val velocities: List<Double> = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
    @SerializedName("efforts") val efforts: List<Double> = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
)

data class GripperState(
    @SerializedName("width") val width: Double = 0.0,
    @SerializedName("force") val force: Double = 0.0
)

data class TelemetryRecord(
    @SerializedName("timestamp_sec") val timestampSec: Double,
    @SerializedName("frame_index") val frameIndex: Int,
    @SerializedName("imu") val imu: IMUReading,
    @SerializedName("joints") val joints: JointState = JointState(),
    @SerializedName("gripper") val gripper: GripperState = GripperState(),
    @SerializedName("mode") val mode: String = "HANDHELD_PHONE"
)

data class TelemetryChunk(
    @SerializedName("index") val index: Int,
    @SerializedName("records") val records: List<TelemetryRecord>
) {
    fun hashChunk(): String {
        val canonical = CanonicalJson.canonicalize(this)
        return KeyPair.sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }
}
