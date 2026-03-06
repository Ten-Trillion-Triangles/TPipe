package com.TTT.Context

import com.TTT.Config.TPipeConfig
import com.TTT.Util.deserialize
import com.TTT.Util.httpDelete
import com.TTT.Util.httpGet
import com.TTT.Util.httpPost
import com.TTT.Util.serialize
import kotlinx.coroutines.delay

/**
 * Client for interacting with a remote TPipe memory server.
 * Provides abstracted access to ContextWindow, TodoList, and ContextLock operations.
 */
object MemoryClient
{
    private fun getBaseUrl() = TPipeConfig.remoteMemoryUrl.removeSuffix("/")
    private fun getAuthToken() = TPipeConfig.remoteMemoryAuthToken
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 100L

    /**
     * Executes a network operation with simple retry logic for transient errors.
     */
    private suspend fun <T> withRetry(block: suspend () -> T): T?
    {
        var lastException: Exception? = null
        for(i in 1..MAX_RETRIES)
        {
            try
            {
                return block()
            }
            catch(e: Exception)
            {
                lastException = e
                if(i < MAX_RETRIES) delay(RETRY_DELAY_MS * i)
            }
        }
        return null
    }

    // Cache for lock states to prevent N+1 network requests during lorebook selection.
    private val keyLockCache = mutableMapOf<String, Pair<Boolean, Long>>()
    private val pageLockCache = mutableMapOf<String, Pair<Boolean, Long>>()
    private const val CACHE_TTL_MS = 1000L // 1 second TTL for lock states

    /**
     * Retrieve all context window keys from the remote server.
     */
    suspend fun getPageKeys(): List<String>
    {
        val url = "${getBaseUrl()}/context/bank/keys"
        val response = withRetry { httpGet(url, authToken = getAuthToken()) } ?: return emptyList()
        return deserialize<List<String>>(response) ?: emptyList()
    }

    /**
     * Retrieve a context window from the remote server.
     */
    suspend fun getContextWindow(key: String): ContextWindow?
    {
        val url = "${getBaseUrl()}/context/bank/$key"
        val response = withRetry { httpGet(url, authToken = getAuthToken()) } ?: return null
        if(response.startsWith("Error:")) return null
        return deserialize<ContextWindow>(response)
    }

    /**
     * Send a context window to the remote server.
     */
    suspend fun emplaceContextWindow(key: String, window: ContextWindow): Boolean
    {
        val url = "${getBaseUrl()}/context/bank/$key"
        val body = serialize(window)
        val response = withRetry { httpPost(url, body, authToken = getAuthToken()) } ?: return false
        return !response.startsWith("Error:")
    }

    /**
     * Remotely query the lorebook of a context window.
     */
    suspend fun queryLorebook(
        key: String,
        query: String = "",
        minWeight: Int = Int.MIN_VALUE,
        requiredKeys: List<String> = emptyList(),
        aliasKeys: List<String> = emptyList(),
        extractRegex: String = ""
    ): List<LoreBookQueryResult>
    {
        val required = requiredKeys.joinToString(",")
        val aliases = aliasKeys.joinToString(",")
        val url = "${getBaseUrl()}/context/bank/$key/query?query=$query&minWeight=$minWeight&extractRegex=$extractRegex&requiredKeys=$required&aliasKeys=$aliases"
        val response = withRetry { httpGet(url, authToken = getAuthToken()) } ?: return emptyList()
        if(response.startsWith("Error:")) return emptyList()
        return deserialize<List<LoreBookQueryResult>>(response) ?: emptyList()
    }

    /**
     * Remotely simulate lorebook triggers for a given text.
     */
    suspend fun simulateLorebookTrigger(key: String, text: String): List<String>
    {
        val url = "${getBaseUrl()}/context/bank/$key/simulate?text=$text"
        val response = withRetry { httpGet(url, authToken = getAuthToken()) } ?: return emptyList()
        if(response.startsWith("Error:")) return emptyList()
        return deserialize<List<String>>(response) ?: emptyList()
    }

    /**
     * Delete a context window from the remote server.
     */
    suspend fun deleteContextWindow(key: String): Boolean
    {
        val url = "${getBaseUrl()}/context/bank/$key"
        val response = withRetry { httpDelete(url, authToken = getAuthToken()) } ?: return false
        return !response.startsWith("Error:")
    }

    /**
     * Retrieve all todo list keys from the remote server.
     */
    suspend fun getTodoListKeys(): List<String>
    {
        val url = "${getBaseUrl()}/context/todo/keys"
        val response = withRetry { httpGet(url, authToken = getAuthToken()) } ?: return emptyList()
        return deserialize<List<String>>(response) ?: emptyList()
    }

    /**
     * Retrieve a todo list from the remote server.
     */
    suspend fun getTodoList(key: String): TodoList?
    {
        val url = "${getBaseUrl()}/context/todo/$key"
        val response = withRetry { httpGet(url, authToken = getAuthToken()) } ?: return null
        if(response.startsWith("Error:")) return null
        return deserialize<TodoList>(response)
    }

    /**
     * Send a todo list to the remote server.
     */
    suspend fun emplaceTodoList(key: String, todoList: TodoList): Boolean
    {
        val url = "${getBaseUrl()}/context/todo/$key"
        val body = serialize(todoList)
        val response = withRetry { httpPost(url, body, authToken = getAuthToken()) } ?: return false
        return !response.startsWith("Error:")
    }

    /**
     * Delete a todo list from the remote server.
     */
    suspend fun deleteTodoList(key: String): Boolean
    {
        val url = "${getBaseUrl()}/context/todo/$key"
        val response = withRetry { httpDelete(url, authToken = getAuthToken()) } ?: return false
        return !response.startsWith("Error:")
    }

    /**
     * Get all lock keys from the remote server.
     */
    suspend fun getLockKeys(): Set<String>
    {
        val url = "${getBaseUrl()}/context/lock/keys"
        val response = withRetry { httpGet(url, authToken = getAuthToken()) } ?: return emptySet()
        return deserialize<Set<String>>(response) ?: emptySet()
    }

    /**
     * Check if a key is locked on the remote server.
     */
    suspend fun isKeyLocked(key: String): Boolean
    {
        val now = System.currentTimeMillis()
        keyLockCache[key]?.let { (state, timestamp) ->
            if(now - timestamp < CACHE_TTL_MS) return state
        }

        val url = "${getBaseUrl()}/context/lock/$key/state"
        val response = withRetry { httpGet(url, authToken = getAuthToken()) } ?: return false
        if(response.startsWith("Error:")) return false
        val state = response.trim().toBoolean()

        keyLockCache[key] = state to now
        return state
    }

    /**
     * Check if a page is locked on the remote server.
     */
    suspend fun isPageLocked(pageKey: String): Boolean
    {
        val now = System.currentTimeMillis()
        pageLockCache[pageKey]?.let { (state, timestamp) ->
            if(now - timestamp < CACHE_TTL_MS) return state
        }

        val url = "${getBaseUrl()}/context/lock/page/$pageKey/state"
        val response = withRetry { httpGet(url, authToken = getAuthToken()) } ?: return false
        if(response.startsWith("Error:")) return false
        val state = response.trim().toBoolean()

        pageLockCache[pageKey] = state to now
        return state
    }

    /**
     * Add a lock on the remote server.
     */
    suspend fun addLock(request: LockRequest): Boolean
    {
        val url = "${getBaseUrl()}/context/lock"
        val body = serialize(request)
        val response = withRetry { httpPost(url, body, authToken = getAuthToken()) } ?: return false
        if(!response.startsWith("Error:"))
        {
            val now = System.currentTimeMillis()
            if(request.isPageKey) pageLockCache[request.key] = request.lockState to now
            else keyLockCache[request.key] = request.lockState to now
            return true
        }
        return false
    }

    /**
     * Remove a lock from the remote server.
     */
    suspend fun removeLock(key: String): Boolean
    {
        val url = "${getBaseUrl()}/context/lock/$key"
        val response = withRetry { httpDelete(url, authToken = getAuthToken()) } ?: return false
        if(!response.startsWith("Error:"))
        {
            keyLockCache.remove(key)
            pageLockCache.remove(key)
            return true
        }
        return false
    }

    /**
     * Update a lock's state on the remote server.
     */
    suspend fun updateLockState(key: String, lockState: Boolean): Boolean
    {
        val url = "${getBaseUrl()}/context/lock/$key/state"
        val body = lockState.toString()
        val response = withRetry { httpPost(url, body, authToken = getAuthToken()) } ?: return false
        if(!response.startsWith("Error:"))
        {
            val now = System.currentTimeMillis()
            keyLockCache[key] = lockState to now
            pageLockCache[key] = lockState to now
            return true
        }
        return false
    }
}
