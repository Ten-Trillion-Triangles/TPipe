package com.TTT.TraceServer

import com.TTT.TraceServer.store.DEFAULT_TENANT
import com.TTT.TraceServer.store.FileBackedTraceStore
import com.TTT.TraceServer.store.InMemoryTraceStore
import com.TTT.TraceServer.store.TraceFilter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceServerStoreTest {

    private val tenants = listOf("default", "alpha", "beta")

    @AfterTest
    fun cleanup() {
        for (tenant in tenants) {
            TraceServerRegistry.sessionsFor(tenant).clear()
        }
    }

    private fun payload(id: String, name: String = "n-$id", status: String = "SUCCESS", html: String = "<html/>") =
        TracePayload(pipelineId = id, htmlContent = html, name = name, status = status)

    @Test
    fun inMemoryStoreHonorsLruCapPerTenant() {
        val store = InMemoryTraceStore(maxTraces = 3)
        store.put(payload("a-1"), tenant = "alpha")
        store.put(payload("a-2"), tenant = "alpha")
        store.put(payload("a-3"), tenant = "alpha")
        store.put(payload("b-1"), tenant = "beta")
        // alpha: 3 entries, oldest is "a-1"
        store.put(payload("a-4"), tenant = "alpha")
        assertNull(store.get("a-1", tenant = "alpha"))
        assertNotNull(store.get("a-4", tenant = "alpha"))
        assertEquals(3, store.count("alpha"))
        // beta: untouched
        assertNotNull(store.get("b-1", tenant = "beta"))
        assertEquals(1, store.count("beta"))
    }

    @Test
    fun inMemoryStoreIsolatesTenants() {
        val store = InMemoryTraceStore()
        store.put(payload("p1"), tenant = "alpha")
        store.put(payload("p1"), tenant = "beta")
        store.put(payload("p2"), tenant = "alpha")
        store.put(payload("p3"), tenant = DEFAULT_TENANT)
        assertEquals(2, store.count("alpha"))
        assertEquals(1, store.count("beta"))
        assertEquals(1, store.count(DEFAULT_TENANT))
    }

    @Test
    fun inMemoryStoreFiltersByStatusQueryAndSince() {
        val store = InMemoryTraceStore()
        store.put(payload("a", status = "SUCCESS"), tenant = "default")
        Thread.sleep(5)
        store.put(payload("b", status = "FAILURE"), tenant = "default")
        Thread.sleep(5)
        store.put(payload("c", status = "PENDING"), tenant = "default")
        val cutoff = System.currentTimeMillis()

        val byStatus = store.listSummaries(TraceFilter(status = "FAILURE"))
        assertEquals(1, byStatus.total)
        assertEquals("b", byStatus.items[0].id)

        // Match against the name (case-insensitive). The id is "c" but the test uses
        // query "C" which should match the name "c" (lowercased). The store's
        // applyFilters does case-insensitive contains.
        // The status "SUCCESS" contains the letter "c" (s-u-c-c-e-s-s), so a query
        // of "C" would also match that status. Pick a query that's unique to entry "c".
        val byQuery = store.listSummaries(TraceFilter(query = "n-c"))
        assertEquals(1, byQuery.total)
        assertEquals("c", byQuery.items[0].id)

        // since = now (future) should return 0 entries
        val bySince = store.listSummaries(TraceFilter(since = cutoff + 60_000))
        assertEquals(0, bySince.total)
    }

    @Test
    fun inMemoryStoreListIsDescendingByTimestamp() {
        val store = InMemoryTraceStore()
        store.put(payload("first"))
        Thread.sleep(2)
        store.put(payload("second"))
        Thread.sleep(2)
        store.put(payload("third"))
        val all = store.listSummaries(TraceFilter(limit = 10))
        assertEquals(listOf("third", "second", "first"), all.items.map { it.id })
    }

    @Test
    fun fileBackedStoreRoundTripsAndRecovers() {
        val dir: Path = Files.createTempDirectory("tpipe-trace-store-")
        val store1 = FileBackedTraceStore(dir, maxTraces = 100)
        store1.put(payload("alpha-1", name = "Alpha 1"), tenant = "alpha")
        store1.put(payload("beta-1", name = "Beta 1"), tenant = "beta")
        store1.put(payload("default-1", name = "Default 1"), tenant = "default")
        store1.close()

        // Simulate a restart: new store instance reads the same directory.
        val store2 = FileBackedTraceStore(dir, maxTraces = 100)
        assertEquals(1, store2.count("alpha"))
        assertEquals(1, store2.count("beta"))
        assertEquals(1, store2.count("default"))
        assertNotNull(store2.get("alpha-1", "alpha"))
        assertNotNull(store2.get("beta-1", "beta"))
        store2.close()

        // Cleanup
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun fileBackedStoreDeletePropagates() {
        val dir: Path = Files.createTempDirectory("tpipe-trace-store-")
        val store = FileBackedTraceStore(dir)
        store.put(payload("p1"), tenant = "alpha")
        store.put(payload("p2"), tenant = "alpha")
        assertTrue(store.delete("p1", tenant = "alpha"))
        assertFalse(store.delete("p1", tenant = "alpha"))
        assertEquals(1, store.count("alpha"))
        store.close()
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun fileBackedStoreRecoversFromMidWriteCrashByReplayingLog() {
        val dir: Path = Files.createTempDirectory("tpipe-trace-store-")
        val firstStore = FileBackedTraceStore(dir)
        firstStore.put(payload("crash-1"), tenant = "alpha")
        // Simulate a crash: do not call close() so the index is not snapshotted.
        // The data is on disk in the JSONL log only.

        val secondStore = FileBackedTraceStore(dir)
        val recovered = secondStore.get("crash-1", "alpha")
        assertNotNull(recovered, "log replay should restore the entry even without an index snapshot")
        secondStore.close()
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
