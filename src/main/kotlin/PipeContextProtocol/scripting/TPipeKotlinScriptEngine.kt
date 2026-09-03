@file:Suppress("unused")

package com.TTT.PipeContextProtocol.scripting

import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.providedProperties
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvm.jvmTarget
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvm.util.scriptCompilationClasspathFromContext
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvmhost.createJvmEvaluationConfigurationFromTemplate
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * TPipe-owned K2 scripting backend using a fresh host for each invocation.
 */
internal class TPipeKotlinScriptEngine : KotlinScriptBackend
{
    private val compilationClasspaths = Collections.synchronizedMap(
        WeakHashMap<ClassLoader, List<File>>()
    )

    override fun prepare(contextClassLoader: ClassLoader)
    {
        synchronized(compilationClasspaths)
        {
            compilationClasspaths.getOrPut(contextClassLoader) {
                scriptCompilationClasspathFromContext(
                    classLoader = contextClassLoader,
                    wholeClasspath = true,
                    unpackJarCollections = true
                ).toList()
            }
        }
    }

    override fun evaluate(invocation: KotlinScriptInvocation): KotlinScriptOutcome
    {
        if(containsUnsupportedOutputFunction(invocation.source, invocation.bindings.keys))
        {
            return KotlinScriptOutcome.Failure(
                kind = KotlinScriptFailureKind.COMPILATION,
                message = "Unresolved reference: print"
            )
        }

        return try
        {
            val bindingTypes = invocation.bindings.mapValues { (_, value) ->
                KotlinType(value::class)
            }
            val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<TPipePcpScript> {
                providedProperties(bindingTypes)
                compilerOptions(invocation.dialect.compilerOptions)
                jvm {
                    updateClasspath(compilationClasspath(invocation.contextClassLoader))
                    jvmTarget(invocation.dialect.jvmTarget)
                }
            }
            val evaluationConfiguration = createJvmEvaluationConfigurationFromTemplate<TPipePcpScript> {
                providedProperties(invocation.bindings)
                jvm {
                    baseClassLoader(invocation.contextClassLoader)
                }
            }
            val source = StringScriptSource(
                source = invocation.source,
                name = invocation.sourceName,
                locationId = invocation.sourceName
            )
            val evaluation = BasicJvmScriptingHost(
                compiler = JvmScriptCompiler(),
                evaluator = BasicJvmScriptEvaluator()
            ).eval(
                source,
                compilationConfiguration,
                evaluationConfiguration
            )
            mapEvaluation(evaluation)
        }
        catch(throwable: Throwable)
        {
            rethrowJvmFatal(throwable)
            KotlinScriptOutcome.Failure(
                kind = KotlinScriptFailureKind.BACKEND,
                message = throwable.message ?: "Kotlin scripting backend failed",
                cause = throwable
            )
        }
    }

    private fun compilationClasspath(contextClassLoader: ClassLoader): List<File>
    {
        return synchronized(compilationClasspaths) {
            compilationClasspaths[contextClassLoader]
        }
            ?: error("Kotlin scripting backend was not prepared for the context classloader")
    }

    /**
     * Detects executable unqualified output calls without matching strings or
     * comments that merely contain the same text.
     */
    private fun containsUnsupportedOutputFunction(
        source: String,
        allowedNames: Set<String>
    ): Boolean
    {
        val tokens = lexicalIdentifierTokens(source)
        val declaredNames = tokens
            .windowed(size = 2)
            .filter { (previous, current) ->
                previous.first in setOf("fun", "val", "var") &&
                    current.first in setOf("print", "println")
            }
            .mapTo(mutableSetOf()) { (_, current) -> current.first }

        tokens.forEach { (token, tokenStart) ->
            if(token != "print" && token != "println")
            {
                return@forEach
            }

            var previous = tokenStart - 1
            while(previous >= 0 && source[previous].isWhitespace())
            {
                previous--
            }
            var next = tokenStart + token.length
            while(next < source.length && source[next].isWhitespace())
            {
                next++
            }
            if((previous < 0 || source[previous] !in ".:") &&
                next < source.length && source[next] == '('
            )
            {
                if(token !in declaredNames && token !in allowedNames)
                {
                    return true
                }
            }
        }
        return false
    }

    private fun lexicalIdentifierTokens(source: String): List<Pair<String, Int>>
    {
        val tokens = mutableListOf<Pair<String, Int>>()
        var index = 0
        while(index < source.length)
        {
            when
            {
                source.startsWith("//", index) -> {
                    val newline = source.indexOf('\n', index + 2)
                    index = if(newline >= 0) newline + 1 else source.length
                }
                source.startsWith("/*", index) -> index = skipBlockComment(source, index + 2)
                source.startsWith("\"\"\"", index) -> index = skipDelimited(source, index, "\"\"\"")
                source[index] == '\"' -> index = skipQuoted(source, index, '\"')
                source[index] == '\'' -> index = skipQuoted(source, index, '\'')
                source[index].isLetter() || source[index] == '_' -> {
                    val tokenStart = index
                    index++
                    while(index < source.length && (source[index].isLetterOrDigit() || source[index] == '_'))
                    {
                        index++
                    }
                    tokens += source.substring(tokenStart, index) to tokenStart
                }
                else -> index++
            }
        }
        return tokens
    }

    private fun skipBlockComment(source: String, start: Int): Int
    {
        var index = start
        var depth = 1
        while(index < source.length - 1 && depth > 0)
        {
            when
            {
                source.startsWith("/*", index) -> {
                    depth++
                    index += 2
                }
                source.startsWith("*/", index) -> {
                    depth--
                    index += 2
                }
                else -> index++
            }
        }
        return index
    }

    private fun skipDelimited(source: String, start: Int, delimiter: String): Int
    {
        val end = source.indexOf(delimiter, start + delimiter.length)
        return if(end >= 0) end + delimiter.length else source.length
    }

    private fun skipQuoted(source: String, start: Int, quote: Char): Int
    {
        var index = start + 1
        while(index < source.length)
        {
            when
            {
                source[index] == '\\' -> index += 2
                source[index] == quote -> return index + 1
                else -> index++
            }
        }
        return source.length
    }

    private fun mapEvaluation(
        evaluation: ResultWithDiagnostics<EvaluationResult>
    ): KotlinScriptOutcome
    {
        return when(evaluation)
        {
            is ResultWithDiagnostics.Failure -> {
                val diagnostics = KotlinDiagnosticNormalizer.normalize(evaluation.reports)
                KotlinScriptOutcome.Failure(
                    kind = KotlinScriptFailureKind.COMPILATION,
                    message = diagnostics.joinToString("; ") { it.message }
                        .ifBlank { "Kotlin script compilation failed" },
                    diagnostics = diagnostics
                )
            }
            is ResultWithDiagnostics.Success -> mapEvaluationSuccess(evaluation.value, evaluation.reports)
        }
    }

    private fun mapEvaluationSuccess(
        evaluation: EvaluationResult,
        reports: List<ScriptDiagnostic>
    ): KotlinScriptOutcome
    {
        val diagnostics = KotlinDiagnosticNormalizer.normalize(reports)
        if(diagnostics.isNotEmpty())
        {
            return KotlinScriptOutcome.Failure(
                kind = KotlinScriptFailureKind.COMPILATION,
                message = diagnostics.joinToString("; ") { it.message },
                diagnostics = diagnostics
            )
        }

        val returnValue = evaluation.returnValue
        return when(returnValue)
        {
            is ResultValue.Value -> KotlinScriptOutcome.Success(
                value = returnValue.value,
                hasResultValue = returnValue.value != null && returnValue.value !is Unit,
                stdout = "",
                stderr = ""
            )
            is ResultValue.Unit,
            is ResultValue.NotEvaluated -> KotlinScriptOutcome.Success(
                value = null,
                hasResultValue = false,
                stdout = "",
                stderr = ""
            )
            is ResultValue.Error -> KotlinScriptOutcome.Failure(
                kind = KotlinScriptFailureKind.EVALUATION,
                message = returnValue.error.message ?: "Kotlin script evaluation failed",
                cause = returnValue.error,
                diagnostics = diagnostics
            )
            else -> KotlinScriptOutcome.Failure(
                kind = KotlinScriptFailureKind.EVALUATION,
                message = "Kotlin script evaluation returned an unsupported result",
                diagnostics = diagnostics
            )
        }
    }

    private fun rethrowJvmFatal(throwable: Throwable)
    {
        if(throwable is VirtualMachineError || throwable is ThreadDeath)
        {
            throw throwable
        }
    }
}
