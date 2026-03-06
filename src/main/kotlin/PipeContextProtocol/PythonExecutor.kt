package com.TTT.PipeContextProtocol

import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.TimeUnit

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
     * Execute Python script with context validation and security enforcement.
     * 
     * @param request The PCP request to execute
     * @param context The security context defining allowed operations
     * @return PcpRequestResult with execution results or validation errors
     */
    override suspend fun execute(request: PcPRequest, context: PcpContext): PcpRequestResult
    {
        val startTime = System.currentTimeMillis()
        
        // Merge context options with request options (context takes precedence for security)
        val mergedOptions = mergeContextOptions(request.pythonContextOptions, context.pythonOptions)
        
        // Validate Python request through security manager
        val script = request.argumentsOrFunctionParams.joinToString("\n")
        val validation = securityManager.validatePythonRequest(script, mergedOptions)
        val warnings = validation.warnings.toMutableList()

        if(!validation.isValid)
        {
            return PcpRequestResult(
                success = false,
                output = mergeWarningsWithOutput(warnings, ""),
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Python,
                error = "Python security validation failed: ${validation.errors.joinToString("; ")}"
            )
        }
        
        // Get script content
        if(script.isEmpty())
        {
            return PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Python,
                error = "Python script content is required"
            )
        }
        
        // Validate package imports against context whitelist
        val importValidation = validatePackageImports(script, mergedOptions)
        if(!importValidation.isValid)
        {
            return PcpRequestResult(
                success = false,
                output = mergeWarningsWithOutput(warnings, ""),
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Python,
                error = importValidation.error
            )
        }

        // Execute with merged options (security enforced)
        val secureRequest = request.copy(pythonContextOptions = mergedOptions)
        return executeSecure(secureRequest, warnings)
    }
    
    /**
     * Merge context Python options with request options.
     * Context options take precedence for security settings.
     */
    private fun mergeContextOptions(requestOptions: PythonContext, contextOptions: PythonContext): PythonContext
    {
        return PythonContext().apply {
            // Use context interpreter path if specified, otherwise request path
            pythonPath = if(contextOptions.pythonPath.isNotEmpty())
                contextOptions.pythonPath else requestOptions.pythonPath
            
            // Use context timeout if specified, otherwise request timeout
            timeoutMs = if(contextOptions.timeoutMs > 0)
                contextOptions.timeoutMs else requestOptions.timeoutMs
            
            // CRITICAL: Use context package whitelist (security override)
            availablePackages.addAll(contextOptions.availablePackages)
            if(availablePackages.isEmpty())
            {
                availablePackages.addAll(requestOptions.availablePackages)
            }
            
            // Context permissions override request permissions
            permissions.addAll(contextOptions.permissions)
            if(permissions.isEmpty())
            {
                permissions.addAll(requestOptions.permissions)
            }
            
            // Context working directory overrides request
            workingDirectory = if(contextOptions.workingDirectory.isNotEmpty())
                contextOptions.workingDirectory else requestOptions.workingDirectory
                
            // Merge environment variables (context takes precedence)
            environmentVariables.putAll(requestOptions.environmentVariables)
            environmentVariables.putAll(contextOptions.environmentVariables)
            
            // Context capture output setting overrides request
            captureOutput = contextOptions.captureOutput
            
            // Context python version overrides request
            pythonVersion = if(contextOptions.pythonVersion.isNotEmpty())
                contextOptions.pythonVersion else requestOptions.pythonVersion
        }
    }
    
    /**
     * Execute Python script with merged security options.
     */
    private suspend fun executeSecure(request: PcPRequest, warnings: List<String>): PcpRequestResult
    {
        val startTime = System.currentTimeMillis()
        val options = request.pythonContextOptions
        
        return try
        {
            // Get script content from request
            val script = request.argumentsOrFunctionParams.joinToString("\n")
            
            // Create temporary script file
            val scriptFile = File.createTempFile("tpipe_python_", ".py")
            scriptFile.writeText(script)
            
            // Build command
            val command = buildList<String> {
                add(options.pythonPath.ifEmpty { resolvePythonExecutable(options) ?: "python3" })
                add(scriptFile.absolutePath)
            }
            
            // Execute Python script
            val processBuilder = ProcessBuilder(command)
            
            // Set working directory if specified
            if(options.workingDirectory.isNotEmpty())
            {
                processBuilder.directory(File(options.workingDirectory))
            }
            
            // Set environment variables
            val environment = processBuilder.environment()
            options.environmentVariables.forEach { (key, value) ->
                environment[key] = value
            }
            
            val process = processBuilder.start()
            
            // Handle timeout
            val completed = if(options.timeoutMs > 0)
            {
                process.waitFor(options.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            }
            else
            {
                process.waitFor()
                true
            }
            
            if(!completed)
            {
                process.destroyForcibly()
                scriptFile.delete()
                return PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.Python,
                    error = "Python script timed out after ${options.timeoutMs}ms"
                )
            }
            
            // Capture output
            val output = process.inputStream.bufferedReader().readText()
            val errorOutput = process.errorStream.bufferedReader().readText()

            val finalOutput = if(errorOutput.isNotEmpty())
            {
                "$output\nSTDERR: $errorOutput"
            }
            else
            {
                output
            }
            val outputWithWarnings = mergeWarningsWithOutput(warnings, finalOutput)
            
            // Clean up
            scriptFile.delete()
            
            PcpRequestResult(
                success = process.exitValue() == 0,
                output = outputWithWarnings,
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Python,
                error = if(process.exitValue() != 0) "Python script failed with exit code: ${process.exitValue()}" else null
            )
        }
        catch(e: Exception)
        {
            PcpRequestResult(
                success = false,
                output = mergeWarningsWithOutput(warnings, ""),
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Python,
                error = "Python execution failed: ${e.message}"
            )
        }
    }
    
    /**
     * Validate Python script imports against context package whitelist.
     */
    private fun validatePackageImports(script: String, contextOptions: PythonContext): PythonValidationResult
    {
        // If no package restrictions, allow all imports
        if(contextOptions.availablePackages.isEmpty())
        {
            return PythonValidationResult(true, null)
        }
        
        // Extract import statements from script
        val imports = extractImportStatements(script)
        
        // Check each import against whitelist
        for(importName in imports)
        {
            val isAllowed = contextOptions.availablePackages.any { allowedPackage ->
                importName == allowedPackage || importName.startsWith("$allowedPackage.")
            }
            
            if(!isAllowed)
            {
                return PythonValidationResult(false, "Import '$importName' not in allowed packages list")
            }
        }
        
        return PythonValidationResult(true, null)
    }
    
    /**
     * Extract import statements from Python script.
     */
    private fun extractImportStatements(script: String): List<String>
    {
        val imports = mutableListOf<String>()
        val lines = script.lines()
        
        for(line in lines)
        {
            val trimmed = line.trim()
            
            // Handle "import module" statements
            if(trimmed.startsWith("import ") && !trimmed.startsWith("import *"))
            {
                val parts = trimmed.substring(7).split(",")
                parts.forEach { part ->
                    val moduleName = part.trim().split(" ")[0]
                    if(moduleName.isNotEmpty()) imports.add(moduleName)
                }
            }
            
            // Handle "from module import" statements
            else if(trimmed.startsWith("from ") && " import " in trimmed)
            {
                val fromPart = trimmed.substring(5, trimmed.indexOf(" import ")).trim()
                if(fromPart.isNotEmpty()) imports.add(fromPart)
            }
        }
        
        return imports.distinct()
    }
    
    /**
     * Python validation result for context checks.
     */
    private data class PythonValidationResult(val isValid: Boolean, val error: String?)
    
    /**
     * Resolve Python executable to use for execution.
     * 
     * Uses specified pythonPath from context if provided and valid,
     * otherwise detects best available Python installation.
     */
    private fun resolvePythonExecutable(context: PythonContext): String?
    {
        // Use specified Python path if provided and valid
        if(context.pythonPath.isNotEmpty())
        {
            if(platformManager.validatePythonExecutable(context.pythonPath))
            {
                return context.pythonPath
            }
        }
        
        // Detect available Python installations
        val detectionResult = platformManager.detectPythonInstallations()
        if(!detectionResult.success || detectionResult.defaultInstallation == null)
        {
            return null
        }
        
        // Filter by version if specified
        if(context.pythonVersion.isNotEmpty())
        {
            val matchingInstallation = detectionResult.installations.find { installation ->
                installation.version.startsWith(context.pythonVersion)
            }
            if(matchingInstallation != null)
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

    private fun mergeWarningsWithOutput(warnings: List<String>, output: String): String
    {
        if(warnings.isEmpty())
        {
            return output
        }

        val warningSection = buildString {
            append("Warnings:\n")
            warnings.distinct().forEach { warning ->
                append("- ").append(warning).append('\n')
            }
        }.trimEnd()

        return if(output.isBlank())
        {
            warningSection
        }
        else
        {
            "$warningSection\n\n${output.trimStart()}"
        }
    }
}
