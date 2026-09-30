package org.dattapool.capture

import org.dattapool.capture.sensor.IMUReading
import org.dattapool.capture.sensor.SensorCollector
import org.dattapool.capture.sensor.TelemetryRecord
import org.junit.Assert.*
import org.junit.Test

class SensorCollectorTest {

    @Test
    fun testChunkRecordsEvenDistribution() {
        val records = (0 until 100).map { i ->
            TelemetryRecord(
                timestampSec = i * 0.02,
                frameIndex = i,
                imu = IMUReading(
                    linearAccel = listOf(0.0, 0.0, 9.81),
                    angularVel = listOf(0.0, 0.0, 0.0)
                )
            )
        }

        val chunks = SensorCollector.chunkRecords(records, 10)
        assertEquals(10, chunks.size)
        assertEquals(10, chunks[0].records.size)
        assertEquals(0, chunks[0].index)
        assertEquals(9, chunks[9].index)
    }

    @Test
    fun testChunkRecordsEmpty() {
        val chunks = SensorCollector.chunkRecords(emptyList(), 5)
        assertEquals(5, chunks.size)
        assertTrue(chunks.all { it.records.isEmpty() })
    }
}
