package com.TTT.PipeContextProtocol.scripting

/**
 * Evaluates one Kotlin script invocation synchronously.
 *
 * The caller owns the execution thread and timeout boundary so a backend can
 * be replaced without changing the public PCP executor contract.
 */
internal fun interface KotlinScriptBackend
{
    /**
     * Prepares immutable compiler metadata for [contextClassLoader].
     *
     * Preparation is deliberately outside the user-script timeout boundary;
     * compilation and evaluation remain bounded by the caller.
     *
     * @param contextClassLoader Loader whose classpath will be used by the
     * script compiler and evaluator.
     */
    fun prepare(contextClassLoader: ClassLoader)
    {
    }

    /**
     * Evaluates [invocation] and converts compiler or runtime failures to an
     * internal outcome.
     *
     * @param invocation Immutable data for one script evaluation.
     * @return The backend outcome.
     */
    fun evaluate(invocation: KotlinScriptInvocation): KotlinScriptOutcome
}
