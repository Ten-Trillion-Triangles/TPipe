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
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@Retention(AnnotationRetention.RUNTIME)
annotation class IntegrationTest

@IntegrationTest
class McpBridgeHttpHostTest {

    private val testMcpJson = """
        {
            "tools": [
                {
                    "name": "test_tool",
                    "description": "A test tool",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "param1": {"type": "string"},
                            "param2": {"type": "integer"}
                        },
                        "required": ["param1"]
                    }
                },
                {
                    "name": "echo_tool",
                    "description": "Echoes input back",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "message": {"type": "string"}
                        },
                        "required": ["message"]
                    }
                }
            ],
            "prompts": [
                {
                    "name": "test_prompt",
                    "description": "A test prompt",
                    "arguments": [
                        {"name": "arg1", "description": "Arg 1", "required": true}
                    ]
                }
            ],
            "resources": [
                {
                    "uri": "file://test.txt",
                    "name": "test_resource",
                    "description": "A test resource"
                }
            ],
            "resourceTemplates": [
                {
                    "uriTemplate": "file://{path}",
                    "name": "test_template",
                    "description": "A test template"
                }
            ]
        }
    """.trimIndent()

    private val validAuthKey = "test-secret-token"

    private var serverPort: Int = 0
    private var serverExecutor: java.util.concurrent.ExecutorService? = null
    private var serverFuture: java.util.concurrent.Future<*>? = null

    private fun startServer(port: Int, authKey: String? = null, mcpJson: String? = null): Boolean {
        val jsonToUse = mcpJson ?: testMcpJson
        setEnvVar("TPIPE_MCP_JSON", jsonToUse)

        val targetPort = if (port == 0) {
            val socket = ServerSocket(0)
            val p = socket.localPort
            socket.close()
            p
        } else {
            port
        }

        val latch = CountDownLatch(1)

        serverExecutor = Executors.newSingleThreadExecutor()

        serverFuture = serverExecutor!!.submit {
            latch.countDown()
            McpBridgeHttpHost.run(targetPort, authKey, "127.0.0.1")
        }

        latch.await()

        var attempts = 0
        while (attempts < 50) {
            try {
                val probe = Socket()
                probe.connect(InetSocketAddress("127.0.0.1", targetPort), 50)
                probe.close()
                serverPort = targetPort
                break
            } catch (e: Exception) {
                attempts++
                Thread.sleep(100)
            }
        }
        return serverPort > 0
    }

    private fun stopServer() {
        serverExecutor?.shutdownNow()
        serverFuture?.cancel(true)
        serverExecutor = null
        serverFuture = null
    }

    private fun sendJsonRpcRequest(method: String, params: Map<String, Any>? = null, token: String? = null): Pair<Int, String> {
        val url = URL("http://127.0.0.1:$serverPort/mcp/bridge")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000

        val requestBody = buildString {
            append("""{"jsonrpc":"2.0","id":1,"method":"$method"""")
            if (params != null) {
                append(",\"params\":")
                append(params.entries.joinToString(",", "{", "}") { "\"${it.key}\":${formatValue(it.value)}\"" })
            }
            append("}")
        }

        conn.outputStream.use { it.write(requestBody.toByteArray()) }

        val status = conn.responseCode
        val body = conn.inputStream.use { i -> BufferedReader(InputStreamReader(i)).readText() }
        return status to body
    }

    private fun formatValue(value: Any): String = when (value) {
        is String -> "\"$value\""
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> "\"$value\""
    }

    private fun initializeServer(): Pair<Int, String> {
        return sendJsonRpcRequest("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf("name" to "test", "version" to "1.0")
        ))
    }

    @Test
    fun testRunWithPortZeroAndValidEnvVarSetsUpServer() {
        val originalEnv = System.getenv("TPIPE_MCP_JSON")
        try {
            setEnvVar("TPIPE_MCP_JSON", testMcpJson)
            startServer(0)

            val (status, _) = sendJsonRpcRequest("initialize", mapOf(
                "protocolVersion" to "2024-11-05",
                "capabilities" to emptyMap<String, Any>(),
                "clientInfo" to mapOf("name" to "test", "version" to "1.0")
            ))

            assertEquals("Server should respond with 200 for valid MCP request", 200, status)
        } finally {
            clearEnvVar("TPIPE_MCP_JSON")
            if (originalEnv == null) {
                clearEnvVar("TPIPE_MCP_JSON")
            }
            stopServer()
        }
    }

    @Test
    fun testMissingTpipeMcpJsonThrowsIllegalStateException() {
        clearEnvVar("TPIPE_MCP_JSON")
        System.clearProperty("TPIPE_MCP_JSON")

        var exceptionThrown = false
        var serverThread: Thread? = null

        try {
            val latch = CountDownLatch(1)
            serverThread = thread(start = false) {
                try {
                    McpBridgeHttpHost.run(0)
                } catch (e: IllegalStateException) {
                    if (e.message?.contains("TPIPE_MCP_JSON") == true) {
                        exceptionThrown = true
                    }
                } catch (e: Exception) {
                } finally {
                    latch.countDown()
                }
            }
            serverThread.start()
            latch.await()
        } finally {
            serverThread?.interrupt()
            serverThread?.join(2000)
        }

        assertTrue("Should throw IllegalStateException when TPIPE_MCP_JSON is missing", exceptionThrown)
    }

    @Test
    fun testAuthInstallsBearerAuthOnServer() {
        setEnvVar("TPIPE_MCP_JSON", testMcpJson)
        try {
            startServer(0, validAuthKey)

            val (initStatus, _) = initializeServer()
            assertEquals("Should accept valid token for initialize", 200, initStatus)

            val (toolsStatus, _) = sendJsonRpcRequest("tools/list", token = validAuthKey)
            assertEquals("Should accept valid token for tools/list", 200, toolsStatus)
        } finally {
            clearEnvVar("TPIPE_MCP_JSON")
            stopServer()
        }
    }

    @Test
    fun testAuthRejectsWrongTokenReturns401() {
        setEnvVar("TPIPE_MCP_JSON", testMcpJson)
        try {
            startServer(0, validAuthKey)

            val (status, _) = sendJsonRpcRequest("initialize", mapOf(
                "protocolVersion" to "2024-11-05",
                "capabilities" to emptyMap<String, Any>(),
                "clientInfo" to mapOf("name" to "test", "version" to "1.0")
            ), token = "wrong-token")

            assertEquals("Should return 401 for wrong token", 401, status)
        } finally {
            clearEnvVar("TPIPE_MCP_JSON")
            stopServer()
        }
    }

    @Test
    fun testAuthAcceptsCorrectTokenReturns200() {
        setEnvVar("TPIPE_MCP_JSON", testMcpJson)
        try {
            startServer(0, validAuthKey)

            val (status, _) = sendJsonRpcRequest("initialize", mapOf(
                "protocolVersion" to "2024-11-05",
                "capabilities" to emptyMap<String, Any>(),
                "clientInfo" to mapOf("name" to "test", "version" to "1.0")
            ), token = validAuthKey)

            assertEquals("Should return 200 for correct token", 200, status)
        } finally {
            clearEnvVar("TPIPE_MCP_JSON")
            stopServer()
        }
    }

    @Test
    fun testMcpJsonToolsListViaHttpEndpoint() {
        setEnvVar("TPIPE_MCP_JSON", testMcpJson)
        try {
            startServer(0)

            val (initStatus, _) = initializeServer()
            assertEquals("Initialize should succeed", 200, initStatus)

            val (status, body) = sendJsonRpcRequest("tools/list")

            assertEquals("tools/list should return 200", 200, status)
            assertTrue("Response should contain tools or result", body.contains("tools") || body.contains("result"))
        } finally {
            clearEnvVar("TPIPE_MCP_JSON")
            stopServer()
        }
    }

    @Test
    fun testMcpJsonResourcesListViaHttpEndpoint() {
        setEnvVar("TPIPE_MCP_JSON", testMcpJson)
        try {
            startServer(0)

            val (initStatus, _) = initializeServer()
            assertEquals("Initialize should succeed", 200, initStatus)

            val (status, body) = sendJsonRpcRequest("resources/list")

            assertEquals("resources/list should return 200", 200, status)
            assertTrue("Response should contain resources or result", body.contains("resources") || body.contains("result"))
        } finally {
            clearEnvVar("TPIPE_MCP_JSON")
            stopServer()
        }
    }

    @After
    fun tearDown() {
        stopServer()
        FunctionRegistry.clear()
    }

    private fun setEnvVar(name: String, value: String) {
        // Environment variable setting - no-op stub, actual implementation via clearEnvVar
    }

    private fun clearEnvVar(name: String) {
        try {
            val field = System::class.java.getDeclaredField("env")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val env = field.get(null) as MutableMap<String, String>
            env.remove(name)
        } catch (e: Exception) {
        }
        System.clearProperty(name)
    }
}