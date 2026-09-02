package com.TTT.AgentCore.Runtime.AgUi

import com.TTT.AgentCore.runtime.AgentCoreRuntimeHostConfig
import com.TTT.AgentCore.runtime.AgentCoreSessionContext
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentCoreAgUiRuntimeHostTest
{
    @Test
    fun sseUsesResolvedSessionAndStreamsChunks()
    {
        runBlocking {
            val port = freePort()
            val root = StreamingRoot()
            var createdSessionId: String? = null
            val host = AgentCoreAgUiRuntimeHost(
                config = AgentCoreRuntimeHostConfig(
                    bindAddress = "127.0.0.1",
                    port = port
                ),
                factory = { context: AgentCoreSessionContext ->
                    createdSessionId = context.sessionId
                    root
                },
                mapper = AgentCoreAgUiInputMapper { "resolved-session" }
            )
            val client = HttpClient(CIO)
            try
            {
                host.start()
                waitForServer(port)
                val response = client.post("http://127.0.0.1:$port/invocations") {
                    contentType(ContentType.Application.Json)
                    setBody(inputJson())
                }
                val body = response.bodyAsText()

                assertEquals("resolved-session", createdSessionId)
                assertEquals(2, body.split("TEXT_MESSAGE_CONTENT").size - 1)
                assertTrue(body.contains("chunk-1"))
                assertTrue(body.contains("chunk-2"))
                assertTrue(!body.contains("final-output"))
                assertEquals(1, root.clearCount)
            }

            finally
            {
                client.close()
                host.close()
            }
        }
    }

    @Test
    fun websocketStreamsChunksAndCleansCallbacks()
    {
        runBlocking {
            val port = freePort()
            val root = StreamingRoot()
            val host = AgentCoreAgUiRuntimeHost(
                config = AgentCoreRuntimeHostConfig(
                    bindAddress = "127.0.0.1",
                    port = port
                ),
                factory = { root }
            )
            val client = HttpClient(CIO) { install(WebSockets) }
            try
            {
                host.start()
                waitForServer(port)
                val events = mutableListOf<String>()
                client.webSocket(
                    method = HttpMethod.Get,
                    host = "127.0.0.1",
                    port = port,
                    path = "/ws"
                ) {
                    send(Frame.Text(inputJson()))
                    while(events.none { it.contains("RUN_FINISHED") })
                    {
                        events += (incoming.receive() as Frame.Text).readText()
                    }
                }

                assertEquals(2, events.count { it.contains("TEXT_MESSAGE_CONTENT") })
                assertTrue(events.any { it.contains("chunk-1") })
                assertTrue(events.any { it.contains("chunk-2") })
                assertEquals(1, root.clearCount)
            }

            finally
            {
                client.close()
                host.close()
            }
        }
    }

    @Test
    fun malformedSseInputEmitsRunError()
    {
        runBlocking {
            val port = freePort()
            val host = AgentCoreAgUiRuntimeHost(
                config = AgentCoreRuntimeHostConfig(
                    bindAddress = "127.0.0.1",
                    port = port
                ),
                factory = { StreamingRoot() }
            )
            val client = HttpClient(CIO)
            try
            {
                host.start()
                waitForServer(port)
                val response = client.post("http://127.0.0.1:$port/invocations") {
                    contentType(ContentType.Application.Json)
                    setBody("not-json")
                }

                assertTrue(response.bodyAsText().contains("RUN_ERROR"))
            }

            finally
            {
                client.close()
                host.close()
            }
        }
    }

    @Test
    fun malformedWebsocketInputEmitsRunError()
    {
        runBlocking {
            val port = freePort()
            val host = AgentCoreAgUiRuntimeHost(
                config = AgentCoreRuntimeHostConfig(
                    bindAddress = "127.0.0.1",
                    port = port
                ),
                factory = { StreamingRoot() }
            )
            val client = HttpClient(CIO) { install(WebSockets) }
            try
            {
                host.start()
                waitForServer(port)
                client.webSocket(
                    method = HttpMethod.Get,
                    host = "127.0.0.1",
                    port = port,
                    path = "/ws"
                ) {
                    send(Frame.Text("not-json"))
                    val error = (incoming.receive() as Frame.Text).readText()
                    assertTrue(error.contains("RUN_ERROR"))
                }
            }

            finally
            {
                client.close()
                host.close()
            }
        }
    }

    private fun inputJson(): String = """
        {
          "threadId": "thread",
          "runId": "run",
          "sessionId": "input-session",
          "messages": [{"role": "user", "content": "hello"}]
        }
    """.trimIndent()

    private suspend fun waitForServer(port: Int)
    {
        repeat(50) {
            runCatching { java.net.Socket("127.0.0.1", port).use { } }
                .onSuccess { return }
            delay(20)
        }
        error("Server did not start on port $port")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private class StreamingRoot : P2PInterface
    {
        private var callback: (suspend (String) -> Unit)? = null
        var clearCount = 0

        override fun setStreamingCallbackRecursive(callback: suspend (String) -> Unit)
        {
            this.callback = callback
        }

        override fun clearStreamingCallbacksRecursive()
        {
            callback = null
            clearCount++
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
        {
            callback?.invoke("chunk-1")
            callback?.invoke("chunk-2")
            return P2PResponse(output = MultimodalContent("final-output"))
        }

        override var killSwitch: com.TTT.P2P.KillSwitch? = null
    }
}
