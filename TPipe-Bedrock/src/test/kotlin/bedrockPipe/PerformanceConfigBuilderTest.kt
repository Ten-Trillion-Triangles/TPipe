package bedrockPipe

import aws.sdk.kotlin.services.bedrockruntime.model.PerformanceConfigLatency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PerformanceConfigBuilderTest
{
    @Test
    fun defaultIsNull()
    {
        val pipe = BedrockPipe()
        assertNull(pipe.getPerformanceConfig())
    }

    @Test
    fun setOptimizedPersists()
    {
        val pipe = BedrockPipe()
        val returned = pipe.setPerformanceConfig(PerformanceConfigLatency.Optimized)
        assertEquals(pipe, returned, "setPerformanceConfig must return this for chaining")
        assertEquals(PerformanceConfigLatency.Optimized, pipe.getPerformanceConfig()?.latency)
    }

    @Test
    fun setStandardPersists()
    {
        val pipe = BedrockPipe()
        pipe.setPerformanceConfig(PerformanceConfigLatency.Standard)
        assertEquals(PerformanceConfigLatency.Standard, pipe.getPerformanceConfig()?.latency)
    }

    @Test
    fun clearRestoresNull()
    {
        val pipe = BedrockPipe()
        pipe.setPerformanceConfig(PerformanceConfigLatency.Optimized)
        pipe.clearPerformanceConfig()
        assertNull(pipe.getPerformanceConfig())
    }
}
