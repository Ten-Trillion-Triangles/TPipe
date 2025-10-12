package com.TTT.PipeContextProtocol

import kotlinx.serialization.Serializable

/**
 * Executes Python scripts with cross-platform environment management and security controls.
 * 
 * Handles package validation, platform detection, and output capture across Windows, macOS, and Linux.
 * Uses existing StdioExecutor infrastructure for process execution while adding Python-specific
 * security validation and platform compatibility.
 */
class PythonExecutor : PcpExecutor
{
    private val securityManager = PythonSecurityManager()
    private val platformManager = PythonPlatformManager()
    // Remove stdioExecutor - we'll execute directly
    
    /**
     * Set Python security level for this executor.
     */
    fun setSecurityLevel(level: PythonSecurityLevel)
    {
        securityManager.setSecurityLevel(level)
    }
    
    /**
     * Set custom Python security configuration.
     */
    fun setSecurityConfig(config: PythonSecurityConfig)
    {
        securityManager.setSecurityConfig(config)
    }
    
    /**
     * Allow specific imports that are normally blocked.
     */
    fun allowImports(vararg imports: String)
    {
        val currentConfig = securityManager.getSecurityConfig()
        val newConfig = currentConfig.copy(
            allowedImports = currentConfig.allowedImports + imports.toSet()
        )
        securityManager.setSecurityConfig(newConfig)
    }
    
    /**
     * Allow specific function calls that are normally blocked.
     */
    fun allowFunctions(vararg functions: String)
    {
        val currentConfig = securityManager.getSecurityConfig()
        val newConfig = currentConfig.copy(
            allowedFunctions = currentConfig.allowedFunctions + functions.toSet()
        )
        securityManager.setSecurityConfig(newConfig)
    }
    
    /**
     * Allow custom patterns that are normally blocked.
     */
    fun allowPatterns(vararg patterns: String)
    {
        val currentConfig = securityManager.getSecurityConfig()
        val newConfig = currentConfig.copy(
            allowedPatterns = currentConfig.allowedPatterns + patterns.toSet()
        )
        securityManager.setSecurityConfig(newConfig)
    }
    
    /**
     * Execute Python script based on PCP context options.
     */
    override suspend fun execute(request: PcPRequest): PcpRequestResult
    {
        val startTime = System.currentTimeMillis()
        val options = request.pythonContextOptions
        
        return try
        {
            // Get script content from argumentsOrFunctionParams
            val script = request.argumentsOrFunctionParams.joinToString("\n")
            if (script.isEmpty())
            {
                return PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.Python,
                    error = "Python script content is required"
                )
            }
            
            // Sanitize script input
            if (script.contains('\u0000'))
            {
                return PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.Python,
                    error = "Script contains null bytes"
                )
            }
            
            // Validate security
            val validation = securityManager.validatePythonRequest(script, options)
            if (!validation.isValid)
            {
                return PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.Python,
                    error = "Python validation failed: ${validation.errors.joinToString(", ")}"
                )
            }
            
            // Detect or use specified Python executable
            val pythonExecutable = resolvePythonExecutable(options)
            if (pythonExecutable == null)
            {
                return PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.Python,
                    error = "No suitable Python installation found"
                )
            }
            
            // Write script to temporary file to avoid command injection
            val tempFile = createTempScriptFile(script)
            
            try
            {
                // Build Python command using temp file
                val command = listOf(pythonExecutable, tempFile.absolutePath)
                
                // Execute Python process directly
                val processBuilder = ProcessBuilder(command)
                processBuilder.directory(java.io.File(options.workingDirectory.ifEmpty { System.getProperty("user.dir") }))
                
                // Set environment variables
                val environment = processBuilder.environment()
                options.environmentVariables.forEach { (key, value) ->
                    environment[key] = value
                }
                
                val process = processBuilder.start()
                
                // Wait for completion with timeout
                val completed = process.waitFor(options.timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                
                if (!completed)
                {
                    process.destroyForcibly()
                    return PcpRequestResult(
                        success = false,
                        output = "",
                        executionTimeMs = System.currentTimeMillis() - startTime,
                        transport = Transport.Python,
                        error = "Python script execution timed out after ${options.timeoutMs}ms"
                    )
                }
                
                // Capture output
                val output = if (options.captureOutput)
                {
                    process.inputStream.bufferedReader().readText()
                }
                else
                {
                    ""
                }
                
                val errorOutput = process.errorStream.bufferedReader().readText()
                val exitCode = process.exitValue()
                
                // Return result
                PcpRequestResult(
                    success = exitCode == 0,
                    output = output + if (errorOutput.isNotEmpty()) "\nSTDERR: $errorOutput" else "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.Python,
                    error = if (exitCode != 0) "Python script exited with code $exitCode" else null
                )
            }
            finally
            {
                // Clean up temp file
                tempFile.delete()
            }
        }
        catch (e: Exception)
        {
            PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Python,
                error = "Python execution failed: ${e.message}"
            )
        }
    }
    
    /**
     * Resolve Python executable to use for execution.
     * 
     * Uses specified pythonPath from context if provided and valid,
     * otherwise detects best available Python installation.
     */
    private fun resolvePythonExecutable(context: PythonContext): String?
    {
        // Use specified Python path if provided and valid
        if (context.pythonPath.isNotEmpty())
        {
            if (platformManager.validatePythonExecutable(context.pythonPath))
            {
                return context.pythonPath
            }
        }
        
        // Detect available Python installations
        val detectionResult = platformManager.detectPythonInstallations()
        if (!detectionResult.success || detectionResult.defaultInstallation == null)
        {
            return null
        }
        
        // Filter by version if specified
        if (context.pythonVersion.isNotEmpty())
        {
            val matchingInstallation = detectionResult.installations.find { installation ->
                installation.version.startsWith(context.pythonVersion)
            }
            if (matchingInstallation != null)
            {
                return matchingInstallation.executable
            }
        }
        
        // Use default installation
        return detectionResult.defaultInstallation.executable
    }
    
    /**
     * Create temporary script file to avoid command injection.
     * 
     * @param script Python script content
     * @return Temporary file containing the script
     */
    private fun createTempScriptFile(script: String): java.io.File
    {
        val tempFile = java.io.File.createTempFile("tpipe_python_", ".py")
        tempFile.writeText(script, Charsets.UTF_8)
        return tempFile
    }
}
