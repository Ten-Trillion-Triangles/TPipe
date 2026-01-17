package com.TTT.Context

import com.TTT.Config.TPipeConfig
import com.TTT.Util.deepCopy
import com.TTT.Util.deleteFile
import com.TTT.Util.deserialize
import com.TTT.Util.readStringFromFile
import com.TTT.Util.serialize
import com.TTT.Util.writeStringToFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File


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
    @Volatile
    private var bank = mutableMapOf<String, ContextWindow>()

    /**
     * Stores todo lists as page keys to active todo lists. Allows for multiple agents to access this and
     * update, or view the todo lists as sandboxed, or global tasks.
     */
    @Volatile
    private var todoList = mutableMapOf<String, TodoList>()

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
     * Storage metadata for tracking storage modes and access patterns.
     * Maps keys to their storage configuration and statistics.
     */
    @Volatile
    private var storageMetadata = mutableMapOf<String, StorageMetadata>()

    /**
     * Cache configuration controlling eviction policies and memory limits.
     */
    @Volatile
    private var cacheConfig = CacheConfig()

    /**
     * Update or create storage metadata for a key.
     *
     * @param key The context bank key
     * @param mode The storage mode for this key
     */
    private fun updateMetadata(key: String, mode: StorageMode)
    {
        val existing = storageMetadata[key]
        storageMetadata[key] = StorageMetadata(
            key = key,
            storageMode = mode,
            lastAccessed = System.currentTimeMillis(),
            accessCount = (existing?.accessCount ?: 0) + 1,
            sizeBytes = existing?.sizeBytes ?: 0L
        )
    }

    /**
     * Track access to a key for cache statistics and eviction policy.
     *
     * @param key The context bank key being accessed
     */
    private fun trackAccess(key: String)
    {
        val existing = storageMetadata[key] ?: return
        storageMetadata[key] = existing.copy(
            lastAccessed = System.currentTimeMillis(),
            accessCount = existing.accessCount + 1
        )
    }

    /**
     * Enforce cache eviction policy based on configured limits.
     * Removes entries from memory when maxEntries or maxMemoryBytes limits are exceeded.
     */
    private fun enforceEvictionPolicy()
    {
        if (cacheConfig.evictionPolicy == EvictionPolicy.MANUAL)
        {
            return
        }

        while (bank.size > cacheConfig.maxEntries)
        {
            evictLeastValuable()
        }

        var totalBytes = bank.values.sumOf { estimateSize(it) }

        while (totalBytes > cacheConfig.maxMemoryBytes && bank.isNotEmpty())
        {
            val evictedSize = evictLeastValuable()
            totalBytes -= evictedSize
        }
    }

    /**
     * Evict the least valuable entry from memory based on configured eviction policy.
     * Only evicts entries with DISK_WITH_CACHE storage mode.
     *
     * @return Size in bytes of the evicted entry, or 0 if nothing was evicted
     */
    private fun evictLeastValuable(): Long
    {
        val candidates = storageMetadata.values
            .filter { bank.containsKey(it.key) }
            .filter { it.storageMode == StorageMode.DISK_WITH_CACHE }

        if (candidates.isEmpty())
        {
            return 0L
        }

        val toEvict = when (cacheConfig.evictionPolicy)
        {
            EvictionPolicy.LRU -> candidates.minByOrNull { it.lastAccessed }
            EvictionPolicy.LFU -> candidates.minByOrNull { it.accessCount }
            EvictionPolicy.FIFO -> candidates.minByOrNull { it.key }
            else -> null
        }

        if (toEvict != null)
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
        if (window == null) return 0L
        return serialize(window).length.toLong()
    }


    /**
     * Retrieve the existing banked context window reference.
     * Warning: Do not call this if you are updating the context window inside of pipes or coroutines. Use copy
     * instead to collect the window for safety reasons.
     */
    fun getBankedContextWindow() : ContextWindow
    {
        return bankedContextWindow
    }

    /**
     * Get a copy of the existing banked context window. This should be used when inside a coroutine or alternate
     * thread.
     */
    fun copyBankedContextWindow() : ContextWindow?
    {
        val json = serialize(bankedContextWindow)
        return deserialize<ContextWindow>(json) as? ContextWindow
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
     */
    fun emplace(key: String, window: ContextWindow, mode: StorageMode)
    {
        val bankDir = "${TPipeConfig.getLorebookDir()}/${key}.bank"

        when (mode)
        {
            StorageMode.MEMORY_ONLY ->
            {
                bank[key] = window
            }

            StorageMode.MEMORY_AND_DISK ->
            {
                bank[key] = window
                val value = serialize(window)
                writeStringToFile(bankDir, value)
            }

            StorageMode.DISK_ONLY ->
            {
                val value = serialize(window)
                writeStringToFile(bankDir, value)
            }

            StorageMode.DISK_WITH_CACHE ->
            {
                val value = serialize(window)
                writeStringToFile(bankDir, value)
                bank[key] = window
                enforceEvictionPolicy()
            }
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
     */
    fun emplace(key: String, window: ContextWindow, persistToDisk: Boolean = false)
    {
        val mode = if (persistToDisk) StorageMode.MEMORY_AND_DISK else StorageMode.MEMORY_ONLY
        emplace(key, window, mode)
    }


    /**
     * Safely emplace a context window back using the mutex. This is the recommended way to emplace when possible.
     * This should always be used over the regular emplace if you are updating the context inside a pipe or pipeline.
     *
     * @param key map key to replace
     * @param window Context window to replace the map key with.
     * @param mode Storage mode controlling memory and disk persistence behavior
     */
    suspend fun emplaceWithMutex(key: String, window: ContextWindow, mode: StorageMode)
    {
        bankMutex.withLock {
            emplace(key, window, mode)
        }
    }

    /**
     * Backward compatible emplaceWithMutex overload using persistToDisk boolean.
     *
     * @param key map key to replace
     * @param window Context window to replace the map key with.
     * @param persistToDisk If true, stores to both memory and disk
     */
    suspend fun emplaceWithMutex(key: String, window: ContextWindow, persistToDisk: Boolean = false)
    {
        bankMutex.withLock {
            emplace(key, window, persistToDisk)
        }
    }

    /**
     * Delete the key file that is holding a persisting context bank key.
     */
    fun deletePersistingBankKey(key: String) : Boolean
    {
        val bankDir = "${TPipeConfig.getLorebookDir()}/${key}.bank"
        return deleteFile(bankDir)
    }

    /**
     * Delete the key file that is holding a persisting context bank key, and lock with the bank mutex for thread
     * safety.
     */
    suspend fun deletePersistingBankKeyWithMutex(key: String) : Boolean
    {
        bankMutex.withLock {
            return deletePersistingBankKey(key)
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
        val existed = bank.containsKey(key)
        bank.remove(key)
        return existed
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
        cacheConfig = config
        enforceEvictionPolicy()
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
        val cacheHitRate = if (totalAccesses == 0) 0.0 else
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
        val diskWithCacheKeys = storageMetadata.values
            .filter { it.storageMode == StorageMode.DISK_WITH_CACHE }
            .map { it.key }

        for (key in diskWithCacheKeys)
        {
            bank.remove(key)
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
        bankedContextWindow = newContext
    }

    /**
     * Safely update the banked context window using mutex.
     */
    suspend fun updateBankedContextWithMutex(newContext: ContextWindow)
    {
        bankMutex.withLock {
            bankedContextWindow = newContext
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
        val context = bank[key] ?: ContextWindow()

        /**
         * By default, we want to copy it for safety, though this can be a much slower operation. If we do,
         * we'll use serialization to perform a deep copy and pass that to the swapped bank variable.
         */
        if(copy)
        {
            val json = serialize(context)
            val copyContext = deserialize<ContextWindow>(json)
            bankedContextWindow = copyContext ?: ContextWindow()
            return
        }

        //Otherwise, the banked window becomes a reference.
        bankedContextWindow = context
    }

    /**
     * Function to safely bank swap inside a coroutine or multithreaded environment.
     * @see swapBank
     */
    suspend fun swapBankWithMutex(key: String)
    {
        bankMutex.withLock {
            swapMutex.withLock {
                swapBank(key)
            }
        }
    }


    /**
     * Retrieve a banked context window directly. By default, this returns a copy for safety but can also return
     * a direct reference.
     *
     * @param key The page key for the bank
     * @param copy If true, a deep copy will be made using serialization. Otherwise, return the reference directly.
     * Defaults to true.
     */
    fun getContextFromBank(key: String, copy: Boolean = true) : ContextWindow
    {
        if (ContextLock.isPageLocked(key))
        {
            return ContextWindow()
        }

        trackAccess(key)
        val mode = getStorageMode(key)
        var context: ContextWindow

        if (bank.containsKey(key))
        {
            context = bank[key]!!
            return if (copy) context.deepCopy() else context
        }

        val diskPath = "${TPipeConfig.getLorebookDir()}/${key}.bank"
        if (File(diskPath).exists())
        {
            val contextJson = readStringFromFile(diskPath)
            context = deserialize<ContextWindow>(contextJson) ?: ContextWindow()

            when (mode)
            {
                StorageMode.DISK_ONLY ->
                {
                    // Don't cache
                }

                StorageMode.DISK_WITH_CACHE ->
                {
                    bank[key] = context
                    enforceEvictionPolicy()
                }

                StorageMode.MEMORY_AND_DISK, StorageMode.MEMORY_ONLY ->
                {
                    bank[key] = context
                }
            }

            return if (copy) context.deepCopy() else context
        }

        return ContextWindow()
    }

    /**
     * Access function to get all the pages that are stored inside the context bank.
     */
    fun getPageKeys() : List<String>
    {
        return bank.keys.toList()
    }

    /**
     * Clear all banked context. Useful when some code is checking if this contains data or not and applies logic
     * if it does.
     */
    fun clearBankedContext()
    {
        bankedContextWindow = ContextWindow()
    }

    /**
     * Get a todo list by it's page key.
     */
    fun getPagedTodoList(key: String, copy: Boolean = true) : TodoList
    {
        trackAccess(key)
        val mode = getStorageMode(key)

        if (todoList.containsKey(key))
        {
            val list = todoList[key]!!
            return if (copy) list.deepCopy() else list
        }

        val diskPath = TPipeConfig.getTodoListDir()
        val fullFilePath = "${diskPath}/${key}.todo"
        val fileContents = readStringFromFile(fullFilePath)

        if (fileContents.isNotEmpty())
        {
            val result = deserialize<TodoList>(fileContents) ?: TodoList()

            when (mode)
            {
                StorageMode.DISK_ONLY ->
                {
                    // Don't cache
                }

                StorageMode.DISK_WITH_CACHE ->
                {
                    todoList[key] = result
                    enforceTodoListEvictionPolicy()
                }

                StorageMode.MEMORY_AND_DISK, StorageMode.MEMORY_ONLY ->
                {
                    todoList[key] = result
                }
            }

            return if (copy) result.deepCopy() else result
        }

        return TodoList()
    }

    /**
     * Emplace a new todo list into the context bank. Adding if it does not exist, or overwriting it if it does.
     * @param key Bank key to write into.
     * @param todoList [TodoList] to write into the page.
     * @param  allowUpdatesOnly If true, only existing tasks on the list can be modified, no new tasks can be added.
     * Does not apply if the page is empty or does not exist yet.
     * @param allowCompletionsOnly If true, any existing tasks can only allow the isCompleted checkbox to be marked
     * true or false. No other changes to the task are allowed. Does not affect tasks that do not exist yet in the
     * task list.
     * @param persistToDisk If true, this task will be written directly to disk as well as memory. If a task is found
     * by this name on disk. That will be overwritten regardless of weather this true or not.
     */
    fun emplaceTodoList(
        key: String,
        todoList: TodoList,
        allowUpdatesOnly: Boolean = true,
        allowCompletionsOnly: Boolean = false,
        persistToDisk: Boolean = false
    )
    {
        //Declare array for testing valid tasks. Also cache our banked tasks to compare if required.
        val validTaskNumbers = mutableListOf<Int>()
        val bankedTasks = ContextBank.todoList[key]

        /**
         * Ignore both write protect flags because there's nothing banked at this key right now.
         * Instead, just right into it. Because we need to return early we need to adress writing
         * to the file here resulting in us having to frustratingly duplicate this. However, given that we
         * won't be writing to that file path anywhere else in this entire codebase it's not justifiable
         * making that step into its own function.
         */
        if(bankedTasks == null)
        {
            ContextBank.todoList[key] = todoList

            val todoPath = TPipeConfig.getTodoListDir()
            val fullFilePath = "${todoPath}/key.todo"

            if(persistToDisk || File(fullFilePath).exists())
            {
                val todoAsString = serialize(todoList)
                writeStringToFile(fullFilePath, todoAsString)
                return
            }
        }

        //Write protect: Disallow the llm adding any new items to the checklist.
        if(allowUpdatesOnly)
        {
            for(task in todoList.tasks.tasks)
            {
                val isValidTaskNumber = bankedTasks?.find(task.taskNumber)
                if(isValidTaskNumber != null)
                {
                    validTaskNumbers.add(task.taskNumber)
                }
            }
        }

        var todoListToEmplace = TodoList()

        //Enforce our write protect on only allowing existing tasks to be updated.
        if(validTaskNumbers.isNotEmpty())
        {
            for(number in validTaskNumbers)
            {
                val task = todoList.find(number)
                if(task != null) todoListToEmplace.tasks.tasks.add(task)
            }
        }

        else todoListToEmplace = todoList

        /**
         * Write protect to only allow the completion status of a task to be updated.
         * This only applies to tasks already in the array. Any new tasks will just be added since the prior
         * write protect guard will already prevent adding new tasks that weren't there to begin with.
         */
        if(allowCompletionsOnly)
        {
            for(task in todoListToEmplace.tasks.tasks)
            {
                bankedTasks?.find(task.taskNumber)?.isComplete = task.isComplete
            }
        }

        //Otherwise allow the entire task to be written over.
        else
        {
            for(task in todoListToEmplace.tasks.tasks)
            {
                if(bankedTasks?.tasks?.tasks!!.contains(task))
                {
                    bankedTasks.tasks.tasks[bankedTasks.tasks.tasks.indexOf(task)] = task
                }

                bankedTasks.tasks.tasks.add(task)
            }
        }

        ContextBank.todoList[key] = bankedTasks as TodoList

        val todoPath = TPipeConfig.getTodoListDir()
        val fullFilePath = "${todoPath}/key.todo"

        //Required here too because we can't justify a full function call over this small snippet of code.
        if(persistToDisk || File(fullFilePath).exists())
        {
            val todoAsString = serialize(bankedTasks)
            writeStringToFile(fullFilePath, todoAsString)
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
        persistToDisk: Boolean = false
    )
    {
        todoMutex.withLock {
            emplaceTodoList(key,
                todoList,
                allowUpdatesOnly,
                allowCompletionsOnly,
                persistToDisk)
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
     */
    fun emplaceTodoList(
        key: String,
        todoList: TodoList,
        mode: StorageMode,
        allowUpdatesOnly: Boolean = true,
        allowCompletionsOnly: Boolean = false
    )
    {
        val bankedTasks = ContextBank.todoList[key]
        val todoListToEmplace = if (bankedTasks == null) todoList
        else applyTodoListWriteProtection(todoList, bankedTasks, allowUpdatesOnly, allowCompletionsOnly)

        val todoPath = TPipeConfig.getTodoListDir()
        val fullFilePath = "${todoPath}/${key}.todo"

        when (mode)
        {
            StorageMode.MEMORY_ONLY ->
            {
                ContextBank.todoList[key] = todoListToEmplace
            }

            StorageMode.MEMORY_AND_DISK ->
            {
                ContextBank.todoList[key] = todoListToEmplace
                val todoAsString = serialize(todoListToEmplace)
                writeStringToFile(fullFilePath, todoAsString)
            }

            StorageMode.DISK_ONLY ->
            {
                val todoAsString = serialize(todoListToEmplace)
                writeStringToFile(fullFilePath, todoAsString)
            }

            StorageMode.DISK_WITH_CACHE ->
            {
                val todoAsString = serialize(todoListToEmplace)
                writeStringToFile(fullFilePath, todoAsString)
                ContextBank.todoList[key] = todoListToEmplace
                enforceTodoListEvictionPolicy()
            }
        }

        updateMetadata(key, mode)
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

        if (allowUpdatesOnly)
        {
            for (task in todoList.tasks.tasks)
            {
                if (bankedTasks.find(task.taskNumber) != null)
                {
                    validTaskNumbers.add(task.taskNumber)
                }
            }
        }

        var todoListToEmplace = TodoList()

        if (validTaskNumbers.isNotEmpty())
        {
            for (number in validTaskNumbers)
            {
                val task = todoList.find(number)
                if (task != null) todoListToEmplace.tasks.tasks.add(task)
            }
        }
        else
        {
            todoListToEmplace = todoList
        }

        if (allowCompletionsOnly)
        {
            for (task in todoListToEmplace.tasks.tasks)
            {
                bankedTasks.find(task.taskNumber)?.isComplete = task.isComplete
            }
            return bankedTasks
        }
        else
        {
            for (task in todoListToEmplace.tasks.tasks)
            {
                if (bankedTasks.tasks.tasks.contains(task))
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
    private fun enforceTodoListEvictionPolicy()
    {
        if (cacheConfig.evictionPolicy == EvictionPolicy.MANUAL)
        {
            return
        }

        while (todoList.size > cacheConfig.maxEntries)
        {
            evictLeastValuableTodoList()
        }
    }

    /**
     * Evict least valuable TodoList based on eviction policy.
     */
    private fun evictLeastValuableTodoList()
    {
        val candidates = storageMetadata.values
            .filter { todoList.containsKey(it.key) }
            .filter { it.storageMode == StorageMode.DISK_WITH_CACHE }

        if (candidates.isEmpty()) return

        val toEvict = when (cacheConfig.evictionPolicy)
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
        val existed = todoList.containsKey(key)
        todoList.remove(key)
        return existed
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
}