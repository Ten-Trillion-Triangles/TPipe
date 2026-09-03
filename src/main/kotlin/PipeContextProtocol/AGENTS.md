# PipeContextProtocol (PCP)

**OVERVIEW**
PCP enables secure multi-language tool execution (Kotlin/JS/Python) with strict transport routing, session management, and sandboxed security boundaries.

## STRUCTURE
```
PipeContextProtocol/
├── Pcp.kt                    # Transport, Permissions, ParamType, StdioContextOptions enums
├── PcpExecutionDispatcher.kt  # Routes requests to appropriate executor
├── PcpFunctionHandler.kt      # Tpipe transport function handler
├── FunctionInvoker.kt         # Native function invocation engine
├── FunctionRegistry.kt        # Function/type converter registry
├── FunctionWrapper.kt         # Function metadata wrapper
├── FunctionSignature.kt       # Parameter/return type signatures
├── PcpRegistry.kt            # PCP registry operations
├── PcpInstructionGenerator.kt # Generates PCP instructions
├── PcpResponseParser.kt       # Parses response transport
├── PcpStdioHost.kt           # STDIO host implementation
├── TypeConverter.kt          # Cross-language type conversion
├── ReturnValueHandler.kt     # Return value processing
├── LanguageExecutors/
│   ├── KotlinExecutor.kt
│   ├── JavaScriptExecutor.kt
│   └── PythonExecutor.kt
├── LanguageSecurity/
│   ├── KotlinSecurityManager.kt
│   ├── JavaScriptSecurityManager.kt
│   ├── PythonSecurityManager.kt
│   ├── HttpSecurityManager.kt
│   └── CommandSecurityManager.kt
├── TransportExecutors/
│   ├── StdioExecutor.kt       # STDIO transport with session management
│   ├── StdioSessionManager.kt
│   ├── StdioBufferManager.kt
│   └── HttpExecutor.kt        # HTTP transport
├── PlatformManagers/
│   ├── PythonPlatformManager.kt
│   └── (Kotlin/JS platform managers)
└── Constants/
    ├── KotlinConstants.kt, JavaScriptConstants.kt
    ├── PythonConstants.kt, HttpConstants.kt
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Add new language executor | `LanguageExecutors/` | Implement transport routing in `PcpExecutionDispatcher` |
| Security policy for language | `LanguageSecurity/` | Per-language `SecurityManager` validates permissions |
| PCP request/response flow | `PcpExecutionDispatcher.kt` | `routeRequest()` → executor → result |
| Function parameter validation | `FunctionInvoker.kt` | Uses `FunctionRegistry` and `TypeConverter` |
| STDIO session management | `StdioSessionManager.kt`, `StdioBufferManager.kt` | Persistent session support |
| Multi-language type conversion | `TypeConverter.kt` | Handles String/Int/Bool/Float/Enum/List/Map/Object |
| PCP protocol enums | `Pcp.kt` | Transport, Permissions, ParamType, StdioExecutionMode |

## CONVENTIONS
- **Transport enum**: `Auto, Stdio, Tpipe, Http, Python, Kotlin, JavaScript, Unknown`
- **StdioExecutionMode**: `ONE_SHOT, INTERACTIVE, CONNECT, BUFFER_REPLAY`
- **Security boundary**: Each language has a dedicated `SecurityManager` (not shared)
- **Execution flow**: `Pcp` → `PcpExecutionDispatcher.routeRequest()` → LanguageExecutor → SecurityManager → response

## ANTI-PATTERNS
- **Never** bypass `PcpExecutionDispatcher.routeRequest()` — transport validation ensures security boundaries
- **Never** share `SecurityManager` across language executors — sandbox isolation required
- **Never** call `FunctionInvoker.invoke()` without validating `parameters` map first

## OUTPUT CAPTURE (2026-06-25 hardening)

Every `PcpRequestResult` now carries two output fields:

- **`output: String`** — the legacy merged string (`stdout + "\nSTDERR: " + stderr`).
  Back-compat: existing callers that read `result.output` keep working unchanged.
- **`outputBuffer: BufferedOutput?`** — the new channel-separated payload:
  - `stdout: String?` populated when stdout bytes are valid UTF-8
  - `stderr: String?` populated from the dedicated stderr pipe
  - `binary: ByteArray?` populated when stdout bytes are NOT valid UTF-8
    (exactly one of stdout/binary is set, never both)
  - `totalBytes: Long` — full stream byte count, even when overflowed
  - `truncated: Boolean` — true when stdout was held to the in-memory
    cap and the remainder spilled to a temp file referenced by `overflowPath`

### How capture works

`SubprocessOutputCapture.capture(process, timeoutMs, maxInMemoryBytes)`:

- Reads stdout and stderr **in parallel** via `async(Dispatchers.IO) { readAllBytes() }`.
  Sequential `readText()` deadlocks past the ~64KB pipe-buffer limit.
- Decodes stdout via `Charset.forName("UTF-8").newDecoder()` with default
  `CodingErrorAction.REPORT`. On `MalformedInputException`, surfaces the raw
  bytes as `binary` instead of substituting U+FFFD.
- Caps in-memory footprint at `maxInMemoryBytes` (executor default 256 KB).
  Anything past that is spilled to a temp file referenced by `outputBuffer.overflowPath`
  so output size is unbounded while resident memory stays bounded.
- Enforces `timeoutMs` via `process.waitFor(timeoutMs, MILLISECONDS)`. On
  timeout, `destroyForcibly()` kills the child and the buffers drain to
  avoid pipe-buffer leaks.

### Concurrency bound

Every language executor routes process starts through `PcpThreadPool`:

- Capped at `Runtime.availableProcessors() * 2` workers, no unbounded queue
  (backed by `SynchronousQueue` + `ThreadPoolExecutor.AbortPolicy`).
- Saturated submissions throw `RejectedExecutionException` immediately. The
  executor converts this into `PcpRequestResult(success=false, error="Executor saturated: ...")`
  rather than queuing or spawning unbounded OS processes.

### Kotlin timeout (acknowledged in-process limitation)

`KotlinExecutor.execute` runs `engine.eval()` on a daemon thread and joins
with `Thread.join(timeoutMs)`. When the timeout fires:

- The dispatcher returns `PcpRequestResult(success=false, error="Kotlin script timed out after Xms")`.
- The in-process compiler/evaluation thread is not forcibly terminated — it
  keeps running until the script returns or the JVM exits.
- Document this limitation to any context that exposes `Transport.Kotlin`.
  For untrusted scripts, wrap the dispatcher call in an outer
  `withTimeoutOrNull` at the pipe/manifold layer.

### Conventions (new)

- **Output**: every `PcpRequestResult` populates both `output` (back-compat) and `outputBuffer` (channel-separated).
  New code should read `outputBuffer`. Legacy callers can keep reading `output`.
- **Capture**: never call `process.inputStream.readText()` directly on a subprocess —
  always route through `SubprocessOutputCapture.capture()` to avoid the pipe-buffer deadlock.
- **Charset**: subprocess script files are written with `Charsets.UTF_8` so non-ASCII source
  survives platform default charset transcoding.
- **Concurrency**: never create a `ProcessBuilder` outside `threadPool.submit { ... }` —
  bypasses the concurrency bound.

### Anti-patterns (new)

- **Never** read `process.inputStream` / `process.errorStream` directly via `readText()` — use `SubprocessOutputCapture`.
- **Never** call `process.waitFor()` without `timeoutMs` — the timeout is the only signal the dispatcher can give.
- **Never** start a process without `PcpThreadPool.submit { processBuilder.start() }` — bypasses backpressure.
- **Never** expect Kotlin's `while (true) {}` to be killed by timeout — it isn't, the daemon thread remains until JVM exit.
