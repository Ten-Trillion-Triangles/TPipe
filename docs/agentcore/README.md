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

## Capability matrix

| Area | TPipe-AgentCore coverage | Operational boundary |
| --- | --- | --- |
| Runtime | HTTP, SSE, WebSocket, generated one-shot commands, interactive WSS shells | Shell commands are caller-authorized and are never logged by default |
| Runtime compute/storage | microVM, Instances capacity providers, managed session storage, EBS, S3 Files, EFS | VPC, KMS, S3/EFS, and EC2 prerequisites remain customer-owned |
| Memory | exact ContextBank persistence plus semantic events/retrieval and `IngestData` | Ingestion is distinct from short-term event storage |
| Gateway | MCP targets, SigV4, OAuth/API-key wiring, rate limits, and rules | Weighted rules retain AWS's exactly-two-variant constraint |
| Identity | workload identities, OAuth/API-key providers, token vault, resource policies, consent portals, Private Key JWT, OBO | Tokens are short-lived, scope-isolated, and redacted in diagnostics |
| Evaluation | evaluators, online/batch jobs, Insights, datasets, configuration bundles, recommendations, A/B tests | Recommendations require explicit caller review; dataset execution is provider-neutral |
| Tools | Browser and Code Interpreter sessions plus custom resources and profiles | Filesystem/network configuration is passed through without creating customer infrastructure |
| Policy | Cedar helpers, generic definition pass-through, temporal sessions | Default mode is `LOG_ONLY`; enforcement and invalidation are explicit |
| Observability | bounded OTEL sink and stable resource/request attributes | Content, credentials, payment proof, and shell I/O are opt-in/redacted |
| Agent Registry | separate `agentregistry`/`agentregistrycontrol` clients, approval, discovery, descriptors | Uses `agent-registry`, not the deprecated `bedrock-agentcore` Registry namespace |
| Payments | manager/connector/provider administration and explicit instrument/session/payment calls | No automatic spending, 402 retry, or implicit transaction completion |

AgentCore != a TPipe model provider. TPipe-Bedrock remains the AWS model
provider integration; AgentCore supplies optional infrastructure and
governance adapters around ordinary TPipe execution.

The Registry and Payments APIs are AWS preview surfaces and may change
independently of TPipe. Shell execution, customer-managed storage, delegated
identity credentials, temporal-policy sessions, and payment calls require
explicit application authorization and operational safeguards.

## Security defaults

- Workload tokens are loaded dynamically and are not put in P2P descriptors.
- OTEL export excludes prompt/context/reasoning content by default.
- MCP auth headers may be generated per request.
- Policy starts in `LOG_ONLY`; PCP schemas are not automatically translated to Cedar.
- Browser and Code Interpreter are separate, explicit clients.

See [runtime](runtime.md), [memory](memory.md), [mcp](mcp.md),
[identity](identity.md), [evaluations](evaluations.md), [registry](registry.md),
[payments](payments.md), [security](security.md), and [operations](operations.md).
