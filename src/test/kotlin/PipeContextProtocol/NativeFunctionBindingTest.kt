package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit test for native function binding system.
 * Tests complete workflow from function binding to execution with return value validation.
 */
class NativeFunctionBindingTest 
{
    enum class AccessLevel
    {
        READ,
        WRITE
    }

    /**
     * Test function with multiple inputs and return value.
     * Calculates area of rectangle and returns formatted result.
     */
    fun calculateRectangleArea(width: Int, height: Int, unit: String): String 
    {
        val area = width * height
        return "Area: $area $unit"
    }
    
    /**
     * Test function that returns an integer.
     * Multiplies two numbers and returns the result.
     */
    fun multiplyNumbers(a: Int, b: Int): Int 
    {
        return a * b
    }

    /**
     * Test function with a Kotlin default parameter.
     */
    fun greet(name: String, punctuation: String = "!"): String
    {
        return "$name$punctuation"
    }

    /**
     * Test function that enforces enum handling and defaults.
     */
    fun assignRole(user: String, level: AccessLevel = AccessLevel.READ): String
    {
        return "$user:${level.name}"
    }
    
    @Test
    fun testNativeFunctionBinding() 
    {
        runBlocking {
            // Clear registry for clean test
            FunctionRegistry.clear()
            
            // 1. Create and bind native function
            val signature = FunctionRegistry.registerFunction("calculateArea", ::calculateRectangleArea)
            
            // Verify function was registered
            assertTrue(FunctionRegistry.getFunctionNames().contains("calculateArea"))
            
            // 2. Create PCP request JSON (simulating LLM request)
            val pcpRequest = PcPRequest(
                tPipeContextOptions = TPipeContextOptions().apply {
                    functionName = "calculateArea"
                },
                argumentsOrFunctionParams = listOf("10", "5", "sq_meters")
            )
            
            // 3. Process PCP request and call function natively
            val handler = PcpFunctionHandler()
            val response = handler.handleFunctionRequest(pcpRequest)
            
            // 4. Validate return value
            assertTrue(response.success, "Function execution should succeed")
            assertEquals("Area: 50 sq_meters", response.result, "Return value should match expected calculation")
            assertTrue(response.returnValueKey.isNotEmpty(), "Return value key should be generated")
            assertEquals(null, response.error, "No error should occur")
            
            // 5. Verify stored return value can be retrieved
            val storedValue = handler.getStoredReturnValue(response.returnValueKey)
            assertEquals("Area: 50 sq_meters", storedValue, "Stored return value should match")
            
            // Clean up
            handler.clearStoredReturnValues()
            FunctionRegistry.clear()
        }
    }

    @Test
    fun testDefaultParameterHandling()
    {
        runBlocking {
            FunctionRegistry.clear()

            FunctionRegistry.registerFunction("greet", ::greet)

            val handler = PcpFunctionHandler()
            val request = PcPRequest(
                tPipeContextOptions = TPipeContextOptions().apply {
                    functionName = "greet"
                },
                argumentsOrFunctionParams = listOf("Hello")
            )

            val response = handler.handleFunctionRequest(request)

            assertTrue(response.success, "Function execution should succeed with missing optional parameter")
            assertEquals("Hello!", response.result, "Default punctuation should be applied")

            handler.clearStoredReturnValues()
            FunctionRegistry.clear()
        }
    }

    @Test
    fun testEnumParameterValidation()
    {
        runBlocking {
            FunctionRegistry.clear()

            FunctionRegistry.registerFunction("assignRole", ::assignRole)

            val handler = PcpFunctionHandler()

            val successRequest = PcPRequest(
                tPipeContextOptions = TPipeContextOptions().apply {
                    functionName = "assignRole"
                },
                argumentsOrFunctionParams = listOf("alice", "WRITE")
            )

            val successResponse = handler.handleFunctionRequest(successRequest)
            assertTrue(successResponse.success, "Enum parameter should accept valid values")
            assertEquals("alice:WRITE", successResponse.result)

            val failureRequest = PcPRequest(
                tPipeContextOptions = TPipeContextOptions().apply {
                    functionName = "assignRole"
                },
                argumentsOrFunctionParams = listOf("bob", "EXECUTE")
            )

            val failureResponse = handler.handleFunctionRequest(failureRequest)
            assertTrue(!failureResponse.success, "Enum parameter should reject invalid values")
            assertTrue(
                failureResponse.error?.contains("Invalid value 'EXECUTE' for parameter 'level'") == true,
                "Expected enum validation error, got: ${failureResponse.error}"
            )

            handler.clearStoredReturnValues()
            FunctionRegistry.clear()
        }
    }

    @Test
    fun testMissingRequiredParameterFails()
    {
        runBlocking {
            FunctionRegistry.clear()

            FunctionRegistry.registerFunction("multiply", ::multiplyNumbers)

            val handler = PcpFunctionHandler()
            val request = PcPRequest(
                tPipeContextOptions = TPipeContextOptions().apply {
                    functionName = "multiply"
                },
                argumentsOrFunctionParams = listOf("4")
            )

            val response = handler.handleFunctionRequest(request)

            assertTrue(!response.success, "Missing required argument should fail validation")
            assertTrue(
                response.error?.contains("Missing required parameter: b") == true,
                "Expected missing parameter error, got: ${response.error}"
            )

            handler.clearStoredReturnValues()
            FunctionRegistry.clear()
        }
    }

    @Test
    fun testTypeConversionFailure()
    {
        runBlocking {
            FunctionRegistry.clear()

            FunctionRegistry.registerFunction("multiply", ::multiplyNumbers)

            val handler = PcpFunctionHandler()
            val request = PcPRequest(
                tPipeContextOptions = TPipeContextOptions().apply {
                    functionName = "multiply"
                },
                argumentsOrFunctionParams = listOf("foo", "2")
            )

            val response = handler.handleFunctionRequest(request)

            assertTrue(!response.success, "Invalid type conversion should fail")
            assertTrue(
                response.error?.contains("Cannot convert 'foo' to kotlin.Int") == true,
                "Expected conversion error, got: ${response.error}"
            )

            handler.clearStoredReturnValues()
            FunctionRegistry.clear()
        }
    }

    @Test
    fun testIntegerReturnFunction() 
    {
        runBlocking {
            // Clear registry for clean test
            FunctionRegistry.clear()
            
            // 1. Create and bind native function that returns int
            val signature = FunctionRegistry.registerFunction("multiply", ::multiplyNumbers)
            
            // Verify function was registered
            assertTrue(FunctionRegistry.getFunctionNames().contains("multiply"))
            
            // 2. Create PCP request JSON (simulating LLM request)
            val pcpRequest = PcPRequest(
                tPipeContextOptions = TPipeContextOptions().apply {
                    functionName = "multiply"
                },
                argumentsOrFunctionParams = listOf("7", "8")
            )
            
            // 3. Process PCP request and call function natively
            val handler = PcpFunctionHandler()
            val response = handler.handleFunctionRequest(pcpRequest)
            
            // 4. Validate return value
            assertTrue(response.success, "Function execution should succeed")
            assertEquals("56", response.result, "Return value should be 56 as string")
            assertTrue(response.returnValueKey.isNotEmpty(), "Return value key should be generated")
            assertEquals(null, response.error, "No error should occur")
            
            // 5. Verify stored return value can be retrieved as integer
            val storedValue = handler.getStoredReturnValue(response.returnValueKey)
            assertEquals(56, storedValue, "Stored return value should be integer 56")
            
            // Clean up
            handler.clearStoredReturnValues()
            FunctionRegistry.clear()
        }
    }
}
