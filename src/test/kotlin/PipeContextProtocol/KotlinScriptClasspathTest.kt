package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies application-only types remain visible through the context loader. */
class KotlinScriptClasspathTest
{
    @Test
    fun `script can access a named host type loaded only by the application`() = runBlocking {
        val executor = KotlinExecutor()
        val fixture = KotlinApplicationOnlyFixture(13)
        executor.registerBinding("applicationFixture", fixture)
        val originalLoader = Thread.currentThread().contextClassLoader
        try
        {
            Thread.currentThread().contextClassLoader = KotlinScriptClasspathTest::class.java.classLoader
            val context = PcpContext().apply {
                kotlinOptions.allowHostApplicationAccess = true
                kotlinOptions.exposedBindings["applicationFixture"] = "application-only fixture"
            }
            val request = PcPRequest(
                kotlinContextOptions = KotlinContext(cinit = true),
                argumentsOrFunctionParams = listOf("applicationFixture.value")
            )
            val execution = executor.execute(request, context)
            assertTrue(execution.success, "Classpath execution failed: ${execution.error}")
            assertEquals("Result: 13", execution.output)
        }
        finally
        {
            Thread.currentThread().contextClassLoader = originalLoader
        }
    }
}

/** Type compiled only with the host application and not part of TPipe main. */
class KotlinApplicationOnlyFixture(val value: Int)
