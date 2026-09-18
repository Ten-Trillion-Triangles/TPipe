package genericOpenAIPipe

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceEventType
import com.TTT.Pipe.MultimodalContent
import genericOpenAIPipe.api.ApiMode
import genericOpenAIPipe.env.ReasoningConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Opt-in live regression test for CamelStream's OpenAI Responses payload.
 *
 * This test is intentionally disabled unless the caller supplies the explicit
 * live-test gate and an API key through the process environment. No credential
 * is read from a repository file or written to a trace.
 *
 * Run with:
 * ```
 * CAMELSTREAM_LIVE_TEST=true \
 * CAMELSTREAM_API_KEY=... \
 * ./gradlew :TPipe-GenericOpenAI:test --tests '*OpenAIResponsesCamelStreamLiveTest'
 * ```
 */
@EnabledIfEnvironmentVariable(named = "CAMELSTREAM_LIVE_TEST", matches = "true")
class OpenAIResponsesCamelStreamLiveTest
{

    private val traceId = "camelstream-responses-live"

    @AfterEach
    fun clearTrace()
    {
        PipeTracer.getAllTraces().keys.forEach { PipeTracer.clearTrace(it) }
        PipeTracer.disable()
    }

    @Test
    fun camelStreamResponsesPreserveStructuredSafetyAndReasoningTrace() = runBlocking<Unit>
    {
        val apiKey = System.getenv("CAMELSTREAM_API_KEY")
        Assumptions.assumeTrue(!apiKey.isNullOrBlank(), "CAMELSTREAM_API_KEY is not set")

        val baseUrl = System.getenv("CAMELSTREAM_BASE_URL")
            ?.takeIf { it.isNotBlank() }
            ?: "https://stream.camelai.com/v1"
        val model = System.getenv("CAMELSTREAM_MODEL")
            ?.takeIf { it.isNotBlank() }
            ?: "auto"

        val pipe = GenericOpenAIPipe()
            .setApiKey(apiKey!!)
            .setBaseUrl(baseUrl)
            .setApiMode(ApiMode.OpenAIResponses) as GenericOpenAIPipe
        pipe.setModel(model)
        pipe.setMaxTokens(512)
        pipe.setReasoningConfig(ReasoningConfig(maxTokens = 128))
        pipe.setStreamingEnabled(false)
        pipe.setJsonOutput(SafetyResult())
        pipe.enableTracing(
            TraceConfig(
                enabled = true,
                detailLevel = TraceDetailLevel.DEBUG,
                includeContext = true,
                includeMetadata = true
            )
        )
        pipe.addTraceId(traceId)

        PipeTracer.enable()
        PipeTracer.startTrace(traceId)

        try
        {
            pipe.initForTest()
            val result = pipe.generateContent(
                MultimodalContent(
                    text = "Classify this harmless player prompt as safe. Think briefly, then return only the JSON safety object."
                )
            )

            val safety = Json.decodeFromString<SafetyResult>(result.text)
            Assertions.assertTrue(safety.isSafe, "CamelStream safety result should be safe")
            Assertions.assertTrue(result.modelReasoning.isNotBlank(), "Responses reasoning summary should be observable")

            val events = PipeTracer.getTrace(traceId)
            val success = events.firstOrNull { it.eventType == TraceEventType.API_CALL_SUCCESS }
            Assertions.assertNotNull(success, "CamelStream call must produce an API_CALL_SUCCESS trace")
            Assertions.assertEquals(result.modelReasoning, success!!.metadata["reasoningContent"])
            Assertions.assertEquals(result.modelReasoning, success.content?.modelReasoning)
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    @Serializable
    private data class SafetyResult(
        val isSafe: Boolean = false,
        val reason: String = ""
    )
}
