package com.TTT.AgentCore.examples

import com.TTT.AgentCore.observability.AgentCoreOtelTraceSink
import io.opentelemetry.api.OpenTelemetry

/** Install the bounded, content-redacted default OTEL trace sink. */
fun installObservabilityExample(openTelemetry: OpenTelemetry): AgentCoreOtelTraceSink =
    AgentCoreOtelTraceSink(openTelemetry)
