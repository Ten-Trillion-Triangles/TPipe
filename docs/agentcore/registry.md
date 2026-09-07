# Agent Registry

TPipe uses the separate AWS Agent Registry service through
`agentregistry` and `agentregistrycontrol`. `AgentRegistryClients` is kept
separate from `AgentCoreClients` so closing one bundle cannot close the other
service's clients.

`AgentRegistryAdmin` exposes Registry and RegistryRecord CRUD, submission for
approval, and status changes. `AgentRegistryDiscovery` exposes approved-record
search, list, and batch-get while preserving per-record errors in partial
responses. MCP, Skill, and Custom descriptor helpers populate exactly one AWS
descriptor union member.

`DEPRECATED` is treated as terminal for local lifecycle decisions. TPipe does
not add A2A invocation or transport; an agent descriptor can still be passed
to the raw AWS model when a caller explicitly catalogs one.

The public preview `bedrock-agentcore` Registry namespace is being retired by
AWS. New code must use the `agent-registry` service namespace.
