package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinScriptOutputCompatibilityTest
{
    @Test
    fun printFunctionsRemainUncapturedAsInTheExistingHost()
    {
        val printResult = runBlocking {
            KotlinExecutor().execute(
                PcPRequest(argumentsOrFunctionParams = listOf("print(\"a\"); println(\"b\"); \"value\"")),
                PcpContext()
            )
        }
        assertFalse(printResult.success)
        assertTrue(printResult.error?.startsWith("Kotlin execution failed: ") == true)
        assertEquals("", printResult.output)
        assertEquals(null, printResult.outputBuffer)
    }

    @Test
    fun directSystemStreamsDoNotPopulateTheLegacyOutputString()
    {
        val systemResult = runBlocking {
            KotlinExecutor().execute(
                PcPRequest(argumentsOrFunctionParams = listOf("System.out.print(\"c\"); System.err.print(\"d\"); \"value\"")),
                PcpContext()
            )
        }
        assertTrue(systemResult.success, "Execution should be successful: ${systemResult.error}")
        assertEquals("Result: value", systemResult.output)
        val outputBuffer = assertNotNull(systemResult.outputBuffer)
        assertEquals("", outputBuffer.stdout)
        assertEquals("", outputBuffer.stderr)
    }

    @Test
    fun printTextInsideStringsAndCommentsIsNotTreatedAsAnOutputCall()
    {
        val result = runBlocking {
            KotlinExecutor().execute(
                PcPRequest(
                    argumentsOrFunctionParams = listOf(
                        "// print(\"comment\")",
                        "\"print(\\\"literal\\\")\""
                    )
                ),
                PcpContext()
            )
        }

        assertTrue(result.success, "Literal text should compile: ${result.error}")
        assertEquals("Result: print(\"literal\")", result.output)
    }

    @Test
    fun userDefinedPrintFunctionIsNotRejectedByOutputCompatibilityGuard()
    {
        val result = runBlocking {
            KotlinExecutor().execute(
                PcPRequest(
                    argumentsOrFunctionParams = listOf(
                        "fun print(value: String) {}",
                        "print(\"ignored\")",
                        "\"value\""
                    )
                ),
                PcpContext()
            )
        }

        assertTrue(result.success, "User-defined print should compile: ${result.error}")
        assertEquals("Result: value", result.output)
    }

    @Test
    fun exposedPrintBindingIsNotRejectedByOutputCompatibilityGuard()
    {
        val executor = KotlinExecutor()
        executor.registerBinding("print", PrintBinding())
        val context = PcpContext().apply {
            kotlinOptions.allowHostApplicationAccess = true
            kotlinOptions.exposedBindings["print"] = "consumer print binding"
        }
        val result = runBlocking {
            executor.execute(
                PcPRequest(argumentsOrFunctionParams = listOf("print(\"ignored\"); \"value\"")),
                context
            )
        }

        assertTrue(result.success, "Exposed print binding should compile: ${result.error}")
        assertEquals("Result: value", result.output)
    }
}

/** Named host binding used to prove that output names remain shadowable. */
class PrintBinding
{
    /** Accepts the same call shape as Kotlin's output helper without emitting output. */
    operator fun invoke(value: String)
    {
        // Deliberately empty: this binding is only a compatibility fixture.
    }
}
