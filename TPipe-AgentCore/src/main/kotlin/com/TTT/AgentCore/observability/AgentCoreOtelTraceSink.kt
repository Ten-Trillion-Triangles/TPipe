package com.TTT.AgentCore.observability

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceEvent
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TraceSink
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Drop behavior when the asynchronous OTEL queue is full. */
enum class AgentCoreOtelDropPolicy {
    /** Discard the newest event and keep the already queued events. */
    DROP_NEWEST,

    /** Discard one oldest queued event before trying the new event. */
    DROP_OLDEST
}

/**
 * Configuration for the bounded AgentCore OTEL sink.
 *
 * @param sinkName Registry name used by [PipeTracer].
 * @param queueCapacity Maximum number of queued trace events.
 * @param includeContent Whether to export redacted event content.
 * @param includeContextSnapshots Whether to export context snapshots.
 * @param includeModelReasoning Whether to export model reasoning text.
 * @param maximumSerializedSize Maximum size of exported string attributes.
 * @param dropPolicy Queue-full behavior.
 * @param redactionPredicate Predicate for attributes that must be redacted.
 * @param onDroppedEvents Callback invoked after an event is dropped.
 */
data class AgentCoreOtelConfig(
    val sinkName: String = "agentcore-otel",
    val queueCapacity: Int = 1024,
    val includeContent: Boolean = false,
    val includeContextSnapshots: Boolean = false,
    val includeModelReasoning: Boolean = false,
    val maximumSerializedSize: Int = 16_384,
    val dropPolicy: AgentCoreOtelDropPolicy = AgentCoreOtelDropPolicy.DROP_NEWEST,
    val redactionPredicate: (String) -> Boolean = { false },
    val onDroppedEvents: (Long) -> Unit = {}
)

/**
 * Bounded asynchronous OpenTelemetry trace sink for AgentCore observability.
 *
 * Sink failures and a full queue are isolated from TPipe execution. Lifecycle
 * events share a span per pipe and point-in-time events become span events.
 *
 * @param openTelemetry OpenTelemetry provider used to create spans.
 * @param config Sink capacity, redaction, and export settings.
 */
class AgentCoreOtelTraceSink(
    private val openTelemetry: OpenTelemetry,
    private val config: AgentCoreOtelConfig = AgentCoreOtelConfig()
) : AutoCloseable
{
    private sealed interface QueueItem {
        data class Event(val traceId: String, val event: TraceEvent) : QueueItem
        data class Flush(val completion: CompletableDeferred<Unit>) : QueueItem
    }

    private val queue = Channel<QueueItem>(config.queueCapacity.coerceAtLeast(1))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tracer: Tracer = openTelemetry.getTracer("com.TTT.TPipe.AgentCore")
    private val activeSpans = ConcurrentHashMap<String, Span>()
    private val droppedEvents = AtomicLong(0)
    private val worker: Job

    init {
        require(config.queueCapacity > 0) { "Trace queue capacity must be positive." }
        require(config.maximumSerializedSize > 0) { "Maximum serialized trace size must be positive." }
        worker = scope.launch {
            for(item in queue)
            {
                when(item)
                {
                    is QueueItem.Event -> runCatching { export(item.traceId, item.event) }
                    is QueueItem.Flush -> item.completion.complete(Unit)
                }
            }
        }
        PipeTracer.registerSink(config.sinkName, TraceSink { traceId, event -> enqueue(traceId, event) })
    }

    /**
     * Return the number of events dropped because the bounded queue was full.
     *
     * @return Number of dropped events.
     */
    fun droppedEvents(): Long = droppedEvents.get()

    /**
     * Return the queue capacity exposed for diagnostics.
     *
     * @return Configured queue capacity.
     */
    fun capacity(): Int = config.queueCapacity

    /**
     * Drain events accepted before this call returns.
     *
     * @return Nothing; completion indicates that accepted events were drained.
     */
    fun flush() = runBlocking { flushSuspend() }

    /**
     * Suspendable variant for coroutine-owned shutdown paths.
     *
     * @return Nothing; completion indicates that accepted events were drained.
     */
    suspend fun flushSuspend()
    {
        if(!worker.isActive) return
        val completion = CompletableDeferred<Unit>()
        queue.send(QueueItem.Flush(completion))
        completion.await()
    }

    /** Unregister, drain, finish active spans, and stop the exporter. */
    override fun close()
    {
        PipeTracer.removeSink(config.sinkName)
        runBlocking { flushSuspend() }
        queue.close()
        runBlocking { worker.join() }
        activeSpans.values.forEach { span -> span.end() }
        activeSpans.clear()
        scope.cancel()
    }

    private fun enqueue(traceId: String, event: TraceEvent)
    {
        val item = QueueItem.Event(traceId, event)
        val sendResult = queue.trySend(item)
        if(sendResult.isSuccess) return
        if(config.dropPolicy == AgentCoreOtelDropPolicy.DROP_OLDEST)
        {
            // Never discard a flush barrier: doing so would leave its
            // completion deferred forever. If the oldest item is a barrier,
            // restore it and drop the incoming event instead.
            val oldest = queue.tryReceive().getOrNull()
            if(oldest is QueueItem.Flush)
            {
                queue.trySend(oldest)
            }
            if(queue.trySend(item).isSuccess) return
        }
        val dropped = droppedEvents.incrementAndGet()
        runCatching { config.onDroppedEvents(dropped) }
    }

    private fun export(traceId: String, event: TraceEvent)
    {
        val spanKey = "${traceId}:${event.pipeId}"
        val lifecycleSpan = if(isStart(event.eventType))
        {
            activeSpans.computeIfAbsent(spanKey) {
                tracer.spanBuilder("tpipe.${event.pipeName}")
                    .setParent(Context.current())
                    .startSpan()
            }
        }
        else
        {
            null
        }
        val span = lifecycleSpan ?: tracer.spanBuilder("tpipe.${event.eventType.name}")
                .setParent(activeSpans[spanKey]?.let { Context.current().with(it) } ?: Context.current())
                .startSpan()
        try
        {
            mapAttributes(span, traceId, event)
            span.addEvent(event.eventType.name)
            if(event.error != null || isFailure(event.eventType))
            {
                span.setStatus(StatusCode.ERROR, event.error?.message ?: event.eventType.name)
            }
        }

        finally
        {
            if(isTerminal(event.eventType))
            {
                if(lifecycleSpan == null)
                {
                    span.end()
                }
                val active = activeSpans.remove(spanKey)
                if(active != null)
                {
                    active.end()
                }
                else if(lifecycleSpan != null)
                {
                    lifecycleSpan.end()
                }
            }
            else if(!isStart(event.eventType))
            {
                span.end()
            }
        }
    }

    private fun mapAttributes(span: Span, traceId: String, event: TraceEvent)
    {
        span.setAttribute("tpipe.trace_id", traceId)
        span.setAttribute("tpipe.pipe_id", event.pipeId)
        span.setAttribute("tpipe.pipe_name", event.pipeName)
        span.setAttribute("tpipe.event_type", event.eventType.name)
        span.setAttribute("tpipe.phase", event.phase.name)
        span.setAttribute("tpipe.timestamp", event.timestamp)
        val containerType = event.metadata["container_type"] ?: event.metadata["containerType"]
        val provider = event.metadata["provider"]
        val model = event.metadata["model"]
        val sessionId = event.metadata["session.id"] ?: event.metadata["sessionId"]
        setStableAttribute(span, "tpipe.container_type", containerType)
        setStableAttribute(span, "tpipe.provider", provider)
        setStableAttribute(span, "tpipe.model", model)
        setStableAttribute(span, "session.id", sessionId)
        event.metadata.forEach { (key, value) ->
            if(!config.redactionPredicate(key))
            {
                val attribute = "tpipe.metadata.$key"
                when(value)
                {
                    is String -> span.setAttribute(attribute, value.take(config.maximumSerializedSize))
                    is Boolean -> span.setAttribute(attribute, value)
                    is Int -> span.setAttribute(attribute, value.toLong())
                    is Long -> span.setAttribute(attribute, value)
                    is Float -> span.setAttribute(attribute, value.toDouble())
                    is Double -> span.setAttribute(attribute, value)
                }
            }
        }
        if(config.includeContent && !config.redactionPredicate("content"))
        {
            span.setAttribute("tpipe.content", event.content?.text.orEmpty().take(config.maximumSerializedSize))
        }
        if(config.includeModelReasoning && !config.redactionPredicate("modelReasoning"))
        {
            span.setAttribute(
                "tpipe.model_reasoning",
                event.content?.modelReasoning.orEmpty().take(config.maximumSerializedSize)
            )
        }
        if(config.includeContextSnapshots && !config.redactionPredicate("contextSnapshot"))
        {
            span.setAttribute(
                "tpipe.context_snapshot",
                event.contextSnapshot?.toString().orEmpty().take(config.maximumSerializedSize)
            )
        }
    }

    private fun setStableAttribute(span: Span, name: String, value: Any?)
    {
        if(value == null || config.redactionPredicate(name)) return
        span.setAttribute(name, value.toString().take(config.maximumSerializedSize))
    }

    private fun isStart(type: TraceEventType): Boolean = type.name.endsWith("_START") ||
        type.name.endsWith("_STARTED") || type == TraceEventType.PIPE_START

    private fun isTerminal(type: TraceEventType): Boolean = type.name.endsWith("_END") ||
        type.name.endsWith("_SUCCESS") || type.name.endsWith("_FAILURE") ||
        type.name.endsWith("_COMPLETED") || type == TraceEventType.PIPE_END

    private fun isFailure(type: TraceEventType): Boolean = type.name.endsWith("_FAILURE") ||
        type == TraceEventType.PIPE_TIMEOUT || type == TraceEventType.KILLSWITCH_TRIPPED
}
