# Basic PCP Usage

## Table of Contents
- [Quick Start](#quick-start)
- [Shell Commands](#shell-commands)
- [HTTP Requests](#http-requests)
- [Filesystem Controls](#filesystem-controls)
- [Request Anatomy](#request-anatomy)
- [Handling Results](#handling-results)
- [Troubleshooting](#troubleshooting)

## Quick Start

PCP becomes active as soon as a pipe has a populated `PcpContext` and you enable tool use in your
system prompt. This minimal example exposes a single read-only shell command:

```kotlin
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.StdioContextOptions
import com.TTT.PipeContextProtocol.Permissions

fun minimalContext(): PcpContext = PcpContext().apply {
    addStdioOption(
        StdioContextOptions().apply {
            command = "ls"
            description = "List files in the working directory"
            permissions.add(Permissions.Read)
            timeoutMs = 3_000
        }
    )
}
```

Attach it to a pipe:

```kotlin
val pipe = myBedrockPipe()
pipe.setPcPContext(minimalContext())
pipe.setSystemPrompt("You may use PCP tools to inspect the workspace.")
```

## Shell Commands

### Command allowlists

Limit arguments the model may pass by supplying an allowlist. The executor enforces this list for
both default arguments (`args`) and runtime arguments supplied via `argumentsOrFunctionParams`:

```kotlin
val gitStatus = StdioContextOptions().apply {
    command = "git"
    description = "Read-only Git operations"
    args.addAll(listOf("status", "diff", "log"))
    permissions.addAll(listOf(Permissions.Read, Permissions.Execute))
}
```

If `argumentsOrFunctionParams` contains `push`, validation fails with `Argument 'push' is not in the allowed arguments list`.

### Execution modes

Interactive workflows require persistent sessions:

```kotlin
val shell = StdioContextOptions().apply {
    command = "bash"
    executionMode = StdioExecutionMode.INTERACTIVE
    keepSessionAlive = true
    bufferPersistence = true
    permissions.addAll(listOf(Permissions.Read, Permissions.Write, Permissions.Execute))
}
```

To reconnect later, pass the returned `sessionId` in a `CONNECT` request.

## HTTP Requests

Configure an endpoint with explicit method and host allowlists so
`HttpSecurityManager` passes validation:

```kotlin
import com.TTT.PipeContextProtocol.HttpContextOptions

val issues = HttpContextOptions().apply {
    baseUrl = "https://api.github.com"
    endpoint = "/repos/octocat/Hello-World/issues"
    method = "GET"
    allowedMethods.add("GET")
    allowedHosts.add("api.github.com")
    permissions.add(Permissions.Read)
    headers["Accept"] = "application/vnd.github+json"
    timeoutMs = 10_000
    description = "List public issues"
}
```

Attach the option with `context.addHttpOption(issues)` and ensure the pipe's PCP context also
includes any required auth tokens inside `authType`/`authCredentials` if needed.

## Filesystem Controls

Restrictions live on the enclosing `PcpContext` and apply to every transport. If the model submits a
path outside the allowlist, the command is rejected before execution.

```kotlin
val context = minimalContext().apply {
    allowedDirectoryPaths.addAll(listOf(
        "/home/app/project",
        "/tmp"
    ))
    forbiddenDirectoryPaths.add("/home/app/project/secret")
    allowedFiles.add("/home/app/project/config.yaml")
    forbiddenFiles.add("/home/app/project/.env")
}
```

## Request Anatomy

A typical stdio request produced by the LLM looks like this:

```json
{
  "requests": [
    {
      "stdioContextOptions": {
        "command": "ls",
        "executionMode": "ONE_SHOT",
        "timeoutMs": 3000
      },
      "argumentsOrFunctionParams": ["-la", "/home/app/project"],
      "httpContextOptions": {},
      "tPipeContextOptions": {},
      "pythonContextOptions": {}
    }
  ]
}
```

`PcpResponseParser.extractPcpRequests` can pull this structure from raw LLM output and validate it
with `PcpResponseParser.validatePcpRequest` before dispatch.

## Handling Results

Each executor returns a `PcpRequestResult`:

```kotlin
runBlocking {
    when (val result = dispatcher.executeRequest(request, context)) {
        else -> if (result.success) {
            println(result.output)
        } else {
            println("PCP error (${result.transport}): ${result.error}")
        }
    }
}
```

For batch executions, `PcpExecutionResult.errors` collects any failures while preserving successful
outputs.

## Troubleshooting

- **"Command ... exceeds maximum allowed level"**: grant additional `Permissions` or reclassify the
  command via `CommandSecurityManager.setCommandClassification`.
- **"Host ... is not in allowed hosts list"**: add the host to `HttpContextOptions.allowedHosts` or
  disable host enforcement by setting `HttpSecurityConfig(requireExplicitHosts = false)`.
- **"Python script is required"**: ensure the request populates `argumentsOrFunctionParams` with the
  script content when targeting the Python executor.
- **Timeout errors**: increase `timeoutMs` on the relevant context option. The executor terminates
  processes when the limit is reached.

With these building blocks you can progressively open more capabilities while keeping the model
inside a controlled sandbox. For deeper features—Python execution, native functions, and security
policies—see [Intermediate PCP Features](intermediate-pcp-features.md).
