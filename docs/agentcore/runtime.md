# Runtime and sessions

`AgentCoreRuntimeHost` exposes the AgentCore Runtime-shaped endpoints:

- `POST /invocations` accepts exactly one of `{ "prompt": "..." }` or
  `{ "content": <Core-serialized MultimodalContent> }`; `input` remains an
  ingress-only compatibility alias for older TPipe-AgentCore callers.
- `GET /ping` returns a health response with the last update timestamp.
- `GET /ws` accepts the same invocation objects over WebSocket.
- `stream: true` on `/invocations` returns `chunk`, `final`, and `error` SSE
  events. WebSocket streaming uses the same JSON event objects.

Successful responses use `{ "output": <Core-serialized MultimodalContent> }`.
Errors use `type`, `errorType`, `message`, and `retryable`. Runtime session
correlation is carried by `x-amzn-bedrock-agentcore-runtime-session-id`, not by
the canonical request body.

The host maps input to the existing `P2PRequest` and maps the output back to
the existing `P2PResponse` shape. It does not change Core's `Transport` enum.

The default `ISOLATED` mode creates one TPipe root per session id. A per-session
mutex serializes requests for that root, while different session mutexes allow
concurrency. `SHARED` is available for applications that intentionally want a
single root, but it should be selected only when cross-session state sharing is
acceptable.

`AgentCoreRuntimeAgent` is the inverse adapter: it implements generic
`P2PInterface` and invokes an external Runtime endpoint. `AgentCoreHarnessAgent`
uses the same P2P boundary for an external Harness worker.
