# AgentCore getting started

Add `implementation(project(":TPipe-AgentCore"))` to an application that
needs AgentCore Runtime, Memory, Gateway, or tool integrations. The module
depends on Core and TPipe-MCP, never TPipe-Bedrock. Build an ordinary TPipe
Pipeline or Manifold and pass a session-aware factory to
`AgentCoreRuntimeBootstrap`.

All default tests are local and require no AWS credentials. Use the raw
`AgentCoreClients.data` and `.control` clients for SDK operations that do not
need a TPipe-specific adapter.
