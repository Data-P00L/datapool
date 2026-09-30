package org.dattapool.capture.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.google.gson.Gson
import java.io.File
import java.io.FileWriter
import java.util.concurrent.CopyOnWriteArrayList

class SensorCollector(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val records = CopyOnWriteArrayList<TelemetryRecord>()
    private var isRecording = false
    private var startTimestampNs: Long = 0
    private var frameCounter = 0

    @Volatile
    private var lastAccel = floatArrayOf(0f, 0f, 9.81f)

    @Volatile
    private var lastGyro = floatArrayOf(0f, 0f, 0f)

    fun getLatestAccel(): FloatArray = lastAccel.clone()
    fun getLatestGyro(): FloatArray = lastGyro.clone()

    fun start() {
        records.clear()
        frameCounter = 0
        startTimestampNs = System.nanoTime()
        isRecording = true

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop(): List<TelemetryRecord> {
        isRecording = false
        sensorManager.unregisterListener(this)
        return records.toList()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRecording || event == null) return

        val nowNs = System.nanoTime()
        val elapsedSec = (nowNs - startTimestampNs) / 1_000_000_000.0

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel = event.values.clone()
            }
            Sensor.TYPE_GYROSCOPE -> {
                lastGyro = event.values.clone()
            }
        }

        // Emit frame at regular intervals (~50Hz sampling)
        val rec = TelemetryRecord(
            timestampSec = elapsedSec,
            frameIndex = frameCounter++,
            imu = IMUReading(
                linearAccel = listOf(lastAccel[0].toDouble(), lastAccel[1].toDouble(), lastAccel[2].toDouble()),
                angularVel = listOf(lastGyro[0].toDouble(), lastGyro[1].toDouble(), lastGyro[2].toDouble())
            )
        )
        records.add(rec)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun writeToJsonl(file: File, records: List<TelemetryRecord>) {
        val gson = Gson()
        FileWriter(file).use { writer ->
            for (r in records) {
                writer.write(gson.toJson(r))
                writer.write("\n")
            }
        }
    }

    companion object {
        fun chunkRecords(records: List<TelemetryRecord>, numChunks: Int): List<TelemetryChunk> {
            val count = if (numChunks <= 0) 1 else numChunks
            if (records.isEmpty()) {
                return List(count) { idx -> TelemetryChunk(index = idx, records = emptyList()) }
            }

            val total = records.size
            val chunkSize = (total + count - 1) / count

            return List(count) { idx ->
                val start = idx * chunkSize
                val end = minOf(start + chunkSize, total)
                val sub = if (start < total) records.subList(start, end) else emptyList()
                TelemetryChunk(index = idx, records = sub)
            }
        }
    }
}
