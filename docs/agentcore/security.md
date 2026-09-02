# Security model

- Use `AgentCoreIdentityAuthProvider` with a workload-token loader for dynamic
  bearer headers. Do not put credentials in `P2PDescriptor.authBody`, persisted
  context, or trace metadata.
- Keep MCP endpoints bound to a private interface unless an authenticated
  deployment path is in place. The configurable host makes the bind explicit.
- `AgentCoreOtelTraceSink` uses a bounded, non-blocking queue. Content export is
  opt-in and includes only event text; context snapshots and reasoning remain
  excluded.
- `AgentCorePolicyEvaluator` defaults to `LOG_ONLY`. Enforcement requires an
  explicit evaluator. There is no automatic PCP-to-Cedar translation.
- Browser and Code Interpreter have separate client surfaces and must be
  explicitly attached by the application.
- AgentCore Memory exact records are checksummed and validated before decode;
  malformed or incomplete record sets fail rather than becoming empty state.
