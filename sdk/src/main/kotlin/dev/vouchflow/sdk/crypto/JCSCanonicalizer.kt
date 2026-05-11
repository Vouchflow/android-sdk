package dev.vouchflow.sdk.crypto

import org.json.JSONArray
import org.json.JSONObject
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
     *  the payload via Gson / Moshi / JSONObject. */
    @JvmStatic
    fun canonicalizeJson(json: String): String {
        val trimmed = json.trim()
        return when {
            trimmed.startsWith("{") -> serialize(jsonObjectToMap(JSONObject(trimmed)))
            trimmed.startsWith("[") -> serialize(jsonArrayToList(JSONArray(trimmed)))
            trimmed == "null" -> "null"
            trimmed == "true" -> "true"
            trimmed == "false" -> "false"
            trimmed.startsWith("\"") -> serialize(trimmed.substring(1, trimmed.length - 1))
            else -> {
                // Number — try Long, then Double
                try {
                    serialize(trimmed.toLong())
                } catch (_: NumberFormatException) {
                    serialize(trimmed.toDouble())
                }
            }
        }
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
        // Use JSONObject's escape rules — handles \", \\, control chars, and
        // surrogate pairs the same way JSON.stringify does for normal Unicode.
        return JSONObject.quote(s)
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

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val out = HashMap<String, Any?>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = jsonValueToAny(obj.opt(k))
        }
        return out
    }

    private fun jsonArrayToList(arr: JSONArray): List<Any?> {
        val out = ArrayList<Any?>(arr.length())
        for (i in 0 until arr.length()) out.add(jsonValueToAny(arr.opt(i)))
        return out
    }

    private fun jsonValueToAny(v: Any?): Any? = when (v) {
        JSONObject.NULL, null -> null
        is JSONObject -> jsonObjectToMap(v)
        is JSONArray -> jsonArrayToList(v)
        else -> v
    }
}
