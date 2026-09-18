package genericOpenAIPipe

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceEventType
import com.TTT.Pipe.MultimodalContent
import genericOpenAIPipe.api.ApiMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Fake-driven transport coverage for loopback GenericOpenAI endpoints.
 *
 * These tests intentionally stop at the HTTP boundary: the canned response is
 * enough to exercise URL construction, headers, and the existing parser without
 * starting a local model server.
 */
class GenericOpenAIPipeLocalTransportTest
{

//=========================================Lifecycle===============================================================

    @AfterEach
    fun clearInsecureOverride()
    {
        System.clearProperty("tpipe.allowInsecureBaseUrl")
    }

//=========================================Base URL Policy=========================================================

    @Test
    fun loopbackHttpTargetsAreAccepted()
    {
        listOf(
            "http://localhost:8080/v1",
            "http://127.0.0.1:8080/v1",
            "http://127.42.9.8:8080/v1",
            "http://[::1]:8080/v1"
        ).forEach { url ->
            Assertions.assertDoesNotThrow {
                GenericOpenAIPipe().setBaseUrl(url)
            }
        }
    }

    @Test
    fun nonLoopbackHttpAndMalformedTargetsAreRejected()
    {
        listOf(
            "http://192.168.1.20:8080/v1",
            "http://10.0.0.5:8080/v1",
            "http://example.com/v1",
            "http://localhost.example.com/v1",
            "http://127.0.0.1.example.com/v1",
            "https://user:password@example.com/v1",
            "https://example.com/v1?api_key=secret",
            "https://example.com/v1#fragment",
            "ftp://localhost/v1",
            "not a url"
        ).forEach { url ->
            Assertions.assertThrows(IllegalArgumentException::class.java) {
                GenericOpenAIPipe().setBaseUrl(url)
            }
        }
    }

    @Test
    fun explicitInsecureOverrideAllowsValidNonLoopbackHttp()
    {
        System.setProperty("tpipe.allowInsecureBaseUrl", "true")

        Assertions.assertDoesNotThrow {
            GenericOpenAIPipe().setBaseUrl("http://private-proxy.example/v1")
        }
    }

//=========================================Authentication===========================================================

    @Test
    fun localEndpointInitializesWithoutApiKeyAndOmitsCredentialHeader() = runBlocking<Unit>
    {
        var capturedUrl = ""
        var capturedAuthorization: String? = null
        var capturedApiKey: String? = null
        val pipe = localPipe(
            apiMode = ApiMode.OpenAI,
            profile = genericOpenAIPipe.api.GenericOpenAIEndpointProfile.localV1(),
            requestObserver = { request ->
                capturedUrl = request.url.toString()
                capturedAuthorization = request.headers[HttpHeaders.Authorization]
                capturedApiKey = request.headers["x-api-key"]
            }
        )

        try
        {
            pipe.initForTest()
            Assertions.assertEquals("local-pong", pipe.generateTextForTest("hi"))
            Assertions.assertEquals("http://127.0.0.1:8080/v1/chat/completions", capturedUrl)
            Assertions.assertNull(capturedAuthorization)
            Assertions.assertNull(capturedApiKey)
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    @Test
    fun localEndpointWithKeyStillUsesBearerAuthentication() = runBlocking<Unit>
    {
        var capturedAuthorization: String? = null
        val pipe = localPipe(
            apiMode = ApiMode.OpenAI,
            apiKey = "local-key",
            requestObserver = { request ->
                capturedAuthorization = request.headers[HttpHeaders.Authorization]
            }
        )

        try
        {
            pipe.initForTest()
            pipe.generateTextForTest("hi")
            Assertions.assertEquals("Bearer local-key", capturedAuthorization)
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    @Test
    fun localAnthropicEndpointKeepsVersionHeaderWithoutApiKey() = runBlocking<Unit>
    {
        var capturedUrl = ""
        var capturedHeaders: Map<String, String> = emptyMap()
        val pipe = localPipe(
            apiMode = ApiMode.Anthropic,
            profile = genericOpenAIPipe.api.GenericOpenAIEndpointProfile.localV1(),
            requestObserver = { request ->
                capturedUrl = request.url.toString()
                capturedHeaders = request.headers.entries().associate { (name, values) -> name to values.joinToString(",") }
            }
        )

        try
        {
            pipe.initForTest()
            pipe.generateTextForTest("hi")
            Assertions.assertEquals("http://127.0.0.1:8080/v1/messages", capturedUrl)
            Assertions.assertEquals("2023-06-01", capturedHeaders["anthropic-version"])
            Assertions.assertNull(capturedHeaders["x-api-key"])
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    @Test
    fun remoteHttpsEndpointStillRequiresCredentials() = runBlocking<Unit>
    {
        val pipe = GenericOpenAIPipe()
            .setBaseUrl("https://api.example.com/v1")

        pipe.setModel("test-model")

        Assertions.assertThrows(IllegalStateException::class.java) {
            runBlocking { pipe.initForTest() }
        }
    }

    @Test
    fun responsesReasoningSummarySurvivesPipeAndTraceProjection() = runBlocking<Unit>
    {
        val traceId = "camelstream-reasoning-summary-trace"
        val engine = MockEngine {
            respond(
                content = cannedCamelStreamResponsesBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = HttpClient(engine)
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://mock.camelstream.test/v1")
            .setApiMode(ApiMode.OpenAIResponses) as GenericOpenAIPipe
        pipe.setModel("auto")
        pipe.setStreamingEnabled(false)
        pipe.injectHttpClientForTest(client)

        PipeTracer.enable()
        PipeTracer.startTrace(traceId)
        pipe.enableTracing(
            TraceConfig(
                enabled = true,
                detailLevel = TraceDetailLevel.DEBUG,
                includeContext = true,
                includeMetadata = true
            )
        )
        pipe.addTraceId(traceId)

        try
        {
            pipe.initForTest()
            val result = pipe.generateContent(MultimodalContent(text = "classify this prompt"))

            Assertions.assertEquals(
                "{\"isSafe\":true,\"reason\":\"No unsafe content detected.\"}",
                result.text
            )
            Assertions.assertEquals(
                "Evaluating whether the player prompt is safe.\nNo unsafe request was found.",
                result.modelReasoning
            )

            val events = PipeTracer.getTrace(traceId)
            val success = events.firstOrNull { it.eventType == TraceEventType.API_CALL_SUCCESS }
            Assertions.assertNotNull(success, "Expected an API_CALL_SUCCESS trace event")
            Assertions.assertEquals(
                result.modelReasoning,
                success!!.content?.modelReasoning
            )
            Assertions.assertEquals(
                result.modelReasoning,
                success.metadata["reasoningContent"]
            )
            Assertions.assertFalse(
                events.toString().contains("encrypted-reasoning-must-not-escape"),
                "Encrypted reasoning content must not enter TPipe tracing"
            )
        }
        finally
        {
            pipe.abortForTest()
            client.close()
            PipeTracer.clearTrace(traceId)
            PipeTracer.disable()
        }
    }

//=========================================Helpers==================================================================

    private fun localPipe(
        apiMode: ApiMode,
        apiKey: String = "",
        profile: genericOpenAIPipe.api.GenericOpenAIEndpointProfile = genericOpenAIPipe.api.GenericOpenAIEndpointProfile.DEFAULT,
        requestObserver: (io.ktor.client.request.HttpRequestData) -> Unit = {}
    ): GenericOpenAIPipe
    {
        val engine = MockEngine { request ->
            requestObserver(request)
            respond(
                content = if(request.url.encodedPath.endsWith("/messages")) {
                    cannedAnthropicMessagesBody
                }
                else {
                    cannedChatCompletionsBody
                },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        return GenericOpenAIPipe()
            .setApiKey(apiKey)
            .setBaseUrl("http://127.0.0.1:8080")
            .setEndpointProfile(profile)
            .setApiMode(apiMode)
            .setStreamingEnabled(false)
            .also {
                it.setModel("test-model")
                it.injectHttpClientForTest(HttpClient(engine))
            }
    }

    private val cannedChatCompletionsBody = """
        {
          "id": "chatcmpl-local-1",
          "object": "chat.completion",
          "created": 1700000000,
          "model": "test-model",
          "choices": [
            {
              "index": 0,
              "message": { "role": "assistant", "content": "local-pong" },
              "finish_reason": "stop"
            }
          ],
          "usage": { "prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2 }
        }
    """.trimIndent()

    private val cannedAnthropicMessagesBody = """
        {
          "id": "msg-local-1",
          "type": "message",
          "role": "assistant",
          "content": [ { "type": "text", "text": "local-pong" } ],
          "model": "test-model",
          "stop_reason": "end_turn",
          "usage": { "input_tokens": 1, "output_tokens": 1 }
        }
    """.trimIndent()

    private val cannedCamelStreamResponsesBody = """
        {
          "id": "resp_camelstream_safety",
          "object": "response",
          "created_at": 1758209701,
          "status": "completed",
          "model": "auto",
          "output": [
            {
              "type": "reasoning",
              "id": "rs_camelstream_safety",
              "status": "completed",
              "summary": [
                {
                  "type": "summary_text",
                  "text": "Evaluating whether the player prompt is safe."
                }
              ],
              "content": [
                {
                  "type": "reasoning_text",
                  "text": "No unsafe request was found."
                }
              ],
              "encrypted_content": "encrypted-reasoning-must-not-escape"
            },
            {
              "type": "message",
              "id": "msg_camelstream_safety",
              "role": "assistant",
              "status": "completed",
              "content": [
                {
                  "type": "output_text",
                  "text": "{\"isSafe\":true,\"reason\":\"No unsafe content detected.\"}",
                  "annotations": []
                }
              ]
            }
          ],
          "usage": {
            "input_tokens": 42,
            "output_tokens": 18,
            "total_tokens": 60,
            "output_tokens_details": { "reasoning_tokens": 8 }
          }
        }
    """.trimIndent()
}
