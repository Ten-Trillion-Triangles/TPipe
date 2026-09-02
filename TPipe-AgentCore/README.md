# TPipe-AgentCore

Optional AgentCore infrastructure integration for TPipe. This module depends
on TPipe Core and TPipe-MCP and deliberately does not depend on TPipe-Bedrock.
AgentCore is treated as a runtime, memory, tools, gateway, identity, policy,
and evaluation surface; model-provider pipes remain in their provider modules.

The module is pinned to `aws.sdk.kotlin` `1.6.107`, the last SDK line supported
by the repository's Kotlin `2.3.21` boundary. Do not upgrade this module to the
AWS Kotlin SDK `1.8.x` line as part of a compatibility-only change.

## Runtime

```kotlin
val host = AgentCoreRuntimeHost(
    AgentCoreRuntimeHostConfig(port = 8080),
    AgentCoreSessionFactory { session ->
        // Construct one ordinary Pipeline/Manifold root for this session.
        buildApplicationRoot(session)
    }
)
host.start()
```

The host serves `/invocations`, `/ping`, and `/ws`. The canonical invocation
body contains exactly one of `prompt` or Core's `MultimodalContent`-shaped
`content`; the legacy `input` field is accepted only for source/wire
compatibility. Successful responses contain an `output` `MultimodalContent`.
Streaming uses `chunk`, `final`, and `error` events over both SSE and
WebSocket. Each session gets one root by default, requests within a session
are serialized, and different sessions can execute concurrently.

## Memory

Use `AgentCoreMemoryBackend` for exact `ContextBank` persistence. It stores
opaque gzip/base64 records with a manifest, checksum, revision, chunking, list
pagination, and batches of at most 100 records. Use
`AgentCoreSemanticMemory` separately when an application wants AgentCore's
semantic retrieval or event extraction.

## MCP and Gateway

`McpRemoteClient` in TPipe-MCP implements generic MCP Streamable HTTP. It
reuses MCP sessions, follows pagination, supports dynamic request headers, and
can bind remote tools to PCP dynamic handlers. `AgentCoreGatewayConnector`
wires that generic MCP client into a Pipe and supports namespaced PCP tool
registration; it does not add an AgentCore transport. `AgentCoreGateway`
remains the source-compatible lower-level adapter.

## Validation

```bash
./gradlew :TPipe-AgentCore:test
./gradlew :TPipe-AgentCore:check
```

The module's compatibility task fails if an AWS Kotlin SDK dependency resolves
outside the pinned `1.6.107` line.
