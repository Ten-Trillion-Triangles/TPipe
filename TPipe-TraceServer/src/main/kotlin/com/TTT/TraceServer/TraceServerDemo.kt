package com.TTT.TraceServer

import com.TTT.Debug.TraceEvent
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TracePhase
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.RemoteTraceConfig
import com.TTT.Debug.RemoteTraceDispatcher
import kotlin.concurrent.thread

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

    // 2. Start the remote server in a native Java thread
    thread(start = true, isDaemon = false, name = "Ktor-TraceServer") {
        startTraceServer(port = 8081, wait = true)
    }

    // Wait for server to bind
    Thread.sleep(2000)

    // In local run environment Java versions get misaligned between compile/run
    // We will simulate agent behavior using direct TraceRegistry calls for the demo,
    // to avoid the Classfile version 68.0 vs 65.0 mismatch on the JVM here

    println("Injecting dummy traces directly to bypass JVM class loader issues in local run...")

    // Create HTML template string for demo
    val mockHtml = """
        <html>
        <body style="background-color: #0f111a; color: #e2e8f0; font-family: monospace; padding: 20px;">
            <h2>Trace Rendered Output</h2>
            <div style="border-left: 2px solid #38bdf8; padding-left: 10px;">
                <p><strong>Status:</strong> Mock Trace Executed</p>
                <p>This is a simulated HTML payload showing how a Mermaid or Pipeline visualization would look in this frame.</p>
            </div>
        </body>
        </html>
    """.trimIndent()

    TraceServerRegistry.registerTrace(TracePayload(
        pipelineId = "pipeline-sync-user-data-1",
        htmlContent = mockHtml.replace("Mock Trace", "Data Sync"),
        name = "User Data Sync",
        status = "SUCCESS"
    ))

    TraceServerRegistry.registerTrace(TracePayload(
        pipelineId = "pipeline-llm-generate-2",
        htmlContent = mockHtml.replace("Mock Trace", "LLM Failed: Connection Refused"),
        name = "LLM Generation",
        status = "FAILURE"
    ))

    TraceServerRegistry.registerTrace(TracePayload(
        pipelineId = "pipeline-image-upload-3",
        htmlContent = mockHtml.replace("Mock Trace", "Uploading fragments..."),
        name = "S3 Image Processing",
        status = "PENDING"
    ))

    println("================================================================")
    println("Demo running! ")
    println("To view the dashboard, open your browser and navigate to:")
    println("➡️  http://localhost:8081")
    println("")
    println("🔑 When prompted for an Authentication Key, enter: demo123")
    println("================================================================")

    while (true) {
        Thread.sleep(1000)
    }
}
