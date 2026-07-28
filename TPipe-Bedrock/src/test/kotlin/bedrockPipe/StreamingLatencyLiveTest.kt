package bedrockPipe

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Live test: exercises a real streaming Converse call against Bedrock and
 * asserts that the captured `BedrockCallMetadata.latencyMs` is populated and
 * strictly positive.
 *
 * Gated on `AllowTest=true` (env var) because it requires AWS credentials,
 * a reachable Bedrock endpoint, and pays for a real model invocation.
 *
 * Bedrock's ConverseStream always reports `metrics.latencyMs` on the server
 * side for completed streams — if the field is null on a successful call,
 * either the SDK wiring or the service contract is broken, and this test
 * should fail loudly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamingLatencyLiveTest
{
    @BeforeAll
    fun checkCredentials()
    {
        val allowTest = System.getenv("AllowTest")
        assumeTrue(allowTest == "true", "AllowTest flag not enabled — skipping live test")
    }

    @Test
    fun liveStreamingPopulatesLatencyMs()
    {
        val pipe = BedrockPipe()
        pipe.setRegion("us-east-1")
        pipe.setModel("anthropic.claude-3-haiku-20240307-v1:0")
        pipe.useConverseApi()
        pipe.enableStreaming()
        kotlinx.coroutines.runBlocking { pipe.init() }

        val result = kotlinx.coroutines.runBlocking {
            pipe.generateText("Say 'hello' and nothing else.")
        }

        val metadata = pipe.getLastCallMetadata()
        assertNotNull(metadata, "Streaming call should populate BedrockCallMetadata")
        metadata?.let { meta ->
            // BedrockConverseMetrics.latencyMs is always populated for streaming Converse
            // (it's a server-side measurement). On a successful streaming call, this is
            // never null. If it is null, either the SDK or the service is broken.
            val latency = meta.latencyMs
            assertTrue(latency != null && latency > 0L,
                "latencyMs should be populated and positive for a live streaming call; got $latency")
        }
    }
}
