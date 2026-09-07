# Version compatibility

| Component | Supported boundary |
|---|---|
| TPipe Kotlin | 2.3.21 |
| AWS SDK for Kotlin | 1.8.47 |
| TPipe MCP SDK | 0.11.1 |
| Gateway MCP target | 2025-06-18 |
| AG-UI Kotlin core | 0.4.1 if compatibility tests pass |

The AgentCore and Bedrock modules use the common AWS SDK Kotlin 1.8.47 and
Smithy Kotlin 1.7.9 generations. Their compatibility checks fail if another
AWS Kotlin SDK version resolves. TPipe source compatibility remains Kotlin 2.3.
