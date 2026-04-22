package com.TTT.MCP.Bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class McpBridgeMainTest
{
    private val expectedUsage = """
TPipe-MCP Bridge Server
Usage: java -jar TPipe-MCP-*-all.jar [command]
Commands:
  --mcp-bridge-stdio-once   Run MCP bridge for single request
  --mcp-bridge-stdio-loop   Run MCP bridge with request loop
  --help                    Show this help message
    """.trimIndent()

    @Test
    fun testHelpArgOutputsCorrectUsageToStdout()
    {
        val output = captureStdout { mcpmain(arrayOf("--help")) }
        assertEquals(expectedUsage, output)
    }

    @Test
    fun testEmptyArgsShowsUsage()
    {
        val output = captureStdout { mcpmain(arrayOf()) }
        assertEquals(expectedUsage, output)
    }

    @Test
    fun testNoArgsShowsUsage()
    {
        val output = captureStdout { mcpmain(emptyArray()) }
        assertEquals(expectedUsage, output)
    }

    @Test
    fun testStdioOnceWithoutEnvVarThrowsIllegalStateException()
    {
        val exception = assertFailsWith<IllegalStateException> {
            mcpmain(arrayOf("--mcp-bridge-stdio-once"))
        }
        assertTrue(exception.message?.contains("TPIPE_MCP_JSON") == true)
    }

    @Test
    fun testStdioLoopWithoutEnvVarThrowsIllegalStateException()
    {
        val exception = assertFailsWith<IllegalStateException> {
            mcpmain(arrayOf("--mcp-bridge-stdio-loop"))
        }
        assertTrue(exception.message?.contains("TPIPE_MCP_JSON") == true)
    }

    @Test
    fun testUnknownArgShowsUsage()
    {
        val output = captureStdout { mcpmain(arrayOf("--unknown-flag")) }
        assertEquals(expectedUsage, output)
    }

    private fun captureStdout(block: () -> Unit): String {
        val originalOut = System.out
        val captureOut = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(captureOut))
        try {
            block()
        }
        finally {
            System.setOut(originalOut)
        }
        return captureOut.toString().trim()
    }
}