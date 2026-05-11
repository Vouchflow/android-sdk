package dev.vouchflow.sdk.crypto

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.lang.NumberFormatException

/**
 * RFC 8785 — JSON Canonicalization Scheme (JCS).
 *
 * Produces byte-identical canonical serialization of any JSON-typed value so
 * that this Android-canonicalized payload's SHA-256 matches the
 * TypeScript / Swift canonicalizers' output. Without byte-for-byte agreement
 * the `payload_sha256` claim Vouchflow embeds in the JWS won't match what
 * the customer's backend recomputes, breaking signed-payload verification.
 *
 * Scope: the JSON subset that customer payloads exercise — null, bool,
 * number, string, array, object. Numbers serialize per JSON spec
 * (ECMAScript ToString), keys sort by UTF-16 code units, NaN/Infinity rejected.
 */
object JCSCanonicalizer {

    /** Canonicalize an arbitrary value. Accepts Map<String, Any?>, List<Any?>,
     *  Number, Boolean, String, null. The recursion mirrors JSONObject's
     *  type expectations. */
    @JvmStatic
    fun canonicalize(value: Any?): String = serialize(value)

    /** Canonicalize a pre-parsed JSON string. Handy for callers that built
     *  the payload via Gson / Moshi / JSONObject.
     *
     *  Parsed via Gson rather than org.json so that this code path is callable
     *  from plain-JVM unit tests (Android's org.json is a stub in the mockable
     *  android.jar and throws "Method not mocked" on JSONObject construction). */
    @JvmStatic
    fun canonicalizeJson(json: String): String {
        val trimmed = json.trim()
        val parsed = JsonParser.parseString(trimmed)
        return serialize(jsonElementToAny(parsed))
    }

    // MARK: - Internals

    private fun serialize(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Number -> serializeNumber(value)
        is String -> serializeString(value)
        is List<*> -> serializeArray(value)
        is Map<*, *> -> serializeObject(value)
        else -> "null"
    }

    private fun serializeNumber(n: Number): String {
        val d = n.toDouble()
        if (d.isNaN() || d.isInfinite()) {
            throw IllegalArgumentException("JCS forbids NaN and Infinity")
        }
        // Integer-valued numbers serialize without a decimal point. Long, Int,
        // Short, Byte all reach this path with no fractional component.
        if (n is Long || n is Int || n is Short || n is Byte) {
            return n.toString()
        }
        if (d == 0.0) return "0"
        if (d % 1.0 == 0.0 && Math.abs(d) < 1e16) {
            return d.toLong().toString()
        }
        // Fall back to Kotlin's default Double formatting. For ECMAScript-shaped
        // numbers this matches JSON.stringify byte-for-byte. Pathological
        // doubles (1e-100, ...) differ slightly; customers should use string
        // representations for those.
        return d.toString()
    }

    private fun serializeString(s: String): String {
        // Hand-rolled escape per JCS §3.2.2.2 / JSON spec. We avoid
        // org.json.JSONObject.quote because Android's org.json is a stub in
        // the mockable android.jar used for unit tests — calling it throws
        // "Method not mocked". The escape table here matches what
        // JSON.stringify (and JSONObject.quote on-device) produce for the
        // BMP code-point inputs customer payloads exercise.
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (i in s.indices) {
            val c = s[i]
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u")
                        val hex = Integer.toHexString(c.code)
                        for (j in 0 until 4 - hex.length) sb.append('0')
                        sb.append(hex)
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun serializeArray(arr: List<*>): String {
        if (arr.isEmpty()) return "[]"
        return arr.joinToString(prefix = "[", postfix = "]", separator = ",") { serialize(it) }
    }

    private fun serializeObject(dict: Map<*, *>): String {
        if (dict.isEmpty()) return "{}"
        val sorted = dict.keys
            .mapNotNull { it as? String }
            .sortedWith(Comparator { a, b -> compareUtf16(a, b) })
        return sorted.joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
            "${serializeString(key)}:${serialize(dict[key])}"
        }
    }

    /** UTF-16 code-unit comparison. Kotlin's default String.compareTo is by
     *  Char (UTF-16 code unit), matching JCS §3.2.3. */
    private fun compareUtf16(a: String, b: String): Int = a.compareTo(b)

    private fun jsonElementToAny(el: JsonElement): Any? = when {
        el.isJsonNull -> null
        el.isJsonObject -> {
            val o = el.asJsonObject
            val out = LinkedHashMap<String, Any?>(o.size())
            for ((k, v) in o.entrySet()) out[k] = jsonElementToAny(v)
            out
        }
        el.isJsonArray -> {
            val a = el.asJsonArray
            val out = ArrayList<Any?>(a.size())
            for (v in a) out.add(jsonElementToAny(v))
            out
        }
        el.isJsonPrimitive -> {
            val p = el.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isString -> p.asString
                p.isNumber -> {
                    // Preserve integer vs. fractional shape for serializeNumber.
                    val asString = p.asString
                    try {
                        if (asString.contains('.') || asString.contains('e') || asString.contains('E')) {
                            p.asDouble
                        } else {
                            asString.toLong()
                        }
                    } catch (_: NumberFormatException) {
                        p.asDouble
                    }
                }
                else -> null
            }
        }
        else -> null
    }
}
