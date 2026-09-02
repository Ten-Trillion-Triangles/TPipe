package com.TTT.Context.Persistence

import com.TTT.Context.LoreBookQueryResult

/** Optional query capability for a persistence backend. */
interface ContextQueryBackend {
    /** Query lorebook entries associated with [key].
     *
     * @param key Context key.
     * @param query Search query.
     * @param minWeight Minimum entry weight.
     * @param requiredKeys Keys that must be present.
     * @param aliasKeys Alias keys accepted by the query.
     * @param extractRegex Optional extraction expression.
     * @return Matching lorebook entries.
     */
    suspend fun queryLorebook(
        key: String,
        query: String = "",
        minWeight: Int = Int.MIN_VALUE,
        requiredKeys: List<String> = emptyList(),
        aliasKeys: List<String> = emptyList(),
        extractRegex: String = ""
    ): List<LoreBookQueryResult>

    /** Simulate lorebook triggers against [text].
     *
     * @param key Context key.
     * @param text Text to evaluate.
     * @return Triggered lorebook keys.
     */
    suspend fun simulateLorebookTrigger(key: String, text: String): List<String>
}
