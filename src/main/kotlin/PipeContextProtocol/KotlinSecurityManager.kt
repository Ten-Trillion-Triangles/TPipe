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
    fun validateKotlinRequest(script: String, context: KotlinContext, pcpContext: PcpContext = PcpContext()): KotlinValidationResult
    {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        validateImports(script, context, errors)
        validateMemoryAccess(script, context, errors)
        validateFileAccess(script, context, errors)
        validateNetworkAccess(script, context, errors)
        validateProcessExecution(script, context, errors)
        validateReflection(script, context, errors)

        return KotlinValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    private fun validateImports(script: String, context: KotlinContext, errors: MutableList<String>)
    {
        val importPattern = Regex("import\\s+([\\w.*]+)")
        val imports = importPattern.findAll(script).map { it.groupValues[1] }.toList()

        if (context.allowedImports.isNotEmpty())
        {
            imports.forEach { import ->
                val isAllowed = context.allowedImports.any { allowed ->
                    import == allowed || import.startsWith("$allowed.")
                }
                if (!isAllowed)
                {
                    errors.add("Import '$import' is not allowed")
                }
            }
        }
        else
        {
            val blockedImports = if (context.blockedImports.isNotEmpty())
                context.blockedImports.toSet()
            else
                KotlinConstants.DANGEROUS_IMPORTS

            imports.forEach { import ->
                val isBlocked = blockedImports.any { blocked ->
                    import == blocked || import.startsWith("$blocked.")
                }
                if (isBlocked)
                {
                    errors.add("Import '$import' is not allowed")
                }
            }
        }

        if (context.allowedPackages.isNotEmpty())
        {
            imports.forEach { import ->
                val packageName = import.substringBeforeLast(".", "")
                val isAllowed = context.allowedPackages.any { allowed ->
                    packageName.startsWith(allowed)
                }
                if (!isAllowed && packageName.isNotEmpty())
                {
                    errors.add("Package '$packageName' is not in allowlist")
                }
            }
        }
    }

    private fun validateMemoryAccess(script: String, context: KotlinContext, errors: MutableList<String>)
    {
        if (!context.allowTpipeIntrospection)
        {
            if (script.contains("PcpRegistry") || script.contains("PcpContext"))
            {
                errors.add("TPipe introspection is disabled")
            }
        }

        if (!context.allowHostApplicationAccess)
        {
            context.exposedBindings.keys.forEach { binding ->
                if (script.contains(binding))
                {
                    errors.add("Host application access is disabled, cannot use binding '$binding'")
                }
            }
        }

        if (!context.allowReflection)
        {
            val reflectionPatterns = listOf("::class", "KClass", "java.lang.reflect", "Class.forName")
            reflectionPatterns.forEach { pattern ->
                if (script.contains(pattern))
                {
                    errors.add("Reflection is disabled")
                    return
                }
            }
        }

        if (!context.allowClassLoaderAccess)
        {
            if (script.contains("ClassLoader") || script.contains("getClassLoader"))
            {
                errors.add("ClassLoader access is disabled")
            }
        }
    }

    private fun validateFileAccess(script: String, context: KotlinContext, errors: MutableList<String>)
    {
        val filePatterns = listOf("File(", "readText", "writeText", "delete(", "readBytes", "writeBytes")
        val hasFileOperations = filePatterns.any { script.contains(it) }

        if (hasFileOperations)
        {
            if (!context.allowFileRead && !context.allowFileWrite && !context.allowFileDelete)
            {
                errors.add("File operations are disabled")
            }
        }
    }

    private fun validateNetworkAccess(script: String, context: KotlinContext, errors: MutableList<String>)
    {
        if (!context.allowNetworkAccess)
        {
            val networkPatterns = listOf("Socket", "URL(", "HttpURLConnection", "ServerSocket")
            networkPatterns.forEach { pattern ->
                if (script.contains(pattern))
                {
                    errors.add("Network access is disabled")
                    return
                }
            }
        }
    }

    private fun validateProcessExecution(script: String, context: KotlinContext, errors: MutableList<String>)
    {
        if (!context.allowProcessExecution)
        {
            val processPatterns = listOf("ProcessBuilder", "Runtime.getRuntime", "exec(")
            processPatterns.forEach { pattern ->
                if (script.contains(pattern))
                {
                    errors.add("Process execution is disabled")
                    return
                }
            }
        }
    }

    private fun validateReflection(script: String, context: KotlinContext, errors: MutableList<String>)
    {
        if (!context.allowReflection)
        {
            KotlinConstants.DANGEROUS_PATTERNS.forEach { pattern ->
                if (Regex(pattern).containsMatchIn(script))
                {
                    errors.add("Pattern '$pattern' is not allowed (reflection disabled)")
                    return
                }
            }
        }
    }
}
