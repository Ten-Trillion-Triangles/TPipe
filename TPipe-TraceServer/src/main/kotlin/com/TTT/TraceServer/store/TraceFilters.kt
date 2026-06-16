package com.TTT.TraceServer.store

import com.TTT.TraceServer.TraceSummary

/**
 * Shared filter helpers for [TraceStore] implementations. Both [InMemoryTraceStore]
 * and [FileBackedTraceStore] apply the same AND-combinator semantics so the
 * dashboard sees a consistent result regardless of which backend is in use.
 *
 * The helper is `internal` because callers outside the store package should
 * reach the store via the [TraceStore.listSummaries] entry point; this file
 * exists purely to remove the duplication that was present in v1.
 */
internal object TraceFilters {

    /**
     * Applies the filter dimensions from [filter] to [items] in order: status,
     * then free-text query, then `tag`, then `since`. The `limit` and `offset`
     * fields are the caller's responsibility (they need access to the
     * post-filter total).
     */
    fun apply(items: List<TraceSummary>, filter: TraceFilter): List<TraceSummary>
    {
        var working = items
        val status = filter.status
        if(!status.isNullOrBlank())
        {
            working = working.filter { it.status.equals(status, ignoreCase = true) }
        }
        val query = filter.query
        if(!query.isNullOrBlank())
        {
            val needle = query.trim().lowercase()
            working = working.filter {
                it.id.lowercase().contains(needle) ||
                    it.name.lowercase().contains(needle) ||
                    it.status.lowercase().contains(needle)
            }
        }
        val tag = filter.tag
        if(!tag.isNullOrBlank())
        {
            val (key, value) = parseTagFilter(tag)
            working = working.filter { summary ->
                val entryTags = (filter.tagsById ?: emptyMap())[summary.id].orEmpty()
                if(value == null)
                {
                    entryTags.containsKey(key)
                } else
                {
                    entryTags[key] == value
                }
            }
        }
        val since = filter.since
        if(since != null)
        {
            working = working.filter { it.timestamp >= since }
        }
        return working
    }

    /**
     * Parses a `key:value` (or just `key`) tag filter expression. The colon
     * separator is the only one supported in v2 to keep the URL surface
     * minimal; callers needing different separators should add their own
     * preprocessor before calling [TraceStore.listSummaries].
     */
    private fun parseTagFilter(raw: String): Pair<String, String?>
    {
        val colon = raw.indexOf(':')
        return if(colon < 0) raw to null else raw.substring(0, colon) to raw.substring(colon + 1)
    }
}
