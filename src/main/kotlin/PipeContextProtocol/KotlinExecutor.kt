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

    /**
     * Execute Kotlin script with context validation.
     */
    override suspend fun execute(request: PcPRequest, context: PcpContext): PcpRequestResult
    {
        val startTime = System.currentTimeMillis()
        val script = request.argumentsOrFunctionParams.joinToString("\n")

        // Merge context options
        val mergedOptions = mergeContextOptions(request.kotlinContextOptions, context.kotlinOptions)

        // Security validation
        val validation = securityManager.validateKotlinRequest(script, mergedOptions)
        if (!validation.isValid)
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

            // Expose introspection objects if allowed
            if (mergedOptions.allowIntrospection)
            {
                bindings["PcpRegistry"] = PcpRegistry
                bindings["PcpContext"] = context
            }

            // For JSR-223 Kotlin, we may need to inject bindings into the engine context as well
            // or use a specific way to handle println
            val result = try {
                // Ensure the script can see the bindings by injecting them into the script text if needed
                // or trusting the engine implementation of SimpleScriptContext.
                engine.eval(script, scriptContext)
            } catch (e: Exception) {
                // If eval fails, check if we captured any output before throwing
                val captured = writer.toString().trim()
                if (captured.isNotEmpty()) {
                    throw Exception("Execution failed but captured output: $captured. Error: ${e.message}", e)
                }
                throw e
            }

            val output = writer.toString().trim()
            val finalOutput = if (result != null && result !is Unit)
            {
                if (output.isNotEmpty()) "$output\nResult: $result" else "Result: $result"
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
        catch (e: Exception)
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
        return KotlinContext().apply {
            allowedImports.addAll(requestOptions.allowedImports)
            allowedImports.addAll(contextOptions.allowedImports)

            timeoutMs = if (contextOptions.timeoutMs > 0) contextOptions.timeoutMs else requestOptions.timeoutMs

            // Security override: context can disable introspection even if request wants it
            allowIntrospection = contextOptions.allowIntrospection && requestOptions.allowIntrospection

            permissions.addAll(contextOptions.permissions)
            if (permissions.isEmpty()) permissions.addAll(requestOptions.permissions)
        }
    }
}
