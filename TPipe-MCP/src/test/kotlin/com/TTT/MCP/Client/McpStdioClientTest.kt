package com.TTT.MCP.Client

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Contract tests for direct stdio process configuration and lifecycle bounds. */
class McpStdioClientTest
{
    @Test
    fun `configuration rejects empty or blank commands`()
    {
        assertFailsWith<IllegalArgumentException>
        {
            McpStdioClientConfig(emptyList())
        }
        assertFailsWith<IllegalArgumentException>
        {
            McpStdioClientConfig(listOf(" "))
        }
    }

    @Test
    fun `connect passes argv directly to the process launcher`()
    {
        var command: List<String>? = null
        val client = McpStdioClient(
            McpStdioClientConfig(listOf("mcp-server", "--port", "4000")),
            McpStdioProcessLauncher { builder ->
                command = builder.command()
                throw IOException("test launcher")
            }
        )

        assertFailsWith<IOException>
        {
            kotlinx.coroutines.runBlocking { client.connect() }
        }
        assertEquals(listOf("mcp-server", "--port", "4000"), command)
    }
}
