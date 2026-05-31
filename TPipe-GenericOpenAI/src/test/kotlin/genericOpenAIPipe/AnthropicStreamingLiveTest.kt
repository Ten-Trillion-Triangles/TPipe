package genericOpenAIPipe

import com.TTT.Pipeline.Pipeline
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live streaming test for GenericOpenAIPipe with MiniMax Anthropic API.
 */
class AnthropicStreamingLiveTest
{
    companion object
    {
        private const val MINIMAX_BASE = "https://api.minimax.io"
        private const val MINIMAX_MODEL = "MiniMax-M2.7"
        private const val TEST_PROMPT = "Say hello in 5 words."
        private const val MAX_TOKENS = 256
    }

    @Test
    fun testAnthropicStreamingLive() = runBlocking<Unit>
    {
        val apiKey = System.getenv("MINIMAX_API_KEY")
        assertTrue(!apiKey.isNullOrBlank(), "MINIMAX_API_KEY env var must be set")
        println("API Key loaded: ${apiKey.take(10)}...")
        println("Using base URL: $MINIMAX_BASE")
        println("Anthropic endpoint: $MINIMAX_BASE/v1/messages")

        val chunks = mutableListOf<String>()

        val callback: suspend (String) -> Unit = { chunk ->
            println("STREAM_CHUNK_RECEIVED: [$chunk]")
            chunks.add(chunk)
        }

        val pipe: GenericOpenAIPipe = GenericOpenAIPipe()
            .setApiKey(apiKey)
            .setBaseUrl(MINIMAX_BASE)
            .setApiMode(ApiMode.Anthropic)
            .setModel(MINIMAX_MODEL)
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(0.0) as GenericOpenAIPipe

        pipe.setStreamingCallback(callback)

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.init(true)

        println("Sending streaming request to MiniMax Anthropic API...")
        val result = pipeline.execute(TEST_PROMPT)

        println("Final response: [$result]")
        println("Total chunks received: ${chunks.size}")

        assertNotNull(result, "Response should not be null")
        assertTrue(result.isNotEmpty(), "Response should not be empty, got: [$result]")
        assertTrue(chunks.isNotEmpty(), "Should have received at least one streaming chunk but got none")
        println("ANTHROPIC STREAMING TEST PASSED -- live streaming API call successful. Chunks: ${chunks.size}")
    }
}
