package com.TTT.TraceServer

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.RemoteTraceConfig
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * End-to-end live test for the `kind=pumpstation` discriminator on the
 * TraceServer wire.
 *
 * **What this test verifies.** Booting the real [TraceServer] (Netty) on an
 * OS-picked free port, configuring [RemoteTraceConfig] to point at it, and
 * dispatching a single payload via [com.TTT.Debug.RemoteTraceDispatcher.dispatchTrace]
 * with `kind = "pumpstation"` — exactly the entry point that
 * `Pipeline/PumpStationLoop.kt:3001-3010` uses in production. We then assert
 * that `GET /api/traces` and `GET /api/traces/{id}` both carry the
 * discriminator on the trace they list/return.
 *
 * **Why we do not stand up a real `PumpStation` harness here.** The canonical
 * stub-mode harness in `src/test/kotlin/Pipeline/PumpStationMiniMaxLiveTest.kt`
 * requires the `MINIMAX_API_KEY` env var to be set. This live test is run in
 * the CI sandbox where that key is not present, so the harness cannot be
 * driven end-to-end without breaking the env-gate contract. Exercising the
 * same dispatch entry point in production (Task 3 commit 39315bce added
 * the `kind="pumpstation"` argument at `PumpStationLoop.kt:3009`) is
 * wire-equivalent: the test asserts exactly the property the harness-side
 * call relies on — that `kind` round-trips through the server and lands in
 * both the list and detail responses.
 *
 * **Why we use the v1 dispatcher only, not also `PipeTracer.exportTrace`.**
 * The task plan calls out that `PumpStationLoop.kt:2986-3011` results in TWO
 * POSTs per harness run: one from `PipeTracer.exportTrace` (carrying
 * `kind=null` because the implicit call site predates the v2 wire) and one
 * from the explicit `dispatchTrace(..., kind="pumpstation")` that
 * `_upsertSummary` will replace the first with. This test exercises the
 * explicit kind-stamped path that the dashboard's badge logic depends on;
 * the implicit null-kind path is covered by `src/test/kotlin/Debug/PipeTracerTest.kt`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PumpStationTraceServerLiveTest
{
    private val traceBaseDir: File by lazy {
        File(TPipeConfig.getTraceDir(), "Library/pumpstation-traceserver")
    }

    private val client: HttpClient = HttpClient.newHttpClient()

    // Held so @AfterEach can stop the right engine without depending on a no-arg
    // overload of [stopTraceServer]. The start/stop pair is reset around each
    // test so a single instance can boot/stop the server per @Test.
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    @BeforeEach
    fun setup()
    {
        traceBaseDir.mkdirs()
        // Wipe tenant state from any previous run (default + legacy "test").
        TraceServerRegistry.useInMemoryStore()
        TraceServerRegistry.agentAuthMechanism = null
        TraceServerRegistry.clientAuthMechanism = null
        for(tenant in listOf("default", "test"))
        {
            runCatching { TraceServerRegistry.sessionsFor(tenant).clear() }
        }
        // Reset dispatcher config so tests are independent.
        RemoteTraceConfig.remoteServerUrl = null
        RemoteTraceConfig.authHeader = null
        RemoteTraceConfig.dispatchAutomatically = false
        engine = null
    }

    @AfterEach
    fun teardown()
    {
        RemoteTraceConfig.remoteServerUrl = null
        RemoteTraceConfig.authHeader = null
        RemoteTraceConfig.dispatchAutomatically = false
        runCatching { stopTraceServer(engine!!) }
        engine = null
        // Restore file-backed store so other tests in the same JVM reuse the
        // canonical ~/.TPipe-Debug/trace-server directory.
        TraceServerRegistry.configureStore(
            com.TTT.TraceServer.store.FileBackedTraceStore(
                java.nio.file.Paths.get(System.getProperty("user.home"), ".TPipe-Debug", "trace-server")
            )
        )
    }

    /**
     * End-to-end live assertion: the trace dispatched via
     * [com.TTT.Debug.RemoteTraceDispatcher.dispatchTrace] with
     * `kind="pumpstation"` lands in the running [TraceServer] registry and
     * surfaces the discriminator on `GET /api/traces` and the detail
     * endpoint, so the dashboard badge logic has the data it needs.
     */
    @Test
    fun pumpStationTraceArrivesWithKindDiscriminator() = runBlocking {
        // 1. Boot TraceServer on a random free port.
        val port = pickFreePort()
        val cfg = TraceServerConfig(
            port = port,
            host = "127.0.0.1",
            defaultTenant = "test",
        )
        val started = startTraceServer(cfg, wait = false)
        engine = started
        // Netty cold-start settle — boot + module load + listener bind.
        delay(1500)

        try {
            // 2. Configure the dispatcher to point at the local server.
            val baseUrl = "http://127.0.0.1:$port"
            RemoteTraceConfig.remoteServerUrl = baseUrl
            RemoteTraceConfig.authHeader = "Bearer test-token"
            RemoteTraceConfig.dispatchAutomatically = true

            // 3. Pick a unique run id so we can verify it appears by id.
            val runId = "ps-live-test-${System.currentTimeMillis()}"

            // 4. Exercise the SAME production entry point that
            //    Pipeline/PumpStationLoop.kt:3001-3010 uses: the explicit
            //    kind-stamped dispatch. This is the call that carries
            //    `kind="pumpstation"` through to the dashboard.
            //
            //    Even with no events captured under [runId], the dispatcher
            //    resolves to an empty list via `PipeTracer.getTrace(...)` →
            //    `TraceVisualizer.generateHtmlReport(emptyList())` →
            //    `generateStandardHtmlReport(...)` which returns the standard
            //    "TPipe Pipeline Flow Visualization" HTML scaffold. The HTML
            //    is non-blank, and the `kind` discriminator rides through
            //    the wire payload verbatim — which is the only thing this
            //    test is asserting on.
            com.TTT.Debug.RemoteTraceDispatcher.dispatchTrace(
                pipelineId = runId,
                name = "pumpstation-live-test",
                status = "SUCCESS",
                kind = "pumpstation",
            )
            // The dispatcher launches an async POST in GlobalScope on Dispatchers.IO;
            // give the wire path a moment to land on the server.
            delay(2500)

            // 5. GET /api/traces and verify the kind discriminator on the summary.
            val listResp = client.send(
                HttpRequest.newBuilder(URI("$baseUrl/api/traces?limit=20"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, listResp.statusCode(), "GET /api/traces should return 200; body=${listResp.body()}")
            val listBody = Json.parseToJsonElement(listResp.body()).jsonObject
            val items = listBody["items"]!!.jsonArray
            val ours = items.firstOrNull {
                it.jsonObject["id"]?.jsonPrimitive?.content == runId
            }
            assertNotNull(ours, "Expected trace $runId in server list response; items=${listResp.body()}")
            val oursObj = ours!!.jsonObject
            assertEquals(
                "pumpstation",
                oursObj["kind"]?.jsonPrimitive?.content,
                "Expected kind=pumpstation in summary, got summary: $oursObj"
            )
            assertEquals(
                "pumpstation-live-test",
                oursObj["name"]?.jsonPrimitive?.content,
                "Expected name='pumpstation-live-test' in summary"
            )
            assertEquals(
                "SUCCESS",
                oursObj["status"]?.jsonPrimitive?.content,
                "Expected status=SUCCESS in summary"
            )

            // 6. GET /api/traces/{id} and verify htmlContent is non-empty
            //    and the detail payload retains the kind discriminator.
            val detailResp = client.send(
                HttpRequest.newBuilder(URI("$baseUrl/api/traces/$runId"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, detailResp.statusCode(), "GET /api/traces/$runId should return 200; body=${detailResp.body()}")
            val payload = Json.parseToJsonElement(detailResp.body()).jsonObject
            val html = payload["htmlContent"]!!.jsonPrimitive.content
            assertTrue(html.isNotBlank(), "htmlContent should not be blank; got=${html.take(200)}")
            assertEquals(
                "pumpstation",
                payload["kind"]?.jsonPrimitive?.content,
                "Expected kind=pumpstation on detail payload"
            )

            // 7. Drop artifacts under the canonical trace dir per
            //    tpipe-trace-output-conventions, for postmortem review.
            File(traceBaseDir, "$runId.html").writeText(html)
            File(traceBaseDir, "$runId.summary.json").writeText(listResp.body())
            File(traceBaseDir, "$runId.detail.json").writeText(detailResp.body())
        } finally {
            runCatching { engine?.let { stopTraceServer(it) } }
        }
    }

    private fun pickFreePort(): Int = java.net.ServerSocket(0).use { it.localPort }
}
