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
        validateGeneralSecurity(script, context, errors)

        return KotlinValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    private fun validateImports(script: String, context: KotlinContext, errors: MutableList<String>)
    {
        val importPattern = Regex("import\\s+([\\w.*]+)(?:\\s+as\\s+\\w+)?")
        val imports = importPattern.findAll(script).map { it.groupValues[1] }.toList()

        val blockedImports = if(context.blockedImports.isNotEmpty())
            context.blockedImports.toSet()
        else
            KotlinConstants.DANGEROUS_IMPORTS

        imports.forEach { import ->
            if(context.allowedImports.isNotEmpty())
            {
                val isAllowed = context.allowedImports.any { allowed ->
                    import == allowed || import.startsWith("$allowed.")
                }
                if(!isAllowed)
                {
                    errors.add("Import '$import' is not allowed")
                }
            }
            else
            {
                val isBlocked = blockedImports.any { blocked ->
                    import == blocked || import.startsWith("$blocked.") ||
                            (import.endsWith(".*") && blocked.startsWith(import.substringBeforeLast("*"))) ||
                            (blocked.endsWith(".*") && import.startsWith(blocked.substringBeforeLast("*")))
                }
                if(isBlocked)
                {
                    errors.add("Import '$import' is not allowed (blocked)")
                }
            }

            if(context.allowedPackages.isNotEmpty())
            {
                val packageName = import.substringBeforeLast(".", "")
                val isAllowed = context.allowedPackages.any { allowed ->
                    packageName.startsWith(allowed)
                }
                if(!isAllowed && packageName.isNotEmpty())
                {
                    errors.add("Package '$packageName' is not in allowlist")
                }
            }
        }
    }

    private fun validateGeneralSecurity(script: String, context: KotlinContext, errors: MutableList<String>)
    {
        // 1. Check for dangerous functions and qualified names
        val dangerousItems = KotlinConstants.DANGEROUS_FUNCTIONS + KotlinConstants.DANGEROUS_IMPORTS

        dangerousItems.forEach { item ->
            val itemPattern = Regex("\\b${item.replace(".", "\\.")}\\b")
            if(itemPattern.containsMatchIn(script))
            {
                errors.add("Potentially dangerous usage of '$item'")
            }
        }

        // 2. Check for dangerous patterns
        KotlinConstants.DANGEROUS_PATTERNS.forEach { pattern ->
            if(Regex(pattern).containsMatchIn(script))
            {
                errors.add("Dangerous pattern found: '$pattern'")
            }
        }

        // 3. Conditional security based on context permissions
        if(!context.allowReflection)
        {
            listOf("::class", "KClass", "java.lang.reflect", "kotlin.reflect").forEach { ref ->
                if(script.contains(ref)) errors.add("Reflection is disabled: '$ref'")
            }
        }

        if(!context.allowClassLoaderAccess)
        {
            if(script.contains("ClassLoader") || script.contains("getClassLoader"))
            {
                errors.add("ClassLoader access is disabled")
            }
        }

        if(!context.allowTpipeIntrospection)
        {
            if(script.contains("PcpRegistry") || script.contains("PcpContext"))
            {
                errors.add("TPipe introspection is disabled")
            }
        }

        if(!context.allowFileRead && !context.allowFileWrite && !context.allowFileDelete)
        {
            val filePatterns = listOf("File(", "FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "readText", "readLine", "readBytes")
            filePatterns.forEach { p ->
                if(script.contains(p)) errors.add("File access is restricted: '$p'")
            }
        }

        if(!context.allowNetworkAccess)
        {
            val networkPatterns = listOf("Socket", "URL(", "HttpURLConnection", "ServerSocket", "java.net")
            networkPatterns.forEach { p ->
                if(script.contains(p)) errors.add("Network access is restricted: '$p'")
            }
        }

        if(!context.allowProcessExecution)
        {
            val processPatterns = listOf("ProcessBuilder", "Runtime.getRuntime", "exec(", "java.lang.Process")
            processPatterns.forEach { p ->
                if(script.contains(p)) errors.add("Process execution is restricted: '$p'")
            }
        }
    }
}
