package com.TTT.Context

import com.TTT.Config.TPipeConfig
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Client for interacting with a remote TPipe memory server.
 * Provides typed access to ContextWindow, TodoList, and ContextLock operations.
 */
object MemoryClient
{
    private fun getBaseUrl() = TPipeConfig.remoteMemoryUrl.removeSuffix("/")
    private fun getAuthToken() = TPipeConfig.remoteMemoryAuthToken
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 100L
    private const val CACHE_TTL_MS = 1000L
    private const val REQUEST_TIMEOUT_MS = 1500L

    /**
     * Cache entry for remote values that can tolerate short-lived reuse.
     *
     * @param value Cached value.
     * @param createdAtMillis Millisecond timestamp when the cache entry was written.
     */
    private data class CachedRemoteValue<T>(
        val value: T,
        val createdAtMillis: Long
    )

    // These caches are correctness-neutral hints only. Remote payload reads stay live to avoid invalidation drift.
    private val keyLockCache = ConcurrentHashMap<String, CachedRemoteValue<Boolean>>()
    private val pageLockCache = ConcurrentHashMap<String, CachedRemoteValue<Boolean>>()
    private val pageKeyCache = AtomicReference<CachedRemoteValue<List<String>>?>(null)
    private val todoKeyCache = AtomicReference<CachedRemoteValue<List<String>>?>(null)
    private val lockKeyCache = AtomicReference<CachedRemoteValue<Set<String>>?>(null)

    /**
     * Retrieve all context window keys from the remote server.
     *
     * @return Typed result containing the remote page-key list.
     */
    suspend fun getPageKeys(): MemoryOperationResult<List<String>>
    {
        readListCache(pageKeyCache)?.let { cachedKeys ->
            return MemoryOperationResult.Success(cachedKeys)
        }

        return parseResponse(
            executeRequest(
                pathSegments = listOf("context", "bank", "keys"),
                method = HttpMethod.Get
            ),
            operation = "list remote context keys"
        ) { responseBody ->
            deserialize<List<String>>(responseBody, useRepair = false)
        }.also { result ->
            if(result is MemoryOperationResult.Success)
            {
                pageKeyCache.set(cachedValue(result.value))
            }
        }
    }

    /**
     * Retrieve a context window from the remote server.
     *
     * @param key Page key to retrieve.
     * @return Typed result containing the requested page.
     */
    suspend fun getContextWindow(key: String): MemoryOperationResult<ContextWindow>
    {
        return parseResponse(
            executeRequest(
                pathSegments = listOf("context", "bank", key),
                method = HttpMethod.Get
            ),
            operation = "fetch remote context window '$key'"
        ) { responseBody ->
            deserialize<ContextWindow>(responseBody, useRepair = false)
        }
    }

    /**
     * Send a context window to the remote server.
     *
     * @param key Page key to write.
     * @param window Context window to persist.
     * @return Typed result containing the stored page returned by the server.
     */
    suspend fun emplaceContextWindow(key: String, window: ContextWindow): MemoryOperationResult<ContextWindow>
    {
        invalidatePageKeyCache()
        return parseResponse(
            executeRequest(
                pathSegments = listOf("context", "bank", key),
                method = HttpMethod.Post,
                requestBody = serialize(window)
            ),
            operation = "store remote context window '$key'"
        ) { responseBody ->
            deserialize<ContextWindow>(responseBody, useRepair = false)
        }
    }

    /**
     * Delete a context window from the remote server.
     *
     * @param key Page key to remove.
     * @return Typed success or failure for the delete operation.
     */
    suspend fun deleteContextWindow(key: String): MemoryOperationResult<Unit>
    {
        val result = executeRequest(
            pathSegments = listOf("context", "bank", key),
            method = HttpMethod.Delete
        )

        if(result is MemoryOperationResult.Success)
        {
            invalidatePageKeyCache()
        }

        return mapUnitResult(result)
    }

    /**
     * Remotely query the lorebook of a context window.
     *
     * @param key Page key to query.
     * @param query Optional text query.
     * @param minWeight Minimum lore weight to include.
     * @param requiredKeys Required lorebook keys.
     * @param aliasKeys Alias-key filters.
     * @param extractRegex Optional extraction regex.
     * @return Typed result containing the query matches.
     */
    suspend fun queryLorebook(
        key: String,
        query: String = "",
        minWeight: Int = Int.MIN_VALUE,
        requiredKeys: List<String> = emptyList(),
        aliasKeys: List<String> = emptyList(),
        extractRegex: String = ""
    ): MemoryOperationResult<List<LoreBookQueryResult>>
    {
        return parseResponse(
            executeRequest(
                pathSegments = listOf("context", "bank", key, "query"),
                method = HttpMethod.Get,
                queryParameters = listOf(
                    "query" to query,
                    "minWeight" to minWeight.toString(),
                    "extractRegex" to extractRegex,
                    "requiredKeys" to requiredKeys.joinToString(","),
                    "aliasKeys" to aliasKeys.joinToString(",")
                )
            ),
            operation = "query remote lorebook '$key'"
        ) { responseBody ->
            deserialize<List<LoreBookQueryResult>>(responseBody, useRepair = false)
        }
    }

    /**
     * Remotely simulate lorebook triggers for a given text.
     *
     * @param key Page key to evaluate.
     * @param text Text to simulate.
     * @return Typed result containing the triggered lorebook keys.
     */
    suspend fun simulateLorebookTrigger(key: String, text: String): MemoryOperationResult<List<String>>
    {
        return parseResponse(
            executeRequest(
                pathSegments = listOf("context", "bank", key, "simulate"),
                method = HttpMethod.Get,
                queryParameters = listOf("text" to text)
            ),
            operation = "simulate remote lorebook trigger '$key'"
        ) { responseBody ->
            deserialize<List<String>>(responseBody, useRepair = false)
        }
    }

    /**
     * Retrieve all todo-list keys from the remote server.
     *
     * @return Typed result containing the remote todo-key list.
     */
    suspend fun getTodoListKeys(): MemoryOperationResult<List<String>>
    {
        readListCache(todoKeyCache)?.let { cachedKeys ->
            return MemoryOperationResult.Success(cachedKeys)
        }

        return parseResponse(
            executeRequest(
                pathSegments = listOf("context", "todo", "keys"),
                method = HttpMethod.Get
            ),
            operation = "list remote todo keys"
        ) { responseBody ->
            deserialize<List<String>>(responseBody, useRepair = false)
        }.also { result ->
            if(result is MemoryOperationResult.Success)
            {
                todoKeyCache.set(cachedValue(result.value))
            }
        }
    }

    /**
     * Retrieve a todo list from the remote server.
     *
     * @param key Todo key to retrieve.
     * @return Typed result containing the requested todo list.
     */
    suspend fun getTodoList(key: String): MemoryOperationResult<TodoList>
    {
        return parseResponse(
            executeRequest(
                pathSegments = listOf("context", "todo", key),
                method = HttpMethod.Get
            ),
            operation = "fetch remote todo list '$key'"
        ) { responseBody ->
            deserialize<TodoList>(responseBody, useRepair = false)
        }
    }

    /**
     * Send a todo list to the remote server.
     *
     * @param key Todo key to write.
     * @param todoList Todo list to persist.
     * @return Typed result containing the stored todo list returned by the server.
     */
    suspend fun emplaceTodoList(key: String, todoList: TodoList): MemoryOperationResult<TodoList>
    {
        invalidateTodoKeyCache()
        return parseResponse(
            executeRequest(
                pathSegments = listOf("context", "todo", key),
                method = HttpMethod.Post,
                requestBody = serialize(todoList)
            ),
            operation = "store remote todo list '$key'"
        ) { responseBody ->
            deserialize<TodoList>(responseBody, useRepair = false)
        }
    }

    /**
     * Delete a todo list from the remote server.
     *
     * @param key Todo key to remove.
     * @return Typed success or failure for the delete operation.
     */
    suspend fun deleteTodoList(key: String): MemoryOperationResult<Unit>
    {
        val result = executeRequest(
            pathSegments = listOf("context", "todo", key),
            method = HttpMethod.Delete
        )

        if(result is MemoryOperationResult.Success)
        {
            invalidateTodoKeyCache()
        }

        return mapUnitResult(result)
    }

    /**
     * Get all lock keys from the remote server.
     *
     * @return Typed result containing all visible lock keys.
     */
    suspend fun getLockKeys(): MemoryOperationResult<Set<String>>
    {
        readListCache(lockKeyCache)?.let { cachedKeys ->
            return MemoryOperationResult.Success(cachedKeys)
        }

        return parseResponse(
            executeRequest(
                pathSegments = listOf("context", "lock", "keys"),
                method = HttpMethod.Get
            ),
            operation = "list remote lock keys"
        ) { responseBody ->
            deserialize<Set<String>>(responseBody, useRepair = false)
        }.also { result ->
            if(result is MemoryOperationResult.Success)
            {
                lockKeyCache.set(cachedValue(result.value))
            }
        }
    }

    /**
     * Check if a key is locked on the remote server.
     *
     * @param key Lorebook key to inspect.
     * @return Typed result containing the remote lock state.
     */
    suspend fun isKeyLocked(key: String): MemoryOperationResult<Boolean>
    {
        readBooleanCache(keyLockCache, key)?.let { cachedState ->
            return MemoryOperationResult.Success(cachedState)
        }

        return parseResponse(
            executeRequest(
                pathSegments = listOf("context", "lock", key, "state"),
                method = HttpMethod.Get
            ),
            operation = "check remote key lock '$key'"
        ) { responseBody ->
            responseBody.trim().toBooleanStrictOrNull()
        }.also { result ->
            if(result is MemoryOperationResult.Success)
            {
                keyLockCache[key] = cachedValue(result.value)
            }
        }
    }

    /**
     * Check if a page is locked on the remote server.
     *
     * @param pageKey Page key to inspect.
     * @return Typed result containing the remote page-lock state.
     */
    suspend fun isPageLocked(pageKey: String): MemoryOperationResult<Boolean>
    {
        readBooleanCache(pageLockCache, pageKey)?.let { cachedState ->
            return MemoryOperationResult.Success(cachedState)
        }

        return parseResponse(
            executeRequest(
                pathSegments = listOf("context", "lock", "page", pageKey, "state"),
                method = HttpMethod.Get
            ),
            operation = "check remote page lock '$pageKey'"
        ) { responseBody ->
            responseBody.trim().toBooleanStrictOrNull()
        }.also { result ->
            if(result is MemoryOperationResult.Success)
            {
                pageLockCache[pageKey] = cachedValue(result.value)
            }
        }
    }

    /**
     * Add a lock on the remote server.
     *
     * @param request Lock request to submit.
     * @return Typed success or failure for the lock creation.
     */
    suspend fun addLock(request: LockRequest): MemoryOperationResult<Unit>
    {
        val result = executeRequest(
            pathSegments = listOf("context", "lock"),
            method = HttpMethod.Post,
            requestBody = serialize(request)
        )

        if(result is MemoryOperationResult.Success)
        {
            val now = System.currentTimeMillis()
            if(request.isPageKey)
            {
                pageLockCache[request.key] = CachedRemoteValue(request.lockState, now)
            }
            else
            {
                keyLockCache[request.key] = CachedRemoteValue(request.lockState, now)
            }
            lockKeyCache.set(null)
        }

        return mapUnitResult(result)
    }

    /**
     * Remove a lock from the remote server.
     *
     * @param key Lock key to remove.
     * @return Typed success or failure for the delete operation.
     */
    suspend fun removeLock(key: String): MemoryOperationResult<Unit>
    {
        val result = executeRequest(
            pathSegments = listOf("context", "lock", key),
            method = HttpMethod.Delete
        )

        if(result is MemoryOperationResult.Success)
        {
            keyLockCache.remove(key)
            pageLockCache.remove(key)
            lockKeyCache.set(null)
        }

        return mapUnitResult(result)
    }

    /**
     * Update a lock's state on the remote server.
     *
     * @param key Lock key to update.
     * @param lockState New lock state to store.
     * @return Typed success or failure for the update operation.
     */
    suspend fun updateLockState(key: String, lockState: Boolean): MemoryOperationResult<Unit>
    {
        val result = executeRequest(
            pathSegments = listOf("context", "lock", key, "state"),
            method = HttpMethod.Post,
            requestBody = lockState.toString()
        )

        if(result is MemoryOperationResult.Success)
        {
            val now = System.currentTimeMillis()
            keyLockCache[key] = CachedRemoteValue(lockState, now)
            pageLockCache[key] = CachedRemoteValue(lockState, now)
            lockKeyCache.set(null)
        }

        return mapUnitResult(result)
    }

    /**
     * Clear all local remote-memory caches.
     * Useful in tests and after configuration changes.
     */
    fun clearCaches()
    {
        keyLockCache.clear()
        pageLockCache.clear()
        pageKeyCache.set(null)
        todoKeyCache.set(null)
        lockKeyCache.set(null)
    }

    /**
     * Execute an HTTP request against the memory server and capture a typed success or failure.
     * Transport exceptions are retried; typed server failures are returned directly.
     *
     * @param pathSegments Path segments under the configured base URL.
     * @param method HTTP method to execute.
     * @param queryParameters Optional query parameters.
     * @param requestBody Optional request body.
     * @return Typed raw response body or typed failure.
     */
    private suspend fun executeRequest(
        pathSegments: List<String>,
        method: HttpMethod,
        queryParameters: List<Pair<String, String>> = emptyList(),
        requestBody: String? = null
    ): MemoryOperationResult<String>
    {
        var lastFailure: MemoryOperationResult.Failure? = null

        for(attempt in 1..MAX_RETRIES)
        {
            val client = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    connectTimeoutMillis = REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MS
                }
            }
            try
            {
                val response = client.request {
                    url {
                        takeFrom(getBaseUrl())
                        pathSegments.forEach { pathSegment ->
                            appendPathSegments(pathSegment)
                        }
                        queryParameters.forEach { (parameterName, parameterValue) ->
                            parameters.append(parameterName, parameterValue)
                        }
                    }
                    this.method = method
                    accept(ContentType.Application.Json)
                    getAuthToken().takeIf { authToken -> authToken.isNotEmpty() }?.let { authToken ->
                        header(HttpHeaders.Authorization, "Bearer $authToken")
                    }
                    if(requestBody != null)
                    {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(requestBody)
                    }
                }

                val responseBody = response.bodyAsText()
                if(response.status.value in 200..299)
                {
                    return MemoryOperationResult.Success(responseBody)
                }

                val failure = buildFailure(response.status, responseBody)
                if(response.status.value in 500..599 && attempt < MAX_RETRIES)
                {
                    lastFailure = failure
                    delay(RETRY_DELAY_MS * attempt)
                    continue
                }

                return failure
            }
            catch(exception: Exception)
            {
                lastFailure = MemoryOperationResult.Failure(
                    statusCode = null,
                    error = MemoryErrorResponse(
                        errorType = MemoryErrorType.transport,
                        message = exception.message ?: "Unknown transport failure while calling remote memory."
                    )
                )
                if(attempt < MAX_RETRIES)
                {
                    delay(RETRY_DELAY_MS * attempt)
                }
            }
            finally
            {
                client.close()
            }
        }

        return lastFailure ?: MemoryOperationResult.Failure(
            statusCode = null,
            error = MemoryErrorResponse(
                errorType = MemoryErrorType.unknown,
                message = "Unknown remote-memory failure."
            )
        )
    }

    /**
     * Parse a successful response body into [T], or return a typed serialization failure.
     *
     * @param rawResult Raw HTTP result.
     * @param operation Description of the remote operation being parsed.
     * @param parser Parser that converts the response body into the desired type.
     * @return Typed parsed value or typed failure.
     */
    private fun <T> parseResponse(
        rawResult: MemoryOperationResult<String>,
        operation: String,
        parser: (String) -> T?
    ): MemoryOperationResult<T>
    {
        return when(rawResult)
        {
            is MemoryOperationResult.Success ->
            {
                val parsedValue = parser(rawResult.value)
                if(parsedValue == null)
                {
                    MemoryOperationResult.Failure(
                        statusCode = HttpStatusCode.OK,
                        error = MemoryErrorResponse(
                            errorType = MemoryErrorType.serialization,
                            message = "Failed to parse remote-memory response for $operation."
                        )
                    )
                }
                else
                {
                    MemoryOperationResult.Success(parsedValue)
                }
            }

            is MemoryOperationResult.Failure -> rawResult
        }
    }

    /**
     * Convert a raw string result into a unit-only success or pass through the typed failure.
     *
     * @param rawResult Raw HTTP result.
     * @return Typed unit success or typed failure.
     */
    private fun mapUnitResult(rawResult: MemoryOperationResult<String>): MemoryOperationResult<Unit>
    {
        return when(rawResult)
        {
            is MemoryOperationResult.Success -> MemoryOperationResult.Success(Unit)
            is MemoryOperationResult.Failure -> rawResult
        }
    }

    /**
     * Build a typed failure from the HTTP status and response body.
     *
     * @param statusCode HTTP status returned by the memory server.
     * @param responseBody Raw response body.
     * @return Typed failure value.
     */
    private fun buildFailure(statusCode: HttpStatusCode, responseBody: String): MemoryOperationResult.Failure
    {
        val parsedError = deserialize<MemoryErrorResponse>(responseBody, useRepair = false)
        return MemoryOperationResult.Failure(
            statusCode = statusCode,
            error = parsedError ?: MemoryErrorResponse(
                errorType = defaultErrorType(statusCode),
                message = responseBody.ifEmpty { "Remote memory request failed with status ${statusCode.value}." }
            )
        )
    }

    /**
     * Map an HTTP status code into the default remote-memory error type.
     *
     * @param statusCode HTTP status returned by the server.
     * @return Default error type for that status.
     */
    private fun defaultErrorType(statusCode: HttpStatusCode): MemoryErrorType
    {
        return when(statusCode)
        {
            HttpStatusCode.BadRequest -> MemoryErrorType.badRequest
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> MemoryErrorType.auth
            HttpStatusCode.NotFound -> MemoryErrorType.notFound
            HttpStatusCode.Conflict -> MemoryErrorType.conflict
            else -> if(statusCode.value >= 500) MemoryErrorType.server else MemoryErrorType.unknown
        }
    }

    /**
     * Read a boolean cache entry when it is still fresh.
     *
     * @param cache Cache map to inspect.
     * @param key Key to load.
     * @return Cached value when still valid, otherwise null.
     */
    private fun readBooleanCache(
        cache: ConcurrentHashMap<String, CachedRemoteValue<Boolean>>,
        key: String
    ): Boolean?
    {
        val cachedValue = cache[key] ?: return null
        if(isExpired(cachedValue.createdAtMillis))
        {
            cache.remove(key, cachedValue)
            return null
        }

        return cachedValue.value
    }

    /**
     * Read a list-style cache entry when it is still fresh.
     *
     * @param cache Cache reference to inspect.
     * @return Cached value when still valid, otherwise null.
     */
    private fun <T> readListCache(cache: AtomicReference<CachedRemoteValue<T>?>): T?
    {
        val cachedValue = cache.get() ?: return null
        if(isExpired(cachedValue.createdAtMillis))
        {
            cache.compareAndSet(cachedValue, null)
            return null
        }

        return cachedValue.value
    }

    /**
     * Create a new cache entry stamped with the current time.
     *
     * @param value Value to cache.
     * @return Fresh cache entry.
     */
    private fun <T> cachedValue(value: T): CachedRemoteValue<T>
    {
        return CachedRemoteValue(value, System.currentTimeMillis())
    }

    /**
     * Check whether a cache entry is older than the configured TTL.
     *
     * @param createdAtMillis Timestamp when the cache entry was written.
     * @return True when the cache entry should be treated as stale.
     */
    private fun isExpired(createdAtMillis: Long): Boolean
    {
        return System.currentTimeMillis() - createdAtMillis >= CACHE_TTL_MS
    }

    /**
     * Invalidate the remote page-key cache.
     */
    private fun invalidatePageKeyCache()
    {
        pageKeyCache.set(null)
    }

    /**
     * Invalidate the remote todo-key cache.
     */
    private fun invalidateTodoKeyCache()
    {
        todoKeyCache.set(null)
    }
}
