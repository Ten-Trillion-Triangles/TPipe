package genericOpenAIPipe

import genericOpenAIPipe.env.GenericOpenAIEnv
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue

/**
 * Live integration tests for GenericOpenAIPipe streaming callbacks (Mantle path).
 * Requires MINIMAX_API_KEY or GENERIC_OPENAI_API_KEY exported AND GENERIC_OPENAI_LIVE_TEST=true.
 *
 * Phase 4 verification: exercises the streaming-callback chain on live Mantle API calls.
 * Uses model: anthropic.claude-3-haiku-20240307-v1:0 via https://api.minimax.io/v1
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericOpenAIPipeStreamingCallbacksLiveTest
{
    private fun getApiKey(): String =
        System.getenv("MINIMAX_API_KEY")
            ?: System.getenv("GENERIC_OPENAI_API_KEY")
            ?: ""

    private fun gateLiveTest()
    {
        assumeTrue(
            getApiKey().isNotBlank() && System.getenv("GENERIC_OPENAI_LIVE_TEST") == "true",
            "MINIMAX_API_KEY or GENERIC_OPENAI_API_KEY must be exported AND GENERIC_OPENAI_LIVE_TEST=true"
        )
    }

    @BeforeAll
    fun installCredentials()
    {
        gateLiveTest()
        if (getApiKey().isNotBlank())
        {
            GenericOpenAIEnv.setApiKey(getApiKey())
        }
    }

    @Test
    fun testEnableStreamingCallbackReceivesChunks()
    {
        gateLiveTest()
        val capturedChunks = mutableListOf<String>()

        val pipe = GenericOpenAIPipe()
        pipe.setBaseUrl("https://api.minimax.io/v1")
        pipe.setModel("MiniMax-M3")
        runBlocking { pipe.init() }

        pipe.setStreamingCallback { chunk ->
            capturedChunks.add(chunk)
        }

        runBlocking {
            pipe.generateText("Reply with exactly three words: hello world test")
        }

        assertTrue(capturedChunks.isNotEmpty(), "Expected at least one streaming chunk, got none")
        println("DEBUG: captured ${capturedChunks.size} chunks, first=${capturedChunks.firstOrNull()}")
    }

    @Test
    fun testStreamingCallbacksMultipleListenersBothReceive()
    {
        gateLiveTest()
        val chunksA = mutableListOf<String>()
        val chunksB = mutableListOf<String>()

        val pipe = GenericOpenAIPipe()
        pipe.setBaseUrl("https://api.minimax.io/v1")
        pipe.setModel("MiniMax-M3")
        runBlocking { pipe.init() }

        // Register two callbacks via enableStreaming
        pipe.enableStreaming { chunk -> chunksA.add(chunk) }
        pipe.enableStreaming { chunk -> chunksB.add(chunk) }

        runBlocking {
            pipe.generateText("Reply with exactly three words: hello world test")
        }

        assertTrue(chunksA.isNotEmpty(), "Callback A received no chunks")
        assertTrue(chunksB.isNotEmpty(), "Callback B received no chunks")
        println("DEBUG: A=${chunksA.size} chunks, B=${chunksB.size} chunks")
    }

    @Test
    fun testEnableStreamingPropagatesCallbackToDescendants()
    {
        gateLiveTest()
        val parentChunks = mutableListOf<String>()

        val parentPipe = GenericOpenAIPipe()
        parentPipe.setBaseUrl("https://api.minimax.io/v1")
        parentPipe.setModel("MiniMax-M3")
        runBlocking { parentPipe.init() }

        // Register callback on parent — propagateStreamingCallback in GenericOpenAIPipe
        // automatically propagates to any descendant pipes (reasoning, transformation).
        parentPipe.setStreamingCallback { chunk ->
            parentChunks.add(chunk)
        }

        // Fire a live LLM call — exercises the full streaming propagation chain
        runBlocking {
            parentPipe.generateText("Reply with exactly three words: hello world test")
        }

        assertTrue(parentChunks.isNotEmpty(),
            "Parent callback should have received streaming chunks; got none")
        println("DEBUG: parent received ${parentChunks.size} chunks via propagated streaming")
    }
}
