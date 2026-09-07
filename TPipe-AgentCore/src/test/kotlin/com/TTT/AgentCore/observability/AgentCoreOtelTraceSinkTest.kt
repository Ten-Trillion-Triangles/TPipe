package com.TTT.AgentCore.observability

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceEvent
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TracePhase
import com.TTT.Pipe.MultimodalContent
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentCoreOtelTraceSinkTest
{
    @Test
    fun defaultExportOmitsPrivateMetadataAndExceptionText()
    {
        val exported = CopyOnWriteArrayList<SpanData>()
        val exporter = object : SpanExporter
        {
            override fun export(spans: Collection<SpanData>): CompletableResultCode
            {
                exported += spans
                return CompletableResultCode.ofSuccess()
            }

            override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

            override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
        }
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
        val openTelemetry: OpenTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build()
        val sinkName = "agentcore-otel-privacy-test"
        val sink = AgentCoreOtelTraceSink(openTelemetry, AgentCoreOtelConfig(sinkName = sinkName))
        val traceId = "privacy-trace"

        PipeTracer.enable()
        try
        {
            PipeTracer.startTrace(traceId)
            PipeTracer.addEvent(
                traceId,
                TraceEvent(
                    timestamp = 1L,
                    pipeId = "pipe",
                    pipeName = "privacy-test",
                    eventType = TraceEventType.PIPE_FAILURE,
                    phase = TracePhase.EXECUTION,
                    content = MultimodalContent("private content"),
                    contextSnapshot = null,
                    metadata = mapOf(
                        "safe.dimension" to "worker",
                        "shellIo" to "private shell output",
                        "commandText" to "private command",
                        "authorization" to "private credential",
                        "content" to "private content",
                        "reasoningContent" to "private reasoning",
                        "paymentProof" to "private payment proof"
                    ),
                    error = IllegalStateException("credentials and command must not be exported")
                )
            )
            sink.flush()
        }
        finally
        {
            sink.close()
            PipeTracer.disable()
            tracerProvider.shutdown()
        }

        val serializedSpans = exported.joinToString { span ->
            "${span.attributes.asMap()} ${span.status.description.orEmpty()}"
        }
        assertTrue(serializedSpans.contains("safe.dimension"))
        assertFalse(serializedSpans.contains("private shell output"))
        assertFalse(serializedSpans.contains("private command"))
        assertFalse(serializedSpans.contains("private credential"))
        assertFalse(serializedSpans.contains("private content"))
        assertFalse(serializedSpans.contains("private reasoning"))
        assertFalse(serializedSpans.contains("private payment proof"))
        assertFalse(serializedSpans.contains("credentials and command must not be exported"))
    }
}
