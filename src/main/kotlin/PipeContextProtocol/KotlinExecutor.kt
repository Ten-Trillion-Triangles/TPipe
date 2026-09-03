package com.TTT.PipeContextProtocol

import com.TTT.PipeContextProtocol.scripting.DefaultKotlinScriptBackend
import com.TTT.PipeContextProtocol.scripting.KotlinOutcomeMapper
import com.TTT.PipeContextProtocol.scripting.KotlinScriptFailureKind
import com.TTT.PipeContextProtocol.scripting.KotlinScriptInvocation
import com.TTT.PipeContextProtocol.scripting.KotlinScriptOutcome
import com.TTT.PipeContextProtocol.scripting.PcpKotlinDialectV1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes one-shot Kotlin scripts through TPipe's internal scripting host.
 *
 * The executor validates the complete source before compilation, snapshots
 * only explicitly exposed host bindings, and keeps the existing daemon-thread
 * compilation/evaluation timeout boundary. A timeout returns control to the
 * caller but cannot terminate arbitrary in-process compiler or script bytecode.
 */
class KotlinExecutor : PcpExecutor
{
    private val securityManager = KotlinSecurityManager()
    private val backend = DefaultKotlinScriptBackend.create()
    private val customBindings = ConcurrentHashMap<String, Any>()

    /**
     * Registers a live host object for explicitly exposed Kotlin bindings.
     *
     * @param name Binding name used by the script.
     * @param obj Host object made available when the request exposes [name].
     * @param description Retained for public API compatibility; PCP metadata
     * does not use it during execution.
     */
    fun registerBinding(name: String, obj: Any, description: String = "")
    {
        customBindings[name] = obj
    }

    /**
     * Executes a PCP Kotlin request using the merged policy and live context.
     *
     * @param request Kotlin source and request-level options.
     * @param context Application PCP context and context-level options.
     * @return Public Kotlin transport result with unchanged serialization shape.
     */
    override suspend fun execute(request: PcPRequest, context: PcpContext): PcpRequestResult
    {
        val startTime = System.currentTimeMillis()
        val script = request.argumentsOrFunctionParams.joinToString("\n")
        val mergedOptions = mergeContextOptions(request.kotlinContextOptions, context.kotlinOptions)
        val validation = securityManager.validateKotlinRequest(script, mergedOptions, context)
        if(!validation.isValid)
        {
            return PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Kotlin,
                error = "Kotlin security validation failed: ${validation.errors.joinToString("; ")}"
            )
        }

        val bindings = snapshotBindings(mergedOptions, context)
        val contextClassLoader = Thread.currentThread().contextClassLoader
            ?: KotlinExecutor::class.java.classLoader
            ?: ClassLoader.getSystemClassLoader()
        val timeoutMs = mergedOptions.timeoutMs.toLong()
        val invocation = KotlinScriptInvocation(
            source = script,
            sourceName = "pcp-script.tpipe.kts",
            bindings = bindings,
            contextClassLoader = contextClassLoader,
            dialect = PcpKotlinDialectV1
        )

        return try
        {
            val outcome = withContext(Dispatchers.IO) {
                backend.prepare(contextClassLoader)
                evaluateWithTimeout(
                    invocation = invocation,
                    timeoutMs = timeoutMs
                )
            }
            if(outcome == null)
            {
                PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.Kotlin,
                    error = "Kotlin script timed out after ${timeoutMs}ms"
                )
            }
            else
            {
                KotlinOutcomeMapper.toResult(
                    outcome = outcome,
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            }
        }
        catch(exception: Exception)
        {
            PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Kotlin,
                error = "Kotlin execution failed: ${exception.message}"
            )
        }
    }

    private fun evaluateWithTimeout(
        invocation: KotlinScriptInvocation,
        timeoutMs: Long
    ): KotlinScriptOutcome?
    {
        val outcomeHolder = arrayOfNulls<KotlinScriptOutcome>(1)
        val fatalHolder = arrayOfNulls<Throwable>(1)
        val engineThread = Thread({
            try
            {
                outcomeHolder[0] = backend.evaluate(invocation)
            }
            catch(throwable: Throwable)
            {
                if(throwable is VirtualMachineError || throwable is ThreadDeath)
                {
                    fatalHolder[0] = throwable
                }
                else
                {
                    outcomeHolder[0] = KotlinScriptOutcome.Failure(
                        kind = KotlinScriptFailureKind.BACKEND,
                        message = throwable.message ?: "Kotlin scripting backend failed",
                        cause = throwable
                    )
                }
            }
        }, "kotlin-engine-thread").apply {
            isDaemon = true
            this.contextClassLoader = invocation.contextClassLoader
        }

        engineThread.start()
        val deadline = System.currentTimeMillis() + timeoutMs
        engineThread.join(timeoutMs)
        val joined = System.currentTimeMillis() < deadline && !engineThread.isAlive
        if(!joined)
        {
            return null
        }

        fatalHolder[0]?.let { throw it }
        return outcomeHolder[0]
    }

    private fun snapshotBindings(options: KotlinContext, context: PcpContext): Map<String, Any>
    {
        val bindings = LinkedHashMap<String, Any>()
        if(options.allowTpipeIntrospection)
        {
            bindings["PcpRegistry"] = PcpRegistry
            bindings["PcpContext"] = context
        }

        if(options.allowHostApplicationAccess)
        {
            options.exposedBindings.keys.forEach { bindingName ->
                customBindings[bindingName]?.let { bindingValue ->
                    bindings[bindingName] = bindingValue
                }
            }
        }
        return bindings.toMap()
    }

    private fun mergeContextOptions(requestOptions: KotlinContext, contextOptions: KotlinContext): KotlinContext
    {
        return KotlinContext(
            allowedImports = (contextOptions.allowedImports + requestOptions.allowedImports).toMutableList(),
            blockedImports = (contextOptions.blockedImports + requestOptions.blockedImports).toMutableList(),
            allowedPackages = (contextOptions.allowedPackages + requestOptions.allowedPackages).toMutableList(),
            blockedPackages = (contextOptions.blockedPackages + requestOptions.blockedPackages).toMutableList(),
            allowTpipeIntrospection = contextOptions.allowTpipeIntrospection && requestOptions.allowTpipeIntrospection,
            allowHostApplicationAccess = contextOptions.allowHostApplicationAccess || requestOptions.allowHostApplicationAccess,
            exposedBindings = (contextOptions.exposedBindings + requestOptions.exposedBindings).toMutableMap(),
            allowReflection = contextOptions.allowReflection || requestOptions.allowReflection,
            allowClassLoaderAccess = contextOptions.allowClassLoaderAccess || requestOptions.allowClassLoaderAccess,
            workingDirectory = if(contextOptions.workingDirectory.isNotEmpty()) contextOptions.workingDirectory else requestOptions.workingDirectory,
            allowFileRead = contextOptions.allowFileRead || requestOptions.allowFileRead,
            allowFileWrite = contextOptions.allowFileWrite || requestOptions.allowFileWrite,
            allowFileDelete = contextOptions.allowFileDelete || requestOptions.allowFileDelete,
            timeoutMs = if(contextOptions.timeoutMs > 0) contextOptions.timeoutMs else requestOptions.timeoutMs,
            permissions = (contextOptions.permissions + requestOptions.permissions).toMutableList(),
            environmentVariables = (contextOptions.environmentVariables + requestOptions.environmentVariables).toMutableMap(),
            allowNetworkAccess = contextOptions.allowNetworkAccess || requestOptions.allowNetworkAccess,
            allowProcessExecution = contextOptions.allowProcessExecution || requestOptions.allowProcessExecution
        )
    }
}
