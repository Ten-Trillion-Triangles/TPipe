// PumpStation F1: path descriptors + PathRequest schema are injected into the
// dispatch pipe's actual API request. The test decodes the Responses payload
// before checking the path names embedded in the top-level instructions field.

package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.ApiMode
import genericOpenAIPipe.env.OpenAIResponsesContentPart
import genericOpenAIPipe.env.OpenAIResponsesOutputItem
import genericOpenAIPipe.env.OpenAIResponsesRequest
import genericOpenAIPipe.env.OpenAIResponsesResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Builds a minimal PumpStation with two paths and asserts that the dispatch
 * pipe's actual outbound Responses API request contains the registered path
 * names in its decoded instructions field.
 */
class PumpStationF1PathInjectionTest
{
    private lateinit var server: HttpServer
    private val capturedRequest = AtomicReference<String?>(null)
    private var port: Int = 0

    @BeforeEach
    fun setUp()
    {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/v1/responses", ResponsesHandler(capturedRequest))
        server.start()
    }

    @AfterEach
    fun tearDown()
    {
        server.stop(0)
    }

    @Test
    fun dispatchRequestBodyContainsPathNames()
    {
        val dispatchPipe = GenericOpenAIPipe()
            .setApiKey("stub-key")
            .setApiMode(ApiMode.OpenAIResponses)
            .setBaseUrl("http://127.0.0.1:$port/v1")
            .also { p ->
                p.setPipeName("dispatch")
                p.setModel("stub-model")
                p.setSystemPrompt("stub dispatch system prompt")
                p.setMaxTokens(256)
            }

        val judgePipe = GenericOpenAIPipe()
            .setApiKey("stub-key")
            .setApiMode(ApiMode.OpenAIResponses)
            .setBaseUrl("http://127.0.0.1:$port/v1")
            .also { p ->
                p.setPipeName("judge")
                p.setModel("stub-model")
                p.setSystemPrompt("stub judge system prompt")
                p.setMaxTokens(256)
            }

        val dispatchPipeline = Pipeline().apply { add(dispatchPipe) }
        val judgePipeline = Pipeline().apply { add(judgePipe) }
        runBlocking { dispatchPipeline.init(true) }
        runBlocking { judgePipeline.init(true) }

        val station = pumpStation("f1-test-station") {
            judgeAgent = judgePipeline
            dispatchAgent = dispatchPipeline
            path("gather") {
                description = "Researches a topic and returns raw findings."
                schema = "{}"
                setExecutionFunction { _, _, _, _ ->
                    MultimodalContent(text = "gathered findings").apply { passPipeline = true }
                }
            }
            path("report") {
                description = "Synthesizes the brief from gathered findings."
                schema = "{}"
                setExecutionFunction { _, _, _, _ ->
                    MultimodalContent(text = "final brief").apply { passPipeline = true }
                }
            }
            maxHarnessTurns = 1
        }

        runBlocking {
            station.executeLocal(MultimodalContent(text = "Research Kotlin coroutines vs Java virtual threads"))
        }

        val body = capturedRequest.get()
        assertNotNull(body, "Dispatch pipe made no API call; cannot verify path injection")
        // The Responses wire format JSON-escapes quotes inside the top-level
        // instructions string. Decode the request before checking the embedded
        // path-descriptor JSON.
        val decodedRequest = deserialize<OpenAIResponsesRequest>(body)
        val instructions = decodedRequest?.instructions.orEmpty()
        assertTrue(
            instructions.contains("\"gather\""),
            "F1: decoded dispatch instructions did not contain path name 'gather'. " +
                "Captured body length=${body.length}; first 500 chars=${body.take(500)}"
        )
        assertTrue(
            instructions.contains("\"report\""),
            "F1: decoded dispatch instructions did not contain path name 'report'. " +
                "Captured body length=${body.length}; first 500 chars=${body.take(500)}"
        )
    }
}

/**
 * Minimal HTTP handler that captures the request body and returns canned
 * Responses-API completions. Recognises the dispatch vs judge call by the
 * `instructions` field text.
 */
private class ResponsesHandler(
    private val captured: AtomicReference<String?>,
) : HttpHandler
{
    override fun handle(exchange: HttpExchange)
    {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        captured.compareAndSet(null, body)

        val isJudge = body.contains("\"stub judge system prompt\"") ||
            body.contains("Is the task complete")
        // Each stub response is a real OpenAIResponsesResponse instance built
        // from the data class directly, then serialised via the project's
        // canonical serialize() tool. Using the data class (not a hand-rolled
        // string literal) means the wire body is guaranteed to satisfy every
        // required field on the schema the parser consumes — no more
        // hand-rolled escape nesting. The text content part carries the
        // pathName / isComplete JSON payload as a string.
        val textJson = if (isJudge)
        {
            """{"isComplete":true,"shouldTerminate":false,"reason":"stub"}"""
        }
        else
        {
            """{"pathName":"report","pathSchema":""}"""
        }
        val textPart = OpenAIResponsesContentPart.OutputText(text = textJson)
        val messageItem = OpenAIResponsesOutputItem.Message(content = listOf(textPart))
        val response = OpenAIResponsesResponse(
            id = "stub-id",
            model = "stub-model",
            status = "completed",
            output = listOf(messageItem)
        )
        val responseJson = serialize(response, true)

        val responseBytes = responseJson.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, responseBytes.size.toLong())
        exchange.responseBody.use { it.write(responseBytes) }
    }
}
