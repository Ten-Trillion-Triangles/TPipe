package com.TTT.Context

import com.TTT.PipeContextProtocol.ContextOptionParameter

import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.Config.TPipeConfig
import com.TTT.PipeContextProtocol.TPipeContextOptions
import com.TTT.PipeContextProtocol.ParamType
import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.Context.Persistence.ContextPersistenceRegistry
import com.TTT.Context.Persistence.ContextQueryBackend
import com.TTT.Context.Persistence.TPipeRemotePersistenceBackend
import kotlinx.serialization.Serializable

/**
 * Collection of PCP-callable tools for memory and lorebook introspection.
 * These tools respect the MemoryIntrospection security leash and ContextLock system.
 */
object MemoryIntrospectionTools
{
    /**
     * Lists all allowed page keys in the ContextBank.
     * Respects MemoryIntrospection allowedPageKeys and ContextLock (hides locked pages).
     */
    suspend fun listPageKeys(): List<String>
    {
        val allKeys = ContextBank.getPageKeysSuspend()
        return allKeys.filter { key ->
            MemoryIntrospection.canRead(key) && !ContextLock.isPageLockedSuspend(key)
        }
    }

    /**
     * Retrieves a specific lorebook entry by key from a page.
     * Respects MemoryIntrospection leash and ContextLock (hides locked entries).
     */
    suspend fun getLorebookEntry(pageKey: String, key: String): LoreBook?
    {
        if(!MemoryIntrospection.canRead(pageKey) || ContextLock.isPageLockedSuspend(pageKey)) return null
        if(ContextLock.isKeyLockedSuspend(key, pageKey = pageKey)) return null

        val window = ContextBank.getContextFromBankSuspend(pageKey)
        return window.findLoreBookEntry(key)
    }

    /**
     * Retrieves the entire lorebook for a specific page.
     * Respects MemoryIntrospection leash and ContextLock (filters out locked entries).
     */
    suspend fun getLorebook(pageKey: String): Map<String, LoreBook>
    {
        if(!MemoryIntrospection.canRead(pageKey) || ContextLock.isPageLockedSuspend(pageKey)) return emptyMap()

        val window = ContextBank.getContextFromBankSuspend(pageKey)
        return window.loreBookKeys.filter { (key, _) ->
            !ContextLock.isKeyLockedSuspend(key, pageKey = pageKey)
        }
    }

    /**
     * Queries the lorebook using structured parameters and optional regex extraction.
     * Respects MemoryIntrospection leash and ContextLock.
     */
    suspend fun queryLorebook(
        pageKey: String,
        query: String = "",
        minWeight: Int = Int.MIN_VALUE,
        requiredKeys: List<String> = emptyList(),
        aliasKeys: List<String> = emptyList(),
        extractRegex: String = ""
    ): List<LoreBookQueryResult>
    {
        return queryLorebookInternal(pageKey, query, minWeight, requiredKeys, aliasKeys, extractRegex, skipRemote = false)
    }

    /**
     * Query a local page for the in-process MemoryServer without consulting a remote backend.
     *
     * @param pageKey Page key to query.
     * @param query Optional substring to match.
     * @param minWeight Minimum lorebook weight to include.
     * @param requiredKeys Required lorebook keys.
     * @param aliasKeys Alias keys to match.
     * @param extractRegex Optional regular expression applied to matching values.
     * @return Matching lorebook results.
     */
    internal suspend fun queryLorebookLocally(
        pageKey: String,
        query: String = "",
        minWeight: Int = Int.MIN_VALUE,
        requiredKeys: List<String> = emptyList(),
        aliasKeys: List<String> = emptyList(),
        extractRegex: String = ""
    ): List<LoreBookQueryResult>
    {
        return queryLorebookInternal(pageKey, query, minWeight, requiredKeys, aliasKeys, extractRegex, skipRemote = true)
    }

    /**
     * Execute a lorebook query with explicit local/remote routing.
     *
     * @param pageKey Page key to query.
     * @param query Optional substring to match.
     * @param minWeight Minimum lorebook weight to include.
     * @param requiredKeys Required lorebook keys.
     * @param aliasKeys Alias keys to match.
     * @param extractRegex Optional regular expression applied to matching values.
     * @param skipRemote Whether to force local lookup.
     * @return Matching lorebook results.
     */
    private suspend fun queryLorebookInternal(
        pageKey: String,
        query: String,
        minWeight: Int,
        requiredKeys: List<String>,
        aliasKeys: List<String>,
        extractRegex: String,
        skipRemote: Boolean
    ): List<LoreBookQueryResult>
    {
        if(!MemoryIntrospection.canRead(pageKey) || ContextLock.isPageLockedSuspend(pageKey, skipRemote)) return emptyList()

        // Use a backend's optional query capability when it has one. Exact
        // persistence backends are not required to implement semantic queries.
        queryBackendOrNull(pageKey, skipRemote)?.let { backend ->
            return backend.queryLorebook(pageKey, query, minWeight, requiredKeys, aliasKeys, extractRegex)
        }

        val window = ContextBank.getContextFromBankSuspend(pageKey, skipRemote = skipRemote)
        val regex = if(extractRegex.isNotEmpty()) Regex(extractRegex) else null

        return window.loreBookKeys.filter { (key, entry) ->
            if(ContextLock.isKeyLocked(key, skipRemote)) return@filter false

            val matchesQuery = query.isEmpty() ||
                              key.contains(query, ignoreCase = true) ||
                              entry.value.contains(query, ignoreCase = true)

            val matchesWeight = entry.weight >= minWeight

            val matchesRequired = requiredKeys.isEmpty() ||
                                 requiredKeys.all { req -> entry.requiredKeys.contains(req) }

            val matchesAlias = aliasKeys.isEmpty() ||
                              aliasKeys.any { alias -> entry.aliasKeys.contains(alias) }

            matchesQuery && matchesWeight && matchesRequired && matchesAlias
        }.map { (key, entry) ->
            val extraction = if(regex != null) {
                regex.find(entry.value)?.value ?: ""
            } else ""

            LoreBookQueryResult(entry, extraction)
        }
    }

    /**
     * Simulates what lorebook entries would be triggered by a specific input text.
     * Respects MemoryIntrospection leash and ContextLock.
     */
    suspend fun simulateLorebookTrigger(pageKey: String, text: String): List<String>
    {
        return simulateLorebookTriggerInternal(pageKey, text, skipRemote = false)
    }

    /**
     * Simulate local lorebook triggers for the in-process MemoryServer without consulting a remote backend.
     *
     * @param pageKey Page key to inspect.
     * @param text Input text to scan for triggers.
     * @return Matching lorebook keys.
     */
    internal suspend fun simulateLorebookTriggerLocally(pageKey: String, text: String): List<String>
    {
        return simulateLorebookTriggerInternal(pageKey, text, skipRemote = true)
    }

    /**
     * Execute lorebook trigger simulation with explicit local/remote routing.
     *
     * @param pageKey Page key to inspect.
     * @param text Input text to scan for triggers.
     * @param skipRemote Whether to force local lookup.
     * @return Matching lorebook keys.
     */
    private suspend fun simulateLorebookTriggerInternal(
        pageKey: String,
        text: String,
        skipRemote: Boolean
    ): List<String>
    {
        if(!MemoryIntrospection.canRead(pageKey) || ContextLock.isPageLockedSuspend(pageKey, skipRemote)) return emptyList()

        queryBackendOrNull(pageKey, skipRemote)?.let { backend ->
            return backend.simulateLorebookTrigger(pageKey, text)
        }

        val window = ContextBank.getContextFromBankSuspend(pageKey, skipRemote = skipRemote)
        // Note: findMatchingLoreBookKeys already filters using canSelectLoreBookKey which respects ContextLock
        return window.findMatchingLoreBookKeys(text)
    }

    /**
     * Resolve the optional query capability for a page without allowing a registered remote backend to hijack local pages.
     *
     * @param pageKey The page whose storage mode determines routing.
     * @param skipRemote Whether to force local lookup.
     * @return A remote query backend when this page is remote, or null for local/fallback queries.
     */
    private fun queryBackendOrNull(pageKey: String, skipRemote: Boolean): ContextQueryBackend?
    {
        if(skipRemote)
        {
            return null
        }

        val mode = ContextBank.getStorageMode(pageKey)
        if(mode != StorageMode.REMOTE && !TPipeConfig.useRemoteMemoryGlobally)
        {
            return null
        }

        val configured = ContextPersistenceRegistry.get()
        if(configured != null)
        {
            return configured as? ContextQueryBackend
        }

        return TPipeRemotePersistenceBackend()
    }

    /**
     * Performs a substring search across both lorebook entries and context elements.
     * Respects MemoryIntrospection leash and ContextLock.
     */
    suspend fun searchMemory(
        pageKey: String,
        query: String,
        extractRegex: String = ""
    ): MemorySearchResult
    {
        if(!MemoryIntrospection.canRead(pageKey) || ContextLock.isPageLockedSuspend(pageKey))
        {
            return MemorySearchResult(emptyList(), emptyList())
        }

        val window = ContextBank.getContextFromBankSuspend(pageKey)
        val regex = if(extractRegex.isNotEmpty()) Regex(extractRegex) else null

        val lorebookMatches = queryLorebook(pageKey, query, extractRegex = extractRegex)

        val elementMatches = window.contextElements.filter { element ->
            element.contains(query, ignoreCase = true)
        }.map { element ->
            val extraction = if(regex != null) {
                regex.find(element)?.value ?: ""
            } else ""
            ContextElementSearchResult(element, extraction)
        }

        return MemorySearchResult(lorebookMatches, elementMatches)
    }

    /**
     * Adds or updates a lorebook entry in a page.
     * Respects MemoryIntrospection write leash and ContextLock (cannot modify locked entries).
     */
    suspend fun updateLorebookEntry(pageKey: String, entry: LoreBook): Boolean
    {
        if(!MemoryIntrospection.canWriteSuspend(pageKey) || ContextLock.isPageLockedSuspend(pageKey)) return false
        if(ContextLock.isKeyLockedSuspend(entry.key, pageKey = pageKey)) return false

        ContextBank.mutateContextWindowSuspend(pageKey, mode = ContextBank.getStorageMode(pageKey)) { window ->
            window.addLoreBookEntryWithObject(entry)
        }
        return true
    }

    /**
     * Deletes a lorebook entry from a page.
     * Respects MemoryIntrospection write leash and ContextLock.
     */
    suspend fun deleteLorebookEntry(pageKey: String, key: String): Boolean
    {
        if(!MemoryIntrospection.canWriteSuspend(pageKey) || ContextLock.isPageLockedSuspend(pageKey)) return false
        if(ContextLock.isKeyLockedSuspend(key, pageKey = pageKey)) return false

        var removed = false
        ContextBank.mutateContextWindowSuspend(pageKey, mode = ContextBank.getStorageMode(pageKey)) { window ->
            removed = window.loreBookKeys.remove(key) != null
        }
        return removed
    }

    /**
     * Retrieves the todo list for a page.
     * Respects MemoryIntrospection leash and ContextLock.
     */
    suspend fun getTodoList(pageKey: String): TodoList?
    {
        if(!MemoryIntrospection.canRead(pageKey) || ContextLock.isPageLockedSuspend(pageKey)) return null
        return ContextBank.getPagedTodoListSuspend(pageKey)
    }

    /**
     * Updates the todo list for a page.
     * Respects MemoryIntrospection write leash and ContextLock.
     */
    suspend fun updateTodoList(pageKey: String, todoList: TodoList): Boolean
    {
        if(!MemoryIntrospection.canWriteSuspend(pageKey) || ContextLock.isPageLockedSuspend(pageKey)) return false
        ContextBank.emplaceTodoListSuspend(pageKey, todoList, ContextBank.getStorageMode(pageKey))
        return true
    }

    /**
     * Registers all memory introspection tools in the FunctionRegistry and adds them to a PcpContext.
     */
    fun registerAndEnable(context: PcpContext)
    {
        // Register in FunctionRegistry
        FunctionRegistry.registerFunction("listPageKeys", ::listPageKeys)
        FunctionRegistry.registerFunction("getLorebookEntry", ::getLorebookEntry)
        FunctionRegistry.registerFunction("getLorebook", ::getLorebook)
        FunctionRegistry.registerFunction("queryLorebook", ::queryLorebook)
        FunctionRegistry.registerFunction("simulateLorebookTrigger", ::simulateLorebookTrigger)
        FunctionRegistry.registerFunction("searchMemory", ::searchMemory)
        FunctionRegistry.registerFunction("updateLorebookEntry", ::updateLorebookEntry)
        FunctionRegistry.registerFunction("deleteLorebookEntry", ::deleteLorebookEntry)
        FunctionRegistry.registerFunction("getTodoList", ::getTodoList)
        FunctionRegistry.registerFunction("updateTodoList", ::updateTodoList)

        // Helper to add option only if it doesn't exist
        fun addIfMissing(option: TPipeContextOptions)
        {
            if(context.tpipeOptions.none { it.functionName == option.functionName })
            {
                context.addTPipeOption(option)
            }
        }

        // Add to PcpContext
        addIfMissing(TPipeContextOptions().apply {
            functionName = "listPageKeys"
            description = "Lists all memory page keys you are allowed to access."
        })

        addIfMissing(TPipeContextOptions().apply {
            functionName = "getLorebookEntry"
            description = "Retrieves a specific lorebook entry from a page."
            params["pageKey"] = ContextOptionParameter(ParamType.String, "The page key to access.", emptyList())
            params["key"] = ContextOptionParameter(ParamType.String, "The lorebook trigger key.", emptyList())
        })

        addIfMissing(TPipeContextOptions().apply {
            functionName = "queryLorebook"
            description = "Performs a structured search on the lorebook of a page. Supports filtering by query string, weight, and keys."
            params["pageKey"] = ContextOptionParameter(ParamType.String, "The page key to query.", emptyList())
            params["query"] = ContextOptionParameter(ParamType.String, "Substring to find in keys or values (optional).", emptyList())
            params["minWeight"] = ContextOptionParameter(ParamType.Int, "Minimum weight for entries (optional).", emptyList())
            params["extractRegex"] = ContextOptionParameter(ParamType.String, "Regex to extract specific data from matching entries (optional).", emptyList())
        })

        addIfMissing(TPipeContextOptions().apply {
            functionName = "searchMemory"
            description = "Performs a deep search across all lorebook entries and context elements on a page."
            params["pageKey"] = ContextOptionParameter(ParamType.String, "The page key to search.", emptyList())
            params["query"] = ContextOptionParameter(ParamType.String, "The query string to search for.", emptyList())
            params["extractRegex"] = ContextOptionParameter(ParamType.String, "Regex to extract specific data from matches (optional).", emptyList())
        })

        addIfMissing(TPipeContextOptions().apply {
            functionName = "simulateLorebookTrigger"
            description = "Simulates what lorebook entries would be triggered by a specific input text."
            params["pageKey"] = ContextOptionParameter(ParamType.String, "The page key.", emptyList())
            params["text"] = ContextOptionParameter(ParamType.String, "The input text to test triggers for.", emptyList())
        })

        addIfMissing(TPipeContextOptions().apply {
            functionName = "getLorebook"
            description = "Retrieves the entire lorebook for a specific page."
            params["pageKey"] = ContextOptionParameter(ParamType.String, "The page key to access.", emptyList())
        })

        addIfMissing(TPipeContextOptions().apply {
            functionName = "updateLorebookEntry"
            description = "Adds or updates a lorebook entry on a page. Requires write permission."
            params["pageKey"] = ContextOptionParameter(ParamType.String, "The page key.", emptyList())
            params["entry"] = ContextOptionParameter(ParamType.Object, "The LoreBook entry object.", emptyList())
        })

        addIfMissing(TPipeContextOptions().apply {
            functionName = "deleteLorebookEntry"
            description = "Deletes a lorebook entry from a page. Requires write permission."
            params["pageKey"] = ContextOptionParameter(ParamType.String, "The page key.", emptyList())
            params["key"] = ContextOptionParameter(ParamType.String, "The lorebook trigger key to delete.", emptyList())
        })

        addIfMissing(TPipeContextOptions().apply {
            functionName = "getTodoList"
            description = "Retrieves the todo list for a page."
            params["pageKey"] = ContextOptionParameter(ParamType.String, "The page key.", emptyList())
        })

        addIfMissing(TPipeContextOptions().apply {
            functionName = "updateTodoList"
            description = "Updates the todo list for a page. Requires write permission."
            params["pageKey"] = ContextOptionParameter(ParamType.String, "The page key.", emptyList())
            params["todoList"] = ContextOptionParameter(ParamType.Object, "The TodoList object.", emptyList())
        })
    }
}

@Serializable
data class LoreBookQueryResult(
    val entry: LoreBook,
    val extraction: String = ""
)

@Serializable
data class ContextElementSearchResult(
    val element: String,
    val extraction: String = ""
)

@Serializable
data class MemorySearchResult(
    val lorebookMatches: List<LoreBookQueryResult>,
    val elementMatches: List<ContextElementSearchResult>
)
