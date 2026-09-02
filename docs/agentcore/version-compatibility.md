# Version compatibility

| Component | Supported boundary |
|---|---|
| TPipe Kotlin | 2.3.21 |
| AWS SDK for Kotlin | 1.6.107 maximum |
| TPipe MCP SDK | 0.11.1 |
| Gateway MCP target | 2025-06-18 |
| AG-UI Kotlin core | 0.4.1 if compatibility tests pass |

The AgentCore module pins AWS SDK artifacts to 1.6.107 and its `check` task
fails if another AWS Kotlin SDK version resolves. AWS SDK Kotlin 1.8.x requires
Kotlin 2.4. TPipe cannot adopt Kotlin 2.4 while PCP Kotlin scripting depends on
the current Kotlin scripting stack; replacing that scripting engine is the
prerequisite for expanding AgentCore support.
