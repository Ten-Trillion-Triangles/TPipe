package com.TTT

import com.TTT.P2P.*
import com.TTT.PipeContextProtocol.*
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File

class P2PTransportImplementationTest {

    @Test
    fun testStdioOneShotTransport() = runBlocking {
        // Create a simple mock script that reads JSON from stdin and echoes a P2PResponse
        val scriptFile = File("mock_agent.sh")
        scriptFile.writeText("""
            #!/bin/bash
            # Read input (P2PRequest) - we don't actually need to parse it for the mock
            read input
            # Echo a valid P2PResponse JSON
            echo '{"output": {"text": "Hello from Stdio Agent"}, "rejection": null}'
        """.trimIndent())
        scriptFile.setExecutable(true)

        try {
            val request = P2PRequest().apply {
                transport = P2PTransport(
                    transportMethod = Transport.Stdio,
                    transportAddress = "./mock_agent.sh"
                )
            }

            val response = P2PRegistry.externalP2PCall(request)

            assertNotNull(response.output)
            assertEquals("Hello from Stdio Agent", response.output?.text)
            assertTrue(response.rejection == null || response.rejection?.errorType == P2PError.none)
        } finally {
            scriptFile.delete()
        }
    }

    @Test
    fun testStdioInteractiveTransport() = runBlocking {
        // Create a mock script for interactive session
        val scriptFile = File("mock_interactive_agent.sh")
        scriptFile.writeText("""
            #!/bin/bash
            while true; do
                read input
                if [ "${'$'}input" == "exit" ]; then
                    break
                fi
                echo '{"output": {"text": "Echo: Hello from Interactive Agent"}, "rejection": null}'
            done
        """.trimIndent())
        scriptFile.setExecutable(true)

        try {
            val request = P2PRequest().apply {
                transport = P2PTransport(
                    transportMethod = Transport.Stdio,
                    transportAddress = "./mock_interactive_agent.sh"
                )
                pcpRequest = PcPRequest(
                    stdioContextOptions = StdioContextOptions().apply {
                        executionMode = StdioExecutionMode.INTERACTIVE
                    }
                )
            }

            val response = P2PRegistry.externalP2PCall(request)

            assertNotNull(response.output)
            assertEquals("Echo: Hello from Interactive Agent", response.output?.text)

            // Clean up sessions
            StdioSessionManager.listActiveSessions().forEach {
                StdioSessionManager.closeSession(it)
            }
        } finally {
            scriptFile.delete()
        }
    }

    @Test
    fun testP2PStdioHostRunOnce() {
        val request = P2PRequest().apply {
            transport = P2PTransport(Transport.Tpipe, "test_agent")
            prompt.addText("ping")
        }
        val requestJson = serialize(request)

        val inputStream = requestJson.byteInputStream()
        val outputStream = java.io.ByteArrayOutputStream()

        val oldIn = System.`in`
        val oldOut = System.out

        try {
            System.setIn(inputStream)
            System.setOut(java.io.PrintStream(outputStream))

            // This will likely return an "agent not found" response which is a valid P2PResponse
            P2PStdioHost.runOnce()

            val output = outputStream.toString().trim()
            assertTrue(output.startsWith("{") && output.endsWith("}"))
        } finally {
            System.setIn(oldIn)
            System.setOut(oldOut)
        }
    }
}
