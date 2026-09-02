package com.TTT.AgentCore.runtime

import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentCoreRuntimeHostTest
{
    @Test
    fun retainedSessionDoesNotReuseAStreamingCallback()
    {
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val root = StreamingRoot()
            val host = AgentCoreRuntimeHost(
                config = AgentCoreRuntimeHostConfig(
                    bindAddress = "127.0.0.1",
                    port = port
                ),
                factory = { root }
            )
            val transport = AgentCoreRuntimeClient(
                AgentCoreRuntimeClientConfig("http://127.0.0.1:$port")
            )
            try {
                host.start()
                waitForServer(port)
                val streamedChunks = mutableListOf<String>()
                val streamed = transport.invokeStreaming(
                    input = "first",
                    sessionId = "session",
                    onChunk = { streamedChunks += it }
                )
                val normal = transport.invoke("second", sessionId = "session")

                assertEquals(listOf("chunk-1", "chunk-2"), streamedChunks)
                assertEquals("chunk-1chunk-2", streamed.output)
                assertEquals("final-output", normal.output)
                assertEquals(2, root.clearCount)
                assertFalse(root.hasCallback)
            } finally {
                transport.close()
                host.close()
            }
        }
    }

    @Test
    fun streamedRequestFallsBackToFinalOutputWhenNoChunksArrive()
    {
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val root = StreamingRoot().apply { emitChunks = false }
            val host = AgentCoreRuntimeHost(
                config = AgentCoreRuntimeHostConfig(
                    bindAddress = "127.0.0.1",
                    port = port
                ),
                factory = { root }
            )
            val transport = AgentCoreRuntimeClient(
                AgentCoreRuntimeClientConfig("http://127.0.0.1:$port")
            )
            try {
                host.start()
                waitForServer(port)
                val chunks = mutableListOf<String>()
                val response = transport.invokeStreaming(
                    input = "no-chunks",
                    sessionId = "session",
                    onChunk = { chunks += it }
                )

                assertEquals(listOf("final-output"), chunks)
                assertEquals("final-output", response.output)
            } finally {
                transport.close()
                host.close()
            }
        }
    }

    @Test
    fun websocketResponseIncludesGeneratedSessionId()
    {
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val host = AgentCoreRuntimeHost(
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
                    send(Frame.Text("""{"prompt":"hello"}"""))
                    val response = AgentCoreRuntimeJson.decodeResponse(
                        (incoming.receive() as Frame.Text).readText()
                    )
                    assertTrue(response.sessionId.isNotBlank())
                }
            }
            finally
            {
                client.close()
                host.close()
            }
        }
    }

    private suspend fun waitForServer(port: Int)
    {
        repeat(50) {
            runCatching { java.net.Socket("127.0.0.1", port).use { } }
                .onSuccess { return }
            delay(20)
        }
        error("Server did not start on port $port")
    }

    private class StreamingRoot : P2PInterface
    {
        private var callback: (suspend (String) -> Unit)? = null
        var clearCount = 0
        var emitChunks = true
        val hasCallback: Boolean
            get() = callback != null

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
            if (emitChunks) {
                callback?.invoke("chunk-1")
                callback?.invoke("chunk-2")
            }
            return P2PResponse(output = MultimodalContent("final-output"))
        }

        override var killSwitch: com.TTT.P2P.KillSwitch? = null
    }
}
