package com.TTT.AgentCore.runtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import com.TTT.Pipe.MultimodalContent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentCoreRuntimeClientTest
{
    @Test
    fun canonicalInvocationUsesExactlyOnePromptOrContentAndCanonicalResponseOutput()
    {
        val prompt = AgentCoreRuntimeJson.decodeRequest("""{"prompt":"hello","stream":true}""")
        assertEquals("hello", prompt.effectiveContent().text)
        assertTrue(prompt.stream)

        val content = MultimodalContent("multimodal")
        val encoded = AgentCoreRuntimeJson.encodeRequest(
            AgentCoreInvocationRequest(content = content)
        )
        assertTrue(encoded.contains("\"content\""))
        assertTrue(!encoded.contains("\"prompt\""))

        assertFailsWith<IllegalArgumentException> {
            AgentCoreRuntimeJson.decodeRequest("""{"prompt":"one","content":{"text":"two"}}""")
        }

        val response = AgentCoreRuntimeJson.decodeResponse(
            AgentCoreRuntimeJson.encodeResponse(
                AgentCoreInvocationResponse(output = "answer", sessionId = "session")
            )
        )
        assertEquals("answer", response.output)
        assertEquals("MultimodalContent", response.outputContent!!::class.simpleName)

        val websocketResponse = AgentCoreRuntimeJson.encodeResponse(
            AgentCoreInvocationResponse(output = "answer", sessionId = "resolved-session"),
            includeSessionId = true
        )
        assertEquals("resolved-session", AgentCoreRuntimeJson.decodeResponse(websocketResponse).sessionId)
    }

    @Test
    fun readsSseChunksInOrderAndAccumulatesOutput()
    {
        runBlocking {
            val body = listOf(
                AgentCoreRuntimeJson.encodeStreamEvent(
                    AgentCoreStreamEvent(text = "hello", sessionId = "resolved")
                ),
                AgentCoreRuntimeJson.encodeStreamEvent(
                    AgentCoreStreamEvent(text = " world", sessionId = "resolved")
                ),
                AgentCoreRuntimeJson.encodeStreamEvent(
                    AgentCoreStreamEvent(done = true, sessionId = "resolved")
                )
            ).joinToString(separator = "") { event -> "data: $event\n\n" }
            val httpClient = HttpClient(MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Text.EventStream.toString())
                )
            })
            val client = AgentCoreRuntimeClient(
                AgentCoreRuntimeClientConfig("http://runtime"),
                httpClient
            )
            try
            {
                val chunks = mutableListOf<String>()
                val response = client.invokeStreaming("input", onChunk = { chunks += it })

                assertEquals(listOf("hello", " world"), chunks)
                assertEquals("hello world", response.output)
                assertEquals("resolved", response.sessionId)
                assertEquals(true, response.streamed)
            }

            finally
            {
                httpClient.close()
            }
        }
    }

    @Test
    fun usesSessionHeaderWhenStreamEventsDoNotRepeatIt()
    {
        runBlocking {
            val body = "data: ${AgentCoreRuntimeJson.encodeStreamEvent(AgentCoreStreamEvent(done = true))}\n\n"
            val httpClient = HttpClient(MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        "Content-Type" to listOf(ContentType.Text.EventStream.toString()),
                        "x-amzn-bedrock-agentcore-runtime-session-id" to listOf("header-session")
                    )
                )
            })
            val client = AgentCoreRuntimeClient(
                AgentCoreRuntimeClientConfig("http://runtime"),
                httpClient
            )
            try
            {
                assertEquals("header-session", client.invokeStreaming("input", onChunk = {}).sessionId)
            }
            finally
            {
                httpClient.close()
            }
        }
    }

    @Test
    fun raisesStreamedErrorFrames()
    {
        runBlocking {
            val body = "data: ${AgentCoreRuntimeJson.encodeError(AgentCoreInvocationError("boom"))}\n\n"
            val httpClient = HttpClient(MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Text.EventStream.toString())
                )
            })
            val client = AgentCoreRuntimeClient(
                AgentCoreRuntimeClientConfig("http://runtime"),
                httpClient
            )
            try
            {
                assertFailsWith<IllegalStateException> {
                    client.invokeStreaming("input", onChunk = {})
                }
            }

            finally
            {
                httpClient.close()
            }
        }
    }

    @Test
    fun rejectsTruncatedSseWithoutTerminalEvent()
    {
        runBlocking {
            val body = "data: ${AgentCoreRuntimeJson.encodeStreamEvent(AgentCoreStreamEvent(text = "partial"))}\n\n"
            val httpClient = HttpClient(MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Text.EventStream.toString())
                )
            })
            val client = AgentCoreRuntimeClient(
                AgentCoreRuntimeClientConfig("http://runtime"),
                httpClient
            )
            try
            {
                assertFailsWith<IllegalStateException> {
                    client.invokeStreaming("input", onChunk = {})
                }
            }

            finally
            {
                httpClient.close()
            }
        }
    }
}
