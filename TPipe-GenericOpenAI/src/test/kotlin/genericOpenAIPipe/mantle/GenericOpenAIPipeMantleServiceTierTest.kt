package genericOpenAIPipe.mantle

import com.TTT.Util.serialize
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.ApiMode
import genericOpenAIPipe.env.ChatChoice
import genericOpenAIPipe.env.ChatMessage
import genericOpenAIPipe.env.GenericOpenAIChatResponse
import genericOpenAIPipe.env.MessageContent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Pipe-level RED tests for Bedrock Mantle `service_tier` threading.
 *
 * Pins the contracts [MantleServiceTierContractTest] covers at the
 * serializer level, one level up:
 *  - `setBedrockMantleServiceTier` threads the tier into the serialized
 *    request body on both the OpenAI and OpenAIResponses wires,
 *  - an unset (or cleared) tier keeps the wire shape byte-preserved,
 *  - the provider's `service_tier` echo lands on the pipe's
 *    `lastResponseServiceTier` property (null when the provider omits it).
 *
 * The transport is a Ktor [MockEngine] injected via
 * `injectHttpClientForTest` — no network, no LLM calls.
 */
class GenericOpenAIPipeMantleServiceTierTest
{
    private val cannedResponsesBody = """{"id":"r1","object":"response","status":"completed","model":"test-model","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"pong"}]}]}"""

    private fun cannedChatResponse(serviceTier: String? = null): String
    {
        val response = GenericOpenAIChatResponse(
            id = "c1",
            created = 0L,
            model = "test-model",
            choices = listOf(ChatChoice(index = 0, message = ChatMessage(role = "assistant", content = MessageContent.TextContent("pong")))),
            serviceTier = serviceTier
        )
        return serialize(response)
    }

    private fun jsonElement(json: String) = Json.parseToJsonElement(json).jsonObject

//=========================================Request Threading=========================================

    @Test
    fun testSetterThreadsTierIntoOpenAIRequestJson() = runBlocking<Unit>
    {
        val captured = AtomicReference("")
        val engine = MockEngine { request ->
            captured.set((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            respond(cannedChatResponse(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = HttpClient(engine)
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAI)
            .setBedrockMantleServiceTier(MantleServiceTier.Flex)
        pipe.setModel("google.gemma-4-31b")
        pipe.setStreamingEnabled(false)
        pipe.injectHttpClientForTest(client)
        pipe.initForTest()
        try
        {
            pipe.generateTextForTest("hi")
            Assertions.assertEquals("flex", jsonElement(captured.get())["service_tier"]?.jsonPrimitive?.content)
        }
        finally
        {
            pipe.abortForTest()
            client.close()
        }
    }

    @Test
    fun testSetterThreadsTierIntoResponsesRequestJson() = runBlocking<Unit>
    {
        val captured = AtomicReference("")
        val engine = MockEngine { request ->
            captured.set((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            respond(cannedResponsesBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = HttpClient(engine)
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAIResponses)
            .setBedrockMantleServiceTier(MantleServiceTier.Standard)
        pipe.setModel("google.gemma-4-31b")
        pipe.setStreamingEnabled(false)
        pipe.injectHttpClientForTest(client)
        pipe.initForTest()
        try
        {
            pipe.generateTextForTest("hi")
            Assertions.assertEquals("default", jsonElement(captured.get())["service_tier"]?.jsonPrimitive?.content)
        }
        finally
        {
            pipe.abortForTest()
            client.close()
        }
    }

    @Test
    fun testUnsetTierKeepsWireShapeUnchanged() = runBlocking<Unit>
    {
        val captured = AtomicReference("")
        val engine = MockEngine { request ->
            captured.set((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            respond(cannedChatResponse(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = HttpClient(engine)
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAI)
        pipe.setModel("google.gemma-4-31b")
        pipe.setStreamingEnabled(false)
        pipe.injectHttpClientForTest(client)
        pipe.initForTest()
        try
        {
            pipe.generateTextForTest("hi")
            Assertions.assertFalse(jsonElement(captured.get()).containsKey("service_tier"))
        }
        finally
        {
            pipe.abortForTest()
            client.close()
        }
    }

    @Test
    fun testClearingSetterRemovesTierFromWire() = runBlocking<Unit>
    {
        val captured = AtomicReference("")
        val engine = MockEngine { request ->
            captured.set((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            respond(cannedChatResponse(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = HttpClient(engine)
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAI)
            .setBedrockMantleServiceTier(MantleServiceTier.Priority)
            .setBedrockMantleServiceTier(null)
        pipe.setModel("google.gemma-4-31b")
        pipe.setStreamingEnabled(false)
        pipe.injectHttpClientForTest(client)
        pipe.initForTest()
        try
        {
            pipe.generateTextForTest("hi")
            Assertions.assertFalse(jsonElement(captured.get()).containsKey("service_tier"))
        }
        finally
        {
            pipe.abortForTest()
            client.close()
        }
    }

//=========================================Response Echo=========================================

    @Test
    fun testProviderEchoLandsOnProperty() = runBlocking<Unit>
    {
        val engine = MockEngine {
            respond(cannedChatResponse(serviceTier = "priority"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = HttpClient(engine)
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAI)
        pipe.setModel("google.gemma-4-31b")
        pipe.setStreamingEnabled(false)
        pipe.injectHttpClientForTest(client)
        pipe.initForTest()
        try
        {
            pipe.generateTextForTest("hi")
            Assertions.assertEquals("priority", pipe.lastResponseServiceTier)
        }
        finally
        {
            pipe.abortForTest()
            client.close()
        }
    }

    @Test
    fun testPropertyStaysNullWhenProviderOmitsEcho() = runBlocking<Unit>
    {
        val engine = MockEngine {
            respond(cannedChatResponse(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = HttpClient(engine)
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAI)
        pipe.setModel("google.gemma-4-31b")
        pipe.setStreamingEnabled(false)
        pipe.injectHttpClientForTest(client)
        pipe.initForTest()
        try
        {
            pipe.generateTextForTest("hi")
            Assertions.assertNull(pipe.lastResponseServiceTier)
        }
        finally
        {
            pipe.abortForTest()
            client.close()
        }
    }
}
