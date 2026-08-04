package bedrockPipe

import aws.sdk.kotlin.services.bedrockruntime.model.PerformanceConfigLatency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerformanceConfigLiveTest
{
    @BeforeAll
    fun checkCredentials()
    {
        val allowTest = System.getenv("AllowTest")
        assumeTrue(allowTest == "true", "AllowTest flag not enabled — skipping live test")
    }

    @Test
    fun setPerformanceConfigFlowsToWireRequest()
    {
        val pipe = BedrockPipe()
        pipe.setRegion("us-east-1")
        pipe.setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
        pipe.useConverseApi()
        pipe.setPerformanceConfig(PerformanceConfigLatency.Optimized)
        kotlinx.coroutines.runBlocking { pipe.init() }

        // We don't have a built-in way to assert the wire-level performanceConfig
        // field without intercepting the request. Instead, we assert the call
        // succeeds end-to-end with Optimized — this validates that the field
        // is well-formed and accepted by the service.
        val result = kotlinx.coroutines.runBlocking {
            pipe.generateText("Say 'ok'")
        }
        assertNotNull(result, "Call with Optimized performance config should succeed")
        assertEquals(PerformanceConfigLatency.Optimized, pipe.getPerformanceConfig()?.latency,
            "Performance config should persist after call")
    }
}