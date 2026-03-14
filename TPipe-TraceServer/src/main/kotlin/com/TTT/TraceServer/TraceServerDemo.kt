package com.TTT.TraceServer

import com.TTT.Debug.*
import com.TTT.Context.*
import kotlinx.coroutines.*

fun main(args: Array<String>) {
    println("--- Starting TPipe Trace Dashboard Demo ---")
    println("Setting up Auth Mechanism (Key = demo123)")

    // 1. Setup global auth mechanisms
    TraceServerRegistry.agentAuthMechanism = { token ->
        token == "Bearer secret-agent-key"
    }
    TraceServerRegistry.clientAuthMechanism = { key ->
        key == "demo123"
    }

    // 2. Start the remote server in the background
    GlobalScope.launch {
        startTraceServer(port = 8081, wait = true)
    }

    // Wait for server to bind
    Thread.sleep(2000)

    // 3. Configure the core dispatcher to target our new server
    RemoteTraceConfig.remoteServerUrl = "http://127.0.0.1:8081"
    RemoteTraceConfig.authHeader = "Bearer secret-agent-key"

    // 4. Generate some realistic fake traces
    println("Injecting dummy traces...")

    PipeTracer.enable()

    fun createEvent(pipeId: String, pipeName: String, eventType: TraceEventType, phase: TracePhase, error: Throwable? = null): TraceEvent {
        return TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = pipeId,
            pipeName = pipeName,
            eventType = eventType,
            phase = phase,
            content = null,
            contextSnapshot = null,
            error = error
        )
    }

    // Trace 1: Successful Data Sync
    val p1 = "pipeline-sync-user-data-1"
    PipeTracer.startTrace(p1)
    PipeTracer.addEvent(p1, createEvent(p1, "UserAuthPipe", TraceEventType.VALIDATION_SUCCESS, TracePhase.VALIDATION))
    PipeTracer.addEvent(p1, createEvent(p1, "DBFetchPipe", TraceEventType.TRANSFORMATION_START, TracePhase.TRANSFORMATION))
    PipeTracer.addEvent(p1, createEvent(p1, "DBFetchPipe", TraceEventType.PIPE_SUCCESS, TracePhase.CLEANUP))
    RemoteTraceDispatcher.dispatchTrace(p1, name = "User Data Sync", status = "SUCCESS")

    // Trace 2: Failure LLM Call
    val p2 = "pipeline-llm-generate-2"
    PipeTracer.startTrace(p2)
    PipeTracer.addEvent(p2, createEvent(p2, "OllamaInitPipe", TraceEventType.VALIDATION_SUCCESS, TracePhase.VALIDATION))
    PipeTracer.addEvent(p2, createEvent(p2, "OllamaGeneratePipe", TraceEventType.TRANSFORMATION_START, TracePhase.TRANSFORMATION))
    PipeTracer.addEvent(p2, createEvent(p2, "OllamaGeneratePipe", TraceEventType.API_CALL_FAILURE, TracePhase.TRANSFORMATION, RuntimeException("Connection Refused to 11434")))
    RemoteTraceDispatcher.dispatchTrace(p2, name = "LLM Generation", status = "FAILURE")

    // Trace 3: Complex Pipeline (Pending/Active)
    val p3 = "pipeline-image-upload-3"
    PipeTracer.startTrace(p3)
    PipeTracer.addEvent(p3, createEvent(p3, "UploadGateway", TraceEventType.VALIDATION_SUCCESS, TracePhase.VALIDATION))
    PipeTracer.addEvent(p3, createEvent(p3, "S3UploadPipe", TraceEventType.TRANSFORMATION_START, TracePhase.TRANSFORMATION))
    PipeTracer.addEvent(p3, createEvent(p3, "MetadataExtractor", TraceEventType.TRANSFORMATION_START, TracePhase.TRANSFORMATION))
    RemoteTraceDispatcher.dispatchTrace(p3, name = "S3 Image Processing", status = "PENDING")

    println("================================================================")
    println("Demo running! ")
    println("To view the dashboard, open your browser and navigate to:")
    println("➡️  http://localhost:8081")
    println("")
    println("🔑 When prompted for an Authentication Key, enter: demo123")
    println("================================================================")

    // Block forever
    while (true) {
        Thread.sleep(1000)
    }
}
