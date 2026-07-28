package bedrockPipe

import aws.sdk.kotlin.services.bedrockruntime.model.Citation
import aws.sdk.kotlin.services.bedrockruntime.model.GuardrailAssessment
import aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BedrockCallMetadataTest
{
    @Test
    fun defaultValuesAreEmpty()
    {
        val metadata = BedrockCallMetadata()
        assertNull(metadata.latencyMs)
        assertEquals(emptyList<ToolUseBlock>(), metadata.toolUse)
        assertEquals(emptyList<Citation>(), metadata.citations)
        assertEquals(emptyList<GuardrailAssessment>(), metadata.guardAssessments)
        assertNull(metadata.cacheReadInputTokens)
        assertNull(metadata.cacheWriteInputTokens)
        assertNull(metadata.serviceTier)
        assertNull(metadata.stopReason)
    }

    @Test
    fun copyUpdatesSingleField()
    {
        val base = BedrockCallMetadata()
        val withLatency = base.copy(latencyMs = 42L)
        assertEquals(42L, withLatency.latencyMs)
        assertNull(base.latencyMs, "Original instance should be unchanged after copy")
    }

    @Test
    fun equalityUsesFieldValues()
    {
        val a = BedrockCallMetadata(latencyMs = 10L, cacheReadInputTokens = 5L)
        val b = BedrockCallMetadata(latencyMs = 10L, cacheReadInputTokens = 5L)
        val c = BedrockCallMetadata(latencyMs = 10L, cacheReadInputTokens = 6L)
        assertEquals(a, b)
        assertNotNull(a.hashCode())
        assert(a != c)
    }
}