package com.TTT.TraceServer

import com.TTT.TraceServer.store.FileBackedTraceStore
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceServerStoreQuotaTest {

    private val tempDirs: MutableList<Path> = mutableListOf()

    private fun tempStore(
        maxTraces: Int = 100,
        ttlMs: Long? = null,
        perTenantQuota: Int? = null
    ): FileBackedTraceStore
    {
        val dir = Files.createTempDirectory("tpipe-test-store-")
        tempDirs.add(dir)
        val ttl = if(ttlMs == null) null else java.time.Duration.ofMillis(ttlMs)
        return FileBackedTraceStore(dir, maxTraces, ttl, perTenantQuota)
    }

    @AfterTest
    fun tearDown() {
        TraceServerRegistry.configureStore(com.TTT.TraceServer.store.InMemoryTraceStore())
        for (d in tempDirs) {
            d.toFile().deleteRecursively()
        }
        tempDirs.clear()
    }

    @Test
    fun perTenantQuotaEvictsOldest() {
        val store = tempStore(maxTraces = 100, perTenantQuota = 3)
        for (i in 1..5) {
            store.put(TracePayload("p-$i", "<x/>", "P$i", "SUCCESS"), tenant = "alpha")
        }
        assertEquals(3, store.count("alpha"))
        // The two newest should be retained.
        assertEquals("P5", store.get("p-5", "alpha")?.name)
        assertEquals("P4", store.get("p-4", "alpha")?.name)
        assertEquals(null, store.get("p-1", "alpha"))
    }

    @Test
    fun ttlEvictsOldEntries() {
        val store = tempStore(maxTraces = 100, ttlMs = 50)
        store.put(TracePayload("old", "<x/>", "Old", "SUCCESS"), tenant = "alpha")
        Thread.sleep(100)
        store.put(TracePayload("new", "<x/>", "New", "SUCCESS"), tenant = "alpha")
        // Adding new triggers eviction of expired.
        assertEquals(null, store.get("old", "alpha"))
        assertEquals("New", store.get("new", "alpha")?.name)
    }

    @Test
    fun replayOnStartupAlsoEvicts() {
        val dir = Files.createTempDirectory("tpipe-test-replay-")
        tempDirs.add(dir)
        val ttl = java.time.Duration.ofMillis(50)
        val s1 = FileBackedTraceStore(dir, maxTraces = 100, ttl = ttl)
        s1.put(TracePayload("p-1", "<x/>", "P1", "SUCCESS"), tenant = "alpha")
        Thread.sleep(80)
        s1.close()
        val s2 = FileBackedTraceStore(dir, maxTraces = 100, ttl = ttl)
        // Initial eviction pass during init should drop the expired entry.
        assertEquals(null, s2.get("p-1", "alpha"))
    }

    @Test
    fun healthEndpointExposesTtlAndQuota() = testApplication {
        val store = tempStore(maxTraces = 100, ttlMs = 60_000, perTenantQuota = 50)
        // The route reads TraceServerRegistry.store; the install block in
        // traceServerModule does NOT auto-swap the registry store, so we
        // configure it explicitly here.
        TraceServerRegistry.configureStore(store)
        application {
            traceServerModule(TraceServerConfigBridge.legacy().copy(store = StoreConfig(
                type = StoreType.FILE_BACKED,
                directory = store.directoryPath().let { java.nio.file.Paths.get(it) },
                maxTraces = 100,
                ttl = java.time.Duration.ofMillis(60_000),
                perTenantQuota = 50
            )))
        }
        val res = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"ttlMs\""), body)
        assertTrue(body.contains("\"perTenantQuota\""), body)
        assertTrue(body.contains("FILE_BACKED"), body)
    }

    @Test
    fun tagFilterReturnsMatchingTraces() = testApplication {
        application { traceServerModule() }
        TraceServerRegistry.agentAuthMechanism = { true }
        client.post("/api/traces") {
            contentType(ContentType.Application.Json)
            header("X-Tenant", "alpha")
            setBody("""{"pipelineId":"p-1","htmlContent":"<x/>","name":"P1","status":"SUCCESS","tags":{"team":"platform","env":"prod"}}""")
        }
        client.post("/api/traces") {
            contentType(ContentType.Application.Json)
            header("X-Tenant", "alpha")
            setBody("""{"pipelineId":"p-2","htmlContent":"<x/>","name":"P2","status":"SUCCESS","tags":{"team":"research"}}""")
        }
        val byTeam = client.get("/api/traces?tag=team:platform") {
            header("X-Tenant", "alpha")
        }
        assertEquals(HttpStatusCode.OK, byTeam.status)
        val body = byTeam.bodyAsText()
        assertTrue(body.contains("\"p-1\""), body)
        assertTrue(!body.contains("\"p-2\""), "tag filter should not include p-2: $body")
    }
}
