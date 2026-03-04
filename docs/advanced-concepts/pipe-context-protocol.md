# Pipe Context Protocol (PCP)

## Table of Contents
- [Overview](#overview)
- [Transports and Executors](#transports-and-executors)
- [Building a PCP Context](#building-a-pcp-context)
- [Context Option Reference](#context-option-reference)
- [Applying PCP to a Pipe](#applying-pcp-to-a-pipe)
- [PCP Requests and Responses](#pcp-requests-and-responses)
- [Security Layers](#security-layers)
- [Next Steps](#next-steps)

## Overview

Pipe Context Protocol (PCP) is TPipe's unified function-calling system. It lets an LLM invoke
approved tools—shell commands, HTTP calls, Python scripts, or native Kotlin functions—through a
single, validated interface. Every tool the model can reach is declared in a `PcpContext`, which is
then attached to a pipe before generation.

Key traits:
- **Declarative**: you describe the allowed actions once; the runtime enforces them everywhere.
- **Transport-agnostic**: irrespective of tool type, requests flow through a single dispatcher.
- **Guarded**: all executors share common validation and per-transport security layers.

## Transports and Executors

Each transport is backed by an executor that implements the `PcpExecutor` interface.

| Transport     | Executor class             | Typical usage                               |
|---------------|----------------------------|----------------------------------------------|
| `Stdio`       | `StdioExecutor`            | Shell commands, CLIs, interactive sessions   |
| `Http`        | `HttpExecutor`             | REST APIs, webhooks, internal services       |
| `Python`      | `PythonExecutor`           | Ad-hoc scripts, analysis, automation         |
| `Kotlin`      | `KotlinExecutor`           | JVM scripting, type-safe code execution      |
| `JavaScript`  | `JavaScriptExecutor`       | Node.js scripts, npm packages, async I/O     |
| `Tpipe`       | `PcpFunctionHandler`       | Registered Kotlin functions and lambdas      |

`PcpExecutionDispatcher` chooses the executor based on the populated portion of the
`PcPRequest`. The dispatcher also aggregates results when a response contains multiple requests.

**See Also:** [PCP Kotlin and JavaScript Support](pcp-kotlin-javascript.md) for detailed coverage of Kotlin and JavaScript scripting.

## Building a PCP Context

`PcpContext` collects the transports you want to expose and optional filesystem restrictions.

```kotlin
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.StdioContextOptions
import com.TTT.PipeContextProtocol.HttpContextOptions
import com.TTT.PipeContextProtocol.PythonContext
import com.TTT.PipeContextProtocol.Permissions

fun buildContext(): PcpContext {
    val context = PcpContext()

    // Shell access: read-only `ls`
    val ls = StdioContextOptions().apply {
        command = "ls"
        permissions.add(Permissions.Read)
        description = "List directory contents"
        timeoutMs = 5_000
    }
    context.addStdioOption(ls)

    // HTTP access: GitHub API
    val github = HttpContextOptions().apply {
        baseUrl = "https://api.github.com"
        endpoint = "/repos/owner/repo"
        method = "GET"
        allowedMethods.add("GET")
        allowedHosts.add("api.github.com")
        permissions.add(Permissions.Read)
        description = "Fetch repository metadata"
    }
    context.addHttpOption(github)

    // Python execution sandbox
    context.pythonOptions = PythonContext().apply {
        pythonPath = "/usr/bin/python3"
        workingDirectory = "/home/app/analysis"
        availablePackages.addAll(listOf("json", "statistics"))
        permissions.addAll(listOf(Permissions.Read, Permissions.Execute))
        timeoutMs = 30_000
    }

    // Restrict filesystem scope when commands reference paths
    context.allowedDirectoryPaths.addAll(listOf(
        "/home/app",
        "/tmp"
    ))
    context.forbiddenDirectoryPaths.add("/home/app/secrets")

    return context
}
```

## Context Option Reference

### `StdioContextOptions`

| Field                   | Purpose                                                            |
|-------------------------|--------------------------------------------------------------------|
| `command`               | Binary or script to run.                                           |
| `args`                  | Default arguments (overridden by request arguments if provided).   |
| `permissions`           | Allowed actions (`Read`, `Write`, `Execute`, `Delete`).            |
| `description`           | Natural-language instruction for the model.                        |
| `executionMode`         | `ONE_SHOT`, `INTERACTIVE`, `CONNECT`, or `BUFFER_REPLAY`.          |
| `sessionId`/`bufferId`  | Routing for `CONNECT` or `BUFFER_REPLAY` modes.                    |
| `workingDirectory`      | Execution directory.                                               |
| `environmentVariables`  | Key/value pairs injected into the process.                         |
| `timeoutMs`             | Millisecond timeout for the operation.                             |
| `keepSessionAlive`      | Leave the process running after command completion.                |
| `bufferPersistence`     | Persist interactive IO to buffers managed by `StdioBufferManager`. |
| `maxBufferSize`         | Maximum bytes captured from stdout/stderr per execution.           |

### `TPipeContextOptions`

| Field          | Purpose                                              |
|----------------|------------------------------------------------------|
| `functionName` | Registry key returned by `FunctionRegistry`.         |
| `description`  | Prompting hint for the LLM.                          |
| `params`       | Map of parameter metadata (`ParamType`, description, enum values).

Populate this via `TPipeContextOptions.fromFunctionSignature` or `PcpContext.bindFunction` to
ensure parameter metadata stays in sync with registered functions.

### `HttpContextOptions`

| Field             | Purpose                                                                      |
|-------------------|------------------------------------------------------------------------------|
| `baseUrl`         | Scheme and host portion of the URL.                                          |
| `endpoint`        | Path/query segment appended to `baseUrl`.                                    |
| `method`          | HTTP method (e.g. `GET`, `POST`).                                            |
| `requestBody`     | Default body payload.                                                        |
| `allowedMethods`  | Optional allowlist enforced by `HttpSecurityManager`.                        |
| `headers`         | Default headers.                                                             |
| `authType`        | `""`, `"BASIC"`, `"BEARER"`, or `"APIKEY"`.                               |
| `authCredentials` | Credential map keyed by type (e.g. `token`, `username`, `password`).         |
| `allowedHosts`    | Host allowlist for SSRF protection.                                          |
| `followRedirects` | Whether redirects are permitted.                                             |
| `timeoutMs`       | Request timeout in milliseconds.                                             |
| `permissions`     | Required permission set (typically `Read` / `Write`).                        |
| `description`     | LLM-facing explanation of the endpoint.                                      |

### `PythonContext`

| Field                  | Purpose                                                          |
|------------------------|------------------------------------------------------------------|
| `availablePackages`    | Whitelist of importable top-level packages.                     |
| `pythonVersion`        | Optional version hint (`major.minor` prefix).                    |
| `pythonPath`           | Interpreter path.                                                |
| `workingDirectory`     | Directory resolved before script execution.                      |
| `environmentVariables` | Environment injected into the interpreter process.               |
| `timeoutMs`            | Maximum runtime.                                                 |
| `captureOutput`        | Whether stdout/stderr should be returned.                        |
| `permissions`          | Required permissions (e.g. `Read`, `Execute`).                   |

### `KotlinContext`

| Field                      | Purpose                                                          |
|----------------------------|------------------------------------------------------------------|
| `allowedImports`           | Whitelist of allowed import statements.                         |
| `blockedImports`           | Blacklist of forbidden import statements.                        |
| `allowedPackages`          | Whitelist of allowed package prefixes.                           |
| `blockedPackages`          | Blacklist of forbidden package prefixes.                         |
| `allowTpipeIntrospection`  | Whether to expose PcpRegistry and PcpContext to scripts.         |
| `allowHostApplicationAccess` | Whether to expose custom bindings to scripts.                  |
| `exposedBindings`          | Map of binding names to descriptions for custom objects.         |
| `allowReflection`          | Whether reflection API usage is permitted.                       |
| `allowClassLoaderAccess`   | Whether ClassLoader access is permitted.                         |
| `allowSystemAccess`        | Whether System class access is permitted.                        |
| `timeoutMs`                | Maximum runtime in milliseconds.                                 |

### `JavaScriptContext`

| Field                  | Purpose                                                          |
|------------------------|------------------------------------------------------------------|
| `nodePath`             | Path to Node.js executable (defaults to "node" in PATH).        |
| `allowedModules`       | Whitelist of npm modules that can be required.                   |
| `workingDirectory`     | Directory for script execution.                                  |
| `environmentVariables` | Environment variables passed to Node.js process.                 |
| `permissions`          | Required permissions (e.g. `Read`, `Execute`).                   |
| `timeoutMs`            | Maximum runtime in milliseconds.                                 |

**For detailed Kotlin and JavaScript configuration, see:** [PCP Kotlin and JavaScript Support](pcp-kotlin-javascript.md)

## Applying PCP to a Pipe

Attach the context to a pipe so the dispatcher can evaluate model tool calls.

```kotlin
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.Pipe.Pipe

fun configurePipe(pipe: Pipe) {
    val context = buildContext()
    pipe.setPcPContext(context)
}
```

When you subsequently call `pipe.generateText()` or `pipe.execute()`, the runtime serialises the context into the
system prompt. Any PCP requests produced by the LLM will be validated against the same context.
To process PCP replies, call `Pipe.processPcpResponse(llmResponse)` after getting the LLM response.

## PCP Requests and Responses

### Request structure

`PcPRequest` contains four context option blocks plus an optional positional-argument list:

```json
{
  "stdioContextOptions": {
    "command": "ls",
    "executionMode": "ONE_SHOT"
  },
  "argumentsOrFunctionParams": ["-la"],
  "httpContextOptions": {},
  "tPipeContextOptions": {},
  "pythonContextOptions": {}
}
```

`PcpExecutionDispatcher.executeRequests` takes a list of requests and returns a
`PcpExecutionResult` comprised of individual `PcpRequestResult` entries. Each result records
success, output, elapsed time, transport, and an optional error message.

### Request parsing

Use `PcpResponseParser` when you need to extract PCP payloads from an LLM response directly. The
parser repairs common JSON issues and validates that each request contains the minimum context
needed for the chosen transport.

## Security Layers

Each executor applies dedicated safeguards on top of the base context:

- **CommandSecurityManager** enforces command classification, validates arguments, screens
  filesystem access against `PcpContext` allow/deny lists, and detects injection patterns.
- **HttpSecurityManager** checks permissions, required host/method allowlists, request sizes,
  and blocks requests to private network destinations by default.
- **PythonSecurityManager** enforces security levels, script-size limits, package whitelists, and
  blocks dangerous imports/functions unless specifically allowed.
- **ReturnValueHandler** and `FunctionInvoker` ensure native function parameters and return values
  are converted safely between PCP wire types and Kotlin types.

## Next Steps

- [Basic PCP Usage](basic-pcp-usage.md) for focused shell and HTTP examples.
- [Intermediate PCP Features](intermediate-pcp-features.md) for Python execution, native function
  binding, and advanced security configuration.
- [Advanced Session Management](advanced-session-management.md) to learn how interactive stdio
  sessions and buffers work.
