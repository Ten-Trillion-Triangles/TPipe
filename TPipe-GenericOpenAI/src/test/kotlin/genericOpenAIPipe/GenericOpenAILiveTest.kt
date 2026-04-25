package genericOpenAIPipe

import com.TTT.Pipeline.Pipeline
import com.TTT.Debug.TracingBuilder
import com.TTT.Debug.TraceFormat
import com.TTT.Debug.TraceDetailLevel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live integration test for TPipe-GenericOpenAI that makes real API calls.
 *
 * This test is disabled by default and must be enabled manually by removing the @Disabled annotation.
 * Set one of the following environment variables before running:
 * - TOGETHER_API_KEY (Together AI)
 * - GENERIC_OPENAI_API_KEY (generic)
 * - OPENAI_API_KEY (OpenAI)
 *
 * To run: ./gradlew :TPipe-GenericOpenAI:test --tests "*.GenericOpenAILiveTest"
 */
@Disabled("Live test — enable manually by removing @Disabled annotation")

class GenericOpenAILiveTest {

    private fun getApiKey(): String? {
        return System.getenv("TOGETHER_API_KEY")
            ?: System.getenv("GENERIC_OPENAI_API_KEY")
            ?: System.getenv("OPENAI_API_KEY")
    }

    private fun getBaseUrl(): String {
        val togetherKey = System.getenv("TOGETHER_API_KEY")
        return if (togetherKey != null) {
            "https://api.together.xyz/v1"
        } else {
            "https://api.openai.com/v1"
        }
    }

    private fun getModel(): String {
        val togetherKey = System.getenv("TOGETHER_API_KEY")
        return if (togetherKey != null) {
            "meta-llama/Llama-3.3-70B-Instruct-Turbo"
        } else {
            "gpt-4o-mini"
        }
    }

    private fun skipIfNoCredentials(): Boolean {
        val apiKey = getApiKey()
        if (apiKey == null || apiKey.isBlank()) {
            println("No API key found — skipping live test")
            return true
        }
        return false
    }

//=========================================Non-Streaming Tests=========================================================

    @Test
    fun testLiveApiCallNonStreaming() = runBlocking {
        if (skipIfNoCredentials()) return@runBlocking

        val apiKey = getApiKey()!!
        val baseUrl = getBaseUrl()
        val model = getModel()

        val traceConfig = TracingBuilder()
            .enabled()
            .detailLevel(TraceDetailLevel.VERBOSE)
            .outputFormat(TraceFormat.CONSOLE)
            .build()

        val pipeline = Pipeline()
            .enableTracing(traceConfig)

        val pipe = GenericOpenAIPipe()
            .setApiKey(apiKey)
            .setBaseUrl(baseUrl)
            .setModel(model)
            .setTemperature(0.7)

        pipeline.add(pipe)

        pipeline.init(true)
        val result = pipeline.execute("Say 'Hello, GenericOpenAI!' in exactly those words.")

        assertNotNull(result, "Response should not be null")
        assertTrue(result.isNotEmpty(), "Response should not be empty")

        println("Response: $result")
    }

    @Test
    fun testLiveApiCallWithSystemPrompt() = runBlocking {
        if (skipIfNoCredentials()) return@runBlocking

        val apiKey = getApiKey()!!
        val baseUrl = getBaseUrl()
        val model = getModel()

        val pipeline = Pipeline()

        val pipe = GenericOpenAIPipe()
            .setApiKey(apiKey)
            .setBaseUrl(baseUrl)
            .setModel(model)
            .setSystemPrompt("You always respond with exactly 3 words.")
            .setTemperature(0.0)

        pipeline.add(pipe)

        pipeline.init(true)
        val result = pipeline.execute("How are you?")

        assertNotNull(result, "Response should not be null")
        val wordCount = result.trim().split("\\s+".toRegex()).size
        assertTrue(wordCount <= 5, "Response should be approximately 3 words, got: $wordCount")

        println("Response ($wordCount words): $result")
    }

//=========================================Error Handling Tests=========================================================

    @Test
    fun testLiveApiWithInvalidModel() = runBlocking {
        if (skipIfNoCredentials()) return@runBlocking

        val apiKey = getApiKey()!!
        val baseUrl = getBaseUrl()

        val pipeline = Pipeline()

        val pipe = GenericOpenAIPipe()
            .setApiKey(apiKey)
            .setBaseUrl(baseUrl)
            .setModel("non-existent-model-12345")
            .setTemperature(0.7)

        pipeline.add(pipe)

        pipeline.init(true)

        val result = pipeline.execute("Hello")
        println("Result with invalid model: '$result'")
    }

    @Test
    fun testLiveApiCallWithoutCredentials() = runBlocking {
        val apiKey = getApiKey()

        if (apiKey == null || apiKey.isBlank()) {
            println("No API credentials available — test skipped gracefully")
            return@runBlocking
        }

        val baseUrl = getBaseUrl()
        val model = getModel()

        val pipeline = Pipeline()

        val pipe = GenericOpenAIPipe()
            .setApiKey(apiKey)
            .setBaseUrl(baseUrl)
            .setModel(model)

        pipeline.add(pipe)

        pipeline.init(true)
        val result = pipeline.execute("Hi")

        assertNotNull(result)
        println("Basic connectivity test passed: $result")
    }

//=========================================Tracing Tests===============================================================

    @Test
    fun testLiveApiCallWithHtmlTracing() = runBlocking {
        if (skipIfNoCredentials()) return@runBlocking

        val apiKey = getApiKey()!!
        val baseUrl = getBaseUrl()
        val model = getModel()

        val traceConfig = TracingBuilder()
            .enabled()
            .detailLevel(TraceDetailLevel.VERBOSE)
            .outputFormat(TraceFormat.HTML)
            .autoExport(true, "~/.TPipe-Debug/traces/")
            .build()

        val pipeline = Pipeline()
            .enableTracing(traceConfig)

        val pipe = GenericOpenAIPipe()
            .setApiKey(apiKey)
            .setBaseUrl(baseUrl)
            .setModel(model)
            .setTemperature(0.7)

        pipeline.add(pipe)

        pipeline.init(true)
        val result = pipeline.execute("Say 'Tracing works!' in exactly those words.")

        assertNotNull(result)
        assertTrue(result.isNotEmpty())

        val htmlReport = pipeline.getTraceReport(TraceFormat.HTML)
        assertNotNull(htmlReport, "HTML trace report should not be null")

        if (htmlReport.isNotEmpty()) {
            println("HTML trace contains 'Tracing works!': ${htmlReport.contains("Tracing works!")}")
        }
    }
}