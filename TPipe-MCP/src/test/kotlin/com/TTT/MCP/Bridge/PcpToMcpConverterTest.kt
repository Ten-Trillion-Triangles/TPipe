package com.TTT.MCP.Bridge

import com.TTT.PipeContextProtocol.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcpToMcpConverterTest 
{
    private val converter = PcpToMcpConverter()

    @Test
    fun testConvertEmptyPcpContext() 
    {
        // Test converting empty PCP context
        val pcpContext = PcpContext()
        val result = converter.convert(pcpContext)
        // Verify resulting MCP request is empty
        assertTrue(result.tools.isEmpty())
        assertTrue(result.resources.isEmpty())
    }

    @Test
    fun testConvertTPipeOptionsToTools() 
    {
        // Create PCP context with a TPipe option
        val pcpContext = PcpContext()
        val tpipeOption = TPipeContextOptions().apply {
            functionName = "test_function"
            description = "Test description"
            params = mutableMapOf(
                "param1" to ContextOptionParameter(ParamType.String, "String parameter", emptyList())
            )
        }
        pcpContext.addTPipeOption(tpipeOption)

        // Convert and verify MCP tool was created correctly
        val result = converter.convert(pcpContext)
        assertEquals(1, result.tools.size)
        assertEquals("test_function", result.tools[0].name)
        assertEquals("Test description", result.tools[0].description)
    }

    @Test
    fun testConvertStdioOptionsToResources() 
    {
        // Create PCP context with a stdio option
        val pcpContext = PcpContext()
        val stdioOption = StdioContextOptions().apply {
            command = "cat"
            args = mutableListOf("/path/to/file")
            description = "Read file"
        }
        pcpContext.addStdioOption(stdioOption)

        // Convert and verify MCP resource was created correctly
        val result = converter.convert(pcpContext)
        assertEquals(1, result.resources.size)
        assertEquals("cat", result.resources[0].name)
        assertEquals("file:///path/to/file", result.resources[0].uri)
    }
}