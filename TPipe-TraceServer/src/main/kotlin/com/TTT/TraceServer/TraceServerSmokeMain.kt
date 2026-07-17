package com.TTT.TraceServer

import kotlin.concurrent.thread

/**
 * Smoke entry point for visual dashboard verification of the PumpStation
 * kind-aware TraceServer. Boots the server on 127.0.0.1:8081, injects 4
 * synthetic traces with varied `kind` values, and holds the JVM alive for
 * 120 seconds so an out-of-band browser screenshot pass can capture the
 * dashboard at http://127.0.0.1:8081/.
 *
 * Run via: ./gradlew :TPipe-TraceServer:TraceServerSmokeMain
 *
 * The 4 traces exercise:
 *  - 2x kind="pumpstation" (with different statuses — SUCCESS and FAILURE)
 *  - 1x kind="manifold"
 *  - 1x with no kind (legacy Pipeline trace, should render with no badge)
 *
 * The HTML content is a small synthetic stand-in. Real harness traces
 * would carry the full TraceVisualizer PumpStation report here; for the
 * screenshot smoke we just need the kind discriminator visible.
 */
fun main()
{
    println("--- TraceServer Smoke: PumpStation kind-aware dashboard ---")
    println("Booting TraceServer on 127.0.0.1:8081 ...")

    // Open dashboard / open agent POST — no auth required for the screenshot smoke.
    // The dashboard's default tab is `key` (not password), so the request body
    // is `{"key":"smoke-key"}`. The server's /api/auth/login short-circuits to
    // the hasher branch ONLY when `req.password != null`. With the key-tab
    // payload + hasher disabled, the request lands in the lambda branch where
    // clientAuthMechanism gates access. Set the lambda to accept any key.
    val cfg = TraceServerConfig(
        port = 8081,
        host = "127.0.0.1",
        defaultTenant = "smoke",
        auth = AuthConfig(passwordHasherEnabled = false),
    )
    TraceServerRegistry.clientAuthMechanism = { _ -> true }
    TraceServerRegistry.agentAuthMechanism = { token -> token?.startsWith("Bearer ") == true }

    // No auth — leaves both mechanisms null. Dashboard open, agent POSTs accepted.
    thread(start = true, isDaemon = false, name = "Ktor-TraceServer-Smoke") {
        startTraceServer(cfg, wait = true)
    }

    Thread.sleep(2000) // wait for server bind

    val mockHtml = """
        <html>
        <body style="background:#0f111a; color:#e2e8f0; font-family:monospace; padding:20px;">
            <h2>Trace Rendered Output</h2>
            <p>Synthetic trace body — the kind discriminator above is what the screenshot proves.</p>
        </body>
        </html>
    """.trimIndent()

    TraceServerRegistry.store.put(TracePayload(
        pipelineId = "smoke-ps-success-001",
        htmlContent = mockHtml,
        name = "PumpStation Live Run",
        status = "SUCCESS",
        tags = mapOf("team" to "platform", "env" to "staging"),
        kind = "pumpstation",
    ), cfg.defaultTenant)
    TraceServerRegistry.store.put(TracePayload(
        pipelineId = "smoke-ps-failure-002",
        htmlContent = mockHtml,
        name = "PumpStation Multi-Path",
        status = "FAILURE",
        tags = mapOf("team" to "research", "env" to "prod"),
        kind = "pumpstation",
    ), cfg.defaultTenant)
    TraceServerRegistry.store.put(TracePayload(
        pipelineId = "smoke-manifold-success-003",
        htmlContent = mockHtml,
        name = "Manifold Worker Run",
        status = "SUCCESS",
        tags = mapOf("team" to "platform"),
        kind = "manifold",
    ), cfg.defaultTenant)
    TraceServerRegistry.store.put(TracePayload(
        pipelineId = "smoke-legacy-no-kind-004",
        htmlContent = mockHtml,
        name = "Legacy Pipeline",
        status = "SUCCESS",
        tags = mapOf("team" to "platform"),
        // no kind — legacy v1 wire shape; should render with NO badge
    ), cfg.defaultTenant)

    println("================================================================")
    println("Smoke server running on http://127.0.0.1:8081")
    println("Injected 4 traces (2x pumpstation, 1x manifold, 1x legacy no-kind)")
    println("Open the dashboard in a browser to verify:")
    println("  - PUMPSTATION badge on the first two list items")
    println("  - MANIFOLD badge on the third")
    println("  - NO badge on the fourth (legacy)")
    println("  - Filter chip row lets you filter to 'PumpStation' / 'Manifold' / 'Pipeline'")
    println("================================================================")
    println("Holding server alive for 120 seconds ...")

    Thread.sleep(120_000)
    // Ktor Netty engine is non-daemon; JVM exit triggers its shutdown hook,
    // which releases :8081. No explicit stopTraceServer() needed in the
    // smoke flow (and the spec's other server runs on `wait = true` in a
    // dedicated thread, so we never block here).
}