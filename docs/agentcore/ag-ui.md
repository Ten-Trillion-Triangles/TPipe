# AgentCore AG-UI

AG-UI is implemented only in TPipe-AgentCore. The host serves SSE and
WebSocket events at the AgentCore runtime paths and maps the latest user
message to a normal `P2PRequest`. Previous messages are request context; the
agent's internal converse history is not mutated behind its back.

Client-supplied tool definitions are informational until a developer supplies
an explicit trusted executor.
