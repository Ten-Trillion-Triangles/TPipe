package com.TTT.PipeContextProtocol

import kotlinx.serialization.Serializable

/**
 * Python security levels for different use cases.
 * 
 * Controls how strictly Python scripts are validated and what operations are allowed.
 * Python is already sandboxed by the OS process model, so defaults are more permissive
 * than HTTP security to enable common data processing and automation tasks.
 */
enum class PythonSecurityLevel
{
    /** Maximum security with minimal allowed operations and short timeouts */
    STRICT,
    
    /** Balanced security with developer-friendly defaults for most use cases */
    BALANCED,
    
    /** Minimal restrictions for development and testing environments */
    PERMISSIVE,
    
    /** No security restrictions - not recommended for production */
    DISABLED
}

/**
 * Python security configuration with per-level defaults and developer overrides.
 * 
 * Provides configurable security settings for Python script execution with sensible
 * defaults based on security level. Developers can override specific restrictions
 * while maintaining overall security posture.
 * 
 * @property level The base security level that determines default restrictions
 * @property maxTimeoutMs Maximum execution time in milliseconds before script is terminated
 * @property maxScriptSize Maximum script size in bytes to prevent resource exhaustion
 * @property requirePermissions Whether to enforce PCP permission requirements for operations
 * @property allowedImports Set of normally-blocked imports that are explicitly allowed
 * @property allowedFunctions Set of normally-blocked functions that are explicitly allowed  
 * @property allowedPatterns Set of normally-blocked regex patterns that are explicitly allowed
 */
@Serializable
data class PythonSecurityConfig(
    val level: PythonSecurityLevel = PythonSecurityLevel.BALANCED,
    val maxTimeoutMs: Long = getDefaultMaxTimeout(level),
    val maxScriptSize: Int = getDefaultMaxScriptSize(level),
    val requirePermissions: Boolean = getDefaultRequirePermissions(level),
    val allowedImports: Set<String> = emptySet(),
    val allowedFunctions: Set<String> = emptySet(),
    val allowedPatterns: Set<String> = emptySet()
) 
{
    companion object 
    {
        fun getDefaultMaxTimeout(level: PythonSecurityLevel): Long = when(level) 
        {
            PythonSecurityLevel.STRICT -> 60000L        // 1 minute
            PythonSecurityLevel.BALANCED -> 300000L     // 5 minutes (generous for data processing)
            PythonSecurityLevel.PERMISSIVE -> 1800000L  // 30 minutes
            PythonSecurityLevel.DISABLED -> Long.MAX_VALUE
        }
        
        fun getDefaultMaxScriptSize(level: PythonSecurityLevel): Int = when(level) 
        {
            PythonSecurityLevel.STRICT -> 1048576       // 1MB - size isn't a security concern when operations are blocked
            PythonSecurityLevel.BALANCED -> 1048576     // 1MB - reasonable for data processing scripts
            PythonSecurityLevel.PERMISSIVE -> 10485760  // 10MB - large data processing/ML scripts
            PythonSecurityLevel.DISABLED -> Int.MAX_VALUE
        }
        
        fun getDefaultRequirePermissions(level: PythonSecurityLevel): Boolean = when(level) 
        {
            PythonSecurityLevel.STRICT -> true
            PythonSecurityLevel.BALANCED -> true
            PythonSecurityLevel.PERMISSIVE -> true
            PythonSecurityLevel.DISABLED -> false
        }
    }
}

/**
 * Result of Python script security validation.
 * 
 * Contains validation outcome and detailed information about any security
 * violations or warnings found during script analysis.
 * 
 * @property isValid Whether the script passes all security checks
 * @property errors List of security violations that prevent script execution
 * @property warnings List of potentially risky patterns that don't block execution
 */
@Serializable
data class PythonValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String> = emptyList()
)

/**
 * Validates Python scripts for security compliance with configurable security levels.
 * 
 * Focuses on preventing system compromise while allowing broad Python functionality.
 * Uses a permissive-by-default approach since Python execution is already sandboxed
 * by the OS process model, unlike HTTP requests which can access external services.
 */
class PythonSecurityManager(
    private var securityConfig: PythonSecurityConfig = PythonSecurityConfig()
)
{
    // Compile regex patterns once for efficiency
    private val compiledPatterns = mutableMapOf<String, Regex>()
    
    private fun getCompiledRegex(pattern: String): Regex
    {
        return compiledPatterns.getOrPut(pattern) { Regex(pattern) }
    }
    /**
     * Update security level using predefined configuration.
     */
    fun setSecurityLevel(level: PythonSecurityLevel)
    {
        securityConfig = PythonSecurityConfig(level = level)
    }
    
    /**
     * Update security configuration with custom settings.
     */
    fun setSecurityConfig(config: PythonSecurityConfig)
    {
        securityConfig = config
    }
    
    /**
     * Get current security configuration.
     */
    fun getSecurityConfig(): PythonSecurityConfig = securityConfig
    
    /**
     * Validate Python script and execution context against security configuration.
     * 
     * Performs comprehensive security validation including script content analysis,
     * resource limit checking, and permission validation based on detected operations.
     */
    fun validatePythonRequest(script: String, context: PythonContext): PythonValidationResult
    {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Validate script size (primarily to prevent memory exhaustion, not security)
        if (script.length > securityConfig.maxScriptSize)
        {
            errors.add("Script size ${script.length} exceeds maximum ${securityConfig.maxScriptSize} bytes")
        }
        
        // Validate timeout
        if (context.timeoutMs > securityConfig.maxTimeoutMs)
        {
            errors.add("Timeout ${context.timeoutMs}ms exceeds maximum allowed ${securityConfig.maxTimeoutMs}ms")
        }
        
        // Validate permissions based on detected operations
        if (securityConfig.requirePermissions)
        {
            if (containsFileOperations(script) && !context.permissions.contains(Permissions.Write))
            {
                errors.add("Write permission required for file operations")
            }
            
            if (containsNetworkOperations(script) && !context.permissions.contains(Permissions.Read))
            {
                errors.add("Read permission required for network operations")
            }
        }
        
        // Validate script content for dangerous operations
        val scriptValidation = validateScriptContent(script)
        errors.addAll(scriptValidation.errors)
        warnings.addAll(scriptValidation.warnings)
        
        return PythonValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
    
    /**
     * Validate Python script content for dangerous operations.
     * 
     * Analyzes script text for potentially dangerous imports, function calls,
     * and patterns while respecting developer overrides for legitimate use cases.
     */
    private fun validateScriptContent(script: String): PythonValidationResult
    {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Check blocked imports with better pattern matching
        val blockedImports = getBlockedImports() - securityConfig.allowedImports
        blockedImports.forEach { blockedImport ->
            val importPatterns = listOf(
                "import\\s+$blockedImport\\b",
                "from\\s+$blockedImport\\s+import",
                "import\\s+$blockedImport\\s+as\\s+\\w+"
            )
            
            importPatterns.forEach { pattern ->
                if (getCompiledRegex(pattern).containsMatchIn(script))
                {
                    errors.add("Import '$blockedImport' is not allowed at current security level")
                    return@forEach // Break inner loop once found
                }
            }
        }

        securityConfig.allowedImports.forEach { allowedImport ->
            val importPatterns = listOf(
                "import\\s+$allowedImport\\b",
                "from\\s+$allowedImport\\s+import",
                "import\\s+$allowedImport\\s+as\\s+\\w+"
            )

            if (importPatterns.any { pattern -> getCompiledRegex(pattern).containsMatchIn(script) })
            {
                warnings.add("Import '$allowedImport' allowed via security override")
            }
        }
        
        // Check blocked functions with better pattern matching
        val blockedFunctions = getBlockedFunctions() - securityConfig.allowedFunctions
        blockedFunctions.forEach { blockedFunction ->
            val functionPatterns = listOf(
                "$blockedFunction\\s*\\(",
                "getattr\\s*\\([^,]+,\\s*['\"]${blockedFunction.split(".").last()}['\"]\\s*\\)"
            )
            
            functionPatterns.forEach { pattern ->
                if (getCompiledRegex(pattern).containsMatchIn(script))
                {
                    errors.add("Function '$blockedFunction' is not allowed at current security level")
                    return@forEach // Break inner loop once found
                }
            }
        }

        securityConfig.allowedFunctions.forEach { allowedFunction ->
            val functionPatterns = listOf(
                "$allowedFunction\\s*\\(",
                "getattr\\s*\\([^,]+,\\s*['\"]${allowedFunction.split(".").last()}['\"]\\s*\\)"
            )

            if (functionPatterns.any { pattern -> getCompiledRegex(pattern).containsMatchIn(script) })
            {
                warnings.add("Function '$allowedFunction' allowed via security override")
            }
        }
        
        // Check blocked patterns with compiled regex
        val blockedPatterns = getBlockedPatterns() - securityConfig.allowedPatterns
        blockedPatterns.forEach { pattern ->
            if (getCompiledRegex(pattern).containsMatchIn(script))
            {
                errors.add("Pattern '$pattern' is not allowed at current security level")
            }
        }

        securityConfig.allowedPatterns.forEach { pattern ->
            if (getCompiledRegex(pattern).containsMatchIn(script))
            {
                warnings.add("Pattern '$pattern' allowed via security override")
            }
        }
        
        return PythonValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
    
    /**
     * Get blocked imports based on security level.
     */
    private fun getBlockedImports(): Set<String>
    {
        return when (securityConfig.level)
        {
            PythonSecurityLevel.STRICT -> PythonConstants.DANGEROUS_IMPORTS + setOf("sys", "importlib", "ctypes")
            PythonSecurityLevel.BALANCED -> PythonConstants.DANGEROUS_IMPORTS // Only most dangerous
            PythonSecurityLevel.PERMISSIVE -> emptySet()
            PythonSecurityLevel.DISABLED -> emptySet()
        }
    }
    
    /**
     * Get blocked functions based on security level.
     */
    private fun getBlockedFunctions(): Set<String>
    {
        return when (securityConfig.level)
        {
            PythonSecurityLevel.STRICT -> PythonConstants.DANGEROUS_FUNCTIONS + setOf("eval", "exec", "compile", "__import__")
            PythonSecurityLevel.BALANCED -> PythonConstants.DANGEROUS_FUNCTIONS // Only direct system calls
            PythonSecurityLevel.PERMISSIVE -> emptySet()
            PythonSecurityLevel.DISABLED -> emptySet()
        }
    }
    
    /**
     * Get blocked patterns based on security level.
     */
    private fun getBlockedPatterns(): Set<String>
    {
        return when (securityConfig.level)
        {
            PythonSecurityLevel.STRICT -> PythonConstants.DANGEROUS_PATTERNS.toSet()
            PythonSecurityLevel.BALANCED -> setOf("eval\\s*\\(", "exec\\s*\\(") // Only code injection
            PythonSecurityLevel.PERMISSIVE -> emptySet()
            PythonSecurityLevel.DISABLED -> emptySet()
        }
    }
    
    /**
     * Check if script contains file operations that require Write permission.
     */
    private fun containsFileOperations(script: String): Boolean
    {
        val filePatterns = listOf(
            "open\\s*\\(",
            "\\.write\\s*\\(",
            "with\\s+open",
            "pathlib",
            "shutil"
        )
        return filePatterns.any { Regex(it).containsMatchIn(script) }
    }
    
    /**
     * Check if script contains network operations that require Read permission.
     */
    private fun containsNetworkOperations(script: String): Boolean
    {
        val networkPatterns = listOf(
            "requests\\.",
            "urllib\\.",
            "http\\.",
            "socket\\.",
            "ssl\\."
        )
        return networkPatterns.any { Regex(it).containsMatchIn(script) }
    }
}
