package genericOpenAIPipe.mantle

import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.ApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for the `enableMantleGpt56ExplicitPromptCaching` extension on
 * [GenericOpenAIPipe].
 *
 * Pins:
 *   - the metadata key constant is written under the agreed literal,
 *   - the value is a typed [MantleGpt56PromptCacheMetadata] (not a Map),
 *     preserving type safety at the serializer's read site,
 *   - default arguments match the documented defaults.
 */
class MantleExtensionTest
{
    @Test
    fun testEnablePopulatesMetadataKey()
    {
        val pipe = GenericOpenAIPipe()
        pipe.enableMantleGpt56ExplicitPromptCaching()

        assertTrue(pipe.pipeMetadata.containsKey(MantleMetadataKeys.GPT56_PROMPT_CACHING))
    }

    @Test
    fun testEnableWritesTypedObjectNotMap()
    {
        val pipe = GenericOpenAIPipe()
        pipe.enableMantleGpt56ExplicitPromptCaching()

        val stored = pipe.pipeMetadata[MantleMetadataKeys.GPT56_PROMPT_CACHING]
        assertIs<MantleGpt56PromptCacheMetadata>(stored)
    }

    @Test
    fun testDefaultsAreBoundaryNoneAndTtl30m()
    {
        val pipe = GenericOpenAIPipe()
        pipe.enableMantleGpt56ExplicitPromptCaching()

        val stored = pipe.pipeMetadata[MantleMetadataKeys.GPT56_PROMPT_CACHING]
            as MantleGpt56PromptCacheMetadata
        assertEquals(MantleGpt56CacheBoundary.NONE, stored.boundary)
        assertEquals("30m", stored.ttl)
        assertEquals("explicit", stored.mode)
    }

    @Test
    fun testEnableAfterInstructionsBoundaryRoundTrips()
    {
        val pipe = GenericOpenAIPipe()
        pipe.enableMantleGpt56ExplicitPromptCaching(
            boundary = MantleGpt56CacheBoundary.AFTER_INSTRUCTIONS,
            ttl = "1h",
        )

        val stored = pipe.pipeMetadata[MantleMetadataKeys.GPT56_PROMPT_CACHING]
            as MantleGpt56PromptCacheMetadata
        assertEquals(MantleGpt56CacheBoundary.AFTER_INSTRUCTIONS, stored.boundary)
        assertEquals("1h", stored.ttl)
    }

    @Test
    fun testEnableReturnsThePipeForChaining()
    {
        val pipe = GenericOpenAIPipe()
        val returned = pipe.enableMantleGpt56ExplicitPromptCaching()
        assertEquals(pipe, returned)
    }
}
