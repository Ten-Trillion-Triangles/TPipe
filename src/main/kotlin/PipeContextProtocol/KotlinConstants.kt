package com.TTT.PipeContextProtocol

/**
 * Constants for Kotlin script security validation.
 */
object KotlinConstants
{
    /**
     * Dangerous imports that should be blocked by default in Kotlin scripts.
     */
    val DANGEROUS_IMPORTS = setOf(
        "java.lang.ProcessBuilder",
        "java.lang.Runtime",
        "java.io.File",
        "java.nio.file",
        "kotlin.io.path",
        "java.net.Socket",
        "java.net.ServerSocket"
    )

    /**
     * Dangerous function calls that should be blocked by default.
     */
    val DANGEROUS_FUNCTIONS = setOf(
        "System.exit",
        "Runtime.getRuntime",
        "Thread.stop"
    )

    /**
     * Dangerous patterns for Kotlin scripts.
     */
    val DANGEROUS_PATTERNS = listOf(
        "Class.forName",
        "Reflection",
        "java.lang.reflect"
    )
}
