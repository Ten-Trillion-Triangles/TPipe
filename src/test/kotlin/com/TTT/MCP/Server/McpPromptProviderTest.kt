package com.TTT.MCP.Server

import com.TTT.PipeContextProtocol.ContextOptionParameter
import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.ParamType
import com.TTT.PipeContextProtocol.PcpContext
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Prompt
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpPromptProviderTest {

    private fun createTPipeContextOption(
        functionName: String,
        description: String = "",
        params: Map<String, ContextOptionParameter> = emptyMap()
    ): com.TTT.PipeContextProtocol.TPipeContextOptions {
        return com.TTT.PipeContextProtocol.TPipeContextOptions().apply {
            this.functionName = functionName
            this.description = description
            this.params.putAll(params)
        }
    }

    @Test
    fun testListPromptsWithEmptyContext() {
        val context = PcpContext()
        val provider = McpPromptProvider(context)
        val result = provider.listPrompts()

        assertTrue(result.isEmpty())
    }

    @Test
    fun testListPromptsFiltersNonPromptPrefix() {
        val context = PcpContext()
        context.addTPipeOption(createTPipeContextOption(
            functionName = "regular_tool",
            description = "A regular tool"
        ))
        context.addTPipeOption(createTPipeContextOption(
            functionName = "another_tool",
            description = "Another tool"
        ))

        val provider = McpPromptProvider(context)
        val result = provider.listPrompts()

        // Should filter out non-prompt_ prefixed functions
        assertTrue(result.isEmpty())
    }

    @Test
    fun testListPromptsStripsPrefix() {
        val context = PcpContext()
        context.addTPipeOption(createTPipeContextOption(
            functionName = "prompt_greeting",
            description = "A greeting prompt"
        ))

        val provider = McpPromptProvider(context)
        val result = provider.listPrompts()

        assertEquals(1, result.size)
        assertEquals("greeting", result[0].name)
        assertEquals("A greeting prompt", result[0].description)
    }

    @Test
    fun testListPromptsMapsParamsToPromptArguments() {
        val context = PcpContext()
        val param1 = ContextOptionParameter(
            type = ParamType.String,
            description = "The user's name",
            isRequired = true
        )
        val param2 = ContextOptionParameter(
            type = ParamType.Int,
            description = "Number of times to greet",
            isRequired = false
        )
        context.addTPipeOption(createTPipeContextOption(
            functionName = "prompt_greet",
            description = "Greeting prompt",
            params = mapOf("name" to param1, "count" to param2)
        ))

        val provider = McpPromptProvider(context)
        val result = provider.listPrompts()

        assertEquals(1, result.size)
        assertEquals(2, result[0].arguments?.size)

        assertTrue(result[0].arguments?.find { it.name == "name" }?.description?.contains("user's name") == true)
        assertEquals(true, result[0].arguments?.find { it.name == "name" }?.required)

        val countArg = result[0].arguments?.find { it.name == "count" }
        assertEquals("Number of times to greet", countArg?.description)
        assertEquals(false, countArg?.required)
    }

    @Test
    fun testGetPromptSuccessWithArguments() {
        val context = PcpContext()
        val provider = McpPromptProvider(context)

        // Register a function directly in FunctionRegistry for getPrompt to find
        val functionSignature = com.TTT.PipeContextProtocol.FunctionSignature(
            name = "prompt_test",
            parameters = emptyList(),
            returnType = com.TTT.PipeContextProtocol.ReturnTypeInfo(ParamType.String, "kotlin.String", false, ""),
            description = "Test prompt description"
        )
        FunctionRegistry.registerLambda("prompt_test", { "result" }, functionSignature)

        val result = provider.getPrompt("test", mapOf("arg1" to "value1", "arg2" to "value2"))

        assertEquals(1, result.messages.size)
        assertEquals(Role.User, result.messages[0].role)
        val text = (result.messages[0].content as TextContent).text
        assertTrue(text.contains("Test prompt description"))
        assertTrue(text.contains("arg1=value1"))
        assertTrue(text.contains("arg2=value2"))

        // Cleanup
        FunctionRegistry.clear()
    }

    @Test
    fun testGetPromptNotFoundReturnsError() {
        val context = PcpContext()
        val provider = McpPromptProvider(context)

        // Ensure no function is registered
        FunctionRegistry.clear()

        val result = provider.getPrompt("nonexistent", emptyMap())

        assertEquals(1, result.messages.size)
        assertEquals(Role.User, result.messages[0].role)
        val text = (result.messages[0].content as TextContent).text
        assertTrue(text.contains("not found"))
    }

    @Test
    fun testGetPromptWithoutArgumentsHandlesGracefully() {
        val context = PcpContext()
        val provider = McpPromptProvider(context)

        // Register a function
        val functionSignature = com.TTT.PipeContextProtocol.FunctionSignature(
            name = "prompt_simple",
            parameters = emptyList(),
            returnType = com.TTT.PipeContextProtocol.ReturnTypeInfo(ParamType.String, "kotlin.String", false, ""),
            description = "Simple prompt without arguments"
        )
        FunctionRegistry.registerLambda("prompt_simple", { "result" }, functionSignature)

        val result = provider.getPrompt("simple", emptyMap())

        assertEquals(1, result.messages.size)
        assertEquals(Role.User, result.messages[0].role)
        val text = (result.messages[0].content as TextContent).text
        assertTrue(text.contains("Simple prompt without arguments"))
        assertTrue(!text.contains("Arguments:"))

        // Cleanup
        FunctionRegistry.clear()
    }

    @Test
    fun testListPromptsWithMixedOptions() {
        val context = PcpContext()
        // Add regular tool - should be filtered
        context.addTPipeOption(createTPipeContextOption(
            functionName = "regular_tool",
            description = "Not a prompt"
        ))
        // Add prompt tool - should be included
        context.addTPipeOption(createTPipeContextOption(
            functionName = "prompt_welcome",
            description = "Welcome message"
        ))
        // Add another regular tool - should be filtered
        context.addTPipeOption(createTPipeContextOption(
            functionName = "compute_something",
            description = "Does computation"
        ))
        // Add another prompt - should be included
        context.addTPipeOption(createTPipeContextOption(
            functionName = "prompt_farewell",
            description = "Farewell message"
        ))

        val provider = McpPromptProvider(context)
        val result = provider.listPrompts()

        assertEquals(2, result.size)

        val welcomePrompt = result.find { it.name == "welcome" }
        assertEquals("Welcome message", welcomePrompt?.description)

        val farewellPrompt = result.find { it.name == "farewell" }
        assertEquals("Farewell message", farewellPrompt?.description)
    }
}