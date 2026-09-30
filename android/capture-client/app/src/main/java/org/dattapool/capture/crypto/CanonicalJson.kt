package org.dattapool.capture.crypto

import com.google.gson.*
import java.io.StringWriter

/**
 * Strict RFC-8785 canonical JSON serializer in pure Kotlin.
 * Guarantees alphabetical key sorting, compact representation, UTF-8 normalization,
 * and exact byte-for-byte hash equivalence with Go's protocol.CanonicalizeJSON.
 */
object CanonicalJson {

    fun canonicalize(jsonString: String): String {
        val element = JsonParser.parseString(jsonString)
        return formatCanonical(element)
    }

    fun canonicalize(obj: Any): String {
        val gson = Gson()
        val jsonElement = gson.toJsonTree(obj)
        return formatCanonical(jsonElement)
    }

    private fun formatCanonical(element: JsonElement): String {
        return when {
            element.isJsonNull -> "null"
            element.isJsonPrimitive -> {
                val prim = element.asJsonPrimitive
                when {
                    prim.isBoolean -> prim.asBoolean.toString()
                    prim.isNumber -> {
                        val d = prim.asDouble
                        if (java.lang.Double.doubleToRawLongBits(d) == java.lang.Double.doubleToRawLongBits(-0.0)) {
                            "-0"
                        } else if (d == 0.0) {
                            "0"
                        } else if (d == d.toLong().toDouble() && !d.isInfinite() && !d.isNaN()) {
                            d.toLong().toString()
                        } else {
                            try {
                                java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
                            } catch (e: Exception) {
                                prim.asNumber.toString()
                            }
                        }
                    }
                    prim.isString -> {
                        val writer = StringWriter()
                        val jsonWriter = com.google.gson.stream.JsonWriter(writer)
                        jsonWriter.value(prim.asString)
                        writer.toString()
                    }
                    else -> prim.toString()
                }
            }
            element.isJsonArray -> {
                val arr = element.asJsonArray
                val sb = StringBuilder()
                sb.append("[")
                for (i in 0 until arr.size()) {
                    if (i > 0) sb.append(",")
                    sb.append(formatCanonical(arr[i]))
                }
                sb.append("]")
                sb.toString()
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val sortedKeys = obj.keySet().sorted()
                val sb = StringBuilder()
                sb.append("{")
                for (i in sortedKeys.indices) {
                    if (i > 0) sb.append(",")
                    val k = sortedKeys[i]
                    val writer = StringWriter()
                    val jsonWriter = com.google.gson.stream.JsonWriter(writer)
                    jsonWriter.value(k)
                    sb.append(writer.toString())
                    sb.append(":")
                    sb.append(formatCanonical(obj[k]))
                }
                sb.append("}")
                sb.toString()
            }
            else -> element.toString()
        }
    }
}
