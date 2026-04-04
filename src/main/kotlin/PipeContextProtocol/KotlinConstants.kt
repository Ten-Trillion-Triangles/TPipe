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
        "java.io.FileInputStream",
        "java.io.FileOutputStream",
        "java.io.FileReader",
        "java.io.FileWriter",
        "java.io.RandomAccessFile",
        "java.nio.file",
        "kotlin.io.path",
        "java.net.Socket",
        "java.net.ServerSocket",
        "java.net.URL",
        "java.net.HttpURLConnection",
        "java.lang.reflect",
        "kotlin.reflect"
    )

    /**
     * Dangerous function calls and property accesses that should be blocked by default.
     */
    val DANGEROUS_FUNCTIONS = setOf(
        "System.exit",
        "Runtime.getRuntime",
        "Thread.stop",
        "Thread.suspend",
        "Thread.resume",
        "System.setProperty",
        "System.setProperties",
        "System.load",
        "System.loadLibrary"
    )

    /**
     * Dangerous patterns for Kotlin scripts.
     */
    val DANGEROUS_PATTERNS = listOf(
        "Class.forName",
        "\\.getDeclaredField",
        "\\.getDeclaredMethod",
        "\\.setAccessible",
        "\\.newInstance",
        "::class",
        "KClass",
        "ClassLoader",
        "getClassLoader"
    )
}
