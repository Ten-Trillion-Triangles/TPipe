package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class PcpIntegrationTest
{
    @Test
    fun testDispatcherRouting() = runBlocking {
        val dispatcher = PcpExecutionDispatcher()
        val context = PcpContext()

        // Test Kotlin routing
        val kotlinRequest = PcPRequest(
            argumentsOrFunctionParams = listOf("println(\"Routing to Kotlin\")"),
            kotlinContextOptions = KotlinContext(cinit = true).apply { allowIntrospection = true }
        )
        val kotlinResult = dispatcher.executeRequest(kotlinRequest, context)
        assertEquals(Transport.Kotlin, kotlinResult.transport)

        // Test JavaScript routing
        val jsRequest = PcPRequest(
            argumentsOrFunctionParams = listOf("console.log(\"Routing to JS\")"),
            javascriptContextOptions = JavaScriptContext(cinit = true).apply { nodePath = "node" }
        )
        val jsResult = dispatcher.executeRequest(jsRequest, context)
        assertEquals(Transport.JavaScript, jsResult.transport)
    }
}
