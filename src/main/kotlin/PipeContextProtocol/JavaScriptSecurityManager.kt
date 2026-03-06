package com.TTT.PipeContextProtocol

import kotlinx.serialization.Serializable

/**
 * Result of JavaScript security validation.
 */
@Serializable
data class JavaScriptValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String> = emptyList()
)

/**
 * Validates JavaScript scripts for security compliance.
 */
class JavaScriptSecurityManager
{
    /**
     * Validate JavaScript request against context.
     */
    fun validateJavaScriptRequest(script: String, context: JavaScriptContext): JavaScriptValidationResult
    {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Check for blocked modules in require calls
        val blockedModules = JavaScriptConstants.DANGEROUS_MODULES - context.allowedModules.toSet()
        blockedModules.forEach { module ->
            val pattern = "require\\s*\\(\\s*['\"]$module['\"]\\s*\\)"
            if(Regex(pattern).containsMatchIn(script))
            {
                errors.add("Module '$module' is not allowed")
            }
        }

        // Check dangerous patterns
        JavaScriptConstants.DANGEROUS_PATTERNS.forEach { pattern ->
            if(Regex(pattern).containsMatchIn(script))
            {
                errors.add("Dangerous pattern detected: $pattern")
            }
        }

        return JavaScriptValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
}
