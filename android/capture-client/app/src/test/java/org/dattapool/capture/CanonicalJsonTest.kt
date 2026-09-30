package org.dattapool.capture

import org.dattapool.capture.crypto.CanonicalJson
import org.dattapool.capture.crypto.KeyPair
import org.dattapool.capture.crypto.MerkleTree
import org.dattapool.capture.sensor.*
import org.junit.Test
import org.junit.Assert.*

class CanonicalJsonTest {

    @Test
    fun testRealTelemetryChunking() {
        val file = java.io.File("../../../session_data/android_30s_session/session-df3f8148c86a5ed769c463f676eed749/telemetry.jsonl")
        if (!file.exists()) return
        val lines = file.readLines().filter { it.isNotBlank() }
        val gson = com.google.gson.Gson()
        val records = lines.map { gson.fromJson(it, TelemetryRecord::class.java) }
        val chunks = SensorCollector.chunkRecords(records, 1009)
        val hashes = chunks.map { it.hashChunk() }
        val root = MerkleTree.computeRoot(hashes)
        println("Kotlin computed root on 1009 chunks: $root")
        java.io.File("../../../scratch/kotlin_hashes.txt").writeText(hashes.joinToString("\n"))
    }
}
