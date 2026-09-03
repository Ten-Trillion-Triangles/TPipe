package com.TTT.PipeContextProtocol.scripting

import com.TTT.PipeContextProtocol.KotlinContext
import com.TTT.PipeContextProtocol.KotlinExecutor
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.PcPRequest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compares the final K2 backend against the committed compatibility corpus. */
class KotlinScriptGoldenTest
{
    @Test
    fun `K2 outcomes match committed canonical PCP results`() = runBlocking {
        val cases = KotlinScriptCompatibilityCorpus.cases().associateBy { it.id }
        val goldens = KotlinScriptCompatibilityCorpus.goldens()
        val executor = KotlinExecutor()

        goldens.forEach { golden ->
            val scriptCase = checkNotNull(cases[golden.id]) { "Missing case for ${golden.id}" }
            val request = PcPRequest(
                kotlinContextOptions = KotlinContext(cinit = true).apply {
                    if(scriptCase.mode == "security")
                    {
                        allowTpipeIntrospection = false
                    }
                },
                argumentsOrFunctionParams = listOf(scriptCase.source)
            )
            val execution = executor.execute(request, PcpContext())

            assertEquals(golden.success, execution.success, golden.id)
            assertEquals(golden.output, execution.output, golden.id)
            assertEquals(golden.stdout, execution.outputBuffer?.stdout ?: "", golden.id)
            assertEquals(golden.stderr, execution.outputBuffer?.stderr ?: "", golden.id)
            if(golden.errorPrefix != null)
            {
                assertTrue(execution.error?.startsWith(golden.errorPrefix) == true, golden.id)
            }
        }
    }
}
