# Identity

Use `AgentCoreIdentityProvider` and `AgentCoreIdentityAdmin` for the pinned
Identity data/control APIs. `AgentCoreIdentityAuthProvider` and
`AgentCoreTokenProvider` expose short-lived bearer headers to MCP without
placing credentials in Core registries or P2P descriptors. Cache entries are
scoped and expire with configurable skew.
