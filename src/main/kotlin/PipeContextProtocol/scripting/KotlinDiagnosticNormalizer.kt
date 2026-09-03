package com.TTT.PipeContextProtocol.scripting

import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode

/**
 * Reduces compiler and runtime diagnostics to deterministic, public-safe text.
 */
internal object KotlinDiagnosticNormalizer
{
    private const val SOURCE_NAME = "pcp-script.tpipe.kts"

    /**
     * Normalizes error diagnostics while preserving their source order.
     *
     * @param diagnostics Compiler reports returned by the scripting host.
     * @return Stable diagnostics with duplicate adjacent reports removed.
     */
    fun normalize(diagnostics: List<ScriptDiagnostic>): List<NormalizedKotlinDiagnostic>
    {
        val normalized = diagnostics
            .asSequence()
            .filter { it.severity == ScriptDiagnostic.Severity.ERROR || it.severity == ScriptDiagnostic.Severity.FATAL }
            .map { diagnostic ->
                NormalizedKotlinDiagnostic(
                    message = sanitize(diagnostic.message),
                    line = diagnostic.location?.start?.line,
                    column = diagnostic.location?.start?.col
                )
            }
            .filter { it.message.isNotBlank() }
            .toList()

        return normalized.fold(mutableListOf()) { uniqueDiagnostics, diagnostic ->
            if(uniqueDiagnostics.lastOrNull() != diagnostic)
            {
                uniqueDiagnostics.add(diagnostic)
            }
            uniqueDiagnostics
        }
    }

    /**
     * Selects a stable public message from a backend failure.
     *
     * @param failure Backend failure to normalize.
     * @return Message suitable for the public Kotlin execution error prefix.
     */
    fun publicMessage(failure: KotlinScriptOutcome.Failure): String
    {
        val rootMessage = failure.cause?.let(::rootCause)?.message?.trim()
        if(!rootMessage.isNullOrEmpty())
        {
            return sanitize(rootMessage)
        }

        val diagnosticMessage = failure.diagnostics.joinToString("; ") { diagnostic ->
            buildString {
                append(diagnostic.message)
                if(diagnostic.line != null)
                {
                    append(" (")
                    append(SOURCE_NAME)
                    append(':')
                    append(diagnostic.line)
                    if(diagnostic.column != null)
                    {
                        append(':')
                        append(diagnostic.column)
                    }
                    append(')')
                }
            }
        }
        return diagnosticMessage.ifBlank { sanitize(failure.message).ifBlank { "Kotlin script execution failed" } }
    }

    private fun rootCause(throwable: Throwable): Throwable
    {
        var current = throwable
        val visited = mutableSetOf<Throwable>()
        while(current.cause != null && visited.add(current))
        {
            current = current.cause!!
        }
        return current
    }

    private fun sanitize(message: String): String
    {
        return message
            .replace(Regex("(?i)(?:[A-Za-z]:)?[/\\\\][^\\n\\r:)]*"), SOURCE_NAME)
            .replace(Regex("[A-Za-z0-9_$]+Script(?:\\$[A-Za-z0-9_$]+)?"), "script")
            .replace(Regex("kotlin\\.script\\.experimental\\.[A-Za-z0-9_.]+"), "kotlin-scripting")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("at ") }
            .joinToString("\n")
            .trim()
    }
}
