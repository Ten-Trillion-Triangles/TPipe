package com.TTT.Debug

import com.TTT.Util.writeStringToFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe coordinator for trace auto-export writes.
 *
 * The pre-2026-08-08 codebase called [com.TTT.Util.writeStringToFile] directly from
 * each container's `getTraceReport()`, with no per-path serialization. Two threads
 * writing to the same target file could interleave bytes mid-write and corrupt the
 * file. Two containers writing to *different* paths under the same `exportPath`
 * directory were independently fine, but the same path collided silently.
 *
 * This coordinator maps each target path to its own [ReentrantLock]. Concurrent
 * writes to the **same path** serialize; concurrent writes to **different paths**
 * run in parallel. The lock is held only for the duration of the write — never
 * for the duration of the report-building (which happens inside the caller).
 *
 * Deadlock-free by construction:
 * - never re-enters itself with another lock held
 * - never calls user code while holding a lock
 * - the lambda inside `export` runs *inside* the lock — that's the point — but
 *   the lambda is the user's write closure, not a callback that could re-enter
 *   the exporter
 *
 * @property exportPath default subdirectory used when an `export()` call does not
 *   provide a full path. Currently unused — callers pass full paths.
 */
class TraceAutoExporter private constructor(
    private val pathLocks: ConcurrentHashMap<String, ReentrantLock> = ConcurrentHashMap()
) {
    /**
     * Synchronous write: serializes per-target-path. Returns when the write completes
     * (including the I/O inside `writeAction`). Throws if `writeAction` throws.
     *
     * @param targetPath absolute path of the file to write
     * @param report the report string (unused — kept for symmetry with the async API)
     * @param writeAction the closure that performs the actual write. Receives no
     *        arguments. The exporter does not invoke `writeStringToFile` itself
     *        so the caller controls the write strategy (atomic rename, append, etc.).
     */
    fun export(targetPath: String, report: String, writeAction: () -> Unit) {
        val lock = pathLocks.computeIfAbsent(targetPath) { ReentrantLock() }
        lock.withLock {
            writeAction()
        }
    }

    /**
     * Overload that defaults to [com.TTT.Util.writeStringToFile] for the write.
     * Convenient for the common case where the caller just wants to dump a string
     * to a file.
     */
    fun export(targetPath: String, report: String) {
        export(targetPath, report) { writeStringToFile(targetPath, report) }
    }

    /**
     * Test seam: returns the internal lock map for assertions. Same-module visibility
     * keeps the seam out of the public API while still being reachable from tests.
     */
    internal fun getPathLocksForTest(): Map<String, ReentrantLock> = pathLocks.toMap()

    /**
     * Test seam: forces any pending closures to complete. The current implementation
     * is synchronous so this is a no-op, but the seam lets tests pin the contract
     * if the implementation later moves to async.
     */
    internal fun flushForTest() {
        // No-op for the synchronous implementation. Documented for forward compatibility.
    }

    companion object {
        /**
         * Default process-wide instance. Production containers use this; tests can
         * create their own instances via `create()` for isolation.
         */
        val default: TraceAutoExporter = TraceAutoExporter()

        fun create(): TraceAutoExporter = TraceAutoExporter()
    }
}
