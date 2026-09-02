# Browser and Code Interpreter

Browser and Code Interpreter are explicit data-plane clients with start/get,
invoke, and stop operations. Their sessions carry an owning TPipe session id.
Optional PCP registration requires an explicit enabled-action set and a trusted
handler; AgentCore sandboxing does not replace PCP authorization.

When creating a runtime root, use the supplied session context to bind cleanup
to the runtime session:

```kotlin
val browser = sessionContext.browserClient(agentCoreClients)
val codeInterpreter = sessionContext.codeInterpreterClient(agentCoreClients)
```

Sessions started through these clients are stopped during idle eviction and
host shutdown. The owner-aware overloads remain available when a caller needs
to validate an existing session explicitly.
