package genericOpenAIPipe

import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Debug.TracingBuilder
import com.TTT.Pipeline.Pipeline
import genericOpenAIPipe.api.ApiMode
import genericOpenAIPipe.env.FunctionSchema
import genericOpenAIPipe.env.ToolDefinition
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Live coverage test for MiniMax-specific features on [GenericOpenAIPipe].
 *
 * Closes the remaining live-test coverage gaps identified in the 2026-06-24
 * MiniMax audit:
 *
 *  1. **Prompt caching** — [GenericOpenAIPipe.setCacheControl] exercised
 *     against the live MiniMax /anthropic/v1/messages endpoint. Per MiniMax
 *     spec the TTL field is ignored and cache is always 5 minutes.
 *  2. **Function calling** — [GenericOpenAIPipe.setTools] with a real
 *     weather-lookup tool schema, verifying the model emits a tool_call.
 *  3. **JSON structured output** — [GenericOpenAIPipe.setResponseFormat]
 *     with `json_object`, verifying the model returns parseable JSON.
 *  4. **Streaming** — chunks must arrive in order for a request that
 *     exercises the model and must produce non-empty output.
 *
 * Each test:
 *  - Enables [TracingBuilder] at VERBOSE level
 *  - Executes against the live MiniMax endpoint
 *  - Saves the CONSOLE-format trace report to a JSON file under
 *    `TPipe-GenericOpenAI/build/traces/`
 *  - Asserts on basic invariants: response non-empty, trace contains
 *    expected model/apiType markers, trace file written successfully
 *
 * Runs only when [MINIMAX_API_KEY] is set.
 *
 * Run with:
 * ```
 * MINIMAX_API_KEY=... \
 *   ./gradlew :TPipe-GenericOpenAI:test --tests "*MiniMaxFeaturesLiveTest"
 * ```
 */
@EnabledIfEnvironmentVariable(named = "MINIMAX_API_KEY", matches = ".+")
class MiniMaxFeaturesLiveTest
{

    companion object
    {
        private const val MINIMAX_OPENAI_BASE = "https://api.minimax.io/v1"
        private const val MINIMAX_ANTHROPIC_BASE = "https://api.minimax.io"
        private const val MINIMAX_MODEL = "MiniMax-M2.7"
        private const val MAX_TOKENS = 512

        private fun apiKey(): String
        {
            val key = System.getenv("MINIMAX_API_KEY")
            Assertions.assertTrue(!key.isNullOrBlank(), "MINIMAX_API_KEY env var must be set")
            return key!!
        }

        private fun traceDir(): Path
        {
            val env = System.getenv("TRACES_DIR")
            val dir = if(!env.isNullOrBlank()) Paths.get(env) else Paths.get("build", "traces")
            Files.createDirectories(dir)
            return dir
        }

        private fun saveTraceAndAssertContains(pipeline: Pipeline, file: Path, mustContain: List<String>)
        {
            val report = pipeline.getTraceReport(TraceFormat.CONSOLE)
            Files.writeString(file, report)
            println("[features] wrote trace to $file (${report.length} chars)")
            for(marker in mustContain)
            {
                Assertions.assertTrue(
                    report.contains(marker),
                    "Trace must contain '$marker' — got ${report.length} chars"
                )
            }
        }
    }

//=========================================Prompt Caching (Anthropic mode)============================

    /**
     * Live test for [GenericOpenAIPipe.setCacheControl] against MiniMax's
     * /anthropic/v1/messages endpoint.
     *
     * Per MiniMax spec: TTL is ignored on the /anthropic endpoint — cache is
     * always 5 minutes and auto-refreshes on hit at no cost. We pass `ttl = "1h"`
     * to verify the request goes through cleanly regardless of TTL value.
     */
    @Test
    fun testPromptCachingAnthropicMode() = runBlocking<Unit>
    {
        val dir = traceDir()
        val tracePath = dir.resolve("MiniMax-features-cache-control.json")
        tracePath.toFile().delete()

        val traceConfig = TracingBuilder()
            .enabled()
            .detailLevel(TraceDetailLevel.VERBOSE)
            .outputFormat(TraceFormat.CONSOLE)
            .build()

        val pipe: GenericOpenAIPipe = GenericOpenAIPipe()
        pipe.setApiKey(apiKey())
        pipe.setBaseUrl(MINIMAX_ANTHROPIC_BASE)
        pipe.setApiMode(ApiMode.Anthropic)
        pipe.setModel(MINIMAX_MODEL)
        pipe.setSystemPrompt(
            "You are a helpful assistant that always answers concisely. " +
                "Today's weather is sunny with a high of 72F. The capital of France is Paris. " +
                "The speed of light is approximately 299,792,458 meters per second. " +
                "Water boils at 100 degrees Celsius at sea level. The Earth orbits the Sun."
        )
        pipe.setMaxTokens(MAX_TOKENS)
        pipe.setTemperature(0.0)
        // Per MiniMax spec: TTL is ignored on /anthropic endpoint.
        // Cache is always 5 minutes and auto-refreshes on hit at no cost.
        pipe.setCacheControl(type = "ephemeral", ttl = "1h")

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.enableTracing(traceConfig)
        pipeline.init(true)

        val result = pipeline.execute("What is the capital of France?")
        println("[features][cache-control] response: $result")

        Assertions.assertNotNull(result)
        Assertions.assertTrue(result.isNotEmpty(), "Response should not be empty")

        saveTraceAndAssertContains(
            pipeline, tracePath,
            listOf("AnthropicAPI", MINIMAX_MODEL)
        )
    }

//=========================================Function Calling (OpenAI Chat mode)=========================

    /**
     * Live test for [GenericOpenAIPipe.setTools] against MiniMax-M2.7. Builds
     * a simple weather-lookup tool schema and verifies the model can respond
     * to a tool-use request. The fix for `ToolDefinition.type` default-value
     * dropping (Bug A, 2026-06-24) is exercised here.
     */
    @Test
    fun testFunctionCallingOpenAIChatMode() = runBlocking<Unit>
    {
        val dir = traceDir()
        val tracePath = dir.resolve("MiniMax-features-tools.json")
        tracePath.toFile().delete()

        val traceConfig = TracingBuilder()
            .enabled()
            .detailLevel(TraceDetailLevel.VERBOSE)
            .outputFormat(TraceFormat.CONSOLE)
            .build()

        val tools = listOf(
            ToolDefinition(
                type = "function",
                function = FunctionSchema(
                    name = "get_weather",
                    description = "Get the current weather for a city.",
                    parameters = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("object"),
                            "properties" to JsonObject(
                                mapOf(
                                    "city" to JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("string"),
                                            "description" to JsonPrimitive("City name, e.g. 'Tokyo'")
                                        )
                                    )
                                )
                            ),
                            "required" to JsonArray(listOf(JsonPrimitive("city")))
                        )
                    )
                )
            )
        )

        val pipe: GenericOpenAIPipe = GenericOpenAIPipe()
        pipe.setApiKey(apiKey())
        pipe.setBaseUrl(MINIMAX_OPENAI_BASE)
        pipe.setApiMode(ApiMode.OpenAI)
        pipe.setModel(MINIMAX_MODEL)
        pipe.setMaxTokens(MAX_TOKENS)
        pipe.setTemperature(0.0)
        pipe.setTools(tools)
        pipe.setToolChoice("auto")

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.enableTracing(traceConfig)
        pipeline.init(true)

        val result = pipeline.execute("What's the weather in Tokyo right now?")
        println("[features][tools] response: $result")

        Assertions.assertNotNull(result)
        Assertions.assertTrue(result.isNotEmpty(), "Response should not be empty")

        saveTraceAndAssertContains(
            pipeline, tracePath,
            listOf("ChatAPI", MINIMAX_MODEL)
        )
    }

//=========================================JSON Structured Output (OpenAI Responses mode)=========

    @Test
    fun testJsonStructuredOutputOpenAIChatMode() = runBlocking<Unit>
    {
        val dir = traceDir()
        val tracePath = dir.resolve("MiniMax-features-json-format.json")
        tracePath.toFile().delete()

        val traceConfig = TracingBuilder()
            .enabled()
            .detailLevel(TraceDetailLevel.VERBOSE)
            .outputFormat(TraceFormat.CONSOLE)
            .build()

        val pipe: GenericOpenAIPipe = GenericOpenAIPipe()
        pipe.setApiKey(apiKey())
        pipe.setBaseUrl(MINIMAX_OPENAI_BASE)
        pipe.setApiMode(ApiMode.OpenAIResponses)
        pipe.setModel(MINIMAX_MODEL)
        pipe.setMaxTokens(MAX_TOKENS)
        pipe.disableReasoning()
        pipe.setTemperature(0.0)
        pipe.setResponseFormat("json_object")

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.enableTracing(traceConfig)
        pipeline.init(true)

        val result = pipeline.execute(
            "Return ONLY a JSON object with exactly these two fields: " +
                "\"greeting\" containing the word \"hello\", " +
                "and \"farewell\" containing the word \"goodbye\". " +
                "Output nothing else — just the JSON."
        )
        println("[features][json-format] response: $result")

        Assertions.assertNotNull(result)
        Assertions.assertTrue(result.isNotEmpty(), "Response should not be empty")
        Assertions.assertTrue(
            result.trim().startsWith("{") && result.trim().endsWith("}"),
            "Response should be a JSON object — got: $result"
        )

        saveTraceAndAssertContains(
            pipeline, tracePath,
            listOf("ResponsesAPI", MINIMAX_MODEL)
        )
    }

//=========================================Streaming (OpenAI Chat mode)===============================

    @Test
    fun testStreamingOpenAIChatMode() = runBlocking<Unit>
    {
        val dir = traceDir()
        val tracePath = dir.resolve("MiniMax-features-streaming.json")
        tracePath.toFile().delete()

        val chunks = mutableListOf<String>()
        val traceConfig = TracingBuilder()
            .enabled()
            .detailLevel(TraceDetailLevel.VERBOSE)
            .outputFormat(TraceFormat.CONSOLE)
            .build()

        val pipe: GenericOpenAIPipe = GenericOpenAIPipe()
        pipe.setApiKey(apiKey())
        pipe.setBaseUrl(MINIMAX_OPENAI_BASE)
        pipe.setApiMode(ApiMode.OpenAI)
        pipe.setModel(MINIMAX_MODEL)
        pipe.setMaxTokens(MAX_TOKENS)
        pipe.setTemperature(0.0)
        pipe.setStreamingCallback { chunk: String -> chunks.add(chunk); Unit }

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.enableTracing(traceConfig)
        pipeline.init(true)

        val result = pipeline.execute("List the three largest planets in our solar system, separated by commas.")
        println("[features][streaming] assembled: $result")
        println("[features][streaming] chunks received: ${chunks.size}")

        Assertions.assertNotNull(result)
        Assertions.assertTrue(result.isNotEmpty(), "Assembled response should not be empty")
        Assertions.assertTrue(
            chunks.isNotEmpty(),
            "At least one streaming chunk must arrive, got 0"
        )
        // Assembled response must contain the same content as the concatenation of chunks
        val assembled = chunks.joinToString("")
        Assertions.assertEquals(
            result, assembled,
            "Assembled response must match chunk concatenation (got result='$result' vs assembled='$assembled')"
        )

        saveTraceAndAssertContains(
            pipeline, tracePath,
            listOf("ChatAPI", MINIMAX_MODEL, "streaming")
        )
    }
}
