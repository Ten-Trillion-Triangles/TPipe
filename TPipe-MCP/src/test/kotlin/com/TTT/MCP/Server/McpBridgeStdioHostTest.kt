package com.TTT.MCP.Server

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [McpBridgeStdioHost] bridge stdio transport delegation.
 * Tests host creation via createHost() private method using reflection.
 * Note: runOnce/runLoop block threads so we test delegation behavior only.
 */
class McpBridgeStdioHostTest {

    companion object {
        private const val ENV_MCP_JSON = "TPIPE_MCP_JSON"
        private val VALID_MCP_JSON = """
            {
                "tools": [
                    {
                        "name": "test_tool",
                        "description": "A test tool",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "param1": {"type": "string"}
                            }
                        }
                    }
                ]
            }
        """.trimIndent()
    }

    private fun invokeCreateHost(): McpBridgeServerHost {
        val method = McpBridgeStdioHost::class.java.getDeclaredMethod("createHost")
        method.isAccessible = true
        return method.invoke(null) as McpBridgeServerHost
    }

    private fun setEnvVar(name: String, value: String?)
    {
        if (value == null) {
            System.getenv().remove(name)
            val field = System::class.java.getDeclaredField("env")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val env = field.get(null) as java.util.Map<String, String>
            env.remove(name)
        } else {
            System.setProperty(name, value)
        }
    }

    @Test
    fun testCreateHost_withValidMcpJson_returnsMcpBridgeServerHost()
    {
        try
        {
            setEnvVar(ENV_MCP_JSON, VALID_MCP_JSON)

            val host = invokeCreateHost()

            assertIs<McpBridgeServerHost>(host)
        }
        finally
        {
            setEnvVar(ENV_MCP_JSON, null)
            System.clearProperty(ENV_MCP_JSON)
        }
    }

    @Test
    fun testCreateHost_withoutMcpJson_throwsIllegalStateException()
    {
        try
        {
            setEnvVar(ENV_MCP_JSON, null)
            System.clearProperty(ENV_MCP_JSON)

            val exception = try
            {
                invokeCreateHost()
                null
            } catch (e: Throwable) {
                e.cause ?: e
            }

            assertTrue(exception is IllegalStateException,
                "Expected IllegalStateException but got ${exception?.javaClass?.name}")
        }
        finally
        {
            setEnvVar(ENV_MCP_JSON, null)
            System.clearProperty(ENV_MCP_JSON)
        }
    }

    @Test
    fun testCreateHost_withoutMcpJson_exceptionMessageContainsTpipeMcpJson()
    {
        try
        {
            setEnvVar(ENV_MCP_JSON, null)
            System.clearProperty(ENV_MCP_JSON)

            val exception = try
            {
                invokeCreateHost()
                null
            } catch (e: Throwable) {
                e.cause ?: e
            }

            assertTrue(exception is IllegalStateException)
            val message = (exception as IllegalStateException).message ?: ""
            assertTrue(message.contains("TPIPE_MCP_JSON"),
                "Exception message should contain 'TPIPE_MCP_JSON' but was: $message")
        }
        finally
        {
            setEnvVar(ENV_MCP_JSON, null)
            System.clearProperty(ENV_MCP_JSON)
        }
    }

    @Test
    fun testCreateHost_delegatesToMcpBridgeServerHost()
    {
        try
        {
            setEnvVar(ENV_MCP_JSON, VALID_MCP_JSON)

            val host = invokeCreateHost()

            assertIs<McpBridgeServerHost>(host)
        }
        finally
        {
            setEnvVar(ENV_MCP_JSON, null)
            System.clearProperty(ENV_MCP_JSON)
        }
    }
}