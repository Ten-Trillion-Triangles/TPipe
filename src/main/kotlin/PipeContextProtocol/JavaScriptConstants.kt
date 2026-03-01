package com.TTT.PipeContextProtocol

/**
 * Constants for JavaScript security validation.
 */
object JavaScriptConstants
{
    /**
     * Dangerous modules in Node.js that should be blocked by default.
     */
    val DANGEROUS_MODULES = setOf("child_process", "fs", "os", "process", "cluster", "worker_threads")

    /**
     * Dangerous patterns for JavaScript scripts.
     */
    val DANGEROUS_PATTERNS = listOf(
        "eval\\s*\\(",
        "new\\s+Function\\s*\\(",
        "require\\s*\\(\\s*['\"]child_process['\"]\\s*\\)",
        "require\\s*\\(\\s*['\"]fs['\"]\\s*\\)"
    )
}
