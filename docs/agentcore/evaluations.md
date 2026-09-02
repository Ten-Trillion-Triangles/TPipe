# Evaluations

Use `AgentCoreEvaluationClient` and `AgentCoreEvaluationAdmin` outside normal
Pipeline execution. `AgentCoreEvaluationPoller` uses cancellation-aware,
bounded exponential backoff. `AgentCoreEvaluationTraceAdapter` produces
redacted evaluator records from TPipe traces without changing `PipeTracer`.
