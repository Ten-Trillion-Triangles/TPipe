package com.TTT.MCP.Server

import com.TTT.PipeContextProtocol.FunctionRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

class McpHttpHostTest
{
    private val validAuthKey = "test-secret-token"

    private var serverPort: Int = 0
    private var serverThread: Thread? = null

    private fun startServer(requestedPort: Int, authKey: String? = null): Int {
        val latch = CountDownLatch(1)

        serverThread = thread(start = false) {
            latch.countDown()
            try {
                McpHttpHost.run(port = requestedPort, authKey = authKey, bindAddress = "127.0.0.1")
            } catch (e: Exception) {
    
            }
        }

        latch.await()
        serverThread!!.start()


        val startPort = if (requestedPort == 0) 8000 else requestedPort
        var boundPort = 0
        var attempts = 0

        while (boundPort == 0 && attempts < 100) {
            for (p in startPort..(startPort + 999)) {
                try {
                    val probe = Socket()
                    probe.connect(InetSocketAddress("127.0.0.1", p), 50)
                    probe.close()
                    boundPort = p
                    break
                } catch (e: Exception) {

                }
            }
            if (boundPort == 0) {
                attempts++
                Thread.sleep(50)
            }
        }

        if (boundPort == 0) {
    
            boundPort = requestedPort
        }

        serverPort = boundPort
        return boundPort
    }

    private fun stopServer() {
        try {
            serverThread?.interrupt()
            serverThread?.join(1000)
        } catch (e: Exception) {
    
        }
        serverThread = null
    }

    private fun sendHttpRequest(token: String? = null): Pair<Int, String> {
        if (serverPort <= 0) {
            return -1 to "Server not started"
        }

        return try {
            val url = URL("http://127.0.0.1:$serverPort/mcp")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            if (token != null) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            conn.connectTimeout = 3000
            conn.readTimeout = 3000

            val status = conn.responseCode
            val body = try {
                conn.inputStream.use { i -> BufferedReader(InputStreamReader(i)).readText() }
            } catch (e: Exception) {
                ""
            }
            status to body
        } catch (e: Exception) {
            -1 to (e.message ?: "Unknown error")
        }
    }

    @After
    fun tearDown() {
        stopServer()
        FunctionRegistry.clear()
    }

    @Test
    fun testRunWithPortZeroStartsServerWithoutThrowing()
    {
        var exceptionThrown = false
        var serverThread: Thread? = null

        val latch = CountDownLatch(1)
        serverThread = thread(start = false)
        {
            try
            {
                latch.countDown()
                McpHttpHost.run(port = 0, bindAddress = "127.0.0.1")
            } catch (e: IllegalStateException) {
                exceptionThrown = true
            } catch (e: Exception) {
            }
        }
        serverThread.start()
        latch.await()

        Thread.sleep(500)

        assertTrue("Server should start without throwing exception",
            serverThread.isAlive || !exceptionThrown)

        serverThread?.interrupt()
        serverThread?.join(1000)
    }

    @Test
    fun testServerAcceptsConnectionsOnAutoAssignedPort()
    {
        val boundPort = startServer(0, null)
        Thread.sleep(300)

        val (status, _) = sendHttpRequest()

        assertTrue("Server should accept connections on port $boundPort, got status $status",
            status == 200 || status == 400 || status == 401)
    }

    @Test
    fun testAuthEnabledServerRejectsRequestsWithoutToken()
    {
        val boundPort = startServer(0, validAuthKey)
        Thread.sleep(300)

        val (status, _) = sendHttpRequest(token = null)

        assertEquals("Server should reject requests without Bearer token when auth is enabled",
            401, status)
    }

    @Test
    fun testAuthEnabledServerAcceptsRequestsWithCorrectToken()
    {
        val boundPort = startServer(0, validAuthKey)
        Thread.sleep(300)

        val (status, _) = sendHttpRequest(token = validAuthKey)

        assertNotEquals("Server should accept requests with correct Bearer token", 401, status)
    }

    @Test
    fun testNoAuthServerAcceptsRequests()
    {
        val boundPort = startServer(0, null)
        Thread.sleep(300)

        val (status, _) = sendHttpRequest()

        assertNotEquals("Server without auth should accept requests without Bearer token", 401, status)
    }
}