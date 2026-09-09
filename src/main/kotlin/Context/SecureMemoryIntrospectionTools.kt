package com.TTT.Context

import com.TTT.PipeContextProtocol.ContextOptionParameter
import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.ParamType
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.TPipeContextOptions

/**
 * PCP-facing memory tools that use the active ContextAccess scope directly.
 *
 * The original [MemoryIntrospectionTools] names remain unchanged for source
 * and tool-contract compatibility. These explicitly prefixed functions are
 * for hosts that want protected-resource denials to be visible as typed
 * [ContextAccessDeniedException] failures.
 */
object SecureMemoryIntrospectionTools
{
    /** List page keys visible to the active access scope. */
    suspend fun listPageKeys(): List<String>
    {
        return withIntrospectionAccess {
            ContextBank.requireEnumerationAccess(ContextResourceKind.CONTEXT_WINDOW)
            val allKeys = ContextBank.getPageKeysSuspend()
            allKeys.filter { key ->
                MemoryIntrospection.canRead(key) && !ContextLock.isPageLockedSuspend(key)
            }
        }
    }

    /** Retrieve one lorebook entry through the active access scope. */
    suspend fun getLorebookEntry(pageKey: String, key: String): LoreBook?
    {
        return withReadAccess(pageKey) {
            MemoryIntrospectionTools.getLorebookEntry(pageKey, key)
        }
    }

    /** Retrieve a complete lorebook through the active access scope. */
    suspend fun getLorebook(pageKey: String): Map<String, LoreBook>
    {
        return withReadAccess(pageKey) {
            MemoryIntrospectionTools.getLorebook(pageKey)
        }
    }

    /** Query a lorebook through the active access scope. */
    suspend fun queryLorebook(
        pageKey: String,
        query: String = "",
        minWeight: Int = Int.MIN_VALUE,
        requiredKeys: List<String> = emptyList(),
        aliasKeys: List<String> = emptyList(),
        extractRegex: String = ""
    ): List<LoreBookQueryResult>
    {
        return withReadAccess(pageKey) {
            MemoryIntrospectionTools.queryLorebook(
                pageKey,
                query,
                minWeight,
                requiredKeys,
                aliasKeys,
                extractRegex
            )
        }
    }

    /** Simulate lorebook triggers through the active access scope. */
    suspend fun simulateLorebookTrigger(pageKey: String, text: String): List<String>
    {
        return withReadAccess(pageKey) {
            MemoryIntrospectionTools.simulateLorebookTrigger(pageKey, text)
        }
    }

    /** Search memory through the active access scope. */
    suspend fun searchMemory(
        pageKey: String,
        query: String,
        extractRegex: String = ""
    ): MemorySearchResult
    {
        return withReadAccess(pageKey) {
            MemoryIntrospectionTools.searchMemory(pageKey, query, extractRegex)
        }
    }

    /** Update a lorebook entry through the active access scope. */
    suspend fun updateLorebookEntry(pageKey: String, entry: LoreBook): Boolean
    {
        return withWriteAccess(pageKey) {
            MemoryIntrospectionTools.updateLorebookEntry(pageKey, entry)
        }
    }

    /** Delete a lorebook entry through the active access scope. */
    suspend fun deleteLorebookEntry(pageKey: String, key: String): Boolean
    {
        return withWriteAccess(pageKey) {
            MemoryIntrospectionTools.deleteLorebookEntry(pageKey, key)
        }
    }

    /** Retrieve a todo list through the active access scope. */
    suspend fun getTodoList(pageKey: String): TodoList?
    {
        return withReadAccess(pageKey, ContextResourceKind.TODO_LIST) {
            MemoryIntrospectionTools.getTodoList(pageKey)
        }
    }

    /** Update a todo list through the active access scope. */
    suspend fun updateTodoList(pageKey: String, todoList: TodoList): Boolean
    {
        return withWriteAccess(pageKey, ContextResourceKind.TODO_LIST) {
            MemoryIntrospectionTools.updateTodoListWithTypedAccessDenial(pageKey, todoList)
        }
    }

    /**
     * Register the secure tool variants with distinct PCP names.
     */
    fun registerAndEnable(context: PcpContext)
    {
        FunctionRegistry.registerFunction("secure_listPageKeys", ::listPageKeys)
        FunctionRegistry.registerFunction("secure_getLorebookEntry", ::getLorebookEntry)
        FunctionRegistry.registerFunction("secure_getLorebook", ::getLorebook)
        FunctionRegistry.registerFunction("secure_queryLorebook", ::queryLorebook)
        FunctionRegistry.registerFunction("secure_simulateLorebookTrigger", ::simulateLorebookTrigger)
        FunctionRegistry.registerFunction("secure_searchMemory", ::searchMemory)
        FunctionRegistry.registerFunction("secure_updateLorebookEntry", ::updateLorebookEntry)
        FunctionRegistry.registerFunction("secure_deleteLorebookEntry", ::deleteLorebookEntry)
        FunctionRegistry.registerFunction("secure_getTodoList", ::getTodoList)
        FunctionRegistry.registerFunction("secure_updateTodoList", ::updateTodoList)

        fun addIfMissing(name: String, description: String, params: Map<String, ContextOptionParameter> = emptyMap())
        {
            if(context.tpipeOptions.none { it.functionName == name })
            {
                context.addTPipeOption(TPipeContextOptions().apply {
                    functionName = name
                    this.description = description
                    this.params.putAll(params)
                })
            }
        }

        val pageKey = ContextOptionParameter(ParamType.String, "The protected page key to access.", emptyList())
        val entryKey = ContextOptionParameter(ParamType.String, "The lorebook key.", emptyList())
        val entry = ContextOptionParameter(ParamType.Object, "The LoreBook entry object.", emptyList())
        val query = ContextOptionParameter(ParamType.String, "The query string.", emptyList())
        val text = ContextOptionParameter(ParamType.String, "The input text.", emptyList())
        val todoList = ContextOptionParameter(ParamType.Object, "The TodoList object.", emptyList())

        addIfMissing("secure_listPageKeys", "Lists page keys visible to the active ContextAccess scope.")
        addIfMissing(
            "secure_getLorebookEntry",
            "Retrieves a lorebook entry through the active ContextAccess scope.",
            mapOf("pageKey" to pageKey, "key" to entryKey)
        )
        addIfMissing(
            "secure_getLorebook",
            "Retrieves a lorebook through the active ContextAccess scope.",
            mapOf("pageKey" to pageKey)
        )
        addIfMissing(
            "secure_queryLorebook",
            "Queries a lorebook through the active ContextAccess scope.",
            mapOf("pageKey" to pageKey, "query" to query)
        )
        addIfMissing(
            "secure_simulateLorebookTrigger",
            "Simulates lorebook triggers through the active ContextAccess scope.",
            mapOf("pageKey" to pageKey, "text" to text)
        )
        addIfMissing(
            "secure_searchMemory",
            "Searches memory through the active ContextAccess scope.",
            mapOf("pageKey" to pageKey, "query" to query)
        )
        addIfMissing(
            "secure_updateLorebookEntry",
            "Updates a lorebook entry through the active ContextAccess scope.",
            mapOf("pageKey" to pageKey, "entry" to entry)
        )
        addIfMissing(
            "secure_deleteLorebookEntry",
            "Deletes a lorebook entry through the active ContextAccess scope.",
            mapOf("pageKey" to pageKey, "key" to entryKey)
        )
        addIfMissing(
            "secure_getTodoList",
            "Retrieves a todo list through the active ContextAccess scope.",
            mapOf("pageKey" to pageKey)
        )
        addIfMissing(
            "secure_updateTodoList",
            "Updates a todo list through the active ContextAccess scope.",
            mapOf("pageKey" to pageKey, "todoList" to todoList)
        )
    }

    private suspend fun <T> withIntrospectionAccess(block: suspend () -> T): T
    {
        val currentConfig = MemoryIntrospection.getCurrentConfigOrNull()
        return MemoryIntrospection.withCoroutineScope(
            currentConfig ?: MemoryIntrospectionConfig(
                    allowedPageKeys = mutableSetOf("*"),
                    allowPageCreation = true,
                    allowRead = true,
                    allowWrite = true
                ),
            block
        )
    }

    private suspend fun <T> withReadAccess(
        pageKey: String,
        kind: ContextResourceKind = ContextResourceKind.CONTEXT_WINDOW,
        block: suspend () -> T
    ): T
    {
        return withIntrospectionAccess {
            ContextBank.requireAccessSuspend(pageKey, kind, ContextAccessOperation.READ)
            block()
        }
    }

    private suspend fun <T> withWriteAccess(
        pageKey: String,
        kind: ContextResourceKind = ContextResourceKind.CONTEXT_WINDOW,
        block: suspend () -> T
    ): T
    {
        return withIntrospectionAccess {
            ContextBank.requireAccessSuspend(pageKey, kind, ContextAccessOperation.WRITE)
            block()
        }
    }
}
