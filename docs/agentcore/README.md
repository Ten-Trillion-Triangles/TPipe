# AgentCore integration

TPipe-AgentCore is an optional adapter module. Core remains provider-neutral,
TPipe-Bedrock remains a model provider, and TPipe-MCP owns the generic MCP
bridge/client transport.

## Boundaries

| Concern | Owner |
| --- | --- |
| LLM provider invocation | TPipe-Bedrock and other provider modules |
| Exact ContextBank persistence | Core `ContextPersistenceBackend` |
| AgentCore Memory record storage | TPipe-AgentCore `AgentCoreMemoryBackend` |
| Semantic Memory events/retrieval | TPipe-AgentCore `AgentCoreSemanticMemory` |
| MCP Streamable HTTP | TPipe-MCP `McpRemoteClient` |
| Runtime sessions and HTTP contract | TPipe-AgentCore runtime package |
| P2P identity and routing | existing generic Core P2P APIs |
| Policy | LOG_ONLY by default; enforcement is explicit |
| Evaluations | separate AgentCore facade over OTEL/evaluation APIs |

There is no `AgentCorePipe`, `ProviderName.AgentCore`, `Transport.AgentCore`,
or AgentCore-specific P2P transport.

## Security defaults

- Workload tokens are loaded dynamically and are not put in P2P descriptors.
- OTEL export excludes prompt/context/reasoning content by default.
- MCP auth headers may be generated per request.
- Policy starts in `LOG_ONLY`; PCP schemas are not automatically translated to Cedar.
- Browser and Code Interpreter are separate, explicit clients.

See [runtime](runtime.md), [memory](memory.md), [mcp](mcp.md),
[security](security.md), and [operations](operations.md).
