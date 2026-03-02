package com.TTT.PipeContextProtocol

/**
 * Generates context-aware PCP instructions for system prompt injection.
 * Creates dynamic security boundary descriptions based on actual PcpContext configuration.
 */
object PcpInstructionGenerator
{
    fun generateKotlinInstructions(kotlinOptions: KotlinContext, pcpContext: PcpContext): String
    {
        val sections = mutableListOf<String>()

        sections.add(generateMemoryAccessSection(kotlinOptions))
        sections.add(generateFileSystemSection(kotlinOptions, pcpContext))
        sections.add(generateImportSection(kotlinOptions))
        sections.add(generateCapabilitiesSection(kotlinOptions))

        return sections.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    private fun generateMemoryAccessSection(kotlinOptions: KotlinContext): String
    {
        val lines = mutableListOf<String>()
        lines.add("MEMORY ACCESS:")

        if (kotlinOptions.allowTpipeIntrospection)
        {
            lines.add("- TPipe introspection ENABLED: You can access PcpRegistry and PcpContext objects")
            lines.add("  - PcpRegistry: Execute additional PCP requests")
            lines.add("  - PcpContext: Inspect current security settings")
        }
        else
        {
            lines.add("- TPipe introspection DISABLED: Internal TPipe objects are not accessible")
        }

        if (kotlinOptions.allowHostApplicationAccess && kotlinOptions.exposedBindings.isNotEmpty())
        {
            lines.add("- Host application access ENABLED: Available bindings:")
            kotlinOptions.exposedBindings.forEach { (name, description) ->
                lines.add("  - $name: $description")
            }
        }
        else
        {
            lines.add("- Host application access DISABLED: No custom bindings available")
        }

        return lines.joinToString("\n")
    }

    private fun generateFileSystemSection(kotlinOptions: KotlinContext, pcpContext: PcpContext): String
    {
        val lines = mutableListOf<String>()
        lines.add("FILE SYSTEM ACCESS:")

        if (kotlinOptions.allowFileRead)
        {
            lines.add("- File read operations: ALLOWED")
        }
        else
        {
            lines.add("- File read operations: BLOCKED")
        }

        if (kotlinOptions.allowFileWrite)
        {
            lines.add("- File write operations: ALLOWED")
        }
        else
        {
            lines.add("- File write operations: BLOCKED")
        }

        if (kotlinOptions.allowFileDelete)
        {
            lines.add("- File delete operations: ALLOWED")
        }
        else
        {
            lines.add("- File delete operations: BLOCKED")
        }

        if (pcpContext.allowedDirectoryPaths.isNotEmpty())
        {
            lines.add("- File operations restricted to: ${pcpContext.allowedDirectoryPaths.joinToString(", ")}")
        }
        else
        {
            lines.add("- File operations allowed in any directory (no path restrictions)")
        }

        if (pcpContext.forbiddenDirectoryPaths.isNotEmpty())
        {
            lines.add("- Forbidden directories: ${pcpContext.forbiddenDirectoryPaths.joinToString(", ")}")
        }

        if (kotlinOptions.workingDirectory.isNotEmpty())
        {
            lines.add("- Working directory: ${kotlinOptions.workingDirectory}")
        }

        return lines.joinToString("\n")
    }

    private fun generateImportSection(kotlinOptions: KotlinContext): String
    {
        val lines = mutableListOf<String>()
        lines.add("IMPORTS AND PACKAGES:")

        if (kotlinOptions.allowedImports.isNotEmpty())
        {
            lines.add("- Import ALLOWLIST mode: Only these imports allowed:")
            lines.add("  ${kotlinOptions.allowedImports.joinToString(", ")}")
        }
        else if (kotlinOptions.blockedImports.isNotEmpty())
        {
            lines.add("- Import BLOCKLIST mode: These imports blocked:")
            lines.add("  ${kotlinOptions.blockedImports.joinToString(", ")}")
        }
        else
        {
            lines.add("- Default blocklist: java.io.File, ProcessBuilder, Runtime, java.net.Socket, etc.")
        }

        if (kotlinOptions.allowedPackages.isNotEmpty())
        {
            lines.add("- Package ALLOWLIST: ${kotlinOptions.allowedPackages.joinToString(", ")}")
        }

        if (kotlinOptions.blockedPackages.isNotEmpty())
        {
            lines.add("- Package BLOCKLIST: ${kotlinOptions.blockedPackages.joinToString(", ")}")
        }

        return lines.joinToString("\n")
    }

    private fun generateCapabilitiesSection(kotlinOptions: KotlinContext): String
    {
        val lines = mutableListOf<String>()
        lines.add("CAPABILITIES:")

        lines.add("- Network access: ${if (kotlinOptions.allowNetworkAccess) "ALLOWED" else "BLOCKED"}")
        lines.add("- Process execution: ${if (kotlinOptions.allowProcessExecution) "ALLOWED" else "BLOCKED"}")
        lines.add("- Reflection: ${if (kotlinOptions.allowReflection) "ALLOWED" else "BLOCKED"}")
        lines.add("- ClassLoader access: ${if (kotlinOptions.allowClassLoaderAccess) "ALLOWED" else "BLOCKED"}")
        lines.add("- Execution timeout: ${kotlinOptions.timeoutMs}ms")

        return lines.joinToString("\n")
    }

    fun generateCodeExecutionGuide(kotlinOptions: KotlinContext): String
    {
        return """
KOTLIN SCRIPT EXECUTION:
- Put Kotlin code in argumentsOrFunctionParams array
- Each element is a line or block of code
- Use println() to return output
- Code runs in JVM via script engine
- Example:
  {
    "kotlinContextOptions": {},
    "argumentsOrFunctionParams": ["val x = 10", "val y = 20", "println(x + y)"]
  }
        """.trimIndent()
    }

    fun generatePythonInstructions(pythonOptions: PythonContext, pcpContext: PcpContext): String
    {
        val sections = mutableListOf<String>()
        
        sections.add(generatePythonPackageSection(pythonOptions))
        sections.add(generatePythonFileSystemSection(pythonOptions, pcpContext))
        sections.add(generatePythonCapabilitiesSection(pythonOptions))
        
        return sections.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    private fun generatePythonPackageSection(pythonOptions: PythonContext): String
    {
        val lines = mutableListOf<String>()
        lines.add("PYTHON PACKAGES:")
        
        if (pythonOptions.availablePackages.isNotEmpty())
        {
            lines.add("- Package ALLOWLIST mode: Only these packages can be imported:")
            lines.add("  ${pythonOptions.availablePackages.joinToString(", ")}")
        }
        else
        {
            lines.add("- No package restrictions (all imports allowed)")
        }
        
        return lines.joinToString("\n")
    }

    private fun generatePythonFileSystemSection(pythonOptions: PythonContext, pcpContext: PcpContext): String
    {
        val lines = mutableListOf<String>()
        lines.add("FILE SYSTEM ACCESS:")
        
        if (pcpContext.allowedDirectoryPaths.isNotEmpty())
        {
            lines.add("- File operations restricted to: ${pcpContext.allowedDirectoryPaths.joinToString(", ")}")
        }
        else
        {
            lines.add("- File operations allowed in any directory (no path restrictions)")
        }
        
        if (pcpContext.forbiddenDirectoryPaths.isNotEmpty())
        {
            lines.add("- Forbidden directories: ${pcpContext.forbiddenDirectoryPaths.joinToString(", ")}")
        }
        
        if (pythonOptions.workingDirectory.isNotEmpty())
        {
            lines.add("- Working directory: ${pythonOptions.workingDirectory}")
        }
        else
        {
            lines.add("- Working directory: system default")
        }
        
        return lines.joinToString("\n")
    }

    private fun generatePythonCapabilitiesSection(pythonOptions: PythonContext): String
    {
        val lines = mutableListOf<String>()
        lines.add("CAPABILITIES:")
        
        if (pythonOptions.permissions.isNotEmpty())
        {
            lines.add("- Permissions: ${pythonOptions.permissions.joinToString(", ")}")
        }
        else
        {
            lines.add("- No explicit permissions configured")
        }
        
        lines.add("- Execution timeout: ${pythonOptions.timeoutMs}ms")
        
        if (pythonOptions.pythonVersion.isNotEmpty())
        {
            lines.add("- Python version: ${pythonOptions.pythonVersion}")
        }
        
        return lines.joinToString("\n")
    }

    fun generatePythonCodeExecutionGuide(): String
    {
        return """
PYTHON SCRIPT EXECUTION:
- Put Python code in argumentsOrFunctionParams array
- Each element is a line or block of code
- Use print() to return output
- Code runs in isolated subprocess
- Example:
  {
    "pythonContextOptions": {},
    "argumentsOrFunctionParams": ["import json", "data = {'result': 42}", "print(json.dumps(data))"]
  }
        """.trimIndent()
    }

    fun generateJavaScriptInstructions(javascriptOptions: JavaScriptContext, pcpContext: PcpContext): String
    {
        val sections = mutableListOf<String>()
        
        sections.add(generateJavaScriptModuleSection(javascriptOptions))
        sections.add(generateJavaScriptFileSystemSection(javascriptOptions, pcpContext))
        sections.add(generateJavaScriptCapabilitiesSection(javascriptOptions))
        
        return sections.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    private fun generateJavaScriptModuleSection(javascriptOptions: JavaScriptContext): String
    {
        val lines = mutableListOf<String>()
        lines.add("JAVASCRIPT MODULES:")
        
        if (javascriptOptions.allowedModules.isNotEmpty())
        {
            lines.add("- Module ALLOWLIST mode: Only these modules can be required:")
            lines.add("  ${javascriptOptions.allowedModules.joinToString(", ")}")
        }
        else
        {
            lines.add("- Default blocklist: fs, child_process, net, etc. are blocked")
        }
        
        return lines.joinToString("\n")
    }

    private fun generateJavaScriptFileSystemSection(javascriptOptions: JavaScriptContext, pcpContext: PcpContext): String
    {
        val lines = mutableListOf<String>()
        lines.add("FILE SYSTEM ACCESS:")
        
        if (pcpContext.allowedDirectoryPaths.isNotEmpty())
        {
            lines.add("- File operations restricted to: ${pcpContext.allowedDirectoryPaths.joinToString(", ")}")
        }
        else
        {
            lines.add("- File operations allowed in any directory (no path restrictions)")
        }
        
        if (pcpContext.forbiddenDirectoryPaths.isNotEmpty())
        {
            lines.add("- Forbidden directories: ${pcpContext.forbiddenDirectoryPaths.joinToString(", ")}")
        }
        
        if (javascriptOptions.workingDirectory.isNotEmpty())
        {
            lines.add("- Working directory: ${javascriptOptions.workingDirectory}")
        }
        else
        {
            lines.add("- Working directory: system default")
        }
        
        return lines.joinToString("\n")
    }

    private fun generateJavaScriptCapabilitiesSection(javascriptOptions: JavaScriptContext): String
    {
        val lines = mutableListOf<String>()
        lines.add("CAPABILITIES:")
        
        if (javascriptOptions.permissions.isNotEmpty())
        {
            lines.add("- Permissions: ${javascriptOptions.permissions.joinToString(", ")}")
        }
        else
        {
            lines.add("- No explicit permissions configured")
        }
        
        lines.add("- Execution timeout: ${javascriptOptions.timeoutMs}ms")
        
        if (javascriptOptions.nodePath.isNotEmpty())
        {
            lines.add("- Node.js path: ${javascriptOptions.nodePath}")
        }
        
        return lines.joinToString("\n")
    }

    fun generateJavaScriptCodeExecutionGuide(): String
    {
        return """
JAVASCRIPT SCRIPT EXECUTION:
- Put JavaScript code in argumentsOrFunctionParams array
- Each element is a line or block of code
- Use console.log() to return output
- Code runs via Node.js in subprocess
- Example:
  {
    "javascriptContextOptions": {},
    "argumentsOrFunctionParams": ["const result = 5 + 10;", "console.log(result);"]
  }
        """.trimIndent()
    }
}
