package com.TTT.PipeContextProtocol.scripting

/**
 * Immutable input passed to a Kotlin script backend.
 *
 * @param source Complete script source assembled by [com.TTT.PipeContextProtocol.KotlinExecutor].
 * @param sourceName Stable source name used in compiler diagnostics.
 * @param bindings Binding names and the exact host objects visible to the script.
 * @param contextClassLoader Loader used for both compilation dependencies and evaluation.
 * @param dialect Versioned script-language contract.
 */
internal data class KotlinScriptInvocation(
    val source: String,
    val sourceName: String,
    val bindings: Map<String, Any>,
    val contextClassLoader: ClassLoader,
    val dialect: PcpKotlinDialect
)
