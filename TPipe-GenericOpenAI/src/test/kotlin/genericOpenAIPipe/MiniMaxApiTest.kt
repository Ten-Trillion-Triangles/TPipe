package genericOpenAIPipe

import com.TTT.Pipeline.Pipeline
import genericOpenAIPipe.api.ApiMode
import genericOpenAIPipe.env.GenericOpenAIEnv
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live integration test for GenericOpenAIPipe with MiniMax API (Anthropic format).
 *
 * This test is disabled by default and must be enabled manually.
 * Credentials are injected via GenericOpenAIEnv at runtime — not hardcoded.
 *
 * To run:
 *   cd TPipe-GenericOpenAI
 *   ../gradlew :TPipe-GenericOpenAI:test --tests "*.MiniMaxApiTest"
 *
 * Prerequisites:
 *   - Java 24
 *   - Kotlin 2.2.20+
 *   - Gradle 8.14.3
 *   - Network access to https://api.minimax.io
 *
 * NOTE: Do NOT commit API keys. Credentials are injected programmatically
 * via GenericOpenAIEnv.setApiKey() in @BeforeAll — never hardcoded in this file.
 */
class MiniMaxApiTest
{

    companion object
    {
        private const val MINIMAX_BASE_URL = "https://api.minimax.io/v1"
        private const val MINIMAX_MODEL = "MiniMax-M2.7"
        private const val TEST_PROMPT = "hello"
        private const val MAX_TOKENS = 256

        @JvmStatic
        @BeforeAll
        fun setup()
        {
            // Inject credentials at runtime — NOT hardcoded.
            val apiKey = System.getenv("MINIMAX_API_KEY")
            assert(apiKey.isNotBlank()) { "MINIMAX_API_KEY environment variable must be set" }
            GenericOpenAIEnv.setApiKey(apiKey)
        }

        @JvmStatic
        @AfterAll
        fun teardown()
        {
            GenericOpenAIEnv.clearApiKey()
        }
    }

//=========================================Non-Streaming Tests=========================================================

    @Test
    fun testMiniMaxSayHello() = runBlocking<Unit>
    {
        val pipe = GenericOpenAIPipe()
            .setApiKey(GenericOpenAIEnv.resolveApiKey())
            .setBaseUrl(MINIMAX_BASE_URL)
            .setApiMode(ApiMode.OpenAI)
            .setModel(MINIMAX_MODEL)
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(0.0)

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.init(true)

        val result = pipeline.execute(TEST_PROMPT)

        assertNotNull(result, "Response should not be null")
        assertTrue(result.isNotEmpty(), "Response should not be empty")
    }

//=========================================System Prompt Tests=========================================================

    @Test
    fun testMiniMaxWithSystemPrompt() = runBlocking<Unit>
    {
        val pipe = GenericOpenAIPipe()
            .setApiKey(GenericOpenAIEnv.resolveApiKey())
            .setBaseUrl(MINIMAX_BASE_URL)
            .setApiMode(ApiMode.OpenAI)
            .setModel(MINIMAX_MODEL)
            .setSystemPrompt("You are a helpful assistant.")
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(0.0)

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.init(true)

        val result = pipeline.execute(TEST_PROMPT)

        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

//=========================================Temperature Variation Tests====================================================

    @Test
    fun testMiniMaxWithTemperature() = runBlocking<Unit>
    {
        val pipe = GenericOpenAIPipe()
            .setApiKey(GenericOpenAIEnv.resolveApiKey())
            .setBaseUrl(MINIMAX_BASE_URL)
            .setApiMode(ApiMode.OpenAI)
            .setModel(MINIMAX_MODEL)
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(0.7)

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.init(true)

        val result = pipeline.execute(TEST_PROMPT)

        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }
}