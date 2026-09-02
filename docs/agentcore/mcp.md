# MCP and Gateway

`McpRemoteClient` implements generic MCP Streamable HTTP against MCP 2025-06-18
servers. It keeps the SDK client and negotiated session alive, follows cursors
for tools/resources/prompts, and calls tools through the SDK.

Request headers have two sources:

- static non-secret defaults in `requestHeaders`;
- a suspendable `McpRemoteAuthProvider` evaluated for each request.

The provider is applied in Ktor's outgoing request pipeline, including each
request made while following a pagination cursor. Providers should load or
refresh a cached token rather than perform a long network operation in that
pipeline. `McpRemoteClientConfig` also exposes request, connect, and socket
timeouts for clients that create their own HTTP client.

IAM-authorized Gateway calls can supply a `McpRemoteRequestSigner`; the signer
receives the final URL, method, headers, and exact serialized body so the
authorization covers the request that is sent.

`bindToolsToPcp` converts remote JSON Schema properties into PCP signatures and
registers `DynamicFunction` handlers. PCP required-field, enum, whitelist, and
return handling remain active.

The existing `McpBridgeHttpHost.run(port, authKey, bindAddress)` behavior and
`/mcp/bridge` default remain source-compatible. `McpHttpHostConfig.path`
allows an AgentCore-shaped `/mcp` endpoint without forking the bridge.

`AgentCoreGateway` binds these generic MCP tools to a Pipe; it does not add a
new provider or transport abstraction.
