package com.TTT.MCP.Server

import com.TTT.PipeContextProtocol.*
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpToolRegistryTest {

    fun addNumbers(a: Int, b: Int): Int = a + b
    fun greet(name: String, greeting: String = "Hello"): String = "$greeting, $name!"
    fun getStatus(deviceId: String): String = "Device $deviceId is online"
    fun noArgsFunction(): String = "no arguments needed"
    fun complexFunction(name: String, age: Int, active: Boolean, tags: List<String>): String {
        return "$name, age $age, active=$active, tags=${tags.joinToString()}"
    }

    @Before
    fun setup() {
        FunctionRegistry.clear()
    }

    @Test
    fun testListTools_emptyRegistry_returnsEmptyList() {
        val context = PcpContext()
        val registry = McpToolRegistry(context)

        val tools = registry.listTools()

        assertTrue(tools.isEmpty(), "Empty registry should return empty tool list")
    }

    @Test
    fun testListTools_withRegisteredFunctions_returnsCorrectNameAndDescription() {
        FunctionRegistry.registerFunction("add", ::addNumbers)
        FunctionRegistry.registerFunction("greet", ::greet)
        FunctionRegistry.registerFunction("status", ::getStatus)

        val context = PcpContext()
        val registry = McpToolRegistry(context)

        val tools = registry.listTools()

        assertEquals(3, tools.size, "Should have 3 tools registered")

        val toolNames = tools.map { it.name }.toSet()
        assertTrue(toolNames.contains("add"), "Should contain 'add' tool")
        assertTrue(toolNames.contains("greet"), "Should contain 'greet' tool")
        assertTrue(toolNames.contains("status"), "Should contain 'status' tool")

        tools.forEach { tool ->
            assertTrue(tool.name.isNotEmpty(), "Tool name should not be empty")
            assertNotNull(tool.inputSchema, "Tool should have inputSchema")
        }
    }

    @Test
    fun testListTools_mapsRequiredVsOptionalParameters() {
        FunctionRegistry.registerFunction("greet", ::greet)

        val context = PcpContext()
        val registry = McpToolRegistry(context)

        val tools = registry.listTools()

        assertEquals(1, tools.size)
        val tool = tools.first()
        val schema = tool.inputSchema

        assertTrue(schema is ToolSchema, "inputSchema should be ToolSchema")
        val toolSchema = schema as ToolSchema
        assertNotNull(toolSchema.required, "Required array should exist")
        assertTrue(toolSchema.required!!.contains("name"), "'name' should be required")
        assertFalse(toolSchema.required!!.contains("greeting"), "'greeting' should not be required")
        assertNotNull(toolSchema.properties, "Properties should exist")
    }

    @Test
    fun testListTools_noRequiredParameters_whenAllOptional() {
        FunctionRegistry.registerFunction("noArgs", ::noArgsFunction)

        val context = PcpContext()
        val registry = McpToolRegistry(context)

        val tools = registry.listTools()

        assertEquals(1, tools.size)
        val tool = tools.first()
        val schema = tool.inputSchema

        assertTrue(schema is ToolSchema, "inputSchema should be ToolSchema")
        val toolSchema = schema as ToolSchema
        assertTrue(toolSchema.required == null || toolSchema.required!!.isEmpty(),
            "No required when function has no mandatory args")
    }

    @Test
    fun testCallTool_success_returnsCallToolResultWithContent() {
        FunctionRegistry.registerFunction("add", ::addNumbers)

        val context = PcpContext()
        val registry = McpToolRegistry(context)

        val result = registry.callTool("add", mapOf("a" to "5", "b" to "3"))

        val isError = result.isError ?: false
        assertFalse(isError, "Successful call should not have isError=true")
        assertEquals(1, result.content.size, "Should have one content item")
        val contentItem = result.content.first()
        assertTrue(contentItem is TextContent, "Content should be TextContent")
        val textContent = contentItem as TextContent
        assertTrue(textContent.text.contains("8") || textContent.text.contains("sum"),
            "Result should contain the sum: ${textContent.text}")
    }

    @Test
    fun testCallTool_unknownFunction_returnsErrorResult() {
        FunctionRegistry.clear()

        val context = PcpContext()
        val registry = McpToolRegistry(context)

        val result = registry.callTool("nonexistent", emptyMap())

        val isError = result.isError ?: false
        assertTrue(isError, "Calling unknown function should return isError=true")
        assertEquals(1, result.content.size, "Should have error content")
        val textContent = result.content.first() as TextContent
        assertTrue(textContent.text.isNotEmpty(), "Error message should not be empty")
    }

    @Test
    fun testCallTool_registersAndCallsFunction() {
        FunctionRegistry.clear()
        FunctionRegistry.registerFunction("status", ::getStatus)

        val context = PcpContext()
        val registry = McpToolRegistry(context)

        val result = registry.callTool("status", mapOf("deviceId" to "device-123"))

        val isError = result.isError ?: false
        assertFalse(isError, "Should succeed for registered function")
        val textContent = result.content.first() as TextContent
        assertTrue(textContent.text.contains("device-123") || textContent.text.contains("online"),
            "Should contain expected output: ${textContent.text}")
    }

    @Test
    fun testCallTool_multipleParameters_work() {
        FunctionRegistry.registerFunction("complex", ::complexFunction)

        val context = PcpContext()
        val registry = McpToolRegistry(context)

        val result = registry.callTool("complex", mapOf(
            "name" to "Alice",
            "age" to "30",
            "active" to "true",
            "tags" to "[\"dev\",\"test\"]"
        ))

        assertNotNull(result)
        assertEquals(1, result.content.size)
    }
}