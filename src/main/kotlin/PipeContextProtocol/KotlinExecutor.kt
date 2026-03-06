package com.TTT.PipeContextProtocol

import java.io.StringWriter
import javax.script.ScriptEngineManager
import javax.script.SimpleBindings

/**
 * Executes Kotlin scripts within the JVM.
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

            val writer = StringWriter()
            val scriptContext = javax.script.SimpleScriptContext()
            scriptContext.writer = writer
            scriptContext.errorWriter = writer

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

            val result = try {
                engine.eval(script, scriptContext)
            }
            catch(e: Exception)
            {
                val captured = writer.toString().trim()
                if(captured.isNotEmpty())
                {
                    throw Exception("Execution failed but captured output: $captured. Error: ${e.message}", e)
                }
                throw e
            }

            val output = writer.toString().trim()
            val finalOutput = if(result != null && result !is Unit)
            {
                if(output.isNotEmpty()) "$output\nResult: $result" else "Result: $result"
            }
            else
            {
                output
            }

            PcpRequestResult(
                success = true,
                output = finalOutput,
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Kotlin
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
