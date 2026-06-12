package com.TTT.TraceServer.store

import com.TTT.TraceServer.TraceEvent
import com.TTT.TraceServer.TracePayload
import com.TTT.TraceServer.TraceSummary

/**
 * Default tenant used by every trace submitted without an explicit `X-Tenant`
 * header or `?tenant=` query parameter. Bumping this constant is a breaking
 * change for any persisted history; the file-backed store keeps tenant
 * partitions under the original key.
 */
const val DEFAULT_TENANT: String = "default"

/**
 * Filter used by [TraceStore.listSummaries] to narrow the trace list returned
 * to the HTTP layer. All fields are optional and combine as an `AND`. Pagination
 * is honored only when at least one filter is present so the default call
 * still returns the full visible list.
 *
 * @property tenant the tenant partition the caller has resolved. Defaults to [DEFAULT_TENANT].
 * @property status if non-null the summaries are restricted to this status (`SUCCESS`, `FAILURE`, `PENDING`, ...).
 * @property query if non-null a case-insensitive substring match against `id`/`name`/`status`.
 * @property tag if non-null the summaries are restricted to traces that carry
 *  the supplied tag. The wire syntax is `key` (existence) or `key:value`
 *  (equality). v2 dashboards send this from the new tag filter UI.
 * @property since if non-null only summaries with `timestamp >= since` are returned.
 * @property limit maximum number of summaries to return. Clamped by the implementation to a sensible upper bound.
 * @property offset number of summaries to skip (for pagination).
 * @property tagsById internal-only tag map. The HTTP layer builds this from
 *  the in-memory store and passes it through to [TraceFilters] so the
 *  filter helper doesn't have to re-fetch every trace's tags. Store
 *  implementations that already have tags in memory set this to `null`
 *  and look up tags directly.
 */
data class TraceFilter(
    val tenant: String = DEFAULT_TENANT,
    val status: String? = null,
    val query: String? = null,
    val tag: String? = null,
    val since: Long? = null,
    val limit: Int = 100,
    val offset: Int = 0,
    val tagsById: Map<String, Map<String, String>>? = null
)

/**
 * Result envelope returned from the HTTP layer. The `total` count is the
 * unfiltered size for the resolved tenant so the dashboard can render a
 * paginator without an extra `count()` call. `limit`/`offset` echo the values
 * the implementation actually honored.
 */
data class TraceListResult(
    val items: List<TraceSummary>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

/**
 * Pluggable persistence layer for trace payloads and events.
 *
 * Implementations must be safe to call from concurrent HTTP request coroutines
 * and from the WebSocket broadcast coroutine. The default [FileBackedTraceStore]
 * uses per-tenant read/write locks; in-memory implementations may use plain
 * `ConcurrentHashMap` semantics.
 *
 * All methods are tenant-scoped: payloads inserted under one tenant are
 * invisible to a request that resolves a different tenant. Existing
 * single-tenant integrations continue to work via [DEFAULT_TENANT].
 *
 * v2 surface: [appendEvent] and [getEvents] back the live event-streaming
 * endpoint (`POST /api/traces/{id}/events`). Implementations that don't
 * support event streaming may return an empty list from [getEvents] and
 * be a no-op from [appendEvent] (the route layer will then broadcast the
 * event in-memory only).
 */
interface TraceStore {
    /**
     * Inserts or replaces the payload under the resolved tenant. The
     * implementation captures the insertion time and uses it as the summary's
     * `timestamp` so the dashboard list is sorted by real insertion order.
     */
    fun put(payload: TracePayload, tenant: String = DEFAULT_TENANT)

    /**
     * Returns the payload for `(tenant, pipelineId)` or `null` if absent.
     */
    fun get(pipelineId: String, tenant: String = DEFAULT_TENANT): TracePayload?

    /**
     * Removes the payload for `(tenant, pipelineId)`. Returns `true` if a
     * payload was removed and `false` if no such payload existed.
     */
    fun delete(pipelineId: String, tenant: String = DEFAULT_TENANT): Boolean

    /**
     * Returns a paginated list of summaries for the resolved tenant, sorted by
     * `timestamp` descending (newest first). The envelope exposes the unfiltered
     * tenant total so the caller can compute pagination bounds.
     */
    fun listSummaries(filter: TraceFilter): TraceListResult

    /**
     * Returns the total number of payloads stored under the resolved tenant,
     * ignoring any other filter dimensions.
     */
    fun count(tenant: String = DEFAULT_TENANT): Int

    /**
     * Returns the set of tenant keys with at least one stored payload. Used
     * by the v2 `/api/health` envelope so operators can see the live tenant
     * count. The default implementation scans every partition; backends
     * with a cheaper access path should override.
     */
    fun tenantNames(): Set<String> = emptySet()

    /**
     * Appends a single event to the event log for `(tenant, pipelineId)`.
     * The event is broadcast to subscribed WebSocket clients regardless of
     * the return value, so implementations that don't support event
     * persistence may simply return without persisting.
     */
    fun appendEvent(pipelineId: String, event: TraceEvent, tenant: String = DEFAULT_TENANT)

    /**
     * Returns the persisted events for `(tenant, pipelineId)` in insertion
     * order, or an empty list if the trace has no events. Used by the
     * `GET /api/traces/{id}/events` endpoint.
     */
    fun getEvents(pipelineId: String, tenant: String = DEFAULT_TENANT): List<TraceEvent>

    /**
     * Releases any resources owned by the store. After `close` the instance
     * must not be used again. Idempotent.
     */
    fun close()
}
