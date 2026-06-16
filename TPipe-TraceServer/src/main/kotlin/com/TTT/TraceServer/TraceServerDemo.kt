package com.TTT.TraceServer

import com.TTT.TraceServer.auth.HashedPassword
import com.TTT.TraceServer.auth.Pbkdf2PasswordHasher
import com.TTT.TraceServer.store.FileBackedTraceStore
import com.TTT.TraceServer.store.InMemoryTraceStore
import kotlin.concurrent.thread

fun main(args: Array<String>)
{
    println("--- Starting TPipe Trace Dashboard Demo (v2) ---")
    println("Setting up auth: dashboard key = demo123 (legacy lambda)")

    // 1. Auth: keep the v1 key path for the dashboard and add a v2 password
    //    path for agent users. The password is hashed at boot with the
    //    default PBKDF2 hasher; pre-computed hashes can be pasted into
    //    `AuthConfig.expectedHash` instead.
    val dashboardHasher = Pbkdf2PasswordHasher()
    TraceServerRegistry.clientAuthMechanism = { key -> key == "demo123" }
    TraceServerRegistry.agentAuthMechanism = { token -> token == "Bearer secret-agent-key" }

    // 2. Parse CLI flags and resolve the persistence + observability config.
    val parsed = parseArgs(args, TraceServerConfigBridge.legacy())
    val config = parsed.copy(
        auth = parsed.auth.copy(
            passwordHasherEnabled = true,
            expectedHash = dashboardHasher.hash("demo123-pw")
        )
    )
    TraceServerRegistry.authConfig = config.auth
    val resolved = config.store.resolveStore()
    TraceServerRegistry.configureStore(resolved)
    println("Trace store: ${config.store.type} @ ${config.store.directory} (max=${config.store.maxTraces}, ttl=${config.store.ttl}, quota=${config.store.perTenantQuota})")
    println("Auth: access TTL=${config.auth.accessTokenTtl}, refresh TTL=${config.auth.refreshTokenTtl}")
    println("Rate limit: per-IP=${config.rateLimit.perIpWrites}/${config.rateLimit.window} | per-tenant=${config.rateLimit.perTenantWrites}/${config.rateLimit.window}")
    println("Compression: ${if (config.compression.enabled) "gzip+deflate" else "off"}")
    println("Metrics: ${if (config.metrics.enabled) "on at ${config.metrics.path}" else "off"}")

    // 3. Start the remote server in a native Java thread.
    thread(start = true, isDaemon = false, name = "Ktor-TraceServer") {
        startTraceServer(config, wait = true)
    }

    // Wait for server to bind
    Thread.sleep(2000)

    println("Injecting dummy traces with v2 tags directly to bypass JVM class loader issues in local run...")

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

    TraceServerRegistry.store.put(TracePayload(
        pipelineId = "pipeline-sync-user-data-1",
        htmlContent = mockHtml.replace("Mock Trace", "Data Sync"),
        name = "User Data Sync",
        status = "SUCCESS",
        tags = mapOf("team" to "platform", "env" to "staging")
    ))

    TraceServerRegistry.store.put(TracePayload(
        pipelineId = "pipeline-llm-generate-2",
        htmlContent = mockHtml.replace("Mock Trace", "LLM Failed: Connection Refused"),
        name = "LLM Generation",
        status = "FAILURE",
        tags = mapOf("team" to "research", "env" to "prod", "model" to "claude-3")
    ))

    TraceServerRegistry.store.put(TracePayload(
        pipelineId = "pipeline-image-upload-3",
        htmlContent = mockHtml.replace("Mock Trace", "Uploading fragments..."),
        name = "S3 Image Processing",
        status = "PENDING",
        tags = mapOf("team" to "platform", "env" to "prod")
    ))

    println("================================================================")
    println("Demo running! ")
    println("To view the dashboard, open your browser and navigate to:")
    println("➡️  http://localhost:${config.port}")
    println("")
    println("🔑 When prompted for an Authentication Key, enter: demo123")
    println("📜 OpenAPI spec: http://localhost:${config.port}/api/openapi.yaml")
    println("📊 Prometheus metrics: http://localhost:${config.port}${config.metrics.path}")
    println("💚 Health: http://localhost:${config.port}/api/health")
    println("================================================================")

    while(true)
    {
        Thread.sleep(1000)
    }
}
