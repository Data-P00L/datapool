package org.dattapool.capture.compensation

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

object CompensationFormatters {

    const val DECIMAL_PLACES = 3
    const val ATOMIC_MULTIPLIER = 1000L

    /**
     * Formats integer atomic DTTA units into deterministic 3-decimal display string.
     * Examples:
     *   25000 -> "25.000"
     *   1000  -> "1.000"
     *   125   -> "0.125"
     *   1     -> "0.001"
     *   0     -> "0.000"
     */
    fun formatAtomicAmount(atomicUnits: Long): String {
        val sign = if (atomicUnits < 0) "-" else ""
        val absUnits = abs(atomicUnits)
        val whole = absUnits / ATOMIC_MULTIPLIER
        val fractional = absUnits % ATOMIC_MULTIPLIER
        val fracStr = fractional.toString().padStart(DECIMAL_PLACES, '0')
        return "$sign$whole.$fracStr"
    }

    /**
     * Parses a 3-decimal display amount back to atomic units without floating-point inaccuracy.
     */
    fun parseDisplayAmount(display: String): Long {
        val trimmed = display.trim()
        val isNegative = trimmed.startsWith("-")
        val clean = if (isNegative) trimmed.substring(1) else trimmed

        val parts = clean.split(".")
        val whole = parts[0].toLongOrNull() ?: 0L
        val fractionPart = if (parts.size > 1) parts[1] else ""
        val paddedFrac = fractionPart.padEnd(DECIMAL_PLACES, '0').take(DECIMAL_PLACES)
        val fraction = paddedFrac.toLongOrNull() ?: 0L

        val total = (whole * ATOMIC_MULTIPLIER) + fraction
        return if (isNegative) -total else total
    }

    /**
     * Returns current time as ISO-8601 UTC timestamp (e.g. 2026-08-19T22:00:00Z).
     */
    fun nowIsoUtc(): String {
        return formatIsoUtc(System.currentTimeMillis())
    }

    /**
     * Formats epoch millisecond timestamp to ISO-8601 UTC string.
     */
    fun formatIsoUtc(epochMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMillis))
    }

    /**
     * Validates if a string is a valid ISO-8601 UTC timestamp.
     */
    fun isValidIsoUtc(timestamp: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.isLenient = false
            sdf.parse(timestamp) != null
        } catch (e: Exception) {
            try {
                // Also support milliseconds representation if present
                val sdfMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                sdfMs.timeZone = TimeZone.getTimeZone("UTC")
                sdfMs.isLenient = false
                sdfMs.parse(timestamp) != null
            } catch (e2: Exception) {
                false
            }
        }
    }
}
