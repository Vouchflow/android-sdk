package dev.vouchflow.sdk

import dev.vouchflow.sdk.crypto.JCSCanonicalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JCS output must be byte-identical across iOS / Android / Web. Drift here
 * silently breaks signed-payload verification.
 */
class JCSCanonicalizerTest {

    // ── Primitives ─────────────────────────────────────────────────────────────

    @Test
    fun `null serializes`() = assertEquals("null", JCSCanonicalizer.canonicalize(null))

    @Test
    fun `booleans serialize`() {
        assertEquals("true", JCSCanonicalizer.canonicalize(true))
        assertEquals("false", JCSCanonicalizer.canonicalize(false))
    }

    @Test
    fun `integers serialize without decimal`() {
        assertEquals("0", JCSCanonicalizer.canonicalize(0))
        assertEquals("123", JCSCanonicalizer.canonicalize(123))
        assertEquals("-7", JCSCanonicalizer.canonicalize(-7))
    }

    @Test
    fun `strings escape properly`() {
        assertEquals("\"hello\"", JCSCanonicalizer.canonicalize("hello"))
        assertEquals("\"a\\\"b\"", JCSCanonicalizer.canonicalize("a\"b"))
        assertEquals("\"a\\nb\"", JCSCanonicalizer.canonicalize("a\nb"))
    }

    // ── Collections ────────────────────────────────────────────────────────────

    @Test
    fun `empty collections`() {
        assertEquals("[]", JCSCanonicalizer.canonicalize(emptyList<Any>()))
        assertEquals("{}", JCSCanonicalizer.canonicalize(emptyMap<String, Any>()))
    }

    @Test
    fun `arrays preserve order`() {
        assertEquals("[3,1,2]", JCSCanonicalizer.canonicalize(listOf(3, 1, 2)))
    }

    @Test
    fun `object keys sort by UTF-16`() {
        val obj = linkedMapOf<String, Any?>("b" to 1, "a" to 2, "c" to 3)
        assertEquals("""{"a":2,"b":1,"c":3}""", JCSCanonicalizer.canonicalize(obj))
    }

    @Test
    fun `nested objects sort recursively`() {
        val obj = linkedMapOf<String, Any?>(
            "z" to linkedMapOf("b" to 1, "a" to 2),
            "a" to 1,
        )
        assertEquals(
            """{"a":1,"z":{"a":2,"b":1}}""",
            JCSCanonicalizer.canonicalize(obj),
        )
    }

    // ── Cross-platform invariant ────────────────────────────────────────────────

    @Test
    fun `mandate-shaped payload matches TypeScript output`() {
        val payload = linkedMapOf<String, Any?>(
            "v" to 1,
            "id" to "mand_abc",
            "grants" to listOf(
                linkedMapOf("asset" to "usd", "max" to 500),
                linkedMapOf("asset" to "eth", "max" to 0.5),
            ),
            "expires_at" to "2026-12-31T00:00:00Z",
        )
        val expected =
            """{"expires_at":"2026-12-31T00:00:00Z","grants":[{"asset":"usd","max":500},{"asset":"eth","max":0.5}],"id":"mand_abc","v":1}"""
        assertEquals(expected, JCSCanonicalizer.canonicalize(payload))
    }

    @Test
    fun `different orderings produce identical output`() {
        val a = linkedMapOf<String, Any?>("name" to "mandate", "amount" to 100, "scope" to "send")
        val b = linkedMapOf<String, Any?>("scope" to "send", "amount" to 100, "name" to "mandate")
        assertEquals(JCSCanonicalizer.canonicalize(a), JCSCanonicalizer.canonicalize(b))
    }

    // ── Failure modes ───────────────────────────────────────────────────────────

    @Test
    fun `NaN rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            JCSCanonicalizer.canonicalize(Double.NaN)
        }
    }

    @Test
    fun `Infinity rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            JCSCanonicalizer.canonicalize(Double.POSITIVE_INFINITY)
        }
    }

    // ── canonicalizeJson roundtrip ──────────────────────────────────────────────

    @Test
    fun `canonicalizeJson parses object`() {
        val out = JCSCanonicalizer.canonicalizeJson("""{"b":1,"a":2}""")
        assertEquals("""{"a":2,"b":1}""", out)
    }

    @Test
    fun `canonicalizeJson parses array`() {
        val out = JCSCanonicalizer.canonicalizeJson("""[3,1,2]""")
        assertEquals("""[3,1,2]""", out)
    }
}
