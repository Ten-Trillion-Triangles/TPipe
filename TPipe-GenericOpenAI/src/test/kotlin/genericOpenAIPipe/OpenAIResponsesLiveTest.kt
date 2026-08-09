package genericOpenAIPipe

import com.TTT.Pipeline.Pipeline
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TracingBuilder
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Live integration test for the OpenAI Responses API mode against MiniMax
 * `MiniMax-M2.7`. Runs only when [MINIMAX_API_KEY] is set in the environment.
 *
 * Run with:
 * ```
 * MINIMAX_API_KEY=... \
 *   ./gradlew :TPipe-GenericOpenAI:test --tests "*OpenAIResponsesLiveTest"
 * ```
 */
@EnabledIfEnvironmentVariable(named = "MINIMAX_API_KEY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAIResponsesLiveTest
{

    private val baseUrl: String = "https://api.minimax.io/v1"
    private val model: String = "MiniMax-M2.7"
    private val maxTokens: Int = 256

    private fun apiKey(): String
    {
        val key = System.getenv("MINIMAX_API_KEY")
        Assertions.assertTrue(!key.isNullOrBlank(), "MINIMAX_API_KEY must be set for live tests")
        return key!!
    }

    @BeforeAll
    fun enableTracingForAllTests()
    {
        setupTraceDirectory(OpenAIResponsesLiveTest::class.java)
        PipeTracer.enable()
    }

    @AfterAll
    fun disableTracingForAllTests()
    {
        PipeTracer.getAllTraces().keys.forEach { PipeTracer.clearTrace(it) }
        PipeTracer.disable()
    }

//=========================================Non-Streaming=========================================

    @Test
    fun testResponsesNonStreaming() = runBlocking<Unit>
    {
        val traceConfig = TracingBuilder()
            .enabled()
            .detailLevel(TraceDetailLevel.DEBUG)
            .outputFormat(TraceFormat.CONSOLE)
            .build()

        val pipe: GenericOpenAIPipe = GenericOpenAIPipe()
        pipe.setApiKey(apiKey())
        pipe.setBaseUrl(baseUrl)
        pipe.setApiMode(ApiMode.OpenAIResponses)
        pipe.setModel(model)
        pipe.setMaxTokens(maxTokens)
        pipe.setTemperature(0.0)

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.enableTracing(traceConfig)
        pipeline.init(true)

        println("Sending non-streaming /v1/responses request to $baseUrl ...")
        val result = pipeline.execute("Think about why the sky is blue, then give a one-sentence summary.")

        println("Non-streaming response: $result")
        Assertions.assertNotNull(result)
        Assertions.assertTrue(result.isNotEmpty(), "Response should not be empty")
        Assertions.assertTrue(result.length >= 5, "Response should be at least 5 chars, got ${'$'}{result.length}: $result")

        val traceReport = pipeline.getTraceReport(TraceFormat.CONSOLE)
        Assertions.assertTrue(
            traceReport.contains("ResponsesAPI"),
            "Trace report should mention the Responses API mode"
        )
        println("Non-streaming live Responses test PASSED")
    }

//=========================================Streaming=========================================

    @Test
    fun testResponsesStreaming() = runBlocking<Unit>
    {
        val chunks = mutableListOf<String>()
        val traceConfig = TracingBuilder()
            .enabled()
            .detailLevel(TraceDetailLevel.DEBUG)
            .outputFormat(TraceFormat.CONSOLE)
            .build()

        val pipe: GenericOpenAIPipe = GenericOpenAIPipe()
        pipe.setApiKey(apiKey())
        pipe.setBaseUrl(baseUrl)
        pipe.setApiMode(ApiMode.OpenAIResponses)
        pipe.setModel(model)
        pipe.setMaxTokens(maxTokens)
        pipe.setTemperature(0.0)
        pipe.setStreamingCallback(suspend { chunk: String -> chunks.add(chunk); Unit })

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.enableTracing(traceConfig)
        pipeline.init(true)

        println("Sending streaming /v1/responses request to $baseUrl ...")
        val result = pipeline.execute("Think through what 17*24 equals, then give me only the final number.")

        println("Streaming assembled result: $result")
        println("Streaming chunks received: ${chunks.size}")
        Assertions.assertNotNull(result)
        Assertions.assertTrue(result.isNotEmpty(), "Response should not be empty")
        Assertions.assertTrue(chunks.isNotEmpty(), "Should have received at least one streaming chunk")

        val traceReport = pipeline.getTraceReport(TraceFormat.CONSOLE)
        Assertions.assertTrue(
            traceReport.contains("ResponsesAPI"),
            "Trace report should mention the Responses API mode"
        )
        println("Streaming live Responses test PASSED -- chunks=${chunks.size}")
    }

//=========================================Streaming + Reasoning Capture=========================================

    @Test
    fun testResponsesStreamingCapturesReasoning() = runBlocking<Unit>
    {
        val traceConfig = TracingBuilder()
            .enabled()
            .detailLevel(TraceDetailLevel.DEBUG)
            .outputFormat(TraceFormat.CONSOLE)
            .build()

        val pipe: GenericOpenAIPipe = GenericOpenAIPipe()
        pipe.setApiKey(apiKey())
        pipe.setBaseUrl(baseUrl)
        pipe.setApiMode(ApiMode.OpenAIResponses)
        pipe.setModel(model)
        pipe.setMaxTokens(256)
        pipe.setTemperature(0.0)

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.enableTracing(traceConfig)
        pipeline.init(true)

        // Open-ended math problem that provokes M2.7 reasoning
        val result = pipeline.execute("What is 17 * 24? Think step by step then give the final number.")

        println("Streaming+reasoning assembled result: $result")
        Assertions.assertNotNull(result)
        Assertions.assertTrue(result.isNotEmpty(), "Response should not be empty")

        val traceReport = pipeline.getTraceReport(TraceFormat.CONSOLE)
        Assertions.assertTrue(
            traceReport.contains("reasoningContent"),
            "Streaming trace should include reasoningContent (MiniMax-M2.7 emits reasoning tokens)"
        )
        Assertions.assertTrue(
            traceReport.contains("streaming"),
            "Streaming trace should include streaming flag"
        )
        println("Streaming+reasoning live Responses test PASSED -- result='" + result + "'")
    }

//=========================================System Prompt Hoisting=========================================

    @Test
    fun testResponsesWithSystemPrompt() = runBlocking<Unit>
    {
        val pipe: GenericOpenAIPipe = GenericOpenAIPipe()
        pipe.setApiKey(apiKey())
        pipe.setBaseUrl(baseUrl)
        pipe.setApiMode(ApiMode.OpenAIResponses)
        pipe.setModel(model)
        pipe.setSystemPrompt("You always respond with exactly 3 words.")
        pipe.setMaxTokens(maxTokens)
        pipe.setTemperature(0.0)
        pipe.enableTracing(traceConfig())

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.init(true)

        val result = pipeline.execute("How are you?")
        println("SystemPrompt response: $result")
        Assertions.assertNotNull(result)
        val wordCount = result.trim().split("\\s+".toRegex()).size
        Assertions.assertTrue(wordCount <= 5, "Response should be <=5 words, got $wordCount: $result")
    }

//=========================================JSON Object Response Format=========================================

    @Test
    fun testResponsesWithJsonObjectFormat() = runBlocking<Unit>
    {
        val pipe: GenericOpenAIPipe = GenericOpenAIPipe()
        pipe.setApiKey(apiKey())
        pipe.setBaseUrl(baseUrl)
        pipe.setApiMode(ApiMode.OpenAIResponses)
        pipe.setModel(model)
        pipe.setMaxTokens(maxTokens)
        pipe.setTemperature(0.0)
        pipe.setResponseFormat("json_object")
        pipe.enableTracing(traceConfig())

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.init(true)

        val result = pipeline.execute("Return a JSON object with one field 'ok' set to true.")
        println("json_object response: $result")
        Assertions.assertNotNull(result)
        Assertions.assertTrue(result.contains("{") && result.contains("}"), "Response should look like JSON")
    }
}
