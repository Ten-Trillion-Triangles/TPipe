package openrouterPipe

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live streaming integration tests for OpenRouterPipe.
 * Tests verify end-to-end streaming behavior with real OpenRouter API.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenRouterStreamingLiveTest
{
    companion object
    {
        private const val TEST_MODEL = "deepseek/deepseek-chat-v3-0324:free"
        private const val API_KEY_ENV_VAR = "OPENROUTER_API_KEY"
    }

    private fun requireOpenRouterApiKey()
    {
        val apiKey = System.getenv(API_KEY_ENV_VAR)
        assumeTrue(apiKey != null && apiKey.isNotBlank(), "Skipping test - API_KEY not set")
    }

    private fun getApiKey(): String?
    {
        return System.getenv(API_KEY_ENV_VAR)
    }

    /**
     * Tests streaming with a single callback.
     * Verifies callback receives chunks and accumulated text matches pipe return.
     */
    @Test
    fun testLiveStreamingWithCallback()
    {
        val accumulated = StringBuilder()
        runBlocking {
            requireOpenRouterApiKey()
            val pipe = OpenRouterPipe()
            pipe.setApiKey(getApiKey()!!)
            pipe.setModel(TEST_MODEL)
            val callback: suspend (String) -> Unit = { chunk -> accumulated.append(chunk) }
            pipe.setStreamingCallback(callback)
            pipe.init()
            val result = pipe.execute("Say: hi")
            assertNotNull(result)
            assertTrue(result.isNotBlank())
            assertTrue(accumulated.isNotEmpty())
            assertEquals(accumulated.toString(), result)
            pipe.abort()
        }
    }

    /**
     * Tests streaming with multiple callbacks added sequentially.
     * Both callbacks should receive identical chunks.
     */
    @Test
    fun testLiveStreamingMultipleCallbacks()
    {
        val text1 = StringBuilder()
        val text2 = StringBuilder()
        runBlocking {
            requireOpenRouterApiKey()
            val pipe = OpenRouterPipe()
            pipe.setApiKey(getApiKey()!!)
            pipe.setModel(TEST_MODEL)
            val cb1: suspend (String) -> Unit = { chunk -> text1.append(chunk) }
            val cb2: suspend (String) -> Unit = { chunk -> text2.append(chunk) }
            pipe.setStreamingCallback(cb1)
            pipe.setStreamingCallback(cb2)
            pipe.init()
            val result = pipe.execute("Say: test")
            assertNotNull(result)
            assertTrue(result.isNotBlank())
            assertTrue(text1.isNotEmpty())
            assertEquals(text1.toString(), text2.toString())
            assertEquals(text1.toString(), result)
            pipe.abort()
        }
    }
}