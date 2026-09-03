package com.TTT.PipeContextProtocol.scripting

/**
 * Internal categories used to distinguish backend failures without changing
 * the serialized PCP result model.
 */
internal enum class KotlinScriptFailureKind
{
    COMPILATION,
    EVALUATION,
    CONFIGURATION,
    CLASSPATH,
    BACKEND
}

/**
 * A compiler diagnostic reduced to stable source-facing data.
 *
 * @param message Normalized diagnostic text.
 * @param line One-based source line, when available.
 * @param column One-based source column, when available.
 */
internal data class NormalizedKotlinDiagnostic(
    val message: String,
    val line: Int? = null,
    val column: Int? = null
)

/**
 * Result returned by a Kotlin script backend before PCP output mapping.
 */
internal sealed interface KotlinScriptOutcome
{
    /**
     * Successful evaluation and its captured channels.
     *
     * @param value Final script value, if one was produced.
     * @param hasResultValue Whether [value] should be rendered publicly.
     * @param stdout Trimmed captured standard output.
     * @param stderr Trimmed captured standard error.
     */
    data class Success(
        val value: Any?,
        val hasResultValue: Boolean,
        val stdout: String,
        val stderr: String
    ) : KotlinScriptOutcome

    /**
     * Failed compilation, configuration, classpath setup, or evaluation.
     *
     * @param kind Internal failure category.
     * @param message Stable failure message.
     * @param cause Root throwable, when available.
     * @param stdout Output emitted before failure, retained only for backend diagnostics.
     * @param stderr Error output emitted before failure, retained only for backend diagnostics.
     * @param diagnostics Normalized compiler diagnostics.
     */
    data class Failure(
        val kind: KotlinScriptFailureKind,
        val message: String,
        val cause: Throwable? = null,
        val stdout: String = "",
        val stderr: String = "",
        val diagnostics: List<NormalizedKotlinDiagnostic> = emptyList()
    ) : KotlinScriptOutcome
}
