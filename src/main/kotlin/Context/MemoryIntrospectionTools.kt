package com.TTT.Context

import com.TTT.PipeContextProtocol.ContextOptionParameter

import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.Config.TPipeConfig
import com.TTT.PipeContextProtocol.TPipeContextOptions
import com.TTT.PipeContextProtocol.ParamType
import com.TTT.PipeContextProtocol.FunctionRegistry
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
        if(ContextLock.isKeyLockedSuspend(key)) return null

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
            !ContextLock.isKeyLockedSuspend(key)
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
        if(!MemoryIntrospection.canRead(pageKey) || ContextLock.isPageLockedSuspend(pageKey)) return emptyList()

        // Optimize for remote memory if configured
        val mode = ContextBank.getStorageMode(pageKey)
        if(mode == StorageMode.REMOTE || TPipeConfig.useRemoteMemoryGlobally)
        {
            return MemoryClient.queryLorebook(pageKey, query, minWeight, requiredKeys, aliasKeys, extractRegex)
                .requireValue("query remote lorebook '$pageKey'")
        }

        val window = ContextBank.getContextFromBankSuspend(pageKey)
        val regex = if(extractRegex.isNotEmpty()) Regex(extractRegex) else null

        return window.loreBookKeys.filter { (key, entry) ->
            if(ContextLock.isKeyLocked(key)) return@filter false

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
        if(!MemoryIntrospection.canRead(pageKey) || ContextLock.isPageLockedSuspend(pageKey)) return emptyList()

        // Optimize for remote memory if configured
        val mode = ContextBank.getStorageMode(pageKey)
        if(mode == StorageMode.REMOTE || TPipeConfig.useRemoteMemoryGlobally)
        {
            return MemoryClient.simulateLorebookTrigger(pageKey, text)
                .requireValue("simulate remote lorebook trigger '$pageKey'")
        }

        val window = ContextBank.getContextFromBankSuspend(pageKey)
        // Note: findMatchingLoreBookKeys already filters using canSelectLoreBookKey which respects ContextLock
        return window.findMatchingLoreBookKeys(text)
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
        if(ContextLock.isKeyLockedSuspend(entry.key)) return false

        ContextBank.mutateContextWindowSuspend(pageKey, mode = StorageMode.MEMORY_AND_DISK) { window ->
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
        if(ContextLock.isKeyLockedSuspend(key)) return false

        var removed = false
        ContextBank.mutateContextWindowSuspend(pageKey, mode = StorageMode.MEMORY_AND_DISK) { window ->
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
        ContextBank.emplaceTodoListSuspend(pageKey, todoList, StorageMode.MEMORY_AND_DISK)
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
