package com.TTT.PipeContextProtocol

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that concurrent K2 evaluations keep bindings, declarations, and
 * diagnostics local to their individual dispatcher jobs.
 */
class KotlinScriptConcurrencyTest
{
    @Test
    fun `executor keeps twenty independent bindings and one compile failure isolated`() = runBlocking {
        val executor = KotlinExecutor()
        val requests = (0 until 20).map { index ->
            val bindingName = "concurrentBinding$index"
            executor.registerBinding(bindingName, KotlinConcurrencyBinding(index))
            PcPRequest(
                kotlinContextOptions = KotlinContext(cinit = true).apply {
                    allowHostApplicationAccess = true
                    exposedBindings[bindingName] = "test binding"
                },
                argumentsOrFunctionParams = listOf("$bindingName.value")
            )
        } + PcPRequest(
            kotlinContextOptions = KotlinContext(cinit = true),
            argumentsOrFunctionParams = listOf("val broken: = 1")
        )

        val results = coroutineScope {
            requests.map { request ->
                async { executor.execute(request, PcpContext()) }
            }.awaitAll()
        }

        assertEquals(21, results.size)
        assertEquals(1, results.count { !it.success })
        assertTrue(results.take(20).all { it.success }, "successful jobs: ${results.filterNot { it.success }}")
        results.take(20).forEachIndexed { index, requestResult ->
            assertEquals("Result: $index", requestResult.output)
        }
        assertTrue(results.last().error?.contains("Kotlin") == true)
    }

    @Test
    fun `separate executors do not cross contaminate the same binding name`() = runBlocking {
        val first = KotlinExecutor().apply {
            registerBinding("binding", KotlinConcurrencyBinding(11))
        }
        val second = KotlinExecutor().apply {
            registerBinding("binding", KotlinConcurrencyBinding(22))
        }
        val context = PcpContext().apply {
            kotlinOptions.allowHostApplicationAccess = true
            kotlinOptions.exposedBindings["binding"] = "test binding"
        }
        val request = PcPRequest(
            kotlinContextOptions = KotlinContext(cinit = true),
            argumentsOrFunctionParams = listOf("binding.value")
        )

        coroutineScope {
            val results = listOf(
                async { first.execute(request, context) },
                async { second.execute(request, context) }
            ).awaitAll()
            assertEquals(setOf("Result: 11", "Result: 22"), results.map { it.output }.toSet())
            assertTrue(results.all { it.success })
        }
    }
}

/** Mutable binding with a stable public property for script access. */
class KotlinConcurrencyBinding(val value: Int)
