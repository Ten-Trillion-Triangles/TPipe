package com.TTT.Context

import com.TTT.Config.TPipeConfig
import com.TTT.module
import com.TTT.Util.deepCopy
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Type definition for a retrieval function that can be bound to a context bank key.
 * This allows for custom logic to fetch context from external sources like databases or APIs.
 *
 * @param key The context bank key being requested.
 * @return A [ContextWindow] if retrieval was successful, or null if it failed.
 */
typealias RetrievalFunction = suspend (String) -> ContextWindow?

/**
 * Type definition for a write back function that can be bound to a context bank key.
 * This allows for custom logic to save context to external sources like databases or APIs.
 *
 * @param key The context bank key being written.
 * @param window The context window to write.
 * @return true if the write back was successful, false otherwise.
 */
typealias WriteBackFunction = suspend (String, ContextWindow) -> Boolean

/**
 * Singleton that holds TPipe's global context window system. Each pipe has its own context window object which
 * allows the pipe to control and manipulate the context the llm sees when injecting data into a prompt. Pipes can then
 * write to this global context bank to update the global state of whatever job the pipeline is doing. The pipelines
 * themselves, can also write to this ContextBank to allow multiple pipelines to share context in parallel.
 *
 * @see ContextWindow
 * @see LoreBook
 * @see Dictionary
 * @see Pipe
 * @see com.TTT.Pipeline.Pipeline
 *
 */
object ContextBank
{
    /**
     * Currently loaded context window from the bank. This is intended to make it easy for the coder to
     * address and manipulate current context without having to fiddle with the map keys.
     */
    @Volatile
    private var bankedContextWindow = ContextWindow()

    /**
     * Banked context windows to allow for TPipe to manage multiple separate and distinct context windows at once.
     */
    private val bank = ConcurrentHashMap<String, ContextWindow>()

    /**
     * Stores todo lists as page keys to active todo lists. Allows for multiple agents to access this and
     * update, or view the todo lists as sandboxed, or global tasks.
     */
    private val todoList = ConcurrentHashMap<String, TodoList>()

    /**
     * Mutex object for managing swapping the banked context window.
     */
    val swapMutex = Mutex()

    /**
     * Mutex used for locking access to the bank so that multiple coroutines can safely update the bank.
     */
    val bankMutex = Mutex()

    /**
     * Mutex for accessing the todo list system in this context bank.
     */
    val todoMutex = Mutex()

    /**
     * Mutex used for coordinating cache policy and eviction decisions across all page keys.
     */
    private val cacheMutex = Mutex()

    /**
     * Per-page locks that allow unrelated page keys to be read and mutated concurrently.
     */
    private val pageMutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Per-todo locks that allow unrelated todo keys to be read and mutated concurrently.
     */
    private val todoMutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Storage metadata for tracking storage modes and access patterns.
     * Maps keys to their storage configuration and statistics.
     */
    private val storageMetadata = ConcurrentHashMap<String, StorageMetadata>()

    /**
     * Cache configuration controlling eviction policies and memory limits.
     */
    @Volatile
    private var cacheConfig = CacheConfig()

    /**
     * Map of retrieval functions bound to specific context bank keys.
     * When a key with a bound function is requested, the function is executed to fetch the data.
     * Uses ConcurrentHashMap for thread-safe access.
     */
    private val retrievalFunctions = ConcurrentHashMap<String, RetrievalFunction>()

    /**
     * Map of write back functions bound to specific context bank keys.
     * When a key with a bound function is written, the function is executed to save the data.
     * Uses ConcurrentHashMap for thread-safe access.
     */
    private val writeBackFunctions = ConcurrentHashMap<String, WriteBackFunction>()

    /**
     * Resolve the mutex for a specific context page key.
     *
     * @param key The page key that needs coordinated access.
     * @return The mutex dedicated to that page key.
     */
    private fun getPageMutex(key: String): Mutex
    {
        return pageMutexes.computeIfAbsent(key) { Mutex() }
    }

    /**
     * Resolve the mutex for a specific todo list key.
     *
     * @param key The todo list key that needs coordinated access.
     * @return The mutex dedicated to that todo key.
     */
    private fun getTodoMutex(key: String): Mutex
    {
        return todoMutexes.computeIfAbsent(key) { Mutex() }
    }

    /**
     * Create the snapshot that should be exposed to a caller for a context window.
     *
     * @param context Context window currently stored in the bank.
     * @param copy When true, return a deep copy instead of the shared reference.
     * @return Caller-facing context window value.
     */
    private fun snapshotContextWindow(context: ContextWindow, copy: Boolean): ContextWindow
    {
        return if(copy) context.deepCopy() else context
    }

    /**
     * Create the snapshot that should be exposed to a caller for a todo list.
     *
     * @param list Todo list currently stored in the bank.
     * @param copy When true, return a deep copy instead of the shared reference.
     * @return Caller-facing todo list value.
     */
    private fun snapshotTodoList(list: TodoList, copy: Boolean): TodoList
    {
        return if(copy) list.deepCopy() else list
    }

    /**
     * Load a context window for [key] while the caller already holds that page key's mutex.
     *
     * @param key The page key being loaded.
     * @param mode The storage mode for the page.
     * @return The loaded or cached context window.
     */
    private fun loadContextWindowForKeyLocked(key: String, mode: StorageMode): ContextWindow
    {
        bank[key]?.let { return it }

        val diskPath = "${TPipeConfig.getLorebookDir()}/${key}.bank"
        if(!File(diskPath).exists())
        {
            return ContextWindow()
        }

        val context = deserialize<ContextWindow>(MemoryPersistence.readMemoryFile(diskPath)) ?: ContextWindow()

        when(mode)
        {
            StorageMode.DISK_ONLY ->
            {
                return context
            }

            StorageMode.DISK_WITH_CACHE, StorageMode.MEMORY_AND_DISK, StorageMode.MEMORY_ONLY ->
            {
                bank.putIfAbsent(key, context)
            }

            StorageMode.REMOTE -> { /* Handled by caller. */ }
        }

        return bank[key] ?: context
    }

    /**
     * Load a todo list for [key] while the caller already holds that todo key's mutex.
     *
     * @param key The todo-list key being loaded.
     * @param mode The storage mode for the todo list.
     * @return The loaded or cached todo list.
     */
    private fun loadTodoListForKeyLocked(key: String, mode: StorageMode): TodoList
    {
        todoList[key]?.let { return it }

        val fileContents = MemoryPersistence.readMemoryFile("${TPipeConfig.getTodoListDir()}/${key}.todo")
        if(fileContents.isEmpty())
        {
            return TodoList()
        }

        val result = deserialize<TodoList>(fileContents) ?: TodoList()

        when(mode)
        {
            StorageMode.DISK_ONLY ->
            {
                return result
            }

            StorageMode.DISK_WITH_CACHE, StorageMode.MEMORY_AND_DISK, StorageMode.MEMORY_ONLY ->
            {
                todoList.putIfAbsent(key, result)
            }

            StorageMode.REMOTE -> { /* Handled by caller. */ }
        }

        return todoList[key] ?: result
    }

    /**
     * Update or create storage metadata for a key.
     *
     * @param key The context bank key
     * @param mode The storage mode for this key
     */
    private fun updateMetadata(key: String, mode: StorageMode)
    {
        storageMetadata.compute(key) { _, existing ->
            StorageMetadata(
                key = key,
                storageMode = mode,
                lastAccessed = System.currentTimeMillis(),
                accessCount = (existing?.accessCount ?: 0) + 1,
                sizeBytes = existing?.sizeBytes ?: 0L,
                version = existing?.version ?: 0L
            )
        }
    }

    /**
     * Track access to a key for cache statistics and eviction policy.
     *
     * @param key The context bank key being accessed
     */
    private fun trackAccess(key: String)
    {
        storageMetadata.computeIfPresent(key) { _, existing ->
            existing.copy(
                lastAccessed = System.currentTimeMillis(),
                accessCount = existing.accessCount + 1
            )
        }
    }

    /**
     * Enforce cache eviction policy based on configured limits.
     * Removes entries from memory when maxEntries or maxMemoryBytes limits are exceeded.
     */
    private suspend fun enforceEvictionPolicy()
    {
        cacheMutex.withLock {
            enforceEvictionPolicyLocked()
        }
    }

    /**
     * Enforce cache eviction policy while holding [cacheMutex].
     */
    private fun enforceEvictionPolicyLocked()
    {
        if(cacheConfig.evictionPolicy == EvictionPolicy.MANUAL)
            {
            return
            }

        while(bank.size > cacheConfig.maxEntries)
            {
            evictLeastValuableLocked()
            }

        var totalBytes = bank.values.sumOf { estimateSize(it) }

        while(totalBytes > cacheConfig.maxMemoryBytes && bank.isNotEmpty())
            {
            val evictedSize = evictLeastValuableLocked()
            totalBytes -= evictedSize
            }
    }

    /**
     * Evict the least valuable entry from memory based on configured eviction policy.
     * Only evicts entries with DISK_WITH_CACHE storage mode.
     *
     * @return Size in bytes of the evicted entry, or 0 if nothing was evicted
     */
    private fun evictLeastValuableLocked(): Long
    {
        val candidates = storageMetadata.values
            .filter { bank.containsKey(it.key) }
            .filter { it.storageMode == StorageMode.DISK_WITH_CACHE }

        if(candidates.isEmpty())
            {
            return 0L
            }

        val toEvict = when(cacheConfig.evictionPolicy)
            {
            EvictionPolicy.LRU -> candidates.minByOrNull { it.lastAccessed }
            EvictionPolicy.LFU -> candidates.minByOrNull { it.accessCount }
            EvictionPolicy.FIFO -> candidates.minByOrNull { it.key }
            else -> null
            }

        if(toEvict != null)
            {
            val size = estimateSize(bank[toEvict.key])
            bank.remove(toEvict.key)
            return size
            }

        return 0L
    }

    /**
     * Estimate the size in bytes of a context window.
     * Uses serialization length as approximation.
     *
     * @param window The context window to measure
     * @return Approximate size in bytes
     */
    private fun estimateSize(window: ContextWindow?): Long
    {
        if(window == null) return 0L
        return serialize(window).length.toLong()
    }


    /**
     * Retrieve the existing banked context window reference.
     * This is a blocking compatibility wrapper and should not be used from coroutine-heavy internal code.
     * Use [getBankedContextWindowSuspend] internally instead.
     */
    fun getBankedContextWindow() : ContextWindow
    {
        return runBlocking {
            getBankedContextWindowSuspend()
        }
    }

    /**
     * Get a copy of the existing banked context window.
     */
    fun copyBankedContextWindow() : ContextWindow?
    {
        return getBankedContextWindow()
    }

    /**
     * Retrieve the existing banked context window using the suspend-safe code path.
     *
     * @param copy When true, return a deep copy instead of the stored reference.
     * @return The caller-facing banked context window snapshot.
     */
    suspend fun getBankedContextWindowSuspend(copy: Boolean = true): ContextWindow
    {
        swapMutex.withLock {
            return snapshotContextWindow(bankedContextWindow, copy)
        }
    }

    /**
     * Retrieve the raw shared banked context window reference.
     * This is unsafe unless the caller guarantees exclusive access.
     */
    fun getBankedContextWindowReference() : ContextWindow
    {
        return bankedContextWindow
    }

    /**
     * replace or add a context window to the bank.
     *
     * Warning: Do not call this inside a coroutine without locking the mutex or using the withMutex version of this
     * function instead.
     *
     * @param key map key to replace
     * @param window Context window to replace the map key with.
     * @param mode Storage mode controlling memory and disk persistence behavior
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    fun emplace(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)
    {
        if(!skipRemote && (mode == StorageMode.REMOTE || TPipeConfig.useRemoteMemoryGlobally))
        {
            runBlocking {
                MemoryClient.emplaceContextWindow(key, window).requireValue("store remote context window '$key'")
            }
            return
        }

        val bankDir = "${TPipeConfig.getLorebookDir()}/${key}.bank"

        when(mode)
        {
            StorageMode.MEMORY_ONLY ->
            {
                bank[key] = window
            }

            StorageMode.MEMORY_AND_DISK ->
            {
                bank[key] = window
                MemoryPersistence.writeMemoryFile(bankDir, serialize(window))
            }

            StorageMode.DISK_ONLY ->
            {
                MemoryPersistence.writeMemoryFile(bankDir, serialize(window))
            }

            StorageMode.DISK_WITH_CACHE ->
            {
                MemoryPersistence.writeMemoryFile(bankDir, serialize(window))
                bank[key] = window
                runBlocking {
                    enforceEvictionPolicy()
                }
            }

            StorageMode.REMOTE -> { /* Handled above */ }
        }

        updateMetadata(key, mode)
    }

    /**
     * Backward compatible emplace overload using persistToDisk boolean.
     * Maps to storage modes: persistToDisk=true -> MEMORY_AND_DISK, persistToDisk=false -> MEMORY_ONLY
     *
     * @param key map key to replace
     * @param window Context window to replace the map key with.
     * @param persistToDisk If true, stores to both memory and disk
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    fun emplace(key: String, window: ContextWindow, persistToDisk: Boolean = false, skipRemote: Boolean = false)
    {
        val mode = if(persistToDisk) StorageMode.MEMORY_AND_DISK else StorageMode.MEMORY_ONLY
        emplace(key, window, mode, skipRemote)
    }

    /**
     * Suspend-safe emplace path for internal coroutine code.
     *
     * @param key Map key to replace.
     * @param window Context window to store.
     * @param mode Storage mode controlling memory and disk persistence behavior.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun emplaceSuspend(
        key: String,
        window: ContextWindow,
        mode: StorageMode,
        skipRemote: Boolean = false,
        useWriteBack: Boolean = true
    )
    {
        val writeBackFunction = if(useWriteBack) writeBackFunctions[key] else null
        if(writeBackFunction != null)
        {
            writeBackFunction(key, window)
            return
        }

        if(!skipRemote && (mode == StorageMode.REMOTE || TPipeConfig.useRemoteMemoryGlobally))
        {
            MemoryClient.emplaceContextWindow(key, window).requireValue("store remote context window '$key'")
            return
        }

        val storedWindow = window
        val bankDir = "${TPipeConfig.getLorebookDir()}/${key}.bank"

        getPageMutex(key).withLock {
            when(mode)
            {
                StorageMode.MEMORY_ONLY ->
                {
                    bank[key] = storedWindow
                }

                StorageMode.MEMORY_AND_DISK ->
                {
                    bank[key] = storedWindow
                    MemoryPersistence.writeMemoryFile(bankDir, serialize(storedWindow))
                }

                StorageMode.DISK_ONLY ->
                {
                    MemoryPersistence.writeMemoryFile(bankDir, serialize(storedWindow))
                }

                StorageMode.DISK_WITH_CACHE ->
                {
                    MemoryPersistence.writeMemoryFile(bankDir, serialize(storedWindow))
                    bank[key] = storedWindow
                }

                StorageMode.REMOTE -> { /* Handled above */ }
            }
        }

        updateMetadata(key, mode)

        if(mode == StorageMode.DISK_WITH_CACHE)
        {
            enforceEvictionPolicy()
        }
    }


    /**
     * Safely emplace a context window back using the mutex. This is the recommended way to emplace when possible.
     * This should always be used over the regular emplace if you are updating the context inside a pipe or pipeline.
     *
     * @param key map key to replace
     * @param window Context window to replace the map key with.
     * @param mode Storage mode controlling memory and disk persistence behavior
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun emplaceWithMutex(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)
    {
        bankMutex.withLock {
            emplaceSuspend(key, window, mode, skipRemote, useWriteBack = true)
        }
    }

    /**
     * Backward compatible emplaceWithMutex overload using persistToDisk boolean.
     *
     * @param key map key to replace
     * @param window Context window to replace the map key with.
     * @param persistToDisk If true, stores to both memory and disk
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun emplaceWithMutex(key: String, window: ContextWindow, persistToDisk: Boolean = false, skipRemote: Boolean = false)
    {
        bankMutex.withLock {
            val mode = if(persistToDisk) StorageMode.MEMORY_AND_DISK else StorageMode.MEMORY_ONLY
            emplaceSuspend(key, window, mode, skipRemote, useWriteBack = true)
        }
    }

    /**
     * Delete the key file that is holding a persisting context bank key.
     * @param key The key to delete.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    fun deletePersistingBankKey(key: String, skipRemote: Boolean = false) : Boolean
    {
        return runBlocking {
            deletePersistingBankKeySuspend(key, skipRemote)
        }
    }

    /**
     * Suspend-safe delete path for persisted context-bank files.
     *
     * @param key The key to delete.
     * @param skipRemote If true, skip remote delegation even if configured.
     * @return True if the delete succeeded.
     */
    suspend fun deletePersistingBankKeySuspend(key: String, skipRemote: Boolean = false) : Boolean
    {
        if(!skipRemote && (getStorageMode(key) == StorageMode.REMOTE || TPipeConfig.useRemoteMemoryGlobally))
        {
            return when(val deleteResult = MemoryClient.deleteContextWindow(key))
            {
                is MemoryOperationResult.Success -> true
                is MemoryOperationResult.Failure ->
                {
                    if(deleteResult.error.errorType == MemoryErrorType.notFound)
                    {
                        false
                    }
                    else
                    {
                        throw MemoryRemoteException("delete persisted remote context window '$key'", deleteResult)
                    }
                }
            }
        }

        val bankDir = "${TPipeConfig.getLorebookDir()}/${key}.bank"
        return getPageMutex(key).withLock {
            MemoryPersistence.deleteMemoryFile(bankDir)
        }
    }

    /**
     * Delete the key file that is holding a persisting context bank key, and lock with the bank mutex for thread
     * safety.
     * @param key The key to delete.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun deletePersistingBankKeyWithMutex(key: String, skipRemote: Boolean = false) : Boolean
    {
        bankMutex.withLock {
            return deletePersistingBankKeySuspend(key, skipRemote)
        }
    }

    /**
     * Check whether a context page currently exists in memory, as a retrieval binding, or on disk.
     *
     * @param key Page key to inspect.
     * @return True when the page exists locally.
     */
    suspend fun contextWindowExistsSuspend(key: String): Boolean
    {
        return getPageMutex(key).withLock {
            if(bank.containsKey(key) || retrievalFunctions.containsKey(key))
            {
                return@withLock true
            }

            File("${TPipeConfig.getLorebookDir()}/${key}.bank").exists()
        }
    }

    /**
     * Check whether a todo list currently exists in memory or on disk.
     *
     * @param key Todo key to inspect.
     * @return True when the todo list exists locally.
     */
    suspend fun todoListExistsSuspend(key: String): Boolean
    {
        return getTodoMutex(key).withLock {
            if(todoList.containsKey(key))
            {
                return@withLock true
            }

            File("${TPipeConfig.getTodoListDir()}/${key}.todo").exists()
        }
    }

    /**
     * Fully remove a context window from remote or local storage.
     * This clears persisted state, in-memory cache, and storage metadata together.
     *
     * @param key Page key to remove.
     * @param skipRemote If true, skip remote delegation even if configured.
     * @return True when the page existed and was removed, false otherwise.
     */
    suspend fun deleteContextWindowSuspend(key: String, skipRemote: Boolean = false): Boolean
    {
        if(!skipRemote && (getStorageMode(key) == StorageMode.REMOTE || TPipeConfig.useRemoteMemoryGlobally))
        {
            return when(val deleteResult = MemoryClient.deleteContextWindow(key))
            {
                is MemoryOperationResult.Success -> deleteContextWindowSuspend(key, skipRemote = true)
                is MemoryOperationResult.Failure ->
                {
                    if(deleteResult.error.errorType == MemoryErrorType.notFound)
                    {
                        deleteContextWindowSuspend(key, skipRemote = true)
                    }
                    else
                    {
                        throw MemoryRemoteException("delete remote context window '$key'", deleteResult)
                    }
                }
            }
        }

        return getPageMutex(key).withLock {
            val filePath = "${TPipeConfig.getLorebookDir()}/${key}.bank"
            val existedInMemory = bank.remove(key) != null
            val existedOnDisk = MemoryPersistence.deleteMemoryFile(filePath)
            val existedAsRetrievalBinding = retrievalFunctions.remove(key) != null
            writeBackFunctions.remove(key)
            storageMetadata.remove(key)
            existedInMemory || existedOnDisk || existedAsRetrievalBinding
        }
    }

    /**
     * Fully remove a todo list from remote or local storage.
     * This clears persisted state, in-memory cache, and storage metadata together.
     *
     * @param key Todo key to remove.
     * @param skipRemote If true, skip remote delegation even if configured.
     * @return True when the todo list existed and was removed, false otherwise.
     */
    suspend fun deleteTodoListSuspend(key: String, skipRemote: Boolean = false): Boolean
    {
        if(!skipRemote && (getStorageMode(key) == StorageMode.REMOTE || TPipeConfig.useRemoteMemoryGlobally))
        {
            return when(val deleteResult = MemoryClient.deleteTodoList(key))
            {
                is MemoryOperationResult.Success -> deleteTodoListSuspend(key, skipRemote = true)
                is MemoryOperationResult.Failure ->
                {
                    if(deleteResult.error.errorType == MemoryErrorType.notFound)
                    {
                        deleteTodoListSuspend(key, skipRemote = true)
                    }
                    else
                    {
                        throw MemoryRemoteException("delete remote todo list '$key'", deleteResult)
                    }
                }
            }
        }

        return getTodoMutex(key).withLock {
            val filePath = "${TPipeConfig.getTodoListDir()}/${key}.todo"
            val existedInMemory = todoList.remove(key) != null
            val existedOnDisk = MemoryPersistence.deleteMemoryFile(filePath)
            storageMetadata.remove(key)
            existedInMemory || existedOnDisk
        }
    }

    /**
     * Remove a context window from memory without deleting the disk file.
     * Useful for freeing memory while keeping data persisted.
     *
     * @param key The context bank key to evict from memory
     * @return true if the key was in memory and was removed, false otherwise
     */
    fun evictFromMemory(key: String): Boolean
    {
        return bank.remove(key) != null
    }

    /**
     * Thread-safe version of evictFromMemory().
     * Remove a context window from memory without deleting the disk file.
     *
     * @param key The context bank key to evict from memory
     * @return true if the key was in memory and was removed, false otherwise
     */
    suspend fun evictFromMemoryWithMutex(key: String): Boolean
    {
        bankMutex.withLock {
            return evictFromMemory(key)
            }
    }

    /**
     * Remove all context windows from memory without deleting disk files.
     * Useful for clearing memory while keeping all data persisted.
     */
    fun evictAllFromMemory()
    {
        bank.clear()
    }

    /**
     * Thread-safe version of evictAllFromMemory().
     * Remove all context windows from memory without deleting disk files.
     */
    suspend fun evictAllFromMemoryWithMutex()
    {
        bankMutex.withLock {
            evictAllFromMemory()
            }
    }

    /**
     * Configure cache policy for DISK_WITH_CACHE storage mode.
     * Controls memory limits and eviction behavior.
     *
     * @param config Cache configuration with memory limits and eviction policy
     */
    fun configureCachePolicy(config: CacheConfig)
    {
        runBlocking {
            configureCachePolicySuspend(config)
        }
    }

    /**
     * Suspend-safe cache configuration path.
     *
     * @param config Cache configuration with memory limits and eviction policy.
     */
    suspend fun configureCachePolicySuspend(config: CacheConfig)
    {
        cacheMutex.withLock {
            cacheConfig = config
            enforceEvictionPolicyLocked()
        }
    }

    /**
     * Get current cache statistics including memory usage and hit rates.
     *
     * @return Cache statistics snapshot
     */
    fun getCacheStatistics(): CacheStatistics
    {
        val memoryEntries = bank.size
        val diskOnlyEntries = storageMetadata.values.count { it.storageMode == StorageMode.DISK_ONLY }
        val totalBytes = bank.values.sumOf { estimateSize(it) }

        val totalAccesses = storageMetadata.values.sumOf { it.accessCount }
        val cacheHitRate = if(totalAccesses == 0) 0.0 else
            {
            val hits = storageMetadata.values
                .filter { bank.containsKey(it.key) }
                .sumOf { it.accessCount }
            hits.toDouble() / totalAccesses.toDouble()
            }

        return CacheStatistics(
            memoryEntries = memoryEntries,
            diskOnlyEntries = diskOnlyEntries,
            totalMemoryBytes = totalBytes,
            cacheHitRate = cacheHitRate
        )
    }

    /**
     * Clear all cached entries (DISK_WITH_CACHE mode only).
     * Does not affect MEMORY_ONLY or MEMORY_AND_DISK entries.
     */
    fun clearCache()
    {
        runBlocking {
            clearCacheSuspend()
        }
    }

    /**
     * Suspend-safe cache clear path.
     */
    suspend fun clearCacheSuspend()
    {
        cacheMutex.withLock {
            val diskWithCacheKeys = storageMetadata.values
                .filter { it.storageMode == StorageMode.DISK_WITH_CACHE }
                .map { it.key }

            for(key in diskWithCacheKeys)
            {
                bank.remove(key)
            }
        }
    }

    /**
     * Set the storage mode for a specific key.
     * This controls how the key is stored and cached.
     *
     * @param key The context bank key
     * @param mode The storage mode to apply
     */
    fun setStorageMode(key: String, mode: StorageMode)
    {
        updateMetadata(key, mode)
    }

    /**
     * Get the storage mode for a specific key.
     * Returns MEMORY_AND_DISK if no mode has been set (default for backward compatibility).
     *
     * @param key The context bank key
     * @return The storage mode for this key
     */
    fun getStorageMode(key: String): StorageMode
    {
        return storageMetadata[key]?.storageMode ?: StorageMode.MEMORY_AND_DISK
    }

    /**
     * Thread-safe version of setStorageMode().
     *
     * @param key The context bank key
     * @param mode The storage mode to apply
     */
    suspend fun setStorageModeWithMutex(key: String, mode: StorageMode)
    {
        bankMutex.withLock {
            setStorageMode(key, mode)
            }
    }


    /**
     * Update the banked context window with a new context.
     */
    fun updateBankedContext(newContext: ContextWindow)
    {
        runBlocking {
            updateBankedContextSuspend(newContext)
        }
    }

    /**
     * Safely update the banked context window using mutex.
     */
    suspend fun updateBankedContextWithMutex(newContext: ContextWindow)
    {
        updateBankedContextSuspend(newContext)
    }

    /**
     * Suspend-safe banked context update used by internal coroutine code.
     *
     * @param newContext New banked context snapshot to store.
     */
    suspend fun updateBankedContextSuspend(newContext: ContextWindow)
    {
        swapMutex.withLock {
            bankedContextWindow = newContext.deepCopy()
        }
    }


    /**
     * Bank swap the context window for one that is on another page.
     * Warning: Do not call this inside a coroutine or outside the main thread. Use the WithMutex version instead.
     *
     * @param key page key for the banked context window we want to pull into visibility.
     *
     * @see swapBankWithMutex
     */
    fun swapBank(key: String, copy: Boolean = true)
    {
        runBlocking {
            swapBankSuspend(key, copy)
        }
    }

    /**
     * Function to safely bank swap inside a coroutine or multithreaded environment.
     * @see swapBank
     */
    suspend fun swapBankWithMutex(key: String, copy: Boolean = true)
    {
        bankMutex.withLock {
            swapBankSuspend(key, copy)
        }
    }

    /**
     * Suspend-safe bank swap used by internal coroutine code.
     *
     * @param key Page key for the banked context window we want to pull into visibility.
     * @param copy When true, store a snapshot instead of the shared reference.
     */
    suspend fun swapBankSuspend(key: String, copy: Boolean = true)
    {
        val context = getContextFromBankSuspend(key, copy)
        swapMutex.withLock {
            bankedContextWindow = context
        }
    }

    /**
     * Safely retrieve a context window from the bank using mutex.
     */
    suspend fun getContextFromBankWithMutex(key: String, copy: Boolean = true, skipRemote: Boolean = false) : ContextWindow
    {
        bankMutex.withLock {
            return getContextFromBankSuspend(key, copy, skipRemote)
        }
    }

    /**
     * Safely retrieve a todo list from the bank using mutex.
     */
    suspend fun getPagedTodoListWithMutex(key: String, copy: Boolean = true, skipRemote: Boolean = false) : TodoList
    {
        todoMutex.withLock {
            return getPagedTodoListSuspend(key, copy, skipRemote)
        }
    }


    /**
     * Retrieve a banked context window directly. By default, this returns a copy for safety but can also return
     * a direct reference.
     *
     * @param key The page key for the bank
     * @param copy If true, a deep copy will be made using serialization. Otherwise, return the reference directly.
     * Defaults to true.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    fun getContextFromBank(key: String, copy: Boolean = true, skipRemote: Boolean = false) : ContextWindow
    {
        return runBlocking {
            getContextFromBankSuspend(key, copy, skipRemote)
        }
    }

    /**
     * Suspend-safe page retrieval path used by internal coroutine code.
     *
     * @param key The page key for the bank.
     * @param copy If true, return a deep copy instead of the shared reference.
     * @param skipRemote If true, skip remote delegation even if configured.
     * @return The requested context window or an empty [ContextWindow].
     */
    suspend fun getContextFromBankSuspend(key: String, copy: Boolean = true, skipRemote: Boolean = false) : ContextWindow
    {
        retrievalFunctions[key]?.let { function ->
            val context = function(key)
                ?: throw IllegalStateException("Retrieval function for key '$key' failed to return a context window.")

            return snapshotContextWindow(context, copy)
        }

        val mode = getStorageMode(key)
        if(!skipRemote && (mode == StorageMode.REMOTE || TPipeConfig.useRemoteMemoryGlobally))
        {
            return when(val remoteResult = MemoryClient.getContextWindow(key))
            {
                is MemoryOperationResult.Success -> snapshotContextWindow(remoteResult.value, copy)
                is MemoryOperationResult.Failure ->
                {
                    if(remoteResult.error.errorType == MemoryErrorType.notFound)
                    {
                        ContextWindow()
                    }
                    else
                    {
                        throw MemoryRemoteException("fetch remote context window '$key'", remoteResult)
                    }
                }
            }
        }

        if(ContextLock.isPageLockedSuspend(key, skipRemote))
        {
            return ContextWindow()
        }

        trackAccess(key)

        return getPageMutex(key).withLock {
            snapshotContextWindow(loadContextWindowForKeyLocked(key, mode), copy)
        }.also {
            if(mode == StorageMode.DISK_WITH_CACHE)
            {
                enforceEvictionPolicy()
            }
        }
    }

    /**
     * Mutate the shared in-memory context reference for a page while holding that page's mutex.
     * This is intended for metadata-only mutations that should not immediately persist to disk.
     *
     * @param key The page key to mutate.
     * @param skipRemote If true, skip remote delegation even if configured.
     * @param block Mutation to apply while the page lock is held.
     * @return The mutated shared context reference.
     */
    suspend fun withContextWindowReferenceSuspend(
        key: String,
        skipRemote: Boolean = false,
        block: (ContextWindow) -> Unit
    ): ContextWindow
    {
        return getPageMutex(key).withLock {
            val mode = getStorageMode(key)
            val context = if(!skipRemote && (mode == StorageMode.REMOTE || TPipeConfig.useRemoteMemoryGlobally))
            {
                MemoryClient.getContextWindow(key).requireValue("fetch remote context window '$key'")
            }
            else
            {
                loadContextWindowForKeyLocked(key, mode)
            }
            block(context)
            context
        }
    }

    /**
     * Perform an atomic read-modify-write on a context window while holding the page's mutex.
     *
     * @param key The page key to mutate.
     * @param mode Storage mode controlling persistence after the mutation.
     * @param skipRemote If true, skip remote delegation even if configured.
     * @param block Mutation to apply to the working snapshot before it is stored.
     * @return The stored context window snapshot after mutation.
     */
    suspend fun mutateContextWindowSuspend(
        key: String,
        mode: StorageMode = getStorageMode(key),
        skipRemote: Boolean = false,
        block: (ContextWindow) -> Unit
    ): ContextWindow
    {
        if(!skipRemote && (mode == StorageMode.REMOTE || TPipeConfig.useRemoteMemoryGlobally))
        {
            val remoteContext = when(val remoteResult = MemoryClient.getContextWindow(key))
            {
                is MemoryOperationResult.Success -> remoteResult.value
                is MemoryOperationResult.Failure ->
                {
                    if(remoteResult.error.errorType == MemoryErrorType.notFound)
                    {
                        ContextWindow()
                    }
                    else
                    {
                        throw MemoryRemoteException("fetch remote context window '$key' for mutation", remoteResult)
                    }
                }
            }
            block(remoteContext)
            MemoryClient.emplaceContextWindow(key, remoteContext).requireValue("store remote context window '$key'")
            return remoteContext.deepCopy()
        }

        return getPageMutex(key).withLock {
            val existingContext = loadContextWindowForKeyLocked(key, mode)

            val workingContext = existingContext.deepCopy()
            block(workingContext)

            when(mode)
            {
                StorageMode.MEMORY_ONLY ->
                {
                    bank[key] = workingContext
                }

                StorageMode.MEMORY_AND_DISK ->
                {
                    bank[key] = workingContext
                    MemoryPersistence.writeMemoryFile("${TPipeConfig.getLorebookDir()}/${key}.bank", serialize(workingContext))
                }

                StorageMode.DISK_ONLY ->
                {
                    MemoryPersistence.writeMemoryFile("${TPipeConfig.getLorebookDir()}/${key}.bank", serialize(workingContext))
                }

                StorageMode.DISK_WITH_CACHE ->
                {
                    MemoryPersistence.writeMemoryFile("${TPipeConfig.getLorebookDir()}/${key}.bank", serialize(workingContext))
                    bank[key] = workingContext
                }

                StorageMode.REMOTE -> { /* Handled above */ }
            }

            updateMetadata(key, mode)
            workingContext.deepCopy()
        }.also {
            if(mode == StorageMode.DISK_WITH_CACHE)
            {
                enforceEvictionPolicy()
            }
        }
    }

    /**
     * Access function to get all the pages that are stored inside the context bank.
     * @param skipRemote If true, skip remote keys even if configured.
     */
    fun getPageKeys(skipRemote: Boolean = false) : List<String>
    {
        return runBlocking {
            getPageKeysSuspend(skipRemote)
        }
    }

    /**
     * Suspend-safe retrieval of page keys for internal coroutine code.
     *
     * @param skipRemote If true, skip remote keys even if configured.
     * @return All visible page keys.
     */
    suspend fun getPageKeysSuspend(skipRemote: Boolean = false) : List<String>
    {
        val localKeys = (bank.keys + retrievalFunctions.keys).distinct()
        if(!skipRemote && (TPipeConfig.remoteMemoryEnabled || TPipeConfig.useRemoteMemoryGlobally))
        {
            val remoteKeys = MemoryClient.getPageKeys().requireValue("list remote context keys")
            return (localKeys + remoteKeys).distinct()
        }
        return localKeys
    }

    /**
     * Access function to get all the todo list keys that are stored inside the context bank.
     * @param skipRemote If true, skip remote keys even if configured.
     */
    fun getTodoListKeys(skipRemote: Boolean = false) : List<String>
    {
        return runBlocking {
            getTodoListKeysSuspend(skipRemote)
        }
    }

    /**
     * Suspend-safe retrieval of todo-list keys for internal coroutine code.
     *
     * @param skipRemote If true, skip remote keys even if configured.
     * @return All visible todo-list keys.
     */
    suspend fun getTodoListKeysSuspend(skipRemote: Boolean = false) : List<String>
    {
        val localKeys = todoList.keys.toList()
        if(!skipRemote && (TPipeConfig.remoteMemoryEnabled || TPipeConfig.useRemoteMemoryGlobally))
        {
            val remoteKeys = MemoryClient.getTodoListKeys().requireValue("list remote todo keys")
            return (localKeys + remoteKeys).distinct()
        }
        return localKeys
    }

    /**
     * Clear all banked context. Useful when some code is checking if this contains data or not and applies logic
     * if it does.
     */
    fun clearBankedContext()
    {
        runBlocking {
            updateBankedContextSuspend(ContextWindow())
        }
    }

    /**
     * Get a todo list by it's page key.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    fun getPagedTodoList(key: String, copy: Boolean = true, skipRemote: Boolean = false) : TodoList
    {
        return runBlocking {
            getPagedTodoListSuspend(key, copy, skipRemote)
        }
    }

    /**
     * Suspend-safe todo-list retrieval path used by internal coroutine code.
     *
     * @param key Page key for the todo list.
     * @param copy If true, return a deep copy instead of the shared reference.
     * @param skipRemote If true, skip remote delegation even if configured.
     * @return The requested todo list or an empty [TodoList].
     */
    suspend fun getPagedTodoListSuspend(key: String, copy: Boolean = true, skipRemote: Boolean = false) : TodoList
    {
        if(!skipRemote && (getStorageMode(key) == StorageMode.REMOTE || TPipeConfig.useRemoteMemoryGlobally))
        {
            return when(val remoteResult = MemoryClient.getTodoList(key))
            {
                is MemoryOperationResult.Success -> snapshotTodoList(remoteResult.value, copy)
                is MemoryOperationResult.Failure ->
                {
                    if(remoteResult.error.errorType == MemoryErrorType.notFound)
                    {
                        TodoList()
                    }
                    else
                    {
                        throw MemoryRemoteException("fetch remote todo list '$key'", remoteResult)
                    }
                }
            }
        }

        trackAccess(key)
        val mode = getStorageMode(key)

        return getTodoMutex(key).withLock {
            snapshotTodoList(loadTodoListForKeyLocked(key, mode), copy)
        }.also {
            if(mode == StorageMode.DISK_WITH_CACHE)
            {
                enforceTodoListEvictionPolicy()
            }
        }
    }

    /**
     * Emplace a new todo list into the context bank. Adding if it does not exist, or overwriting it if it does.
     * @param key Bank key to write into.
     * @param todoList [TodoList] to write into the page.
     * @param allowUpdatesOnly If true, only existing tasks on the list can be modified, no new tasks can be added.
     * Does not apply if the page is empty or does not exist yet.
     * @param allowCompletionsOnly If true, any existing tasks can only allow the isCompleted checkbox to be marked
     * true or false. No other changes to the task are allowed. Does not affect tasks that do not exist yet in the
     * task list.
     * @param persistToDisk If true, this task will be written directly to disk as well as memory.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    fun emplaceTodoList(
        key: String,
        todoList: TodoList,
        allowUpdatesOnly: Boolean = true,
        allowCompletionsOnly: Boolean = false,
        persistToDisk: Boolean = false,
        skipRemote: Boolean = false
    )
    {
        val mode = if(persistToDisk) StorageMode.MEMORY_AND_DISK else StorageMode.MEMORY_ONLY
        runBlocking {
            emplaceTodoListSuspend(key, todoList, mode, allowUpdatesOnly, allowCompletionsOnly, skipRemote)
        }
    }

    /**
     * Thread safe emplace call to emplace a todo list. Calls [emplaceTodoList] under the hood while invoking a mutex
     * lock for safety. Shares the same params as [emplaceTodoList].
     */
    suspend fun emplaceWithMutex(
        key: String,
        todoList: TodoList,
        allowUpdatesOnly: Boolean = true,
        allowCompletionsOnly: Boolean = false,
        persistToDisk: Boolean = false,
        skipRemote: Boolean = false
    )
    {
        todoMutex.withLock {
            val mode = if(persistToDisk) StorageMode.MEMORY_AND_DISK else StorageMode.MEMORY_ONLY
            emplaceTodoListSuspend(key, todoList, mode, allowUpdatesOnly, allowCompletionsOnly, skipRemote)
        }
    }

    /**
     * Emplace a TodoList with explicit storage mode control.
     *
     * @param key Bank key to write into
     * @param todoList TodoList to write into the page
     * @param mode Storage mode controlling memory and disk persistence behavior
     * @param allowUpdatesOnly If true, only existing tasks can be modified
     * @param allowCompletionsOnly If true, only isCompleted can be changed
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    fun emplaceTodoList(
        key: String,
        todoList: TodoList,
        mode: StorageMode,
        allowUpdatesOnly: Boolean = true,
        allowCompletionsOnly: Boolean = false,
        skipRemote: Boolean = false
    )
    {
        runBlocking {
            emplaceTodoListSuspend(key, todoList, mode, allowUpdatesOnly, allowCompletionsOnly, skipRemote)
        }
    }

    /**
     * Suspend-safe todo-list emplace path used by internal coroutine code.
     *
     * @param key Bank key to write into.
     * @param todoList TodoList to write into the page.
     * @param mode Storage mode controlling memory and disk persistence behavior.
     * @param allowUpdatesOnly If true, only existing tasks can be modified.
     * @param allowCompletionsOnly If true, only isCompleted can be changed.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun emplaceTodoListSuspend(
        key: String,
        todoList: TodoList,
        mode: StorageMode,
        allowUpdatesOnly: Boolean = true,
        allowCompletionsOnly: Boolean = false,
        skipRemote: Boolean = false
    )
    {
        if(!skipRemote && (mode == StorageMode.REMOTE || TPipeConfig.useRemoteMemoryGlobally))
        {
            MemoryClient.emplaceTodoList(key, todoList).requireValue("store remote todo list '$key'")
            return
        }

        val todoPath = TPipeConfig.getTodoListDir()
        val fullFilePath = "${todoPath}/${key}.todo"
        var todoListToEmplace = todoList

        getTodoMutex(key).withLock {
            val bankedTasks = ContextBank.todoList[key]
            if(bankedTasks != null)
            {
                todoListToEmplace = applyTodoListWriteProtection(todoListToEmplace, bankedTasks.deepCopy(), allowUpdatesOnly, allowCompletionsOnly)
            }

            when(mode)
            {
                StorageMode.MEMORY_ONLY ->
                {
                    ContextBank.todoList[key] = todoListToEmplace

                    // Maintain backward compatibility: persist if file already exists.
                    if(File(fullFilePath).exists())
                    {
                        MemoryPersistence.writeMemoryFile(fullFilePath, serialize(todoListToEmplace))
                    }
                }

                StorageMode.MEMORY_AND_DISK ->
                {
                    ContextBank.todoList[key] = todoListToEmplace
                    MemoryPersistence.writeMemoryFile(fullFilePath, serialize(todoListToEmplace))
                }

                StorageMode.DISK_ONLY ->
                {
                    MemoryPersistence.writeMemoryFile(fullFilePath, serialize(todoListToEmplace))
                }

                StorageMode.DISK_WITH_CACHE ->
                {
                    MemoryPersistence.writeMemoryFile(fullFilePath, serialize(todoListToEmplace))
                    ContextBank.todoList[key] = todoListToEmplace
                }

                StorageMode.REMOTE -> { /* Handled above */ }
            }
        }

        updateMetadata(key, mode)

        if(mode == StorageMode.DISK_WITH_CACHE)
        {
            enforceTodoListEvictionPolicy()
        }
    }

    /**
     * Apply write protection rules to TodoList updates.
     */
    private fun applyTodoListWriteProtection(
        todoList: TodoList,
        bankedTasks: TodoList,
        allowUpdatesOnly: Boolean,
        allowCompletionsOnly: Boolean
    ): TodoList
    {
        val validTaskNumbers = mutableListOf<Int>()

        if(allowUpdatesOnly)
            {
            for(task in todoList.tasks.tasks)
            {
                if(bankedTasks.find(task.taskNumber) != null)
                {
                    validTaskNumbers.add(task.taskNumber)
                }
            }
            }

        var todoListToEmplace = TodoList()

        if(validTaskNumbers.isNotEmpty())
            {
            for(number in validTaskNumbers)
            {
                val task = todoList.find(number)
                if(task != null) todoListToEmplace.tasks.tasks.add(task)
            }
            }
        else
            {
            todoListToEmplace = todoList
            }

        if(allowCompletionsOnly)
            {
            for(task in todoListToEmplace.tasks.tasks)
            {
                bankedTasks.find(task.taskNumber)?.isComplete = task.isComplete
            }
            return bankedTasks
            }
        else
            {
            for(task in todoListToEmplace.tasks.tasks)
            {
                if(bankedTasks.tasks.tasks.contains(task))
                {
                    bankedTasks.tasks.tasks[bankedTasks.tasks.tasks.indexOf(task)] = task
                }
                else
                {
                    bankedTasks.tasks.tasks.add(task)
                }
            }
            return bankedTasks
            }
    }

    /**
     * Enforce eviction policy for TodoList cache.
     */
    private suspend fun enforceTodoListEvictionPolicy()
    {
        cacheMutex.withLock {
            if(cacheConfig.evictionPolicy == EvictionPolicy.MANUAL)
            {
                return@withLock
            }

            while(todoList.size > cacheConfig.maxEntries)
            {
                evictLeastValuableTodoListLocked()
            }
        }
    }

    /**
     * Evict least valuable TodoList based on eviction policy.
     */
    private fun evictLeastValuableTodoListLocked()
    {
        val candidates = storageMetadata.values
            .filter { todoList.containsKey(it.key) }
            .filter { it.storageMode == StorageMode.DISK_WITH_CACHE }

        if(candidates.isEmpty()) return

        val toEvict = when(cacheConfig.evictionPolicy)
            {
            EvictionPolicy.LRU -> candidates.minByOrNull { it.lastAccessed }
            EvictionPolicy.LFU -> candidates.minByOrNull { it.accessCount }
            EvictionPolicy.FIFO -> candidates.minByOrNull { it.key }
            else -> null
            }

        toEvict?.let { todoList.remove(it.key) }
    }

    /**
     * Remove a TodoList from memory without deleting the disk file.
     * Useful for freeing memory while keeping data persisted.
     *
     * @param key The TodoList key to evict from memory
     * @return true if the key was in memory and was removed, false otherwise
     */
    fun evictTodoListFromMemory(key: String): Boolean
    {
        return todoList.remove(key) != null
    }

    /**
     * Thread-safe version of evictTodoListFromMemory().
     * Remove a TodoList from memory without deleting the disk file.
     *
     * @param key The TodoList key to evict from memory
     * @return true if the key was in memory and was removed, false otherwise
     */
    suspend fun evictTodoListFromMemoryWithMutex(key: String): Boolean
    {
        todoMutex.withLock {
            return evictTodoListFromMemory(key)
            }
    }

    /**
     * Remove all TodoLists from memory without deleting disk files.
     * Useful for clearing memory while keeping all data persisted.
     */
    fun evictAllTodoListsFromMemory()
    {
        todoList.clear()
    }

    /**
     * Thread-safe version of evictAllTodoListsFromMemory().
     * Remove all TodoLists from memory without deleting disk files.
     */
    suspend fun evictAllTodoListsFromMemoryWithMutex()
    {
        todoMutex.withLock {
            evictAllTodoListsFromMemory()
            }
    }

    /**
     * Enable remote hosting for this TPipe instance's memory.
     * This starts a Netty server on the specified port if it's not already running.
     * @param port The port to host the memory server on.
     */
    /**
     * Enable remote hosting for this TPipe instance's memory.
     * This starts a Netty server on the specified port if it's not already running.
     * @param port The port to host the memory server on.
     */
    fun enableRemoteHosting(port: Int = 8080)
    {
        TPipeConfig.remoteMemoryEnabled = true
        // In a library context, we usually assume the caller will start the Ktor engine,
        // but we provide a helper for standard Netty hosting.
        io.ktor.server.engine.embeddedServer(io.ktor.server.netty.Netty, port = port) {
            module()
            }.start(wait = false)
    }

    /**
     * Connect to a remote TPipe memory server.
     * @param url The base URL of the remote memory server.
     * @param token Optional authentication token.
     * @param useGlobally If true, all memory operations will delegate to the remote server regardless of StorageMode.
     */
    fun connectToRemoteMemory(url: String, token: String = "", useGlobally: Boolean = false)
    {
        TPipeConfig.remoteMemoryEnabled = true
        TPipeConfig.remoteMemoryUrl = url
        TPipeConfig.remoteMemoryAuthToken = token
        TPipeConfig.useRemoteMemoryGlobally = useGlobally
    }

    /**
     * Register a retrieval function for a specific context bank key.
     * When this key is requested, the retrieval function will be executed to fetch the context window.
     * retrieval function always wins and overrides local and disk data.
     *
     * @param key The context bank key to bind the function to.
     * @param function The [RetrievalFunction] to execute for this key.
     */
    fun registerRetrievalFunction(key: String, function: RetrievalFunction)
    {
        retrievalFunctions[key] = function
    }

    /**
     * Remove a registered retrieval function for a specific key.
     *
     * @param key The context bank key to remove the retrieval function from.
     */
    fun removeRetrievalFunction(key: String)
    {
        retrievalFunctions.remove(key)
    }

    /**
     * Clear all registered retrieval functions.
     */
    fun clearRetrievalFunctions()
    {
        retrievalFunctions.clear()
    }


    /**
     * Register a write back function for a specific context bank key.
     * When this key is written via emplaceWithMutex, the write back function will be executed to save the context window.
     * write back function always wins and overrides local and disk data.
     *
     * @param key The context bank key to bind the function to.
     * @param function The [WriteBackFunction] to execute for this key.
     */
    fun registerWriteBackFunction(key: String, function: WriteBackFunction)
    {
        writeBackFunctions[key] = function
    }

    /**
     * Remove a registered write back function for a specific key.
     *
     * @param key The context bank key to remove the write back function from.
     */
    fun removeWriteBackFunction(key: String)
    {
        writeBackFunctions.remove(key)
    }

    /**
     * Clear all registered write back functions.
     */
    fun clearWriteBackFunctions()
    {
        writeBackFunctions.clear()
    }
    /**
     * Perform a fetch-merge-save operation on a remote context window.
     * This helps resolve versioning conflicts by pulling the latest remote state and merging it locally before pushing back.
     *
     * @param key The context window key
     * @param localWindow The local window to merge into the remote state
     * @return True if the operation was successful
     */
    suspend fun fetchMergeSaveRemoteContext(key: String, localWindow: ContextWindow): Boolean
    {
        val remoteWindow = when(val remoteResult = MemoryClient.getContextWindow(key))
        {
            is MemoryOperationResult.Success -> remoteResult.value
            is MemoryOperationResult.Failure ->
            {
                if(remoteResult.error.errorType == MemoryErrorType.notFound)
                {
                    return MemoryClient.emplaceContextWindow(key, localWindow) is MemoryOperationResult.Success
                }
                throw MemoryRemoteException("fetch remote context window '$key' for merge-save", remoteResult)
            }
        }

        // Merge the remote window into the local window (or vice-versa, depending on strategy)
        // Here we merge remote into local to preserve local changes but gain remote updates
        localWindow.merge(remoteWindow)
        localWindow.version = maxOf(localWindow.version, remoteWindow.version) + 1

        return MemoryClient.emplaceContextWindow(key, localWindow) is MemoryOperationResult.Success
    }
}
