# Harness interoperability

Harness is an external-agent boundary. `AgentCoreHarnessClient` invokes a
pinned Harness agent and `AgentCoreHarnessAgent` adapts an explicitly selected
worker to `P2PInterface`. Normal TPipe orchestration is never compiled into or
silently routed through Harness.
