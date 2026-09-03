package com.TTT.PipeContextProtocol.scripting

/**
 * Compile-time default backend used by [com.TTT.PipeContextProtocol.KotlinExecutor].
 */
internal object DefaultKotlinScriptBackend
{
    /**
     * Creates the production backend for the current source tree.
     *
     * @return A fresh backend instance.
     */
    fun create(): KotlinScriptBackend = TPipeKotlinScriptEngine()
}
