package bedrockPipe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestMetadataBuilderTest
{
    @Test
    fun defaultIsEmptyMap()
    {
        val pipe = BedrockPipe()
        assertTrue(pipe.getRequestMetadata().isEmpty(),
            "Default requestMetadata must be an empty map (not null)")
    }

    @Test
    fun setPersistsAndReturnsThis()
    {
        val pipe = BedrockPipe()
        val meta = mapOf("tenant" to "cage", "experiment" to "ab-test-42")
        val returned = pipe.setRequestMetadata(meta)
        assertEquals(pipe, returned)
        assertEquals(meta, pipe.getRequestMetadata())
    }

    @Test
    fun additiveMergeAccumulates()
    {
        val pipe = BedrockPipe()
        pipe.setRequestMetadata(mapOf("a" to "1"))
        pipe.setRequestMetadata(mapOf("b" to "2"))
        assertEquals(mapOf("a" to "1", "b" to "2"), pipe.getRequestMetadata())
    }

    @Test
    fun additiveMergeOverwritesDuplicateKey()
    {
        val pipe = BedrockPipe()
        pipe.setRequestMetadata(mapOf("a" to "1", "b" to "2"))
        pipe.setRequestMetadata(mapOf("b" to "3"))
        assertEquals(mapOf("a" to "1", "b" to "3"), pipe.getRequestMetadata())
    }
}
