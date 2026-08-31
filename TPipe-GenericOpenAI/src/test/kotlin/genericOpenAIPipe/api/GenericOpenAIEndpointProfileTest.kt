package genericOpenAIPipe

import genericOpenAIPipe.api.ApiMode
import genericOpenAIPipe.api.GenericOpenAIEndpointProfile
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Verifies endpoint profile validation, defaults, and routing through both
 * regular and direct-streaming transports.
 */
class GenericOpenAIEndpointProfileTest
{

//=========================================Profile Contract=========================================================

    @Test
    fun defaultProfilePreservesHostedPaths()
    {
        val profile = GenericOpenAIEndpointProfile.DEFAULT

        Assertions.assertEquals("/chat/completions", profile.chatCompletionsPath)
        Assertions.assertEquals("/responses", profile.responsesPath)
        Assertions.assertEquals("/anthropic/v1/messages", profile.anthropicMessagesPath)
    }

    @Test
    fun localV1UsesCommonLocalProviderPaths()
    {
        val profile = GenericOpenAIEndpointProfile.localV1()

        Assertions.assertEquals("/v1/chat/completions", profile.chatCompletionsPath)
        Assertions.assertEquals("/v1/responses", profile.responsesPath)
        Assertions.assertEquals("/v1/messages", profile.anthropicMessagesPath)
    }

    @Test
    fun profileRejectsNonAbsoluteOrUrlLikePaths()
    {
        listOf(
            "chat/completions",
            "",
            " /v1/messages",
            "/v1/messages?secret=true",
            "/v1/messages#fragment",
            "//other-host/messages",
            "/v1/messages://other-host"
        ).forEach { invalidPath ->
            Assertions.assertThrows(IllegalArgumentException::class.java) {
                GenericOpenAIEndpointProfile(anthropicMessagesPath = invalidPath)
            }
        }
    }

    @Test
    fun endpointProfileCanBeChangedBeforeFirstRequestAndLocksAfterward() = runBlocking<Unit>
    {
        val engine = MockEngine {
            respond(
                content = cannedChatCompletionsBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://mock.local/v1")
            .setEndpointProfile(GenericOpenAIEndpointProfile.localV1())
            .setApiMode(ApiMode.OpenAI)
            .also {
                it.setModel("test-model")
                it.injectHttpClientForTest(HttpClient(engine))
            }

        Assertions.assertEquals("/v1/chat/completions", pipe.internalGetEndpointForTest())
        pipe.initForTest()
        try
        {
            pipe.generateTextForTest("hi")
            Assertions.assertThrows(IllegalStateException::class.java) {
                pipe.setEndpointProfile(GenericOpenAIEndpointProfile.DEFAULT)
            }
        }
        finally
        {
            pipe.abortForTest()
        }
    }

//=========================================Streaming Routing=======================================================

    @Test
    fun localV1ProfileRoutesDirectStreamingToConfiguredPath() = runBlocking<Unit>
    {
        val factory = MockStreamingConnectionFactory(responseBodySupplier = {
            """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg-1","model":"test-model"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"stream-pong"}}

                event: message_stop
                data: {"type":"message_stop"}

            """.trimIndent()
        })
        val pipe = GenericOpenAIPipe()
            .setBaseUrl("http://127.0.0.1:8080")
            .setEndpointProfile(GenericOpenAIEndpointProfile.localV1())
            .setApiMode(ApiMode.Anthropic)
            .setStreamingEnabled(true)
            .also { it.setModel("test-model") }

        pipe.injectStreamingConnectionFactoryForTest(factory)

        pipe.initForTest()
        try
        {
            Assertions.assertEquals("stream-pong", pipe.generateTextForTest("hi"))
            Assertions.assertEquals("http://127.0.0.1:8080/v1/messages", factory.capturedUrl)
            Assertions.assertEquals("2023-06-01", factory.capturedHeaders["anthropic-version"])
            Assertions.assertFalse(factory.capturedHeaders.containsKey("x-api-key"))
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    private val cannedChatCompletionsBody = """
        {
          "id": "chatcmpl-profile-1",
          "object": "chat.completion",
          "created": 1700000000,
          "model": "test-model",
          "choices": [
            {
              "index": 0,
              "message": { "role": "assistant", "content": "profile-pong" },
              "finish_reason": "stop"
            }
          ]
        }
    """.trimIndent()
}
