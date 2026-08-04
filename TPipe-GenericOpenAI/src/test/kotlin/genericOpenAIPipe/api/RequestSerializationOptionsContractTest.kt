package genericOpenAIPipe.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [RequestSerializationOptions].
 *
 * The class is a thin value carrier with a default-empty metadata bag. The
 * tests pin the default and the round-trip behavior so the contract is
 * stable for downstream serializers and the GenericOpenAIPipe call site.
 */
class RequestSerializationOptionsContractTest
{
    @Test
    fun testDefaultOptionsHaveEmptyMetadata()
    {
        val options = RequestSerializationOptions()
        assertTrue(options.metadata.isEmpty())
    }

    @Test
    fun testNonDefaultOptionsRetainSuppliedKeys()
    {
        val payload = mapOf("some-key" to "some-value", "another-key" to 42)
        val options = RequestSerializationOptions(metadata = payload)
        assertEquals("some-value", options.metadata["some-key"])
        assertEquals(42, options.metadata["another-key"])
    }

    @Test
    fun testOptionsAreValueEqualWhenMetadataIsEqual()
    {
        val payload = mapOf("k" to "v")
        val a = RequestSerializationOptions(metadata = payload)
        val b = RequestSerializationOptions(metadata = payload)
        assertEquals(a, b)
    }

    @Test
    fun testOptionsHoldArbitraryValueTypes()
    {
        // The metadata bag is typed `Map<String, Any?>` so callers can pass
        // typed objects (e.g. MantleGpt56PromptCacheMetadata) without losing
        // type information at the serializer's read site.
        val holder = TypedMetadataHolder()
        val options = RequestSerializationOptions(metadata = mapOf("holder" to holder))
        assertSame(holder, options.metadata["holder"])
    }

    private class TypedMetadataHolder
}
