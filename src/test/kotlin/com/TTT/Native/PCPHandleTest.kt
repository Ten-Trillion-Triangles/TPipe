package com.TTT.Native

import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.FunctionSignature
import com.TTT.PipeContextProtocol.ParameterInfo
import com.TTT.PipeContextProtocol.ParamType
import com.TTT.PipeContextProtocol.ReturnTypeInfo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD tests for PCPHandle — the C ABI wrapper around TPipe's PCP
 * (Pipe Context Protocol) function invocation system.
 *
 * RED PHASE: These tests define expected behavior. Some may fail until the full
 * ABI implementation is complete. Following TDD: RED first, then GREEN.
 */
class PCPHandleTest {

    @BeforeTest
    fun setUp() {
        // Start each test from a clean registry to keep assertions deterministic.
        FunctionRegistry.clear()
    }

    @AfterTest
    fun tearDown() {
        FunctionRegistry.clear()
    }

    //==========================================================================
    // Creation and Type Discriminator
    //==========================================================================

    @Test
    fun testCreate() {
        val handle = PCPHandle()
        assertNotNull(handle, "PCPHandle() default constructor should return a non-null handle")
    }

    @Test
    fun testTypeDiscriminator() {
        // PCP discriminator must match HandleTypes.PCP (=9)
        assertEquals(9, HandleTypes.PCP, "HandleTypes.PCP should be 9")
    }

    //==========================================================================
    // execute() Success and Failure Paths
    //==========================================================================

    @Test
    fun testExecuteSuccess() {
        val signature = FunctionSignature(
            name = "pcp_test_echo",
            parameters = listOf(
                ParameterInfo(
                    name = "input",
                    type = ParamType.String,
                    kotlinType = "kotlin.String",
                    isOptional = false,
                    defaultValue = null,
                    enumValues = emptyList(),
                    description = ""
                )
            ),
            returnType = ReturnTypeInfo(
                type = ParamType.String,
                kotlinType = "kotlin.String",
                isNullable = false,
                description = ""
            ),
            description = ""
        )
        FunctionRegistry.registerLambda(
            name = "pcp_test_echo",
            lambda = { params: Map<String, Any?> -> params["input"] ?: "" },
            signature = signature
        )

        val handle = PCPHandle()
        val result = handle.execute("pcp_test_echo", mapOf("input" to "hello"))
        assertTrue(result is PCPHandle.Result.Success,
            "execute should return Success for a valid registered function, got: $result")
        assertEquals("hello", (result as PCPHandle.Result.Success).returnValue,
            "success returnValue should match input")
    }

    @Test
    fun testExecuteFailure() {
        val handle = PCPHandle()
        val result = handle.execute("pcp_does_not_exist", mapOf("input" to "x"))
        assertTrue(result is PCPHandle.Result.Error,
            "execute should return Error for an unknown function, got: $result")
    }

    //==========================================================================
    // isFunctionRegistered() / getRegisteredFunctions()
    //==========================================================================

    @Test
    fun testIsFunctionRegistered() {
        val signature = FunctionSignature(
            name = "pcp_test_registered",
            parameters = emptyList(),
            returnType = ReturnTypeInfo(
                type = ParamType.String,
                kotlinType = "kotlin.String",
                isNullable = false,
                description = ""
            ),
            description = ""
        )
        FunctionRegistry.registerLambda(
            name = "pcp_test_registered",
            lambda = { _: Map<String, Any?> -> "ok" },
            signature = signature
        )

        val handle = PCPHandle()
        assertTrue(handle.isFunctionRegistered("pcp_test_registered"),
            "isFunctionRegistered should return true for a registered function")
        assertFalse(handle.isFunctionRegistered("pcp_definitely_not_registered"),
            "isFunctionRegistered should return false for an unknown function")
    }

    @Test
    fun testGetRegisteredFunctions() {
        val signature = FunctionSignature(
            name = "pcp_test_listed",
            parameters = emptyList(),
            returnType = ReturnTypeInfo(
                type = ParamType.String,
                kotlinType = "kotlin.String",
                isNullable = false,
                description = ""
            ),
            description = ""
        )
        FunctionRegistry.registerLambda(
            name = "pcp_test_listed",
            lambda = { _: Map<String, Any?> -> "ok" },
            signature = signature
        )

        val handle = PCPHandle()
        val names = handle.getRegisteredFunctions()
        assertTrue("pcp_test_listed" in names,
            "registered function list should contain pcp_test_listed, got: $names")
    }

    //==========================================================================
    // validateParameters()
    //==========================================================================

    @Test
    fun testValidateParameters() {
        val signature = FunctionSignature(
            name = "pcp_test_validate",
            parameters = listOf(
                ParameterInfo(
                    name = "requiredParam",
                    type = ParamType.String,
                    kotlinType = "kotlin.String",
                    isOptional = false,
                    defaultValue = null,
                    enumValues = emptyList(),
                    description = ""
                )
            ),
            returnType = ReturnTypeInfo(
                type = ParamType.String,
                kotlinType = "kotlin.String",
                isNullable = false,
                description = ""
            ),
            description = ""
        )
        FunctionRegistry.registerLambda(
            name = "pcp_test_validate",
            lambda = { _: Map<String, Any?> -> "ok" },
            signature = signature
        )

        val handle = PCPHandle()

        val validJson = handle.validateParameters(
            "pcp_test_validate",
            mapOf("requiredParam" to "value")
        )
        assertTrue(validJson.contains("\"valid\":true"),
            "validate should return valid JSON for complete params, got: $validJson")

        val missingJson = handle.validateParameters(
            "pcp_test_validate",
            emptyMap()
        )
        assertTrue(missingJson.contains("\"valid\":false"),
            "validate should return invalid for missing required params, got: $missingJson")

        val unknownJson = handle.validateParameters("pcp_unknown_function", mapOf())
        assertTrue(unknownJson.contains("\"valid\":false"),
            "validate should return invalid for unknown function, got: $unknownJson")
    }

    //==========================================================================
    // HandleRegistry Integration
    //==========================================================================

    @Test
    fun testRefCounting() {
        val handle = PCPHandle()
        val handleId = HandleRegistry.allocate(HandleTypes.PCP, handle)
        assertTrue(handleId >= 0, "allocate() should return non-negative handle, got: $handleId")
        assertEquals(1, HandleRegistry.getRefCount(handleId),
            "newly allocated PCPHandle should have refCount=1")

        val addResult = HandleRegistry.addRef(handleId)
        assertEquals(0, addResult, "addRef should return 0 on success")
        assertEquals(2, HandleRegistry.getRefCount(handleId),
            "refCount should be 2 after addRef")

        HandleRegistry.release(handleId)
        assertEquals(1, HandleRegistry.getRefCount(handleId),
            "refCount should be 1 after one release")

        HandleRegistry.release(handleId)
        assertEquals(false, HandleRegistry.isValid(handleId),
            "handle should be invalid after final release")
    }
}
