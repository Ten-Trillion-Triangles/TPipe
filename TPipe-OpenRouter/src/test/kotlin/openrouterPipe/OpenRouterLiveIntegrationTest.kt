package openrouterPipe

import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenRouterLiveIntegrationTest
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

    @Test
    fun testLiveNonStreamingChatCompletion()
    {
        runBlocking {
            requireOpenRouterApiKey()
            val pipe = OpenRouterPipe()
                .setApiKey(getApiKey()!!)
                .setModel(TEST_MODEL)
            pipe.init()
            val result = pipe.execute("Say exactly one word: hello")
            assertNotNull(result)
            assertTrue(result.isNotBlank())
            pipe.abort()
        }
    }

    @Test
    fun testLiveNonStreamingWithSystemPrompt()
    {
        runBlocking {
            requireOpenRouterApiKey()
            val pipe = OpenRouterPipe()
                .setApiKey(getApiKey()!!)
                .setModel(TEST_MODEL)
                .setSystemPrompt("You are a math expert.")
            pipe.init()
            val result = pipe.execute("What is 15% of 200?")
            assertNotNull(result)
            assertTrue(result.isNotBlank())
            pipe.abort()
        }
    }

    @Test
    fun testLiveAuthErrorWithBadKey()
    {
        runBlocking {
            requireOpenRouterApiKey()
            val badKeyPipe = OpenRouterPipe()
                .setApiKey("sk-or-v1-BAD_KEY_THIS_IS_INVALID_FOR_TESTING")
                .setModel(TEST_MODEL)
            badKeyPipe.init()
            var caughtException: P2PException? = null
            try
            {
                badKeyPipe.execute("Say hello")
            }
            catch(e: P2PException)
            {
                caughtException = e
            }
            assertNotNull(caughtException, "Expected P2PException to be thrown")
            assertEquals(P2PError.auth, caughtException!!.errorType)
            badKeyPipe.abort()
        }
    }

    @Test
    fun testLiveAbortDoesNotThrow()
    {
        runBlocking {
            requireOpenRouterApiKey()
            val pipe = OpenRouterPipe()
                .setApiKey(getApiKey()!!)
                .setModel(TEST_MODEL)
            pipe.init()
            pipe.abort()
            val pipe2 = OpenRouterPipe()
                .setApiKey(getApiKey()!!)
                .setModel(TEST_MODEL)
            pipe2.init()
            val result = pipe2.execute("Say: ok")
            assertNotNull(result)
            assertTrue(result.isNotBlank())
            pipe2.abort()
        }
    }
}