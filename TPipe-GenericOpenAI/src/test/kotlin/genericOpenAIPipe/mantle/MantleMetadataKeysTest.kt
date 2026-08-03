package genericOpenAIPipe.mantle

import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the Mantle-specific metadata keys + types.
 *
 * Pins:
 *   - the constant value of [MantleMetadataKeys.GPT56_PROMPT_CACHING] so
 *     callers and the serializer agree on the literal string,
 *   - the @Serializable shape of [MantleGpt56PromptCacheMetadata] including
 *     the default-value behavior callers rely on.
 */
class MantleMetadataKeysTest
{
    @Test
    fun testGpt56PromptCachingKeyValue()
    {
        // Pin the literal — both the serializer's read site and
        // `enableMantleGpt56ExplicitPromptCaching()`'s write site must agree.
        assertEquals(
            "bedrockMantle.gpt56.promptCaching",
            MantleMetadataKeys.GPT56_PROMPT_CACHING,
        )
    }
}

class MantleGpt56PromptCacheMetadataTest
{
    @Test
    fun testDefaultsAreModeExplicitTtl30mBoundaryNone()
    {
        val meta = MantleGpt56PromptCacheMetadata()
        assertEquals("explicit", meta.mode)
        assertEquals("30m", meta.ttl)
        assertEquals(MantleGpt56CacheBoundary.NONE, meta.boundary)
    }

    @Test
    fun testRoundTripPreservesAllThreeFields()
    {
        val original = MantleGpt56PromptCacheMetadata(
            mode = "explicit",
            ttl = "1h",
            boundary = MantleGpt56CacheBoundary.AFTER_INSTRUCTIONS,
        )
        val json = serialize(original, encodedefault = false)
        val roundTripped = deserialize<MantleGpt56PromptCacheMetadata>(json)

        assertEquals(original.mode, roundTripped!!.mode)
        assertEquals(original.ttl, roundTripped.ttl)
        assertEquals(original.boundary, roundTripped.boundary)
    }

    @Test
    fun testMantleGpt56CacheBoundaryEnumHasExpectedVariants()
    {
        // Pin the variant names — callers and the serializer's when-branch
        // both depend on the exact set. Adding a new variant is a contract
        // change and must update this test.
        assertEquals(2, MantleGpt56CacheBoundary.values().size)
        assertEquals(
            setOf("NONE", "AFTER_INSTRUCTIONS"),
            MantleGpt56CacheBoundary.values().map { it.name }.toSet(),
        )
    }
}
