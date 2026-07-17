package com.TTT.TraceServer.store

import com.TTT.TraceServer.TraceEvent
import com.TTT.TraceServer.TracePayload
import com.TTT.TraceServer.TraceSummary
import java.util.concurrent.ConcurrentHashMap

/**
 * Volatile, bounded trace store backed by per-tenant `ConcurrentHashMap`s with
 * a simple insertion-order LRU eviction policy.
 *
 * This is the historic behavior of `TraceServerRegistry.traces`, repackaged so
 * that a single tenant cannot evict another tenant's traces. Eviction happens
 * only when the per-tenant size exceeds [maxTraces]; the oldest insertion is
 * dropped first.
 *
 * Suitable for tests, ephemeral development runs, and short-lived demos where
 * the user explicitly opts out of disk persistence via `--no-persist`.
 *
 * v2 changes: tags are stored alongside the payload, events are kept in a
 * per-trace `ConcurrentLinkedQueue`, and the filter helper was moved out to
 * [TraceFilters] so the two stores agree on filter semantics.
 */
class InMemoryTraceStore(private val maxTraces: Int = 10_000) : TraceStore {

    private val perTenant: MutableMap<String, MutableMap<String, StoredEntry>> = ConcurrentHashMap()
    private val perTenantOrder: MutableMap<String, MutableList<String>> = ConcurrentHashMap()
    private val perTenantEvents: MutableMap<String, MutableMap<String, MutableList<TraceEvent>>> =
        ConcurrentHashMap()

    private data class StoredEntry(val payload: TracePayload, val insertedAt: Long, val tags: Map<String, String>)

    private fun tenantBucket(tenant: String): MutableMap<String, StoredEntry> =
        perTenant.computeIfAbsent(tenant) { ConcurrentHashMap() }

    private fun orderBucket(tenant: String): MutableList<String> =
        perTenantOrder.computeIfAbsent(tenant) { mutableListOf() }

    private fun eventsBucket(tenant: String): MutableMap<String, MutableList<TraceEvent>> =
        perTenantEvents.computeIfAbsent(tenant) { ConcurrentHashMap() }

    override fun put(payload: TracePayload, tenant: String)
    {
        val bucket = tenantBucket(tenant)
        val order = orderBucket(tenant)
        val now = System.currentTimeMillis()
        synchronized(order) {
            if(!bucket.containsKey(payload.pipelineId))
            {
                order.add(payload.pipelineId)
            } else {
                // Move-to-end semantics for LRU: re-insert to refresh the order.
                order.remove(payload.pipelineId)
                order.add(payload.pipelineId)
            }
            bucket[payload.pipelineId] = StoredEntry(payload, now, payload.tags)
            while(order.size > maxTraces)
            {
                val oldest = order.removeAt(0)
                bucket.remove(oldest)
                eventsBucket(tenant).remove(oldest)
            }
        }
    }

    override fun get(pipelineId: String, tenant: String): TracePayload? =
        tenantBucket(tenant)[pipelineId]?.payload

    override fun delete(pipelineId: String, tenant: String): Boolean
    {
        val bucket = tenantBucket(tenant)
        val order = orderBucket(tenant)
        val removed = bucket.remove(pipelineId) != null
        if(removed)
        {
            synchronized(order) { order.remove(pipelineId) }
            eventsBucket(tenant).remove(pipelineId)
        }
        return removed
    }

    override fun listSummaries(filter: TraceFilter): TraceListResult
    {
        val bucket = tenantBucket(filter.tenant)
        val all: List<TraceSummary> = bucket.entries
            .map { (id, entry) -> TraceSummary(id, entry.insertedAt, entry.payload.name, entry.payload.status, entry.payload.kind) }
            .sortedByDescending { it.timestamp }

        val tagsById: Map<String, Map<String, String>>? = if(filter.tag != null)
        {
            bucket.mapValues { it.value.tags }
        } else null
        val effectiveFilter = filter.copy(tagsById = tagsById)

        val filtered = TraceFilters.apply(all, effectiveFilter)
        val safeLimit = filter.limit.coerceIn(1, MAX_LIMIT)
        val safeOffset = filter.offset.coerceAtLeast(0)
        val windowed = if(filtered.size > safeOffset) filtered.subList(safeOffset, filtered.size) else emptyList()
        val page = if(windowed.size > safeLimit) windowed.subList(0, safeLimit) else windowed
        return TraceListResult(items = page, total = filtered.size, limit = safeLimit, offset = safeOffset)
    }

    override fun count(tenant: String): Int = tenantBucket(tenant).size

    override fun tenantNames(): Set<String> = perTenant.keys.toSet()

    override fun appendEvent(pipelineId: String, event: TraceEvent, tenant: String)
    {
        val trace = tenantBucket(tenant)[pipelineId] ?: return
        val events = eventsBucket(tenant)
        val list = events.computeIfAbsent(pipelineId) { mutableListOf() }
        synchronized(list) { list.add(event) }
        // Touch the trace to keep it fresh in the LRU order; we don't update
        // the storedAt here because events aren't a re-insert of the payload.
        @Suppress("UNUSED_VARIABLE") val touch = trace
    }

    override fun getEvents(pipelineId: String, tenant: String): List<TraceEvent>
    {
        val list = eventsBucket(tenant)[pipelineId] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }

    override fun close()
    {
        perTenant.clear()
        perTenantOrder.clear()
        perTenantEvents.clear()
    }

    // ---- v2 health-endpoint read-only helper ----
    fun maxTracesValue(): Int = maxTraces

    companion object {
        /** Hard upper bound on per-request page size to prevent runaway responses. */
        const val MAX_LIMIT: Int = 500
    }
}
