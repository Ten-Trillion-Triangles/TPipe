package com.TTT.PipeContextProtocol

import kotlinx.coroutines.async
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Executes JavaScript via an external Node.js process.
 */
class JavaScriptExecutor : PcpExecutor
{
    private val securityManager = JavaScriptSecurityManager()

    override suspend fun execute(request: PcPRequest, context: PcpContext): PcpRequestResult = kotlinx.coroutines.coroutineScope {
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
            scriptFile.writeText(script)

            val nodeExecutable = mergedOptions.nodePath.ifEmpty { "node" }
            val command = listOf(nodeExecutable, scriptFile.absolutePath)

            val processBuilder = ProcessBuilder(command)

            if(mergedOptions.workingDirectory.isNotEmpty())
            {
                processBuilder.directory(File(mergedOptions.workingDirectory))
            }

            processBuilder.environment().putAll(mergedOptions.environmentVariables)

            val process = processBuilder.start()

            // Read output and error streams in parallel to avoid deadlock
            val outputDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                process.inputStream.bufferedReader().readText()
            }
            val errorDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                process.errorStream.bufferedReader().readText()
            }

            val completed = if(mergedOptions.timeoutMs > 0)
            {
                process.waitFor(mergedOptions.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            }
            else
            {
                process.waitFor()
                true
            }

            if(!completed)
            {
                process.destroyForcibly()
                return@coroutineScope PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.JavaScript,
                    error = "JavaScript script timed out after ${mergedOptions.timeoutMs}ms"
                )
            }

            val output = outputDeferred.await()
            val errorOutput = errorDeferred.await()
            val finalOutput = if(errorOutput.isNotEmpty()) "$output\nSTDERR: $errorOutput" else output

            PcpRequestResult(
                success = process.exitValue() == 0,
                output = finalOutput.trim(),
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.JavaScript,
                error = if(process.exitValue() != 0) "JavaScript failed with exit code: ${process.exitValue()}" else null
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
