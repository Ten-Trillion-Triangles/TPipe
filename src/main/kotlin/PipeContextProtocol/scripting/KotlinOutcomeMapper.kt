package com.TTT.PipeContextProtocol.scripting

import com.TTT.PipeContextProtocol.BufferedOutput
import com.TTT.PipeContextProtocol.PcpRequestResult
import com.TTT.PipeContextProtocol.Transport

/**
 * Converts backend outcomes into the unchanged public PCP result shape.
 */
internal object KotlinOutcomeMapper
{
    /**
     * Maps one backend outcome to a serialized PCP result.
     *
     * @param outcome Backend outcome to map.
     * @param executionTimeMs Elapsed execution time measured by [com.TTT.PipeContextProtocol.KotlinExecutor].
     * @return Public Kotlin transport result.
     */
    fun toResult(outcome: KotlinScriptOutcome, executionTimeMs: Long): PcpRequestResult
    {
        return when(outcome)
        {
            is KotlinScriptOutcome.Success -> mapSuccess(outcome, executionTimeMs)
            is KotlinScriptOutcome.Failure -> PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = executionTimeMs,
                transport = Transport.Kotlin,
                error = "Kotlin execution failed: ${KotlinDiagnosticNormalizer.publicMessage(outcome)}"
            )
        }
    }

    private fun mapSuccess(outcome: KotlinScriptOutcome.Success, executionTimeMs: Long): PcpRequestResult
    {
        val resultLine = if(outcome.hasResultValue)
        {
            "Result: ${outcome.value}"
        }
        else
        {
            ""
        }
        val output = when
        {
            outcome.stdout.isNotEmpty() && resultLine.isNotEmpty() -> "${outcome.stdout}\n$resultLine"
            resultLine.isNotEmpty() -> resultLine
            else -> outcome.stdout
        }

        return PcpRequestResult(
            success = true,
            output = output,
            executionTimeMs = executionTimeMs,
            transport = Transport.Kotlin,
            outputBuffer = BufferedOutput(
                stdout = outcome.stdout,
                stderr = outcome.stderr,
                binary = null,
                totalBytes = (outcome.stdout.length + outcome.stderr.length).toLong(),
                truncated = false
            )
        )
    }
}
