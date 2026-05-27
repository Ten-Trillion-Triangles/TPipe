package com.TTT.Pipeline

import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.TPipeContextOptions
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit test for PathObject PCP function binding.
 * Verifies that bindFunction registers the function and populates pcpSchema as serialized JSON.
 */
class PumpStationPathObjectBindingTest {

    /**
     * Test function: validates a user and returns a formatted string.
     */
    fun validateUser(userId: String, role: String): String {
        return "User: $userId, Role: $role"
    }

    /**
     * Test function: processes a code review and returns the result.
     */
    fun processCodeReview(repositoryName: String, prNumber: Int, approvalStatus: String): String {
        return "Repo: $repositoryName, PR #$prNumber: $approvalStatus"
    }

    @Test
    fun testBindFunctionPopulatesPcpSchema() {
        runBlocking {
            // Clear registry for clean test
            FunctionRegistry.clear()

            // Create PathObject and bind a function
            val path = PathObject()
            path.pathName = "code_review"
            path.pathDescription = "Process a code review for a given repository"

            // Bind function - this registers to FunctionRegistry and populates pcpSchema
            path.bindFunction("processCodeReview", ::processCodeReview)

            // Verify pcpSchema was populated
            assertNotNull(path.pcpSchema, "pcpSchema must not be null after bindFunction")
            assertTrue(path.pcpSchema!!.tpipeOptions.isNotEmpty(), "tpipeOptions must have at least one entry")

            // Verify the function is in the registry
            assertTrue(
                FunctionRegistry.getFunctionNames().contains("processCodeReview"),
                "Function must be registered in FunctionRegistry"
            )

            // Verify the pcpSchema contains correct function name
            val pcpSchema = path.pcpSchema!!
            val tpipeOption = pcpSchema.tpipeOptions.find { it.functionName == "processCodeReview" }
            assertNotNull(tpipeOption, "TPipeContextOptions for processCodeReview must exist")
            assertEquals("processCodeReview", tpipeOption!!.functionName)

            // Verify pcpSchema is valid JSON when serialized
            val serialized = serialize(pcpSchema, false)
            assertTrue(serialized.isNotEmpty(), "Serialized pcpSchema must not be empty")
            assertTrue(serialized.contains("processCodeReview"), "Serialized JSON must contain function name")
        }
    }

    @Test
    fun testBindFunctionWithMultipleFunctions() {
        runBlocking {
            FunctionRegistry.clear()

            val path = PathObject()
            path.pathName = "user_validation"
            path.pathDescription = "Validate user credentials and assign roles"

            // Bind first function
            path.bindFunction("validateUser", ::validateUser)
            // Bind second function on same path
            path.bindFunction("processCodeReview", ::processCodeReview)

            // Verify both functions are registered
            val functionNames = FunctionRegistry.getFunctionNames()
            assertTrue(functionNames.contains("validateUser"), "validateUser must be registered")
            assertTrue(functionNames.contains("processCodeReview"), "processCodeReview must be registered")

            // Verify pcpSchema contains both
            assertEquals(2, path.pcpSchema!!.tpipeOptions.size, "pcpSchema must have 2 tpipeOptions")
        }
    }

    @Test
    fun testBindFunctionPreservesExistingPcpSchema() {
        runBlocking {
            FunctionRegistry.clear()

            val path = PathObject()
            path.pathName = "mixed_path"
            path.pathDescription = "A path with multiple invocation modes"

            // Manually set a PcpContext (simulating external PCP tool)
            val existingContext = PcpContext()
            val existingTool = TPipeContextOptions().apply {
                functionName = "external_tool"
                description = "An externally registered tool"
            }
            existingContext.tpipeOptions.add(existingTool)
            path.pcpSchema = existingContext

            // Bind a new function
            path.bindFunction("validateUser", ::validateUser)

            // Verify pcpSchema still contains the external tool AND the new function
            assertEquals(2, path.pcpSchema!!.tpipeOptions.size, "pcpSchema must contain both external_tool and validateUser")
            assertTrue(
                path.pcpSchema!!.tpipeOptions.any { it.functionName == "external_tool" },
                "external_tool must still be present"
            )
            assertTrue(
                path.pcpSchema!!.tpipeOptions.any { it.functionName == "validateUser" },
                "validateUser must be present"
            )
        }
    }

    @Test
    fun testBindFunctionMatchesPipeBindNativeFunctionPattern() {
        runBlocking {
            // This test verifies that PathObject.bindFunction uses the same pattern as
            // Pipe.bindNativeFunction (from PcpFunctionExtensions.kt)
            // Both should produce functionally equivalent pcpSchema structures

            FunctionRegistry.clear()

            // Create PathObject
            val path = PathObject()
            path.pathName = "parallel_path"
            path.bindFunction("validateUser", ::validateUser)

            // Verify the tpipeOption structure matches what Pipe.bindNativeFunction produces
            val tpipeOption = path.pcpSchema!!.tpipeOptions.first()
            assertEquals("validateUser", tpipeOption.functionName)
            assertTrue(tpipeOption.params.containsKey("userId"), "Must have userId param")
            assertTrue(tpipeOption.params.containsKey("role"), "Must have role param")
        }
    }
}