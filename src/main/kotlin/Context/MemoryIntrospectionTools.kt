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
     *
     * @return Page keys visible to the current introspection leash.
     */
    suspend fun listPageKeys(): List<String>
    {
        val allKeys = try
        {
            ContextBank.getPageKeysSuspend()
        }
        catch(_: ContextAccessDeniedException)
        {
            return emptyList()
        }
        return allKeys.filter { key ->
            MemoryIntrospection.canRead(key) && !ContextLock.isPageLockedSuspend(key)
        }
    }

    /**
     * Retrieves a specific lorebook entry by key from a page.
     * Respects MemoryIntrospection leash and ContextLock (hides locked entries).
     *
     * @param pageKey Page containing the lorebook entry.
     * @param key Lorebook key to retrieve.
     * @return The entry, or `null` when it is unavailable.
     */
    suspend fun getLorebookEntry(pageKey: String, key: String): LoreBook?
    {
        if(!hasContextAccess(pageKey, ContextAccessOperation.READ)) return null
        if(!MemoryIntrospection.canRead(pageKey) || ContextLock.isPageLockedSuspend(pageKey)) return null
        if(ContextLock.isKeyLockedSuspend(key, pageKey = pageKey)) return null

        val window = ContextBank.getContextFromBankSuspend(pageKey)
        return window.findLoreBookEntry(key)
    }

    /**
     * Retrieves the entire lorebook for a specific page.
     * Respects MemoryIntrospection leash and ContextLock (filters out locked entries).
     *
     * @param pageKey Page whose lorebook should be retrieved.
     * @return Visible lorebook entries keyed by lorebook key.
     */
    suspend fun getLorebook(pageKey: String): Map<String, LoreBook>
    {
        if(!hasContextAccess(pageKey, ContextAccessOperation.READ)) return emptyMap()
        if(!MemoryIntrospection.canRead(pageKey) || ContextLock.isPageLockedSuspend(pageKey)) return emptyMap()

        val window = ContextBank.getContextFromBankSuspend(pageKey)
        return window.loreBookKeys.filter { (key, _) ->
            !ContextLock.isKeyLockedSuspend(key, pageKey = pageKey)
        }
    }

    /**
     * Queries the lorebook using structured parameters and optional regex extraction.
     * Respects MemoryIntrospection leash and ContextLock.
     *
     * @param pageKey Page whose lorebook should be queried.
     * @param query Optional substring to match.
     * @param minWeight Minimum entry weight to include.
     * @param requiredKeys Required lorebook keys.
     * @param aliasKeys Alias keys to match.
     * @param extractRegex Optional regular expression used to extract result text.
     * @return Matching lorebook results.
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
        if(!hasContextAccess(pageKey, ContextAccessOperation.READ, skipRemote = skipRemote)) return emptyList()
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
     *
     * @param pageKey Page whose lorebook should be inspected.
     * @param text Input text to evaluate.
     * @return Lorebook keys that would be triggered.
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
        if(!hasContextAccess(pageKey, ContextAccessOperation.READ, skipRemote = skipRemote)) return emptyList()
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
     *
     * @param pageKey Page whose memory should be searched.
     * @param query Text to find in memory.
     * @param extractRegex Optional regular expression used to extract result text.
     * @return Matching lorebook and context-element results.
     */
    suspend fun searchMemory(
        pageKey: String,
        query: String,
        extractRegex: String = ""
    ): MemorySearchResult
    {
        if(!hasContextAccess(pageKey, ContextAccessOperation.READ))
        {
            return MemorySearchResult(emptyList(), emptyList())
        }
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
     *
     * @param pageKey Page to update.
     * @param entry Lorebook entry to add or replace.
     * @return `true` when the entry was updated.
     */
    suspend fun updateLorebookEntry(pageKey: String, entry: LoreBook): Boolean
    {
        if(!hasContextAccess(pageKey, ContextAccessOperation.WRITE)) return false
        val canWrite = try
        {
            MemoryIntrospection.canWriteSuspend(pageKey)
        }
        catch(_: ContextAccessDeniedException)
        {
            false
        }
        if(!canWrite || ContextLock.isPageLockedSuspend(pageKey)) return false
        if(ContextLock.isKeyLockedSuspend(entry.key, pageKey = pageKey)) return false

        ContextBank.mutateContextWindowSuspend(pageKey, mode = ContextBank.getStorageMode(pageKey)) { window ->
            window.addLoreBookEntryWithObject(entry)
        }
        return true
    }

    /**
     * Deletes a lorebook entry from a page.
     * Respects MemoryIntrospection write leash and ContextLock.
     *
     * @param pageKey Page to update.
     * @param key Lorebook key to delete.
     * @return `true` when an entry was deleted.
     */
    suspend fun deleteLorebookEntry(pageKey: String, key: String): Boolean
    {
        if(!hasContextAccess(pageKey, ContextAccessOperation.WRITE)) return false
        val canWrite = try
        {
            MemoryIntrospection.canWriteSuspend(pageKey)
        }
        catch(_: ContextAccessDeniedException)
        {
            false
        }
        if(!canWrite || ContextLock.isPageLockedSuspend(pageKey)) return false
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
     *
     * @param pageKey Todo-list key to retrieve.
     * @return The todo list, or `null` when it is unavailable.
     */
    suspend fun getTodoList(pageKey: String): TodoList?
    {
        if(!hasContextAccess(pageKey, ContextAccessOperation.READ, ContextResourceKind.TODO_LIST)) return null
        if(!MemoryIntrospection.canRead(pageKey) || ContextLock.isPageLockedSuspend(pageKey)) return null
        return ContextBank.getPagedTodoListSuspend(pageKey)
    }

    /**
     * Updates the todo list for a page.
     * Respects MemoryIntrospection write leash and ContextLock.
     *
     * @param pageKey Todo-list key to update.
     * @param todoList Replacement todo list.
     * @return `true` when the list was updated.
     */
    suspend fun updateTodoList(pageKey: String, todoList: TodoList): Boolean
    {
        return updateTodoListInternal(pageKey, todoList, propagateAccessDenial = false)
    }

    /**
     * Update a todo list while preserving typed ContextBank denials for secure
     * callers. The public legacy method above keeps its historical Boolean
     * result contract.
     *
     * @param pageKey Todo-list key to update.
     * @param todoList Replacement todo list.
     * @return `true` when the list was updated.
     */
    internal suspend fun updateTodoListWithTypedAccessDenial(
        pageKey: String,
        todoList: TodoList
    ): Boolean
    {
        return updateTodoListInternal(pageKey, todoList, propagateAccessDenial = true)
    }

    /**
     * Execute the todo update with an explicit legacy-versus-secure denial policy.
     *
     * @param pageKey Todo-list key to update.
     * @param todoList Replacement todo list.
     * @param propagateAccessDenial Whether typed access denials should escape.
     * @return `true` when the list was updated.
     */
    private suspend fun updateTodoListInternal(
        pageKey: String,
        todoList: TodoList,
        propagateAccessDenial: Boolean
    ): Boolean
    {
        if(!hasContextAccess(pageKey, ContextAccessOperation.WRITE, ContextResourceKind.TODO_LIST)) return false
        val canWrite = try
        {
            MemoryIntrospection.canWriteSuspend(pageKey)
        }
        catch(e: ContextAccessDeniedException)
        {
            if(propagateAccessDenial)
            {
                throw e
            }
            return false
        }
        if(!canWrite || ContextLock.isPageLockedSuspend(pageKey)) return false
        try
        {
            ContextBank.emplaceTodoListSuspend(pageKey, todoList, ContextBank.getStorageMode(pageKey))
        }
        catch(e: ContextAccessDeniedException)
        {
            if(propagateAccessDenial)
            {
                throw e
            }
            return false
        }
        return true
    }

    /**
     * Preflight ContextBank authority before optional query backends or local
     * value reads are invoked. Legacy tools translate a typed denial back to
     * their historical safe-result contract.
     *
     * @param pageKey ContextBank storage key.
     * @param operation Operation to authorize.
     * @param kind Resource kind being accessed.
     * @param skipRemote Whether to bypass configured remote persistence.
     * @return `true` when access is authorized or no scope is active.
     */
    private suspend fun hasContextAccess(
        pageKey: String,
        operation: ContextAccessOperation,
        kind: ContextResourceKind = ContextResourceKind.CONTEXT_WINDOW,
        skipRemote: Boolean = false
    ): Boolean
    {
        return try
        {
            ContextBank.requireAccessSuspend(pageKey, kind, operation, skipRemote)
            true
        }
        catch(_: ContextAccessDeniedException)
        {
            false
        }
    }

    /**
     * Registers all memory introspection tools in the FunctionRegistry and adds them to a PcpContext.
     *
     * @param context PCP context that receives the tool options.
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
