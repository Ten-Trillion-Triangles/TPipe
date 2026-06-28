package com.TTT.PipeContextProtocol

import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * Executes JavaScript via an external Node.js process.
 */
class JavaScriptExecutor(
    private val threadPool: PcpThreadPool = PcpThreadPool.create()
) : PcpExecutor
{
    private val securityManager = JavaScriptSecurityManager()

    override suspend fun execute(request: PcPRequest, context: PcpContext): PcpRequestResult = coroutineScope {
        val startTime = System.currentTimeMillis()
        val script = request.argumentsOrFunctionParams.joinToString("\n")

        val mergedOptions = mergeContextOptions(request.javascriptContextOptions, context.javascriptOptions)

        val validation = securityManager.validateJavaScriptRequest(script, mergedOptions)
        if(!validation.isValid)
        {
            return@coroutineScope PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.JavaScript,
                error = "JavaScript security validation failed: ${validation.errors.joinToString("; ")}"
            )
        }

        if(script.isEmpty())
        {
            return@coroutineScope PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.JavaScript,
                error = "JavaScript script content is required"
            )
        }

        var scriptFile: File? = null
        try
        {
            scriptFile = File.createTempFile("tpipe_js_", ".js")
            scriptFile.writeText(script, Charsets.UTF_8)

            val nodeExecutable = mergedOptions.nodePath.ifEmpty { "node" }
            val command = listOf(nodeExecutable, scriptFile.absolutePath)

            val processBuilder = ProcessBuilder(command)

            if(mergedOptions.workingDirectory.isNotEmpty())
            {
                processBuilder.directory(File(mergedOptions.workingDirectory))
            }

            processBuilder.environment().putAll(mergedOptions.environmentVariables)

            // Start the process through the bounded pool — saturated
            // executor returns RejectedExecutionException instead of
            // spawning unbounded OS processes
            val process = try
            {
                threadPool.submit<Process> { processBuilder.start() }.get()
            }
            catch(e: java.util.concurrent.RejectedExecutionException)
            {
                scriptFile?.delete()
                return@coroutineScope PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.JavaScript,
                    error = "Executor saturated: ${e.message}"
                )
            }

            // Capture both streams in parallel via the shared helper.
            // Replaces the inline parallel-async pattern that was
            // duplicated in every executor — keeping the implementation
            // in one place means future bug fixes apply to all subprocess
            // sandboxes at once.
            val captureBuffer = SubprocessOutputCapture.capture(
                process = process,
                timeoutMs = mergedOptions.timeoutMs.toLong(),
                maxInMemoryBytes = 256 * 1024
            )

            scriptFile?.delete()

            val backcompatOutput = buildString {
                if(captureBuffer.stdout != null) append(captureBuffer.stdout.trim())
                if(captureBuffer.stderr != null && captureBuffer.stderr.isNotEmpty())
                {
                    if(isNotEmpty()) append('\n')
                    append("STDERR: ").append(captureBuffer.stderr)
                }
            }

            PcpRequestResult(
                success = captureBuffer.stdout != null && process.exitValue() == 0,
                output = backcompatOutput,
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.JavaScript,
                error = when
                {
                    // Timeout path takes precedence over exit code because
                    // SIGKILL (exit 137) from destroyForcibly also produces
                    // a non-zero exit
                    captureBuffer.stdout == null && captureBuffer.totalBytes == 0L ->
                        "JavaScript script timed out after ${mergedOptions.timeoutMs}ms"
                    process.exitValue() != 0 -> "JavaScript failed with exit code: ${process.exitValue()}"
                    else -> null
                },
                outputBuffer = captureBuffer
            )
        }
        catch(e: Exception)
        {
            PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.JavaScript,
                error = "JavaScript execution failed: ${e.message}"
            )
        }
        finally
        {
            scriptFile?.delete()
        }
    }

    private fun mergeContextOptions(requestOptions: JavaScriptContext, contextOptions: JavaScriptContext): JavaScriptContext
    {
        return JavaScriptContext().apply {
            nodePath = if(contextOptions.nodePath.isNotEmpty()) contextOptions.nodePath else requestOptions.nodePath
            timeoutMs = if(contextOptions.timeoutMs > 0) contextOptions.timeoutMs else requestOptions.timeoutMs
            allowedModules.addAll(contextOptions.allowedModules)
            if(allowedModules.isEmpty()) allowedModules.addAll(requestOptions.allowedModules)

            workingDirectory = if(contextOptions.workingDirectory.isNotEmpty()) contextOptions.workingDirectory else requestOptions.workingDirectory
            environmentVariables.putAll(requestOptions.environmentVariables)
            environmentVariables.putAll(contextOptions.environmentVariables)

            permissions.addAll(contextOptions.permissions)
            if(permissions.isEmpty()) permissions.addAll(requestOptions.permissions)
        }
    }
}
