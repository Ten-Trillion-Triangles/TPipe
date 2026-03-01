package com.TTT.PipeContextProtocol

import kotlinx.serialization.Serializable

/**
 * Result of Kotlin script security validation.
 */
@Serializable
data class KotlinValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String> = emptyList()
)

/**
 * Validates Kotlin scripts for security compliance.
 */
class KotlinSecurityManager
{
    /**
     * Validate Kotlin script against security context.
     * For now, we perform basic string-based analysis.
     * In the future, this could be expanded to use Kotlin compiler AST if needed.
     */
    fun validateKotlinRequest(script: String, context: KotlinContext): KotlinValidationResult
    {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Check for blocked imports
        val blockedImports = KotlinConstants.DANGEROUS_IMPORTS - context.allowedImports.toSet()
        blockedImports.forEach { blocked ->
            val pattern = "import\\s+$blocked\\b"
            if (Regex(pattern).containsMatchIn(script))
            {
                errors.add("Import '$blocked' is not allowed")
            }
        }

        // Check for dangerous patterns
        KotlinConstants.DANGEROUS_PATTERNS.forEach { pattern ->
            if (Regex(pattern).containsMatchIn(script))
            {
                errors.add("Pattern '$pattern' is not allowed for security reasons")
            }
        }

        // Check for introspection permissions
        if (script.contains("PcpRegistry") && !context.allowIntrospection)
        {
            errors.add("Access to PcpRegistry is restricted in this context")
        }

        return KotlinValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
}
