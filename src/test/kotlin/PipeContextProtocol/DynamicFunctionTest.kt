package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicFunctionTest
{
    @AfterEach
    fun cleanup()
    {
        FunctionRegistry.clear()
    }

    @Test
    fun invokesDynamicFunctionAfterNormalValidation() = runBlocking {
        val signature = FunctionSignature(
            name = "remote_tool_test",
            parameters = listOf(
                ParameterInfo(
                    name = "mode",
                    type = ParamType.Enum,
                    kotlinType = "kotlin.String",
                    enumValues = listOf("safe", "fast")
                )
            ),
            returnType = ReturnTypeInfo(ParamType.String, "kotlin.String")
        )
        val received = mutableMapOf<String, String>()
        FunctionRegistry.registerDynamicFunction("remote_tool_test", signature) {
            received.putAll(it)
            "ok:${it["mode"]}"
        }

        val invocationResult = FunctionInvoker().invoke("remote_tool_test", mapOf("mode" to "fast"))

        assertTrue(invocationResult.success)
        assertEquals("ok:fast", invocationResult.returnValueAsString)
        assertEquals(mapOf("mode" to "fast"), received)
    }

    @Test
    fun rejectsInvalidEnumBeforeDynamicHandlerRuns() = runBlocking {
        val signature = FunctionSignature(
            name = "remote_tool_enum_test",
            parameters = listOf(
                ParameterInfo(
                    name = "mode",
                    type = ParamType.Enum,
                    kotlinType = "kotlin.String",
                    enumValues = listOf("safe", "fast")
                )
            ),
            returnType = ReturnTypeInfo(ParamType.String, "kotlin.String")
        )
        var invoked = false
        FunctionRegistry.registerDynamicFunction("remote_tool_enum_test", signature) {
            invoked = true
            "unexpected"
        }

        val invocationResult = FunctionInvoker().invoke("remote_tool_enum_test", mapOf("mode" to "unsafe"))

        assertFalse(invocationResult.success)
        assertFalse(invoked)
        assertTrue(invocationResult.error.orEmpty().contains("Invalid value 'unsafe'"))
    }
}
