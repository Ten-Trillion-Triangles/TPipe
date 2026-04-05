# PCP Kotlin and JavaScript Support

## Table of Contents
- [Overview](#overview)
- [When to Use Each Language](#when-to-use-each-language)
- [Kotlin Scripting](#kotlin-scripting)
- [JavaScript Scripting](#javascript-scripting)
- [Security Managers](#security-managers)
- [Complete Examples](#complete-examples)
- [Language Comparison](#language-comparison)

## Overview

TPipe's Pipe Context Protocol (PCP) supports Kotlin and JavaScript scripting alongside Python and native functions. This enables LLMs to execute type-safe JVM code (Kotlin) or leverage the Node.js ecosystem (JavaScript) for tool execution.

**Key Features:**
- **Kotlin**: JVM-based scripting with full type safety and Kotlin stdlib access
- **JavaScript**: Node.js execution with npm module support
- **Security**: Dedicated security managers for each language
- **Bindings**: Expose custom objects to scripts
- **Validation**: Pre-execution security checks

## When to Use Each Language

### Use Kotlin When:
- Need JVM interoperability and type safety
- Want to expose TPipe internals (PcpRegistry, ContextBank)
- Require fast in-process execution
- Working with Java libraries
- Need reflection or advanced JVM features

### Use JavaScript When:
- Need Node.js ecosystem and npm packages
- Working with JSON-heavy APIs
- Require async/await patterns
- Leveraging existing JavaScript libraries
- Need process isolation (runs in separate Node.js process)

### Use Python When:
- Data science and machine learning tasks
- Scientific computing (numpy, pandas)
- Rapid prototyping
- Existing Python codebases

### Use Native Functions When:
- Maximum performance required
- Complex TPipe integration
- Type-safe compile-time checking
- Reusable components across pipelines

## Kotlin Scripting

### Configuration

```kotlin
import com.TTT.PipeContextProtocol.*

val pcpContext = PcpContext()

// Configure Kotlin execution
pcpContext.kotlinOptions = KotlinContext().apply {
    // Allow specific imports
    allowedImports.addAll(listOf(
        "kotlin.math.*",
        "kotlin.collections.*"
    ))
    
    // Block dangerous imports
    blockedImports.addAll(listOf(
        "java.lang.Runtime",
        "java.lang.ProcessBuilder"
    ))
    
    // Allow specific packages
    allowedPackages.addAll(listOf("kotlin.math", "kotlin.text"))
    
    // Security flags
    allowTpipeIntrospection = false  // Expose PcpRegistry, PcpContext
    allowHostApplicationAccess = false  // Expose custom bindings
    allowReflection = false
    allowClassLoaderAccess = false
    allowSystemAccess = false
    
    // Timeout
    timeoutMs = 5000
}
```

### Context Options

```kotlin
data class KotlinContext(
    var allowedImports: MutableList<String> = mutableListOf(),
    var blockedImports: MutableList<String> = mutableListOf(),
    var allowedPackages: MutableList<String> = mutableListOf(),
    var blockedPackages: MutableList<String> = mutableListOf(),
    var allowTpipeIntrospection: Boolean = false,
    var allowHostApplicationAccess: Boolean = false,
    var exposedBindings: MutableMap<String, String> = mutableMapOf(),
    var allowReflection: Boolean = false,
    var allowClassLoaderAccess: Boolean = false,
    var allowSystemAccess: Boolean = false,
    var timeoutMs: Int = 30000
)
```

### Custom Bindings

```kotlin
import com.TTT.PipeContextProtocol.KotlinExecutor

val executor = KotlinExecutor()

// Register custom objects
executor.registerBinding("config", myConfigObject, "Application configuration")
executor.registerBinding("database", dbConnection, "Database connection")

// Enable in context
pcpContext.kotlinOptions.allowHostApplicationAccess = true
pcpContext.kotlinOptions.exposedBindings["config"] = "Application configuration"
pcpContext.kotlinOptions.exposedBindings["database"] = "Database connection"
```

### Execution Example

```kotlin
import com.TTT.PipeContextProtocol.*

val pcpContext = PcpContext()
pcpContext.kotlinOptions = KotlinContext().apply {
    allowedImports.add("kotlin.math.*")
    timeoutMs = 5000
}

val request = PcPRequest().apply {
    kotlinContextOptions = KotlinContext()
    argumentsOrFunctionParams.add("""
        import kotlin.math.sqrt
        import kotlin.math.pow
        
        val a = 3.0
        val b = 4.0
        val c = sqrt(a.pow(2) + b.pow(2))
        
        println("Hypotenuse: ${'$'}c")
        c
    """.trimIndent())
}

val executor = KotlinExecutor()
val result = executor.execute(request, pcpContext)

println("Success: ${result.success}")
println("Output: ${result.output}")
```

## JavaScript Scripting

### Configuration

```kotlin
import com.TTT.PipeContextProtocol.*

val pcpContext = PcpContext()

// Configure JavaScript execution
pcpContext.javascriptOptions = JavaScriptContext().apply {
    // Node.js path (defaults to "node" in PATH)
    nodePath = "/usr/bin/node"
    
    // Allowed npm modules
    allowedModules.addAll(listOf("fs", "path", "crypto"))
    
    // Working directory
    workingDirectory = "/tmp/js-workspace"
    
    // Environment variables
    environmentVariables["NODE_ENV"] = "production"
    
    // Permissions
    permissions.addAll(listOf(Permissions.Read, Permissions.Execute))
    
    // Timeout
    timeoutMs = 10000
}
```

### Context Options

```kotlin
data class JavaScriptContext(
    var nodePath: String = "",
    var allowedModules: MutableList<String> = mutableListOf(),
    var workingDirectory: String = "",
    var environmentVariables: MutableMap<String, String> = mutableMapOf(),
    var permissions: MutableList<Permissions> = mutableListOf(),
    var timeoutMs: Int = 30000
)
```

### Execution Example

```kotlin
import com.TTT.PipeContextProtocol.*

val pcpContext = PcpContext()
pcpContext.javascriptOptions = JavaScriptContext().apply {
    allowedModules.add("crypto")
    timeoutMs = 5000
}

val request = PcPRequest().apply {
    javascriptContextOptions = JavaScriptContext()
    argumentsOrFunctionParams.add("""
        const crypto = require('crypto');
        
        const hash = crypto.createHash('sha256');
        hash.update('Hello, TPipe!');
        const result = hash.digest('hex');
        
        console.log('SHA256:', result);
    """.trimIndent())
}

val executor = JavaScriptExecutor()
val result = executor.execute(request, pcpContext)

println("Success: ${result.success}")
println("Output: ${result.output}")
```

## Security Managers

### KotlinSecurityManager

Validates Kotlin scripts before execution:

```kotlin
class KotlinSecurityManager
{
    fun validateKotlinRequest(
        script: String,
        options: KotlinContext,
        context: PcpContext
    ): ValidationResult
}
```

**Checks:**
- Import statements against allowlists/blocklists
- Package usage restrictions
- Reflection usage (if disabled)
- ClassLoader access (if disabled)
- System class access (if disabled)

### JavaScriptSecurityManager

Validates JavaScript scripts before execution:

```kotlin
class JavaScriptSecurityManager
{
    fun validateJavaScriptRequest(
        script: String,
        options: JavaScriptContext
    ): ValidationResult
}
```

**Checks:**
- `require()` statements against allowed modules
- Dangerous patterns (eval, Function constructor)
- File system access patterns
- Network access patterns

### Validation Example

```kotlin
import com.TTT.PipeContextProtocol.*

val securityManager = KotlinSecurityManager()

val script = """
    import java.lang.Runtime
    Runtime.getRuntime().exec("rm -rf /")
"""

val options = KotlinContext().apply {
    blockedImports.add("java.lang.Runtime")
}

val validation = securityManager.validateKotlinRequest(script, options, PcpContext())

if (!validation.isValid)
{
    println("Security violation: ${validation.errors.joinToString(", ")}")
}
```

## Complete Examples

### Kotlin: Data Processing with Type Safety

```kotlin
import com.TTT.PipeContextProtocol.*
import bedrockPipe.BedrockPipe

suspend fun kotlinDataProcessing()
{
    val pipe = BedrockPipe()
        .setRegion("us-east-1")
        .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    
    val pcpContext = PcpContext()
    pcpContext.kotlinOptions = KotlinContext().apply {
        allowedImports.addAll(listOf(
            "kotlin.math.*",
            "kotlin.collections.*"
        ))
        timeoutMs = 5000
    }
    
    // Add Kotlin tool
    pcpContext.addKotlinOption(KotlinContextOptions().apply {
        description = "Process numerical data with Kotlin"
        permissions.add(Permissions.Execute)
    })
    
    pipe.setPcpContext(pcpContext)
    pipe.setSystemPrompt("""
        You have access to Kotlin scripting for data processing.
        Use it to perform calculations and data transformations.
    """.trimIndent())
    
    val result = pipe.execute("""
        Calculate the standard deviation of these numbers: 10, 20, 30, 40, 50
    """.trimIndent())
    
    println(result.text)
}
```

### JavaScript: JSON Processing with Node.js

```kotlin
import com.TTT.PipeContextProtocol.*
import bedrockPipe.BedrockPipe

suspend fun javascriptJsonProcessing()
{
    val pipe = BedrockPipe()
        .setRegion("us-east-1")
        .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    
    val pcpContext = PcpContext()
    pcpContext.javascriptOptions = JavaScriptContext().apply {
        allowedModules.addAll(listOf("fs", "path"))
        workingDirectory = "/tmp/data"
        timeoutMs = 10000
    }
    
    // Add JavaScript tool
    pcpContext.addJavaScriptOption(JavaScriptContextOptions().apply {
        description = "Process JSON data with JavaScript"
        permissions.addAll(listOf(Permissions.Read, Permissions.Execute))
    })
    
    pipe.setPcpContext(pcpContext)
    pipe.setSystemPrompt("""
        You have access to JavaScript/Node.js for JSON processing.
        Use it to parse, transform, and analyze JSON data.
    """.trimIndent())
    
    val result = pipe.execute("""
        Parse this JSON and extract all email addresses:
        {"users": [{"name": "Alice", "email": "alice@example.com"}, {"name": "Bob", "email": "bob@example.com"}]}
    """.trimIndent())
    
    println(result.text)
}
```

### Multi-Language PCP Context

```kotlin
import com.TTT.PipeContextProtocol.*

fun createMultiLanguageContext(): PcpContext
{
    val context = PcpContext()
    
    // Kotlin for JVM tasks
    context.kotlinOptions = KotlinContext().apply {
        allowedImports.add("kotlin.math.*")
        timeoutMs = 5000
    }
    
    context.addKotlinOption(KotlinContextOptions().apply {
        description = "Execute Kotlin code for mathematical operations"
    })
    
    // JavaScript for Node.js tasks
    context.javascriptOptions = JavaScriptContext().apply {
        allowedModules.addAll(listOf("crypto", "fs"))
        timeoutMs = 10000
    }
    
    context.addJavaScriptOption(JavaScriptContextOptions().apply {
        description = "Execute JavaScript code for JSON and crypto operations"
    })
    
    // Python for data science
    context.pythonOptions = PythonContext().apply {
        availablePackages.addAll(listOf("json", "math"))
        timeoutMs = 15000
    }
    
    // Native TPipe functions
    context.bindFunction("getContextBank") {
        ContextBank.getBankedContextWindow()
    }
    
    return context
}
```

## Language Comparison

| Feature | Kotlin | JavaScript | Python | Native |
|---------|--------|------------|--------|--------|
| **Execution** | In-process (JVM) | External (Node.js) | External (Python) | In-process (JVM) |
| **Performance** | Fast | Medium | Medium | Fastest |
| **Type Safety** | Strong | Weak | Weak | Strong |
| **Ecosystem** | JVM libraries | npm packages | pip packages | Kotlin/Java |
| **Isolation** | Shared JVM | Process isolated | Process isolated | Shared JVM |
| **Startup Time** | Fast | Medium | Medium | Instant |
| **Memory** | Shared heap | Separate process | Separate process | Shared heap |
| **TPipe Access** | Direct | None | None | Direct |
| **Best For** | JVM integration | JSON/async | Data science | Core features |

### Performance Characteristics

**Kotlin:**
- Startup: ~10-50ms (script engine initialization)
- Execution: Native JVM speed
- Memory: Shared with TPipe process

**JavaScript:**
- Startup: ~50-200ms (Node.js process spawn)
- Execution: V8 engine speed
- Memory: Separate Node.js process

**Recommendations:**
- **High-frequency calls**: Use Kotlin or Native functions
- **Heavy computation**: Use Kotlin for JVM, JavaScript for async I/O
- **One-off tasks**: Any language suitable
- **Security-critical**: Use JavaScript for process isolation

## See Also

- [Pipe Context Protocol Overview](pipe-context-protocol.md) - Complete PCP guide
- [Basic PCP Usage](basic-pcp-usage.md) - Getting started with PCP
- [Intermediate PCP Features](intermediate-pcp-features.md) - Advanced PCP capabilities
- [PCP API Reference](../api/pipe-context-protocol.md) - Complete API documentation
## Next Steps

- [Advanced Session Management](advanced-session-management.md) - Continue into interactive stdio sessions and buffers.
