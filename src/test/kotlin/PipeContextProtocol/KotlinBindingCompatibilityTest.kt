package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies explicit binding gates, live object identity, and collision order.
 */
class KotlinBindingCompatibilityTest
{
    @Test
    fun exposedBindingMutatesTheOriginalHostObject()
    {
        val fixture = KotlinBindingFixture()
        val executor = KotlinExecutor()
        executor.registerBinding("fixture", fixture)
        val result = runBlocking {
            executor.execute(
                PcPRequest(argumentsOrFunctionParams = listOf("fixture.increment(); fixture.value")),
                bindingContext("fixture")
            )
        }

        assertTrue(result.success, "Execution should be successful: ${result.error}; elapsed=${result.executionTimeMs}ms")
        assertEquals("Result: 1", result.output)
        assertEquals(1, fixture.value)
    }

    @Test
    fun registeredBindingMustBeExplicitlyExposed()
    {
        val executor = KotlinExecutor()
        executor.registerBinding("fixture", KotlinBindingFixture())
        val result = runBlocking {
            executor.execute(
                PcPRequest(argumentsOrFunctionParams = listOf("fixture.value")),
                bindingContext()
            )
        }

        assertFalse(result.success)
        assertTrue(result.error?.startsWith("Kotlin execution failed: ") == true)
    }

    @Test
    fun hostAccessMustBeEnabledEvenWhenBindingIsExposed()
    {
        val executor = KotlinExecutor()
        executor.registerBinding("fixture", KotlinBindingFixture())
        val context = bindingContext("fixture").apply {
            kotlinOptions.allowHostApplicationAccess = false
        }
        val result = runBlocking {
            executor.execute(
                PcPRequest(argumentsOrFunctionParams = listOf("fixture.value")),
                context
            )
        }

        assertFalse(result.success)
        assertTrue(result.error?.startsWith("Kotlin execution failed: ") == true)
    }

    @Test
    fun customBindingOverwritesReservedIntrospectionName()
    {
        val collision = KotlinBindingFixture(value = 7)
        val executor = KotlinExecutor()
        executor.registerBinding("PcpRegistry", collision)
        val result = runBlocking {
            executor.execute(
                PcPRequest(argumentsOrFunctionParams = listOf("PcpRegistry.value")),
                bindingContext("PcpRegistry").apply {
                    kotlinOptions.allowTpipeIntrospection = true
                }
            )
        }

        assertTrue(result.success, "Execution should be successful: ${result.error}; elapsed=${result.executionTimeMs}ms")
        assertEquals("Result: 7", result.output)
    }

    @Test
    fun introspectionUsesTheLiveContextObject()
    {
        val executor = KotlinExecutor()
        // Initialize the reusable compiler before measuring the request-level
        // 1234ms timeout. The target invocation still creates a fresh host,
        // compilation configuration, evaluation configuration, and binding
        // snapshot, so this does not cache a compiled script or result.
        val warmup = runBlocking {
            executor.execute(
                PcPRequest(argumentsOrFunctionParams = listOf("1")),
                PcpContext()
            )
        }
        assertTrue(warmup.success, "Compiler warmup failed: ${warmup.error}")
        val secondWarmup = runBlocking {
            executor.execute(
                PcPRequest(argumentsOrFunctionParams = listOf("2 + 2")),
                PcpContext()
            )
        }
        assertTrue(secondWarmup.success, "Second compiler warmup failed: ${secondWarmup.error}")
        val context = PcpContext().apply {
            kotlinOptions.environmentVariables["liveMarker"] = "1234"
            kotlinOptions.timeoutMs = 1234
        }
        val result = runBlocking {
            executor.execute(
                PcPRequest(
                    argumentsOrFunctionParams = listOf(
                        "PcpContext.kotlinOptions.environmentVariables[\"liveMarker\"]"
                    )
                ),
                context
            )
        }

        assertTrue(
            result.success,
            "Execution should be successful: ${result.error}; elapsed=${result.executionTimeMs}ms"
        )
        assertEquals("Result: 1234", result.output)
    }

    @Test
    fun scriptDeclarationsDoNotSurviveTheNextRequest()
    {
        val executor = KotlinExecutor()
        val first = runBlocking {
            executor.execute(
                PcPRequest(argumentsOrFunctionParams = listOf("val privateState = 4", "privateState")),
                PcpContext()
            )
        }
        val second = runBlocking {
            executor.execute(
                PcPRequest(argumentsOrFunctionParams = listOf("privateState")),
                PcpContext()
            )
        }

        assertTrue(first.success, "First execution should be successful: ${first.error}")
        assertFalse(second.success)
    }

    private fun bindingContext(vararg exposedNames: String): PcpContext
    {
        return PcpContext().apply {
            kotlinOptions.allowTpipeIntrospection = false
            kotlinOptions.allowHostApplicationAccess = true
            exposedNames.forEach { name ->
                kotlinOptions.exposedBindings[name] = name
            }
        }
    }
}

/**
 * Public test fixture whose named type can be resolved by the script compiler.
 */
class KotlinBindingFixture(var value: Int = 0)
{
    /** Increments [value] on the same object instance exposed to the script. */
    fun increment()
    {
        value += 1
    }
}
