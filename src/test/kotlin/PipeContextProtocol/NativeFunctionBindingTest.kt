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