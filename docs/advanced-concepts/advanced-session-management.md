# Advanced Session Management

## Table of Contents
- [Interactive Sessions](#interactive-sessions)
- [Session Lifecycle API](#session-lifecycle-api)
- [Buffer Management](#buffer-management)
- [Session Configuration Tips](#session-configuration-tips)
- [Common Workflows](#common-workflows)
- [Next Steps](#next-steps)

## Interactive Sessions

`StdioExecutor` supports long-lived processes so the model can maintain environment state across
multiple PCP calls—for example, starting `bash`, running `cd`, then compiling inside the same shell.
Interactive mode keeps the process alive, tracks per-session buffers, and exposes handles you can
tunnel back into later.

Create an interactive option by switching the execution mode:

```kotlin
import com.TTT.PipeContextProtocol.StdioContextOptions
import com.TTT.PipeContextProtocol.StdioExecutionMode
import com.TTT.PipeContextProtocol.Permissions

val shell = StdioContextOptions().apply {
    command = "bash"
    executionMode = StdioExecutionMode.INTERACTIVE
    keepSessionAlive = true
    bufferPersistence = true
    permissions.addAll(listOf(Permissions.Read, Permissions.Write, Permissions.Execute))
    timeoutMs = 120_000
}
```

`StdioExecutor` returns a `PcpRequestResult` whose output string contains the generated
`sessionId` and optional `bufferId`. Subsequent PCP requests can reconnect by setting
`executionMode = CONNECT` and providing the same `sessionId`.

## Session Lifecycle API

Under the hood, interactive requests are backed by `StdioSessionManager`. You can use the manager
directly when you need programmatic control outside PCP.

```kotlin
import com.TTT.PipeContextProtocol.StdioSessionManager
import com.TTT.PipeContextProtocol.SessionResponse

val manager = StdioSessionManager

suspend fun startSession(): String {
    val session = manager.createSession(command = "bash", args = emptyList(), ownerId = "user123", workingDir = null)
    return session.sessionId
}

suspend fun runCommand(sessionId: String, command: String): SessionResponse {
    return manager.sendInput(sessionId, command)
}

suspend fun readPending(sessionId: String): String {
    return manager.readOutput(sessionId, timeoutMs = 2_000)
}

fun close(sessionId: String) {
    manager.closeSession(sessionId)
}
```

Key types:
- **`StdioSession`**: metadata about the running process (command, args, `bufferId`, timestamps).
- **`SessionResponse`**: output, error text (if any), and `isActive` indicator returned by
  `sendInput`.
- **`SessionResult`**: convenience wrapper used by the executor when creating sessions.

`listActiveSessions()` surfaces the currently managed session IDs so you can monitor or shut them
down proactively.

## Buffer Management

When `bufferPersistence = true`, `StdioExecutor` routes IO through `StdioBufferManager`. Buffers are
plain in-memory archives keyed by `bufferId`.

```kotlin
import com.TTT.PipeContextProtocol.StdioBufferManager
import com.TTT.PipeContextProtocol.BufferDirection

val buffers = StdioBufferManager()
val buffer = buffers.createBuffer(sessionId)

buffers.appendToBuffer(buffer.bufferId, "ls -la", BufferDirection.INPUT)
buffers.appendToBuffer(buffer.bufferId, "total 4\nREADME.md", BufferDirection.OUTPUT)

val snapshot = buffers.getBuffer(buffer.bufferId)
println("Entries captured: ${snapshot?.entries?.size}")
```

`searchBuffer` performs a case-insensitive substring match and returns `BufferMatch` objects with the
entry index and the original `BufferEntry`. Use `entry.timestamp` or `entry.metadata` for deeper
analysis.

```kotlin
val matches = buffers.searchBuffer(buffer.bufferId, "error")
matches.forEach { match ->
    println("Found at entry ${match.entryIndex}: ${match.entry.content}")
}
```

To persist or restore session history, use `saveBuffer` and `loadBuffer`. Both rely on
`serialize`/`deserialize` helpers inside the TPipe util package.

## Session Configuration Tips

1. **Timeouts** – `StdioContextOptions.timeoutMs` applies to each interaction. Choose a generous
   value for long-running builds and a tighter one for simple inspection commands.
2. **Buffer size** – Cap `maxBufferSize` to keep runaway output from exhausting memory. The executor
   truncates output once the threshold is reached.
3. **Environment variables** – `environmentVariables` overrides inherited variables per session,
   which is useful when bootstrapping toolchains.
4. **Working directories** – set `workingDirectory` to control where the process starts. Combine this
   with `PcpContext.allowedDirectoryPaths` to stop the model from escaping its sandbox.
5. **Cleanup** – call `closeSession` for idle sessions. The manager destroys the process and releases
   buffered resources.

## Common Workflows

- **Stateful REPLs** – expose Python, Node, or SQL shells with interactive mode, then pipe follow-up
  commands through `CONNECT` requests.
- **Long builds** – run `make` or `gradle` once, keep the session alive, and feed incremental tasks or
  log scraping commands without reinitialising the environment.
- **Audit trails** – persist buffers to disk for compliance or debugging. Pair the JSON output with
  your logging pipeline to review every command the model executed.

## Next Steps

Continue with:
- [Basic PCP Usage](basic-pcp-usage.md) for standard stdio and HTTP patterns.
- [Intermediate PCP Features](intermediate-pcp-features.md) to combine sessions with Python execution or native functions.
- [Conversation History Management](conversation-history-management.md) to see how session output can be injected back into prompts.
