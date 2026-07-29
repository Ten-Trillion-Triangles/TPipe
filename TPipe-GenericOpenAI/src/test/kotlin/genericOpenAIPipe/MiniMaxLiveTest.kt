package genericOpenAIPipe

import com.TTT.Debug.PipeTracer
import com.TTT.Pipeline.Pipeline
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live integration test for GenericOpenAIPipe with MiniMax API.
 *
 * Step 8: Use the minimax api key and url and model settings to build a unit test
 * that does a live api call using minimax and anthropic api with the generic pipe class.
 *
 * Run with: MINIMAX_API_KEY=... ./gradlew :TPipe-GenericOpenAI:test --tests "*.MiniMaxLiveTest"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MiniMaxLiveTest
{
    companion object
    {
        private const val MINIMAX_BASE_URL = "https://api.minimax.io/v1"
        private const val MINIMAX_MODEL = "MiniMax-M2.7"
        private const val TEST_PROMPT = "Say 'Hello from MiniMax' in exactly those words."
        private const val MAX_TOKENS = 256
    }

    @BeforeAll
    fun enableTracingForAllTests()
    {
        setupTraceDirectory(MiniMaxLiveTest::class.java)
        PipeTracer.enable()
    }

    @AfterAll
    fun disableTracingForAllTests()
    {
        PipeTracer.getAllTraces().keys.forEach { PipeTracer.clearTrace(it) }
        PipeTracer.disable()
    }

    @Test
    fun testMiniMaxLiveNonStreaming() = runBlocking<Unit>
    {
        val apiKey = System.getenv("MINIMAX_API_KEY")
        assertTrue(apiKey.isNotBlank(), "MINIMAX_API_KEY env var must be set")
        println("API Key loaded: ${apiKey.take(10)}...")

        val pipe = GenericOpenAIPipe()
            .setApiKey(apiKey)
            .setBaseUrl(MINIMAX_BASE_URL)
            .setApiMode(ApiMode.OpenAI)
            .setModel(MINIMAX_MODEL)
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(0.0)
            .enableTracing(traceConfig())

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.init(true)

        println("Sending request to MiniMax API (non-streaming)...")
        val result = pipeline.execute(TEST_PROMPT)

        println("Response: $result")
        assertNotNull(result, "Response should not be null")
        assertTrue(result.isNotEmpty(), "Response should not be empty")
        println("TEST PASSED — live MiniMax API call successful. Got: ${result.take(200)}")
    }
}