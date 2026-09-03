package com.TTT.PipeContextProtocol.scripting

/**
 * Versioned, host-owned Kotlin scripting contract.
 */
internal interface PcpKotlinDialect
{
    /** Stable dialect identifier. */
    val id: String

    /** Kotlin language version used by scripts. */
    val languageVersion: String

    /** Kotlin API version used by scripts. */
    val apiVersion: String

    /** JVM bytecode target used by scripts. */
    val jvmTarget: String

    /** Compiler flags shared by every invocation. */
    val compilerOptions: List<String>
}

/**
 * Initial PCP Kotlin dialect. Compiler upgrades must not silently change this
 * script contract.
 */
internal object PcpKotlinDialectV1 : PcpKotlinDialect
{
    override val id = "pcp-kotlin-v1"
    override val languageVersion = "2.0"
    override val apiVersion = "2.0"
    override val jvmTarget = "24"
    override val compilerOptions = listOf(
        "-language-version",
        languageVersion,
        "-api-version",
        apiVersion,
        "-Xsuppress-version-warnings"
    )
}
