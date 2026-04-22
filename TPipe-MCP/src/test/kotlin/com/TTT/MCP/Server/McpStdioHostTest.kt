package com.TTT.MCP.Server

import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.PcpContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for McpStdioHost stdio transport delegation.
 *
 * Note: runOnce/runLoop block the calling thread, so these tests focus on
 * verifying the delegation behavior of createHost() through reflection.
 */
class McpStdioHostTest {

    private fun getPrivateField(obj: Any, fieldName: String): Any {
        val field = obj.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(obj)
    }

    private fun callPrivateMethod(obj: Any, methodName: String): Any {
        val method = obj.javaClass.getDeclaredMethod(methodName)
        method.isAccessible = true
        return method.invoke(obj)
    }

    @Test
    fun testCreateHostReturnsMcpServerHost() {
        FunctionRegistry.clear()
        val result = callPrivateMethod(McpStdioHost, "createHost")
        assertNotNull(result, "createHost should return non-null McpServerHost")
        assertTrue(result is McpServerHost, "Result should be McpServerHost instance")
    }

    @Test
    fun testCreateHostWithEmptyFunctionRegistry() {
        FunctionRegistry.clear()
        val result = callPrivateMethod(McpStdioHost, "createHost") as McpServerHost
        val pcpContext = getPrivateField(result, "pcpContext") as PcpContext
        assertTrue(pcpContext.tpipeOptions.isEmpty(), "pcpContext should have no tpipeOptions when FunctionRegistry is empty")
    }

    @Test
    fun testCreateHostWithPopulatedFunctionRegistry() {
        FunctionRegistry.clear()
        FunctionRegistry.registerLambda(
            name = "testFunction",
            lambda = { x: Int -> x * 2 },
            signature = com.TTT.PipeContextProtocol.FunctionSignature(
                name = "testFunction",
                parameters = listOf(
                    com.TTT.PipeContextProtocol.ParameterInfo(
                        name = "x",
                        type = com.TTT.PipeContextProtocol.ParamType.Int,
                        kotlinType = "kotlin.Int",
                        isOptional = false,
                        defaultValue = null,
                        enumValues = emptyList(),
                        description = "A number to double"
                    )
                ),
                returnType = com.TTT.PipeContextProtocol.ReturnTypeInfo(
                    type = com.TTT.PipeContextProtocol.ParamType.Int,
                    kotlinType = "kotlin.Int",
                    isNullable = false,
                    description = "Doubled value"
                ),
                description = "Doubles a number"
            )
        )

        try {
            val result = callPrivateMethod(McpStdioHost, "createHost") as McpServerHost
            val pcpContext = getPrivateField(result, "pcpContext") as PcpContext
            assertEquals(1, pcpContext.tpipeOptions.size, "pcpContext should have one tpipeOption for the registered function")
            assertEquals("testFunction", pcpContext.tpipeOptions[0].functionName, "functionName should match registered function")
        } finally {
            FunctionRegistry.clear()
        }
    }

    @Test
    fun testCreateHostUsesMcpCapabilityConfig() {
        FunctionRegistry.clear()
        val result = callPrivateMethod(McpStdioHost, "createHost") as McpServerHost
        val capabilities = getPrivateField(result, "capabilities")
        assertNotNull(capabilities, "capabilities should not be null")
        assertTrue(capabilities is io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities, "capabilities should be ServerCapabilities type")
        val serverCaps = capabilities as io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
        assertNotNull(serverCaps.tools, "tools capability should be enabled by default")
        assertNotNull(serverCaps.resources, "resources capability should be enabled by default")
        assertNotNull(serverCaps.prompts, "prompts capability should be enabled by default")
    }

    @Test
    fun testCreateHostHandlesMissingFunctionRegistryGracefully() {
        FunctionRegistry.clear()
        val result = callPrivateMethod(McpStdioHost, "createHost") as McpServerHost
        assertNotNull(result, "createHost should succeed with empty FunctionRegistry")
        val pcpContext = getPrivateField(result, "pcpContext") as PcpContext
        assertTrue(pcpContext.tpipeOptions.isEmpty(), "tpipeOptions should be empty")
        assertFalse(pcpContext.tpipeOptions.any { it.functionName.isBlank() }, "No blank function names should exist")
    }

    @Test
    fun testCreateHostWithMultipleFunctions() {
        FunctionRegistry.clear()

        try {
            FunctionRegistry.registerLambda(
                name = "func1",
                lambda = { -> 1 },
                signature = com.TTT.PipeContextProtocol.FunctionSignature(
                    name = "func1",
                    parameters = emptyList(),
                    returnType = com.TTT.PipeContextProtocol.ReturnTypeInfo(
                        type = com.TTT.PipeContextProtocol.ParamType.Int,
                        kotlinType = "kotlin.Int",
                        isNullable = false,
                        description = ""
                    ),
                    description = ""
                )
            )

            FunctionRegistry.registerLambda(
                name = "func2",
                lambda = { x: String -> x },
                signature = com.TTT.PipeContextProtocol.FunctionSignature(
                    name = "func2",
                    parameters = listOf(
                        com.TTT.PipeContextProtocol.ParameterInfo(
                            name = "x",
                            type = com.TTT.PipeContextProtocol.ParamType.String,
                            kotlinType = "kotlin.String",
                            isOptional = false,
                            defaultValue = null,
                            enumValues = emptyList(),
                            description = ""
                        )
                    ),
                    returnType = com.TTT.PipeContextProtocol.ReturnTypeInfo(
                        type = com.TTT.PipeContextProtocol.ParamType.String,
                        kotlinType = "kotlin.String",
                        isNullable = false,
                        description = ""
                    ),
                    description = ""
                )
            )

            val result = callPrivateMethod(McpStdioHost, "createHost") as McpServerHost
            val pcpContext = getPrivateField(result, "pcpContext") as PcpContext
            assertEquals(2, pcpContext.tpipeOptions.size, "Should have 2 tpipeOptions for 2 registered functions")
            assertTrue(pcpContext.tpipeOptions.any { it.functionName == "func1" }, "Should contain func1")
            assertTrue(pcpContext.tpipeOptions.any { it.functionName == "func2" }, "Should contain func2")
        } finally {
            FunctionRegistry.clear()
        }
    }
}