# Observability

`AgentCoreOtelTraceSink` is an additive generic `TraceSink`. It uses a bounded
asynchronous queue and never runs network export on the TPipe execution path.
Call `flush()` before shutdown when export completion matters.

Prompt, context snapshots, and model reasoning are excluded by default.
Configure explicit opt-ins and redaction only for data that is safe to export.
CloudWatch Transaction Search/OTEL setup remains an AWS deployment concern.
