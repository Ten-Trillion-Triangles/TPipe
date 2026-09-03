@file:Suppress("unused")

package com.TTT.PipeContextProtocol.scripting

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports

/**
 * Script template used by the TPipe-owned PCP Kotlin host.
 */
@KotlinScript(
    fileExtension = "tpipe.kts",
    compilationConfiguration = TPipePcpScriptCompilationConfiguration::class,
    evaluationConfiguration = TPipePcpScriptEvaluationConfiguration::class
)
internal abstract class TPipePcpScript

/**
 * Static base compilation configuration for [PcpKotlinDialectV1].
 */
internal object TPipePcpScriptCompilationConfiguration : ScriptCompilationConfiguration({
    compilerOptions(PcpKotlinDialectV1.compilerOptions)
    defaultImports("kotlin.collections.*")
})

/**
 * Minimal static evaluation configuration for [TPipePcpScript].
 */
internal object TPipePcpScriptEvaluationConfiguration : ScriptEvaluationConfiguration()
