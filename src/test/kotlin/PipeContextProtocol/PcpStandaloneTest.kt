package com.TTT.PipeContextProtocol

import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PRegistry
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PcpStandaloneTest
{
    fun testFunction(input: String): String
    {
        return "Received: $input"
    }

    @Test
    fun testPcPRequestSerialization() {
        val request = PcPRequest(
            tPipeContextOptions = TPipeContextOptions().apply {
                functionName = "test"
            }
        )
        val json = serialize(request)
        println("Serialized: $json")
        val deserialized = deserialize<PcPRequest>(json)
        assertEquals("test", deserialized?.tPipeContextOptions?.functionName)
    }

    @Test
    fun testPcpRegistryExecution() = runBlocking {
        FunctionRegistry.clear()
        FunctionRegistry.registerFunction("testFunc", ::testFunction)

        val context = PcpContext().apply {
            addTPipeOption(TPipeContextOptions().apply {
                functionName = "testFunc"
            })
        }
        PcpRegistry.updateGlobalContext(context)

        val request = PcPRequest(
            tPipeContextOptions = TPipeContextOptions().apply {
                functionName = "testFunc"
            },
            argumentsOrFunctionParams = listOf("Hello")
        )

        val result = PcpRegistry.executeRequest(request)
        assertTrue(result.success)
        assertEquals("Received: Hello", result.output)

        FunctionRegistry.clear()
    }

    @Test
    fun testPcpRegistrySecurityEnforcement() = runBlocking {
        FunctionRegistry.clear()
        FunctionRegistry.registerFunction("secretFunc", ::testFunction)

        // Context does NOT allow secretFunc
        val context = PcpContext().apply {
            addTPipeOption(TPipeContextOptions().apply {
                functionName = "otherFunc"
            })
        }
        PcpRegistry.updateGlobalContext(context)

        val request = PcPRequest(
            tPipeContextOptions = TPipeContextOptions().apply {
                functionName = "secretFunc"
            },
            argumentsOrFunctionParams = listOf("secret")
        )

        val result = PcpRegistry.executeRequest(request)
        assertFalse(result.success)
        assertTrue(result.error?.contains("not in context whitelist") == true)

        FunctionRegistry.clear()
    }

    @Test
    fun testPcpStdioHostRunOnce() = runBlocking {
        FunctionRegistry.clear()
        FunctionRegistry.registerFunction("stdioFunc", ::testFunction)

        val context = PcpContext().apply {
            addTPipeOption(TPipeContextOptions().apply {
                functionName = "stdioFunc"
            })
        }
        PcpRegistry.updateGlobalContext(context)

        val request = PcPRequest().apply {
            tPipeContextOptions = TPipeContextOptions().apply {
                functionName = "stdioFunc"
            }
            argumentsOrFunctionParams = listOf("stdio-test")
        }
        // Force single line JSON for Stdio host test
        val inputJson = serialize(request, true).replace("\n", " ").replace("\r", " ") + "\n"

        val oldIn = System.`in`
        val oldOut = System.out

        try {
            val bis = ByteArrayInputStream(inputJson.toByteArray())
            System.setIn(bis)

            val baos = ByteArrayOutputStream()
            val ps = PrintStream(baos)
            System.setOut(ps)

            PcpStdioHost.runOnce()

            val output = baos.toString().trim()
            println("RAW OUTPUT START")
            println(output)
            println("RAW OUTPUT END")
            val result = deserialize<PcpExecutionResult>(output)

            assertTrue(result?.success == true, "Result should be success. Output was: $output")
            assertEquals("Received: stdio-test", result?.results?.get(0)?.output)

        } finally {
            System.setIn(oldIn)
            System.setOut(oldOut)
            FunctionRegistry.clear()
        }
    }

    @Test
    fun testPcpStdioHostAuth() = runBlocking {
        // Setup global auth that always fails
        val oldAuth = P2PRegistry.globalAuthMechanism
        P2PRegistry.globalAuthMechanism = { _ -> false }

        val oldIn = System.`in`
        val oldOut = System.out

        try {
            val request = PcPRequest(tPipeContextOptions = TPipeContextOptions().apply { functionName = "any" })
            val bis = ByteArrayInputStream(serialize(request).toByteArray())
            System.setIn(bis)

            val baos = ByteArrayOutputStream()
            System.setOut(PrintStream(baos))

            PcpStdioHost.runOnce()

            val output = baos.toString().trim()
            println("RAW AUTH OUTPUT: $output")
            val result = deserialize<PcpExecutionResult>(output)
            assertFalse(result?.success == true, "Result should NOT be success. Output was: $output")
            assertTrue(result?.errors?.get(0)?.contains("Unauthorized") == true)

        } finally {
            System.setIn(oldIn)
            System.setOut(oldOut)
            P2PRegistry.globalAuthMechanism = oldAuth
        }
    }
}
