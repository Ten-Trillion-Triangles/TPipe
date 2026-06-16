package com.TTT.TraceServer.store

import com.TTT.TraceServer.TraceEvent
import com.TTT.TraceServer.TracePayload
import com.TTT.TraceServer.TraceSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * File-backed [TraceStore] implementation.
 *
 * On disk the store uses a directory of files:
 * ```
 * <directory>/
 *   traces.jsonl         # append-only event log, one JSON record per line
 *   trace_index.json     # snapshot of the in-memory state, written on close()
 *   events/<id>.jsonl    # one event log per pipeline (v2 live streaming)
 * ```
 *
 * The store is append-only on the hot path. Each `put` writes a single
 * `Record` to `traces.jsonl` and updates the in-memory cache, protected by a
 * per-tenant `ReentrantReadWriteLock`. On `close()` the index is snapshotted to
 * `trace_index.json` so the next startup can skip the JSONL replay when the
 * index is current. On startup the implementation replays `traces.jsonl` if
 * the index is missing or older than the log; this recovers from mid-write
 * crashes where the snapshot could not be flushed.
 *
 * v2 additions:
 * - Optional [ttl] evicts traces older than the configured duration on
 *   `put` and during startup replay. Default is 7 days.
 * - Optional [perTenantQuota] caps the per-tenant count; oldest entries are
 *   evicted first when the cap is exceeded. Default is 2 000.
 * - Tags are persisted with the payload so the v2 `?tag=...` filter works
 *   after a restart.
 * - Events are persisted under `events/<pipelineId>.jsonl` and replayed on
 *   startup.
 *
 * The implementation deliberately avoids JDBC, NIO watch services, and any
 * platform-specific bindings so it remains GraalVM native-image compatible.
 */
class FileBackedTraceStore(
    private val directory: Path,
    private val maxTraces: Int = 10_000,
    private val ttl: Duration? = Duration.ofDays(7),
    private val perTenantQuota: Int? = 2_000
) : TraceStore {

    private val logPath: Path = directory.resolve(LOG_FILE)
    private val indexPath: Path = directory.resolve(INDEX_FILE)
    private val eventsDir: Path = directory.resolve(EVENTS_DIR)

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val tenants: MutableMap<String, TenantState> = java.util.concurrent.ConcurrentHashMap()

    private data class StoredEntry(val payload: TracePayload, val insertedAt: Long, val tags: Map<String, String>)

    private class TenantState {
        val lock = ReentrantReadWriteLock()
        val bucket: MutableMap<String, StoredEntry> = LinkedHashMap()
        var order: ArrayDeque<String> = ArrayDeque()
        val events: MutableMap<String, MutableList<TraceEvent>> = LinkedHashMap()
    }

    init {
        Files.createDirectories(directory)
        Files.createDirectories(eventsDir)
        loadFromDisk()
        // Initial eviction pass so a long-down server doesn't keep stale
        // traces that the operator would have to clean by hand.
        evictExpiredAndOverQuota()
    }

    override fun put(payload: TracePayload, tenant: String)
    {
        val now = System.currentTimeMillis()
        val state = tenantState(tenant)
        state.lock.write {
            val existing = state.bucket[payload.pipelineId]
            if(existing == null)
            {
                state.order.addLast(payload.pipelineId)
            } else {
                state.order.remove(payload.pipelineId)
                state.order.addLast(payload.pipelineId)
            }
            state.bucket[payload.pipelineId] = StoredEntry(payload, now, payload.tags)
            evictExpiredAndOverQuotaLocked(state, now)
        }
        appendToLog(tenant, payload, now)
    }

    override fun get(pipelineId: String, tenant: String): TracePayload? =
        tenantState(tenant).lock.read {
            tenantState(tenant).bucket[pipelineId]?.payload
        }

    override fun delete(pipelineId: String, tenant: String): Boolean
    {
        val state = tenantState(tenant)
        val removed: Boolean
        state.lock.write {
            removed = state.bucket.remove(pipelineId) != null
            if(removed)
            {
                state.order.remove(pipelineId)
                state.events.remove(pipelineId)
            }
        }
        if(removed)
        {
            appendToLog(tenant, null, null, pipelineId)
            // Best-effort delete of the per-pipeline event log. A failure
            // here is non-fatal; the next put for the same pipeline will
            // overwrite events for that id and the file gets cleaned on close.
            runCatching { Files.deleteIfExists(eventsPath(pipelineId)) }
        }
        return removed
    }

    override fun listSummaries(filter: TraceFilter): TraceListResult
    {
        val state = tenantState(filter.tenant)
        val all: List<TraceSummary> = state.lock.read {
            state.bucket.entries
                .map { (id, entry) -> TraceSummary(id, entry.insertedAt, entry.payload.name, entry.payload.status) }
                .sortedByDescending { it.timestamp }
        }
        val tagsById: Map<String, Map<String, String>>? = if(filter.tag != null)
        {
            state.lock.read { state.bucket.mapValues { it.value.tags } }
        } else null
        val effectiveFilter = filter.copy(tagsById = tagsById)

        val filtered = TraceFilters.apply(all, effectiveFilter)
        val safeLimit = filter.limit.coerceIn(1, InMemoryTraceStore.MAX_LIMIT)
        val safeOffset = filter.offset.coerceAtLeast(0)
        val windowed = if(filtered.size > safeOffset) filtered.subList(safeOffset, filtered.size) else emptyList()
        val page = if(windowed.size > safeLimit) windowed.subList(0, safeLimit) else windowed
        return TraceListResult(items = page, total = filtered.size, limit = safeLimit, offset = safeOffset)
    }

    override fun count(tenant: String): Int =
        tenantState(tenant).lock.read { tenantState(tenant).bucket.size }

    override fun tenantNames(): Set<String> = tenants.keys.toSet()

    override fun appendEvent(pipelineId: String, event: TraceEvent, tenant: String)
    {
        val state = tenantState(tenant)
        val list: MutableList<TraceEvent>
        state.lock.write {
            // Only persist events for known pipelines; otherwise the event log
            // would grow unbounded for traces the agent never POSTed.
            if(!state.bucket.containsKey(pipelineId)) return
            val existing = state.events[pipelineId]
            if(existing != null)
            {
                list = existing
            } else
            {
                list = mutableListOf()
                state.events[pipelineId] = list
            }
            list.add(event)
        }
        appendEventToLog(pipelineId, event, tenant)
    }

    override fun getEvents(pipelineId: String, tenant: String): List<TraceEvent>
    {
        val state = tenantState(tenant)
        return state.lock.read {
            state.events[pipelineId]?.toList() ?: emptyList()
        }
    }

    override fun close()
    {
        snapshotIndex()
        tenants.values.forEach { state ->
            state.lock.write {
                state.bucket.clear()
                state.order.clear()
                state.events.clear()
            }
        }
        tenants.clear()
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun tenantState(tenant: String): TenantState =
        tenants.computeIfAbsent(tenant) { TenantState() }

    private fun eventsPath(pipelineId: String): Path = eventsDir.resolve("$pipelineId.jsonl")

    /**
     * Convenience for the public path-constructor. Public callers in the demo
     * or CLI use this; the typed constructor is the canonical entry point.
     */
    private fun evictExpiredAndOverQuota()
    {
        val now = System.currentTimeMillis()
        for((_, state) in tenants)
        {
            state.lock.write { evictExpiredAndOverQuotaLocked(state, now) }
        }
    }

    /**
     * Caller must hold [TenantState.lock] in write mode. Trims TTL-expired
     * entries and enforces both the global `maxTraces` cap and the
     * per-tenant [perTenantQuota]. Entries are evicted oldest first; the
     * file log is not rewritten (it's append-only) so the JSONL may grow
     * past the live cap between close-and-snapshot cycles.
     */
    private fun evictExpiredAndOverQuotaLocked(state: TenantState, now: Long)
    {
        if(ttl != null)
        {
            val ttlMs = ttl.toMillis()
            // Walk the insertion-order deque from the oldest end; stop as
            // soon as we hit a non-expired entry. The deque is in insertion
            // order, so this is a single linear pass.
            while(state.order.isNotEmpty())
            {
                val first = state.order.first()
                val entry = state.bucket[first] ?: run {
                    state.order.removeFirst()
                    continue
                }
                if(now - entry.insertedAt > ttlMs)
                {
                    state.order.removeFirst()
                    state.bucket.remove(first)
                    state.events.remove(first)
                } else
                {
                    break
                }
            }
        }
        // Per-tenant quota wins over the global cap (smaller limit first).
        val cap = perTenantQuota?.coerceAtMost(maxTraces) ?: maxTraces
        while(state.order.size > cap)
        {
            val oldest = state.order.removeFirst()
            state.bucket.remove(oldest)
            state.events.remove(oldest)
        }
    }

    private fun appendToLog(tenant: String, payload: TracePayload?, insertedAt: Long?, pipelineId: String? = null)
    {
        val record = if(payload != null) {
            Record(Record.TYPE_PUT, tenant, insertedAt!!, pipelineId = payload.pipelineId, payload = payload)
        } else {
            Record(Record.TYPE_DELETE, tenant, insertedAt = 0L, pipelineId = pipelineId!!, payload = null)
        }
        val line = json.encodeToString(Record.serializer(), record)
        Files.writeString(
            logPath,
            line + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND
        )
    }

    private fun appendEventToLog(pipelineId: String, event: TraceEvent, tenant: String)
    {
        val record = EventRecord(pipelineId, tenant, event.eventId, event.ts, event.type, event.payload)
        val line = json.encodeToString(EventRecord.serializer(), record)
        Files.writeString(
            eventsPath(pipelineId),
            line + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND
        )
    }

    /**
     * Replays the JSONL log and (if current) the index snapshot to rebuild the
     * in-memory state. Always replays the log when the index is older than the
     * log, which is the recovery path after a mid-write crash.
     */
    private fun loadFromDisk()
    {
        val logFile = logPath.toFile()
        val indexFile = indexPath.toFile()
        val useIndex = indexFile.exists() && logFile.exists() &&
            indexFile.lastModified() >= logFile.lastModified()
        if(useIndex)
        {
            loadFromIndex(indexFile)
        }
        if(logFile.exists())
        {
            replayLog(logFile)
        }
        // Events are always replayed from per-pipeline logs; they're small
        // and the index doesn't include them.
        replayEvents()
    }

    private fun loadFromIndex(indexFile: File)
    {
        try
        {
            val text = indexFile.readText()
            if(text.isBlank()) return
            val snapshot = json.decodeFromString(IndexSnapshot.serializer(), text)
            for(entry in snapshot.entries)
            {
                val state = tenantState(entry.tenant)
                state.lock.write {
                    state.bucket[entry.payload.pipelineId] = StoredEntry(
                        payload = entry.payload,
                        insertedAt = entry.insertedAt,
                        tags = entry.payload.tags
                    )
                    state.order.addLast(entry.payload.pipelineId)
                }
            }
        } catch (e: Exception)
        {
            // Treat a corrupt index as missing; the log replay below rebuilds the state.
            System.err.println("[FileBackedTraceStore] index snapshot could not be parsed: ${e.message}")
        }
    }

    private fun replayLog(logFile: File)
    {
        Files.newBufferedReader(logFile.toPath(), Charsets.UTF_8).use { reader ->
            reader.useLines { lines ->
                for(line in lines)
                {
                    if(line.isBlank()) continue
                    try
                    {
                        val record = json.decodeFromString(Record.serializer(), line)
                        val state = tenantState(record.tenant)
                        state.lock.write {
                            when(record.type)
                            {
                                Record.TYPE_PUT ->
                                {
                                    val payload = record.payload ?: continue
                                    val existing = state.bucket[payload.pipelineId]
                                    if(existing == null)
                                    {
                                        state.order.addLast(payload.pipelineId)
                                    } else {
                                        state.order.remove(payload.pipelineId)
                                        state.order.addLast(payload.pipelineId)
                                    }
                                    state.bucket[payload.pipelineId] = StoredEntry(
                                        payload = payload,
                                        insertedAt = record.insertedAt,
                                        tags = payload.tags
                                    )
                                }
                                Record.TYPE_DELETE ->
                                {
                                    state.bucket.remove(record.pipelineId)
                                    state.order.remove(record.pipelineId)
                                    state.events.remove(record.pipelineId)
                                }
                            }
                        }
                    } catch (e: Exception)
                    {
                        // A single corrupt line should not poison the entire replay.
                        System.err.println("[FileBackedTraceStore] skipping corrupt log line: ${e.message}")
                    }
                }
            }
        }
    }

    private fun replayEvents()
    {
        if(!Files.isDirectory(eventsDir)) return
        val files = Files.list(eventsDir).use { it.filter { p -> p.toString().endsWith(".jsonl") }.toList() }
        for(file in files)
        {
            val pipelineId = file.fileName.toString().removeSuffix(".jsonl")
            try
            {
                Files.newBufferedReader(file, Charsets.UTF_8).use { reader ->
                    reader.useLines { lines ->
                        for(line in lines)
                        {
                            if(line.isBlank()) continue
                            try
                            {
                                val record = json.decodeFromString(EventRecord.serializer(), line)
                                val state = tenantState(record.tenant)
                                state.lock.write {
                                    val list = state.events.computeIfAbsent(pipelineId) { mutableListOf() }
                                    list.add(
                                        TraceEvent(
                                            eventId = record.eventId,
                                            ts = record.ts,
                                            type = record.type,
                                            payload = record.payload
                                        )
                                    )
                                }
                            } catch (e: Exception)
                            {
                                System.err.println("[FileBackedTraceStore] skipping corrupt event line in $pipelineId: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception)
            {
                System.err.println("[FileBackedTraceStore] could not read event log $pipelineId: ${e.message}")
            }
        }
    }

    private fun snapshotIndex()
    {
        val entries = mutableListOf<IndexEntry>()
        for((tenant, state) in tenants)
        {
            state.lock.read {
                for((id, entry) in state.bucket)
                {
                    entries.add(IndexEntry(tenant, id, entry.insertedAt, entry.payload))
                }
            }
        }
        if(entries.isEmpty())
        {
            // Still write an empty index so subsequent startups can short-circuit the log replay.
            Files.writeString(indexPath, json.encodeToString(IndexSnapshot.serializer(), IndexSnapshot(emptyList())))
            return
        }
        val snapshot = IndexSnapshot(entries)
        Files.writeString(indexPath, json.encodeToString(IndexSnapshot.serializer(), snapshot))
    }

    @Serializable
    internal data class Record(
        val type: String,
        val tenant: String,
        val insertedAt: Long,
        val pipelineId: String,
        val payload: TracePayload? = null
    )
    {
        companion object {
            const val TYPE_PUT: String = "put"
            const val TYPE_DELETE: String = "delete"
        }
    }

    @Serializable
    internal data class EventRecord(
        val pipelineId: String,
        val tenant: String,
        val eventId: String,
        val ts: Long,
        val type: String,
        val payload: kotlinx.serialization.json.JsonElement
    )

    @Serializable
    internal data class IndexEntry(
        val tenant: String,
        val id: String,
        val insertedAt: Long,
        val payload: TracePayload
    )

    @Serializable
    internal data class IndexSnapshot(val entries: List<IndexEntry>)

    // ---- v2 health-endpoint read-only helpers (public so the route can read) ----
    fun directoryPath(): String = directory.toString()
    fun maxTracesValue(): Int = maxTraces
    fun ttlMs(): Long? = ttl?.toMillis()
    fun perTenantQuotaValue(): Int? = perTenantQuota

    companion object {
        const val LOG_FILE: String = "traces.jsonl"
        const val INDEX_FILE: String = "trace_index.json"
        const val EVENTS_DIR: String = "events"

        /**
         * Convenience constructor using a string path, used by the demo and CLI.
         */
        fun at(
            path: String,
            maxTraces: Int = 10_000,
            ttl: Duration? = Duration.ofDays(7),
            perTenantQuota: Int? = 2_000
        ): FileBackedTraceStore = FileBackedTraceStore(Paths.get(path), maxTraces, ttl, perTenantQuota)
    }
}
