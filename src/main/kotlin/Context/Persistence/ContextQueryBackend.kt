package com.TTT.Context.Persistence

import com.TTT.Context.LoreBookQueryResult

/** Optional query capability for a persistence backend. */
interface ContextQueryBackend {
    /** Query lorebook entries associated with [key]. */
    suspend fun queryLorebook(
        key: String,
        query: String = "",
        minWeight: Int = Int.MIN_VALUE,
        requiredKeys: List<String> = emptyList(),
        aliasKeys: List<String> = emptyList(),
        extractRegex: String = ""
    ): List<LoreBookQueryResult>

    /** Simulate lorebook triggers against [text]. */
    suspend fun simulateLorebookTrigger(key: String, text: String): List<String>
}
