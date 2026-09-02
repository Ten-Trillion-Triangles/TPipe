# Gateway

AgentCore Gateway is consumed through the generic TPipe-MCP Streamable HTTP
client. `AgentCoreGatewayConnector` discovers tools, namespaces them, and
binds them as PCP dynamic functions. The reverse path is an ordinary
TPipe-MCP endpoint registered as an external MCP target.

Identity headers are supplied dynamically; refreshable tokens must not be put
in global `AuthRegistry` entries. Gateway Policy is an additional AWS-side
authorization layer and does not replace PCP validation.
