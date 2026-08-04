package genericOpenAIPipe.env

import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [PromptCacheOptions] and [PromptCacheBreakpoint].
 *
 * Pins the wire-spec field names (matched by [kotlinx.serialization.SerialName]
 * annotations) and the default-value behavior. Both data classes are pure
 * value carriers; there is no logic to test beyond round-trip serialization.
 */
class PromptCacheOptionsTest
{
    @Test
    fun testPromptCacheOptionsSerializesToCorrectWireName()
    {
        val options = PromptCacheOptions(mode = "explicit", ttl = "30m")
        val json = serialize(options, encodedefault = false)
        val parsed = deserialize<JsonObject>(json)

        assertNotNull(parsed)
        assertEquals("explicit", parsed!!["mode"]?.toString()?.trim('"'))
        assertEquals("30m", parsed["ttl"]?.toString()?.trim('"'))
    }

    @Test
    fun testPromptCacheOptionsAcceptsNullTtl()
    {
        val options = PromptCacheOptions(mode = "explicit", ttl = null)
        val json = serialize(options, encodedefault = false)
        val parsed = deserialize<PromptCacheOptions>(json)

        assertNotNull(parsed)
        assertEquals("explicit", parsed!!.mode)
        assertNull(parsed.ttl)
    }

    @Test
    fun testPromptCacheBreakpointDefaultModeIsExplicit()
    {
        val bp = PromptCacheBreakpoint()
        assertEquals("explicit", bp.mode)
    }

    @Test
    fun testPromptCacheBreakpointSerializesWithCorrectWireName()
    {
        val bp = PromptCacheBreakpoint(mode = "explicit")
        val json = serialize(bp, encodedefault = false)
        val parsed = deserialize<JsonObject>(json)

        assertNotNull(parsed)
        assertTrue(parsed!!.containsKey("mode"))
        assertEquals("explicit", parsed["mode"]?.toString()?.trim('"'))
    }
}
