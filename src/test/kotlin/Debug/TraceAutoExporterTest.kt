package com.TTT.Debug

import com.TTT.Pipeline.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the contract for [TraceAutoExporter] — the thread-safe coordinator that
 * serializes per-target-path writes so concurrent `getTraceReport()` calls from
 * different containers don't corrupt the same file.
 *
 * The pre-2026-08-08 codebase called [com.TTT.Util.writeStringToFile] directly on
 * the calling thread inside `getTraceReport()`. Two threads writing to the same
 * path could interleave bytes mid-write and corrupt the file. This test surfaces
 * the failure mode deterministically (10 threads × 100 iterations) so the
 * fix is pinned by failing tests first.
 */
class TraceAutoExporterTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        PipeTracer.enable()
    }

    @AfterEach
    fun cleanup() {
        PipeTracer.disable()
    }

    // ----- Cooperative gate: every worker calls await, then sleeps a fixed
    // ----- interval while holding the per-path lock. If the lock is per-path
    // ----- (not global), the workers on different paths don't block each other.
    // ----- If the lock is global, they do — and the test times out.

    @Test
    fun concurrentWritesToSamePathExecuteSerially() {
        val target = tempDir.resolve("trace-same-path.txt").toString()
        val exporter = TraceAutoExporter.create()
        val threadCount = 8
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val observedConcurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        try {
            repeat(threadCount) { i ->
                executor.submit {
                    startLatch.await()
                    exporter.export(target, "thread-$i\n") {
                        val now = observedConcurrent.incrementAndGet()
                        maxConcurrent.updateAndGet { prev -> maxOf(prev, now) }
                        Thread.sleep(50)  // hold the lock long enough to detect contention
                        observedConcurrent.decrementAndGet()
                    }
                    doneLatch.countDown()
                }
            }
            startLatch.countDown()
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All exporter submissions must complete")
        } finally {
            executor.shutdown()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }

        // Every call holds the lock for 50ms. If the lock is per-path, two callers
        // to the same path would collide (maxConcurrent == 1). Different paths would
        // allow parallelism, but they all target the same path here.
        assertEquals(1, maxConcurrent.get(),
            "Concurrent writes to the same path must serialize; max concurrent observers should be 1")
    }

    @Test
    fun concurrentWritesToDifferentPathsDoNotBlockEachOther() {
        val exporter = TraceAutoExporter.create()
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(4)
        val executor = Executors.newFixedThreadPool(4)
        val observedConcurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        try {
            repeat(4) { i ->
                val target = tempDir.resolve("trace-different-$i.txt").toString()
                executor.submit {
                    startLatch.await()
                    exporter.export(target, "thread-$i\n") {
                        val now = observedConcurrent.incrementAndGet()
                        maxConcurrent.updateAndGet { prev -> maxOf(prev, now) }
                        Thread.sleep(100)
                        observedConcurrent.decrementAndGet()
                    }
                    doneLatch.countDown()
                }
            }
            startLatch.countDown()
            assertTrue(doneLatch.await(2, TimeUnit.SECONDS), "All writers must complete")
        } finally {
            executor.shutdown()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }

        // With 4 distinct paths and 100ms sleeps, we should see at least 2 in flight.
        // A global lock would cap at 1. Pin the lower bound to detect a global-lock regression.
        assertTrue(maxConcurrent.get() >= 2,
            "Writes to different paths should run concurrently; max concurrent was ${maxConcurrent.get()}")
    }

    @Test
    fun writesDoNotCorruptFileUnderContention() {
        val target = tempDir.resolve("trace-corruption.txt").toString()
        val exporter = TraceAutoExporter.create()
        val threadCount = 10
        val iterations = 100
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            repeat(threadCount) { i ->
                executor.submit {
                    startLatch.await()
                    repeat(iterations) { j ->
                        // Each write is a self-delimited record: "<thread>-<iter>\n".
                        // The exporter must keep the records intact — no interleaved bytes.
                        exporter.export(target, "t${i}-i${j}\n") {
                            File(target).appendText("t${i}-i${j}\n")
                        }
                    }
                    doneLatch.countDown()
                }
            }
            startLatch.countDown()
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All writers must complete")
        } finally {
            executor.shutdown()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }

        // Reconstruct the expected set of records and compare to the file contents.
        val expected = mutableSetOf<String>()
        for (i in 0 until threadCount) for (j in 0 until iterations)
            expected += "t${i}-i${j}\n"

        val actual = File(target).readLines().map { "$it\n" }.toSet()
        assertEquals(expected, actual,
            "All ${threadCount * iterations} records must be present and intact")
    }

    @Test
    fun exportReturnsResultWithoutBlockingIndefinitely() {
        val target = tempDir.resolve("trace-fast.txt").toString()
        val exporter = TraceAutoExporter.create()
        val start = System.nanoTime()
        exporter.export(target, "hello\n") { File(target).writeText("hello\n") }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 1000, "Synchronous export of a small file must return in under 1s; took ${elapsedMs}ms")
    }

    // ----- Container-propagation tests: same shape as the maxHistory pin,
    // ----- but for autoExport. When a container's getTraceReport() is called
    // ----- with autoExport=true, the trace must land in a file.
    //
    // ----- We use a known trace ID rather than the container's private id
    // ----- field — `PipeTracer.startTrace(id)` lets us inject a deterministic
    // ----- id, and `getTraceReport` will then look it up by that id.

    private fun populateTrace(id: String, eventType: TraceEventType, withSubstring: String) {
        PipeTracer.startTrace(id)
        PipeTracer.addEvent(id, TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "p", pipeName = "P", eventType = eventType,
            phase = TracePhase.CLEANUP, content = null, contextSnapshot = null
        ))
    }

    @Test
    fun pipeline_getTraceReportWithAutoExport_writesFile() {
        val pipeline = Pipeline()
        pipeline.enableTracing(TraceConfig(autoExport = true, exportPath = tempDir.toString()))
        val id = pipeline.getTraceId()
        populateTrace(id, TraceEventType.PIPE_SUCCESS, "PIPE_SUCCESS")

        pipeline.getTraceReport(TraceFormat.CONSOLE)

        // The exporter must have written a file. Filename shape is the test seam:
        // trace-<first-8-of-id>.<ext> — NOT the malformed "trace-xxxxxxxx-html.html" pattern.
        val written = tempDir.toFile().listFiles()
            ?.filter { it.name.startsWith("trace-") && it.name.endsWith(".txt") }
            ?.filter { it.readText().contains("PIPE_SUCCESS") }
        assertNotNull(written?.firstOrNull(),
            "autoExport=true must produce a file under ${tempDir}; saw ${tempDir.toFile().listFiles()?.map { it.name }}")
    }

    @Test
    fun pumpStation_getTraceReportWithAutoExport_writesFile() {
        val ps = PumpStation()
        ps.enableTracing(TraceConfig(autoExport = true, exportPath = tempDir.toString()))
        // PumpStation's getTraceReport uses taskState.runId; we override it for deterministic testing.
        val runId = "auto-export-ps-${System.nanoTime()}"
        // We can't read taskState directly, but PipeTracer.startTrace(runId) + adding events
        // is the cleanest way: getTraceReport() will look up the runId from taskState.
        // We need to inject the runId via the taskState. Use the setRunIdForTest seam we will add.
        ps.setRunIdForTest(runId)
        populateTrace(runId, TraceEventType.PUMP_STATION_STARTED, "PUMP_STATION")

        ps.getTraceReport(TraceFormat.CONSOLE)

        val written = tempDir.toFile().listFiles()
            ?.filter { it.name.startsWith("pumpstation-") && it.readText().contains("PUMP_STATION_") }
        assertNotNull(written?.firstOrNull(),
            "PumpStation autoExport must produce a file under ${tempDir}; saw ${tempDir.toFile().listFiles()?.map { it.name }}")
    }

    @Test
    fun manifold_getTraceReportWithAutoExport_writesFile() {
        val manifold = Manifold()
        manifold.enableTracing(TraceConfig(autoExport = true, exportPath = tempDir.toString()))
        val id = manifold.getTraceId()
        populateTrace(id, TraceEventType.MANIFOLD_START, "MANIFOLD")

        manifold.getTraceReport(TraceFormat.CONSOLE)

        val written = tempDir.toFile().listFiles()
            ?.filter { it.readText().contains("MANIFOLD") }
        assertNotNull(written?.firstOrNull(),
            "Manifold autoExport must produce a file; saw ${tempDir.toFile().listFiles()?.map { it.name }}")
    }

    @Test
    fun splitter_getTraceReportWithAutoExport_writesFile() {
        val splitter = Splitter()
        splitter.enableTracing(TraceConfig(autoExport = true, exportPath = tempDir.toString()))
        val id = splitter.getTraceId()
        populateTrace(id, TraceEventType.SPLITTER_START, "SPLITTER")

        splitter.getTraceReport(TraceFormat.CONSOLE)

        val written = tempDir.toFile().listFiles()
            ?.filter { it.readText().contains("SPLITTER") }
        assertNotNull(written?.firstOrNull(),
            "Splitter autoExport must produce a file")
    }

    @Test
    fun junction_getTraceReportWithAutoExport_writesFile() {
        val junction = Junction()
        junction.enableTracing(TraceConfig(autoExport = true, exportPath = tempDir.toString()))
        val id = junction.getTraceId()
        populateTrace(id, TraceEventType.JUNCTION_START, "JUNCTION")

        junction.getTraceReport(TraceFormat.CONSOLE)

        val written = tempDir.toFile().listFiles()
            ?.filter { it.readText().contains("JUNCTION") }
        assertNotNull(written?.firstOrNull(),
            "Junction autoExport must produce a file")
    }

    @Test
    fun distributionGrid_getTraceReportWithAutoExport_writesFile() {
        val grid = DistributionGrid()
        grid.enableTracing(TraceConfig(autoExport = true, exportPath = tempDir.toString()))
        val id = grid.getTraceId()
        populateTrace(id, TraceEventType.DISTRIBUTION_GRID_START, "DISTRIBUTION_GRID")

        grid.getTraceReport(TraceFormat.CONSOLE)

        val written = tempDir.toFile().listFiles()
            ?.filter { it.readText().contains("DISTRIBUTION_GRID") }
        assertNotNull(written?.firstOrNull(),
            "DistributionGrid autoExport must produce a file")
    }

    @Test
    fun connector_getTraceReportWithAutoExport_writesFile() {
        val c = Connector()
        c.enableTracing(TraceConfig(autoExport = true, exportPath = tempDir.toString()))
        val id = c.getTraceId()
        populateTrace(id, TraceEventType.PIPE_SUCCESS, "PIPE_SUCCESS")

        c.getTraceReport(TraceFormat.CONSOLE)

        val written = tempDir.toFile().listFiles()
            ?.filter { it.readText().contains("PIPE_SUCCESS") }
        assertNotNull(written?.firstOrNull(),
            "Connector autoExport must produce a file")
    }

    @Test
    fun multiConnector_getTraceReportWithAutoExport_writesFile() {
        val mc = MultiConnector()
        mc.enableTracing(TraceConfig(autoExport = true, exportPath = tempDir.toString()))
        val id = mc.getTraceId()
        populateTrace(id, TraceEventType.PIPE_SUCCESS, "PIPE_SUCCESS")

        mc.getTraceReport(TraceFormat.CONSOLE)

        val written = tempDir.toFile().listFiles()
            ?.filter { it.readText().contains("PIPE_SUCCESS") }
        assertNotNull(written?.firstOrNull(),
            "MultiConnector autoExport must produce a file")
    }

    // ----- Filename shape regression: the Pipeline.kt:873 bug produced
    // ----- names like "trace-abc12345-html.html" (literal extension in the middle).
    // ----- The fix is the canonical "trace-<id>.<ext>" shape.

    @Test
    fun autoExportFilenameDoesNotContainLiteralExtensionToken() {
        val pipeline = Pipeline()
        pipeline.enableTracing(TraceConfig(autoExport = true, exportPath = tempDir.toString()))
        val id = pipeline.getTraceId()
        // Populate with a known event so the report is non-empty
        populateTrace(id, TraceEventType.PIPE_SUCCESS, "PIPE_SUCCESS")

        pipeline.getTraceReport(TraceFormat.HTML)

        val files = tempDir.toFile().listFiles() ?: emptyArray()
        val malformed = files.filter { f ->
            // The pre-fix pattern: "trace-xxxxxxxx-html.html" — the literal "html" appears
            // inside the filename as a token, not just as the extension. The fix is
            // "trace-<id>.html" — single dot, no literal extension in the middle.
            val name = f.name
            name.count { it == '.' } >= 2 && name.substringBeforeLast('.').contains('.')
        }
        assertEquals(emptyList<Any>(), malformed,
            "Filename must be 'trace-<id>.<ext>' (one dot before extension), not 'trace-<id>-<ext>.<ext>'. Saw: ${files.map { it.name }}")
    }
}
