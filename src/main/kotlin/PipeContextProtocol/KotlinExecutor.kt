package com.TTT.PipeContextProtocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.StringWriter
import javax.script.ScriptEngineManager
import javax.script.SimpleBindings

/**
 * Executes Kotlin scripts within the JVM.
 *
 * KNOWN LIMITATION (Option A): the JSR-223 engine's eval() cannot be
 * interrupted from the outside. When timeoutMs fires, the dispatcher
 * gives up waiting and returns a timeout error — but the engine thread
 * keeps running until the script returns or the JVM exits. This is
 * documented in plan Task 6; the alternative (engine.eval in its own
 * cancellable thread + Thread.interrupt) breaks JSR-223 contract and
 * corrupts engine state. For untrusted Kotlin scripts, wrap the
 * dispatcher's coroutine in an outer timeout at the pipe layer.
 */
class KotlinExecutor : PcpExecutor
{
    private val securityManager = KotlinSecurityManager()
    private val engineManager = ScriptEngineManager()
    private val customBindings = mutableMapOf<String, Any>()

    fun registerBinding(name: String, obj: Any, description: String = "")
    {
        customBindings[name] = obj
    }

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

        return try
        {
            val engine = engineManager.getEngineByExtension("kts")
                ?: throw IllegalStateException("Kotlin script engine not found. Ensure 'kotlin-scripting-jsr223' is in the classpath.")

            val timeoutMs = mergedOptions.timeoutMs.toLong()

            // Run the synchronous JSR-223 eval on a daemon thread so the
            // JVM can exit even if the leak (below) keeps the engine busy.
            // We join with timeout — if the join times out, we return a
            // timeout error and the daemon thread keeps running in the
            // background. When the JVM exits, the daemon thread is killed.
            //
            // ACKNOWLEDGED LEAK (Option A): the JSR-223 engine's eval()
            // cannot be interrupted from outside the engine. When timeout
            // fires, the dispatcher gives up waiting and returns null —
            // but the daemon engine thread keeps running until the script
            // returns or the JVM exits. This is documented in the plan
            // (Task 6) and in this class's KDoc. For untrusted Kotlin
            // scripts, wrap the dispatcher's coroutine in an outer
            // timeout at the pipe layer.
            val captureOutcome = withContext(Dispatchers.IO) {
                val resultHolder = arrayOfNulls<Any>(1) // [eval result]
                val stdoutHolder = arrayOfNulls<StringWriter>(1)
                val stderrHolder = arrayOfNulls<StringWriter>(1)
                val exceptionHolder = arrayOfNulls<Throwable>(1)

                val engineThread = Thread({
                    try
                    {
                        val stdoutWriter = StringWriter()
                        val stderrWriter = StringWriter()
                        stdoutHolder[0] = stdoutWriter
                        stderrHolder[0] = stderrWriter
                        val scriptContext = javax.script.SimpleScriptContext()
                        scriptContext.writer = stdoutWriter
                        scriptContext.errorWriter = stderrWriter

                        val bindings = scriptContext.getBindings(javax.script.ScriptContext.ENGINE_SCOPE)

                        if(mergedOptions.allowTpipeIntrospection)
                        {
                            bindings["PcpRegistry"] = PcpRegistry
                            bindings["PcpContext"] = context
                        }

                        if(mergedOptions.allowHostApplicationAccess)
                        {
                            mergedOptions.exposedBindings.keys.forEach { bindingName ->
                                customBindings[bindingName]?.let { obj ->
                                    bindings[bindingName] = obj
                                }
                            }
                        }

                        resultHolder[0] = engine.eval(script, scriptContext)
                    }
                    catch(e: Throwable)
                    {
                        exceptionHolder[0] = e
                    }
                }, "kotlin-engine-thread").apply { isDaemon = true }

                engineThread.start()

                // Use Thread.join(timeout) directly rather than
                // withTimeoutOrNull because the inner join() is a
                // synchronous blocking call — coroutine cancellation
                // cannot interrupt it, so withTimeoutOrNull would still
                // hang the IO thread until engineThread actually exits.
                // Thread.join returns void, so check via a deadline.
                val deadline = System.currentTimeMillis() + timeoutMs
                engineThread.join(timeoutMs)
                val joined = System.currentTimeMillis() < deadline && !engineThread.isAlive

                if(!joined)
                {
                    // Timeout fired. The daemon thread is still running —
                    // it will be killed when the JVM exits. Return null
                    // marker to signal timeout.
                    null
                }
                else
                {
                    val ex = exceptionHolder[0]
                    if(ex != null)
                    {
                        EvalOutcome(
                            stdout = stdoutHolder[0]?.toString()?.trim() ?: "",
                            stderr = stderrHolder[0]?.toString()?.trim() ?: "",
                            returnValue = null,
                            timedOut = false,
                            error = ex
                        )
                    }
                    else
                    {
                        EvalOutcome(
                            stdout = stdoutHolder[0]?.toString()?.trim() ?: "",
                            stderr = stderrHolder[0]?.toString()?.trim() ?: "",
                            returnValue = resultHolder[0],
                            timedOut = false
                        )
                    }
                }
            }

            if(captureOutcome == null)
            {
                // Timeout fired and we gave up waiting. Return a clean
                // error to the caller. The daemon engine thread is still
                // running — see class-level comment.
                return PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.Kotlin,
                    error = "Kotlin script timed out after ${timeoutMs}ms"
                )
            }

            if(captureOutcome.error != null)
            {
                throw captureOutcome.error
            }

            val output = captureOutcome.stdout
            val finalOutput = if(captureOutcome.returnValue != null && captureOutcome.returnValue !is Unit)
            {
                if(output.isNotEmpty()) "$output\nResult: ${captureOutcome.returnValue}" else "Result: ${captureOutcome.returnValue}"
            }
            else
            {
                output
            }

            PcpRequestResult(
                success = true,
                output = finalOutput,
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Kotlin,
                outputBuffer = BufferedOutput(
                    stdout = captureOutcome.stdout,
                    stderr = captureOutcome.stderr,
                    binary = null,
                    totalBytes = (captureOutcome.stdout.length).toLong() + (captureOutcome.stderr.length).toLong(),
                    truncated = false
                )
            )
        }
        catch(e: Exception)
        {
            PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Kotlin,
                error = "Kotlin execution failed: ${e.message}"
            )
        }
    }

    private data class EvalOutcome(
        val stdout: String,
        val stderr: String,
        val returnValue: Any?,
        val timedOut: Boolean,
        val error: Throwable? = null
    )

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
