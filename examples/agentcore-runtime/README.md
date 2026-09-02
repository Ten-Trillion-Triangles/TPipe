# AgentCore Runtime example

This example is intentionally application-owned: construct an ordinary TPipe
`Pipeline` or `Manifold` in the `AgentCoreSessionFactory`, then pass it to
`AgentCoreRuntimeHost`.

```kotlin
val runtime = AgentCoreRuntimeHost(
    AgentCoreRuntimeHostConfig(),
    AgentCoreSessionFactory { _ -> ordinaryTpipeRoot() }
)
runtime.start(wait = true)
```

Build an ARM64 image using
`TPipe-AgentCore/src/main/docker/Dockerfile` and provide credentials via
the deployment environment, not source or context records.
