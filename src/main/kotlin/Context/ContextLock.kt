package com.TTT.Context

import com.TTT.Config.TPipeConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class KeyBundle(
    var keys: MutableList<String> = mutableListOf(),
    var pages: MutableList<String> = mutableListOf(),
    var isGlobal: Boolean = false,
    var isLocked: Boolean = false,
    var isPageKey: Boolean = false,
    var passthroughFunction: ( () -> Boolean)? = null
)

/**
 * Core class for providing locking mechanisms to the TPipe context system. Provides the ability to lock [ContextBank]
 * page keys, as well as [LoreBook] keys. Either globally or by page key.
 */
object ContextLock
{
    /**
     * Map of keys, to the locks bound to them. This allows for the tracking of alias keys, any pages being locked,
     * and the passthrough function.
     */
    private val locks = mutableMapOf<String, KeyBundle>()

    /**
     * Mutex used for ensuring thread safety in any locking, or unlocking operations occurring in coroutines.
     */
    val lockMutex = Mutex()

    /**
     * Registers a new lock bundle for the provided key so that all affected context windows are marked with the
     * requested state.
     *
     * @param key The lorebook key or bundle identifier being locked.
     * @param pageKeys The comma-separated page key list that the lock applies to; empty means global.
     * @param isPageKey True when the key itself identifies a page rather than a lorebook entry.
     * @param lockState Whether the bundle should be marked as locked; defaults to true.
     * @param passthroughFunction Optional async check invoked before bypassing the lock.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    fun addLock(key: String,
                pageKeys: String,
                isPageKey: Boolean,
                lockState: Boolean = true,
                passthroughFunction: (() -> Boolean)? = null,
                skipRemote: Boolean = false
                )
    {
        if (!skipRemote && (TPipeConfig.remoteMemoryEnabled || TPipeConfig.useRemoteMemoryGlobally))
        {
            runBlocking {
                MemoryClient.addLock(LockRequest(key, pageKeys, isPageKey, lockState))
            }
            return
        }

        /**
         * Declare new key bundle, then scope into it because fuck boilerplate.
         */
        val newKeyBundle = KeyBundle().apply {
            //If global, we need to search every window in the context bank.
            val isGlobal = pageKeys.isEmpty() //Reach out into the main function scope and grab our param.
            this.isGlobal = isGlobal //Set isGlobal in the scoped data class to the above local val.
            this.isLocked = lockState
            this.passthroughFunction = passthroughFunction
            /**
             * If isPageKey the function param is true, then isPageKey the class var of KeyBundle becomes true.
             */
            if(isPageKey)
            {
                this.isPageKey = true
                this.isLocked = lockState
                return //No need to do anything further to lock page keys themselves.
            }

            /**
             * Check if we're global and seek out all instances of which page has the key and issue locks
             * into it's context object that can be checked against during [ContextWindow.selectAndTruncateContext]
             */
            if(isGlobal)
            {
                for(page in ContextBank.getPageKeys())
                {
                    //Get as a reference to avoid pointless copies when we're only doing a read to locate keys.
                    val pagedWindow = ContextBank.getContextFromBank(page, false, skipRemote = true)
                    val lorebook = pagedWindow.findLoreBookEntry(key)

                    if(lorebook != null)
                    {
                        keys.add(lorebook.key) //Save the lorebook key as written to it to ensure case safety.
                    }

                    /**Bind that we're locked or not so we can read this later
                     * during [ContextWindow.selectAndTruncateContext] calls.*/
                    pagedWindow.metaData["isLocked"] = lockState

                    this.pages.add(page)
                }


            }

            /**
             * We're not in a global state. So now we need to grab the exact page and seek out if we can find the key
             * we're locking. If we can't, we just exit and bind nothing. If we can we mark it up and manage the lock
             * state that was requested.
             */
            else
            {
                val pagedWindow = ContextBank.getContextFromBank(key, false, skipRemote = true)
                val lorebook = pagedWindow.findLoreBookEntry(key)
                if(lorebook != null)
                {
                    this.keys.add(lorebook.key) //Ensure case safety.
                    this.isLocked = lockState

                    //Mark this context window to indicate a lock has been placed upon it.
                    pagedWindow.metaData["isLocked"] = lockState
                }

                else
                {
                    return //If we can't find the key there's nothing to lock.
                }
            }
        }

        //Finally save our new key bundle to our ContextLock system.
        locks[key.lowercase()] = newKeyBundle
    }

    /**
     * Thread-safe wrapper around [addLock] that acquires [lockMutex].
     *
     * @param key The lorebook key or bundle identifier being locked.
     * @param pageKeys The comma-separated page key list that the lock applies to; empty means global.
     * @param isPageKey True when the key itself identifies a page rather than a lorebook entry.
     * @param lockState Whether the bundle should be marked as locked; defaults to true.
     * @param passthroughFunction Optional async check invoked before bypassing the lock.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun addLockWithMutex(key: String,
                                 pageKeys: String,
                                 isPageKey: Boolean,
                                 lockState: Boolean = true,
                                 passthroughFunction: (() -> Boolean)? = null,
                                 skipRemote: Boolean = false
                                 )
    {
        lockMutex.withLock {
            addLock(key, pageKeys, isPageKey, lockState, passthroughFunction, skipRemote)
        }
    }

    /**
     * Removes a lock that was previously registered via [addLock] and clears any metadata markers that were
     * placed on the affected context windows.
     *
     * @param key The same identifier that was passed to [addLock].
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    fun removeLock(key: String, skipRemote: Boolean = false)
    {
        if (!skipRemote && (TPipeConfig.remoteMemoryEnabled || TPipeConfig.useRemoteMemoryGlobally))
        {
            runBlocking {
                MemoryClient.removeLock(key)
            }
            return
        }

        val normalizedKey = key.lowercase()
        val bundle = locks.remove(normalizedKey) ?: return

        if(bundle.isPageKey)
        {
            return
        }

        val pagesToClear = when
        {
            bundle.pages.isNotEmpty() -> bundle.pages.toSet()
            bundle.isGlobal -> ContextBank.getPageKeys(skipRemote = true).toSet()
            else -> setOf(key)
        }

        for(page in pagesToClear)
        {
            val pageWindow = ContextBank.getContextFromBank(page, false, skipRemote = true)
            pageWindow.metaData.remove("isLocked")
        }
    }

    /**
     * Thread-safe wrapper around [removeLock].
     *
     * @param key The same identifier that was passed to [addLock].
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun removeLockWithMutex(key: String, skipRemote: Boolean = false)
    {
        lockMutex.withLock {
            removeLock(key, skipRemote)
        }
    }

    /**
     * Locks the bundle identified by [key] so that all associated context windows and metadata markers are
     * marked as locked.
     *
     * @param key The lock bundle identifier to update.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    fun lockKeyBundle(key: String, skipRemote: Boolean = false)
    {
        applyLockState(key, true, skipRemote)
    }

    /**
     * Suspend-safe wrapper around [lockKeyBundle].
     *
     * @param key The lock bundle identifier to update.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun lockKeyBundleWithMutex(key: String, skipRemote: Boolean = false)
    {
        lockMutex.withLock {
            lockKeyBundle(key, skipRemote)
        }
    }

    /**
     * Unlocks the bundle identified by [key] so that all previously marked context windows clear their locked
     * metadata flag.
     *
     * @param key The lock bundle identifier to update.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    fun unlockKeyBundle(key: String, skipRemote: Boolean = false)
    {
        applyLockState(key, false, skipRemote)
    }

    /**
     * Suspend-safe wrapper around [unlockKeyBundle].
     *
     * @param key The lock bundle identifier to update.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun unlockKeyBundleWithMutex(key: String, skipRemote: Boolean = false)
    {
        lockMutex.withLock {
            unlockKeyBundle(key, skipRemote)
        }
    }

    /**
     * Updates the stored bundle and every affected context window metadata entry according to [lockState].
     *
     * @param key The lock bundle identifier to update.
     * @param lockState Desired lock state to persist.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    private fun applyLockState(key: String, lockState: Boolean, skipRemote: Boolean = false)
    {
        if (!skipRemote && (TPipeConfig.remoteMemoryEnabled || TPipeConfig.useRemoteMemoryGlobally))
        {
            runBlocking {
                MemoryClient.updateLockState(key, lockState)
            }
            return
        }

        val normalizedKey = key.lowercase()
        val bundle = locks[normalizedKey] ?: return

        bundle.isLocked = lockState

        if(bundle.isPageKey)
        {
            return
        }

        val pagesToUpdate = when
        {
            bundle.pages.isNotEmpty() -> bundle.pages.toSet()
            bundle.isGlobal -> ContextBank.getPageKeys(skipRemote = true).toSet()
            else -> setOf(key)
        }

        for(page in pagesToUpdate)
        {
            val pageWindow = ContextBank.getContextFromBank(page, false, skipRemote = true)
            pageWindow.metaData["isLocked"] = lockState
        }
    }

    /**
     * Gets the KeyBundle for a specific key using direct map access.
     * This method provides external access to lock bundle information
     * for validation and passthrough function execution.
     *
     * @param key The lorebook key or bundle identifier to retrieve
     * @return KeyBundle if found, null otherwise
     */
    fun getKeyBundle(key: String): KeyBundle?
    {
        return locks[key.lowercase()]
    }

    /**
     * Checks if a specific key is currently locked.
     *
     * @param key The lorebook key to check
     * @param skipRemote If true, skip remote check even if configured.
     * @return True if the key exists and is locked, false otherwise
     */
    fun isKeyLocked(key: String, skipRemote: Boolean = false): Boolean
    {
        if (!skipRemote && (TPipeConfig.remoteMemoryEnabled || TPipeConfig.useRemoteMemoryGlobally))
        {
            return runBlocking {
                MemoryClient.isKeyLocked(key)
            }
        }
        val bundle = locks[key.lowercase()]
        return bundle?.isLocked ?: false
    }

    /**
     * Checks if a specific page key is locked.
     * This method is used by ContextBank to prevent retrieval of locked pages.
     *
     * @param pageKey The page key to check
     * @param skipRemote If true, skip remote check even if configured.
     * @return True if the page exists as a locked page key, false otherwise
     */
    fun isPageLocked(pageKey: String, skipRemote: Boolean = false): Boolean
    {
        if (!skipRemote && (TPipeConfig.remoteMemoryEnabled || TPipeConfig.useRemoteMemoryGlobally))
        {
            return runBlocking {
                MemoryClient.isPageLocked(pageKey)
            }
        }
        val bundle = locks[pageKey.lowercase()]
        return bundle?.isPageKey == true && bundle.isLocked
    }

    /**
     * Gets all locked lorebook keys that affect a specific ContextWindow.
     * This method filters out page keys and returns only lorebook keys that
     * are currently locked and would impact lorebook selection.
     *
     * @param contextWindow The ContextWindow to get locked keys for
     * @param pageKey Optional page key for page-specific lock filtering
     * @param skipRemote If true, skip remote check even if configured.
     * @return Set of locked lorebook key names
     */
    fun getLockedKeysForContext(contextWindow: ContextWindow, pageKey: String? = null, skipRemote: Boolean = false): Set<String>
    {
        if (!skipRemote && (TPipeConfig.remoteMemoryEnabled || TPipeConfig.useRemoteMemoryGlobally))
        {
            return runBlocking {
                MemoryClient.getLockKeys()
            }
        }

        return locks.values
            .filter { bundle ->
                bundle.isLocked && !bundle.isPageKey && (
                    bundle.isGlobal || 
                    (pageKey != null && bundle.pages.contains(pageKey))
                )
            }
            .flatMap { it.keys }
            .toSet()
    }

    /**
     * Get all lock keys currently tracked locally.
     * @param skipRemote Ignored for local retrieval but kept for API consistency.
     */
    fun getLockKeys(skipRemote: Boolean = true): Set<String>
    {
        return locks.keys.toSet()
    }

    /**
     * Enable or disable remote locking operations.
     */
    fun enableRemoteLocking(enabled: Boolean)
    {
        TPipeConfig.remoteMemoryEnabled = enabled
    }

    
}
