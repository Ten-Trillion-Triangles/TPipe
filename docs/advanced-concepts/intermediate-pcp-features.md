# Intermediate PCP Features

## Table of Contents
- [Python Execution](#python-execution)
- [Configuring Python Security](#configuring-python-security)
- [Native Function Binding](#native-function-binding)
- [HTTP Security Levels](#http-security-levels)
- [Timeouts and Resource Controls](#timeouts-and-resource-controls)
- [Validation & Error Handling](#validation--error-handling)
- [Cross-Platform Patterns](#cross-platform-patterns)
- [Advanced Configuration Patterns](#advanced-configuration-patterns)
- [Next Steps](#next-steps)

## Python Execution

`PythonExecutor` allows models to run Python scripts with the safety constraints defined in the
`PythonContext`. Scripts arrive in `PcPRequest.argumentsOrFunctionParams` and are executed inside a
separate process.

```kotlin
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.PythonContext
import com.TTT.PipeContextProtocol.Permissions

fun pythonEnabledContext(): PcpContext = PcpContext().apply {
    pythonOptions = PythonContext().apply {
        pythonPath = "/usr/bin/python3"
        workingDirectory = "/home/app/analysis"
        availablePackages.addAll(listOf("json", "pandas", "numpy"))
        environmentVariables["PYTHONPATH"] = "/home/app/libs"
        timeoutMs = 60_000
        captureOutput = true
        permissions.addAll(listOf(Permissions.Read, Permissions.Execute))
    }
}
```

When the LLM submits a request with `argumentsOrFunctionParams` populated, the executor writes the
script to a temporary file, validates it with `PythonSecurityManager`, and runs it under the policy.

## Configuring Python Security

Security levels apply progressively stricter validation rules:

| Level        | Defaults                                                                 |
|--------------|---------------------------------------------------------------------------|
| `STRICT`     | 60s timeout, 1 MB script limit, dangerous imports/functions blocked.      |
| `BALANCED`   | 5 min timeout, 1 MB script limit, common data libs allowed.                |
| `PERMISSIVE` | 30 min timeout, 10 MB script limit, minimal filtering.                     |
| `DISABLED`   | No enforcement beyond the OS process sandbox.                             |

`PythonSecurityConfig` lets you override any aspect of the policy:

```kotlin
import com.TTT.PipeContextProtocol.PythonExecutor
import com.TTT.PipeContextProtocol.PythonSecurityLevel
import com.TTT.PipeContextProtocol.PythonSecurityConfig

val pythonExecutor = PythonExecutor()
pythonExecutor.setSecurityLevel(PythonSecurityLevel.BALANCED)

val granularConfig = PythonSecurityConfig(
    level = PythonSecurityLevel.BALANCED,
    maxTimeoutMs = 90_000,
    maxScriptSize = 1_048_576,
    requirePermissions = true,
    allowedImports = setOf("pandas", "numpy"),
    allowedFunctions = setOf("open", "print"),
    allowedPatterns = setOf("from\\s+datetime\\s+import")
)
pythonExecutor.setSecurityConfig(granularConfig)
```

**Note**: Package whitelists are managed through the `PythonContext` rather than `PythonSecurityConfig`. The merged whitelist comes from the context + request during execution.

You can selectively re-enable blocked behaviour using helper methods:

```kotlin
pythonExecutor.allowImports("subprocess")
pythonExecutor.allowFunctions("exec")
pythonExecutor.allowPatterns("import\\s+socket")
```

Whenever an override is used, the executor adds a `Warnings:` preamble to the output so reviews can
spot that the script relied on an expanded policy. For example, after calling
`pythonExecutor.allowImports("subprocess")` the warning list contains
“Import 'subprocess' allowed via security override”.

`PythonContext.availablePackages` now merges the request and context lists before validation. A
module is accepted when it appears in *either* list; if both are empty the executor permits any
import. Attempting to load a package that is missing from the combined whitelist yields
`Import '<module>' not in allowed packages list` and the script is not executed.

## Native Function Binding

Binding native Kotlin functions exposes them to PCP as structured tools. The runtime handles
argument conversion using `FunctionInvoker` and `TypeConverter`.

### Automatic signature detection

Use this approach for named functions where reflection can determine the signature:

```kotlin
import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.PcpContext

fun calculateSum(a: Int, b: Int): Int = a + b

fun registerAutomatic(context: PcpContext) {
    val signature = FunctionRegistry.registerFunction("calculateSum", ::calculateSum)
    context.bindFunction(signature.name, ::calculateSum)
}
```

Optional parameters keep their Kotlin default values because the wrapper delegates to `callBy`/`callSuspendBy`. You can omit optional PCP arguments and the runtime substitutes the same default your Kotlin signature declares. Enum parameters are validated against their `enumValues`; if the model supplies anything else the dispatcher rejects the request before your function runs.

`bindFunction` converts the signature into a `TPipeContextOptions` entry so the model receives type
information in the prompt.

### Manual signatures for lambdas

Lambdas need explicit metadata. Construct a `FunctionSignature` manually and register it with the
lambda value:

```kotlin
import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.FunctionSignature
import com.TTT.PipeContextProtocol.ParameterInfo
import com.TTT.PipeContextProtocol.ReturnTypeInfo
import com.TTT.PipeContextProtocol.ParamType
import com.TTT.PipeContextProtocol.Permissions
import com.TTT.PipeContextProtocol.TPipeContextOptions
import com.TTT.PipeContextProtocol.PcpContext

val formatData = { data: String, format: String ->
    when (format.lowercase()) {
        "json" -> """{"data": "$data"}"""
        "csv" -> "data\n$data"
        else -> data
    }
}

val formatSignature = FunctionSignature(
    name = "formatData",
    description = "Format text as JSON or CSV",
    parameters = listOf(
        ParameterInfo("data", ParamType.String, "kotlin.String"),
        ParameterInfo(
            name = "format",
            type = ParamType.Enum,
            kotlinType = "kotlin.String",
            enumValues = listOf("json", "csv", "raw"),
            description = "Output format"
        )
    ),
    returnType = ReturnTypeInfo(ParamType.String, "kotlin.String"),
    permissions = listOf(Permissions.Read)
)

fun registerManual(context: PcpContext) {
    FunctionRegistry.registerLambda("formatData", formatData, formatSignature)
    context.addTPipeOption(
        TPipeContextOptions().fromFunctionSignature(formatSignature)
    )
}
```

`ReturnValueHandler` stores complex return values so later requests can retrieve results using the
returned key if necessary.

## HTTP Security Levels

`HttpSecurityManager` enforces per-transport rules via `HttpSecurityConfig`:

```kotlin
import com.TTT.PipeContextProtocol.HttpExecutor
import com.TTT.PipeContextProtocol.HttpSecurityConfig
import com.TTT.PipeContextProtocol.HttpSecurityLevel

val httpExecutor = HttpExecutor()
httpExecutor.setSecurityLevel(HttpSecurityLevel.BALANCED)

val customHttp = HttpSecurityConfig(
    level = HttpSecurityLevel.BALANCED,
    maxTimeoutMs = 60_000,
    maxRequestBodySize = 512_000,
    maxHeaders = 15,
    requireExplicitHosts = true,
    requireExplicitMethods = true,
    requirePermissions = true,
    allowPrivateNetworks = false
)
httpExecutor.setSecurityConfig(customHttp)
```

Permissive settings are useful for trusted networks, while production deployments should leave host
and method allowlists enabled.

## Timeouts and Resource Controls

Every context option exposes `timeoutMs`; the executors terminate processes when they exceed the
specified limit.

```kotlin
val findCommand = StdioContextOptions().apply {
    command = "find"
    permissions.addAll(listOf(Permissions.Read, Permissions.Execute))
    timeoutMs = 60_000
}

val httpOption = HttpContextOptions().apply {
    baseUrl = "https://example.com"
    method = "POST"
    timeoutMs = 45_000
}

val pythonContext = PythonContext().apply {
    timeoutMs = 120_000
}
```

For stdio output capture, adjust `maxBufferSize` to prevent unexpectedly large responses from
flooding memory.

## Validation & Error Handling

- `PcpResponseParser.validatePcpRequest` checks that the request contains the minimum information
  for its chosen transport before execution.
- `CommandSecurityManager.validateCommand` enforces the highest security level implied by granted
  permissions (`Delete` > `Execute` > `Write` > `Read`).
- `HttpSecurityManager.validateHttpRequest` returns detailed `HttpValidationResult` errors and
  warnings so you can surface actionable feedback to the caller.
- `PythonSecurityManager.validatePythonRequest` ensures scripts obey the security policy before
  they spawn a Python process.

Handle executor output uniformly via `PcpRequestResult`:

```kotlin
fun logResult(result: PcpRequestResult) {
    if (!result.success) {
        println("${result.transport} failed: ${result.error}")
    } else {
        println(result.output)
    }
}
```

## Cross-Platform Patterns

Values such as command names, file paths, and interpreter locations often depend on the host OS.
Use `CommandSecurityManager` and `PythonPlatformManager` logic as a guide when preparing contexts.

```kotlin
fun defaultPythonContext(): PythonContext {
    val ctx = PythonContext()
    val os = System.getProperty("os.name").lowercase()
    when {
        os.contains("windows") -> ctx.pythonPath = "C:/Python311/python.exe"
        os.contains("mac") -> ctx.pythonPath = "/opt/homebrew/bin/python3"
        else -> ctx.pythonPath = "/usr/bin/python3"
    }
    return ctx
}
```

Split stdio options per platform if you expose commands such as `dir` (Windows) versus `ls`
(Unix-like).

## Advanced Configuration Patterns

### Conditional permissions

```kotlin
fun contextForRole(role: String): PcpContext = PcpContext().apply {
    val perms = when (role) {
        "admin" -> listOf(Permissions.Read, Permissions.Write, Permissions.Execute, Permissions.Delete)
        "developer" -> listOf(Permissions.Read, Permissions.Write, Permissions.Execute)
        "analyst" -> listOf(Permissions.Read, Permissions.Write)
        else -> listOf(Permissions.Read)
    }

    addStdioOption(
        StdioContextOptions().apply {
            command = "cat"
            permissions.addAll(perms)
        }
    )
}
```

### Environment-specific configuration

```kotlin
fun contextForEnvironment(environment: String): PcpContext = PcpContext().apply {
    val pythonCtx = PythonContext().apply {
        pythonPath = "/usr/bin/python3"
        permissions.addAll(listOf(Permissions.Read, Permissions.Execute))
    }

    when (environment) {
        "development" -> {
            allowedDirectoryPaths.add("/home/dev/workspace")
            pythonCtx.timeoutMs = 180_000
        }
        "production" -> {
            allowedDirectoryPaths.add("/srv/app/data")
            forbiddenDirectoryPaths.addAll(listOf("/srv/app/secrets", "/etc"))
            pythonCtx.timeoutMs = 30_000
        }
    }

    pythonOptions = pythonCtx
}
```

Ensure you clone the base `pythonOptions` if you mutate it in place when sharing a `PcpContext`
instance across requests.

## Next Steps

**→ [PCP Kotlin and JavaScript Support](pcp-kotlin-javascript.md)** - Kotlin/JS scripting in PCP
