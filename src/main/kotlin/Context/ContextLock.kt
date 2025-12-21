package com.TTT.Context

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class KeyBundle(
    var keys: MutableList<String> = mutableListOf(),
    var pages: MutableList<String> = mutableListOf(),
    var isGlobal: Boolean = false,
    var isLocked: Boolean = false,
    var isPageKey: Boolean = false,
    var passthroughFunction: (suspend () -> Boolean)? = null
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
     */
    fun addLock(key: String,
                pageKeys: String,
                isPageKey: Boolean,
                lockState: Boolean = true,
                passthroughFunction: (suspend () -> Boolean)? = null
                )
    {
        /**
         * Declare new key bundle, then scope into it because fuck boilerplate.
         */
        val newKeyBundle = KeyBundle().apply {
            //If global, we need to search every window in the context bank.
            val isGlobal = pageKeys.isEmpty() //Reach out into the main function scope and grab our param.
            this.isGlobal = isGlobal //Set isGlobal in the scoped data class to the above local val.
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
                    val pagedWindow = ContextBank.getContextFromBank(page, false)
                    val lorebook = pagedWindow.findLoreBookEntry(key)

                    if(lorebook != null)
                    {
                        keys.add(lorebook.key) //Save the lorebook key as written to it to ensure case safety.
                    }

                    /**Bind that we're locked or not so we can read this later
                     * during [ContextWindow.selectAndTruncateContext] calls.*/
                    val isGlobalMetadataForWindow = true
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
                val pagedWindow = ContextBank.getContextFromBank(key, false)
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
     * Suspend wrapper around [addLock] that acquires the mutex before mutating shared state.
     */
    /**
     * Thread-safe wrapper around [addLock] that acquires [lockMutex].
     *
     * @param key The lorebook key or bundle identifier being locked.
     * @param pageKeys The comma-separated page key list that the lock applies to; empty means global.
     * @param isPageKey True when the key itself identifies a page rather than a lorebook entry.
     * @param lockState Whether the bundle should be marked as locked; defaults to true.
     * @param passthroughFunction Optional async check invoked before bypassing the lock.
     */
    suspend fun addLockWithMutex(key: String,
                                 pageKeys: String,
                                 isPageKey: Boolean,
                                 lockState: Boolean = true,
                                 passthroughFunction: (suspend () -> Boolean)? = null
                                 )
    {
        lockMutex.withLock {
            addLock(key, pageKeys, isPageKey, lockState, passthroughFunction)
        }
    }

    /**
     * Removes a lock that was previously registered via [addLock] and clears any metadata markers that were
     * placed on the affected context windows.
     *
     * @param key The same identifier that was passed to [addLock].
     */
    fun removeLock(key: String)
    {
        val normalizedKey = key.lowercase()
        val bundle = locks.remove(normalizedKey) ?: return

        if(bundle.isPageKey)
        {
            return
        }

        val pagesToClear = when
        {
            bundle.pages.isNotEmpty() -> bundle.pages.toSet()
            bundle.isGlobal -> ContextBank.getPageKeys().toSet()
            else -> setOf(key)
        }

        for(page in pagesToClear)
        {
            val pageWindow = ContextBank.getContextFromBank(page, false)
            pageWindow.metaData.remove("isLocked")
        }
    }

    /**
     * Suspend-safe wrapper around [removeLock].
     */
    /**
     * Thread-safe wrapper around [removeLock].
     *
     * @param key The same identifier that was passed to [addLock].
     */
    suspend fun removeLockWithMutex(key: String)
    {
        lockMutex.withLock {
            removeLock(key)
        }
    }

    /**
     * Locks the bundle identified by [key] so that all associated context windows and metadata markers are
     * marked as locked.
     *
     * @param key The lock bundle identifier to update.
     */
    fun lockKeyBundle(key: String)
    {
        applyLockState(key, true)
    }

    /**
     * Suspend-safe wrapper around [lockKeyBundle].
     *
     * @param key The lock bundle identifier to update.
     */
    suspend fun lockKeyBundleWithMutex(key: String)
    {
        lockMutex.withLock {
            lockKeyBundle(key)
        }
    }

    /**
     * Unlocks the bundle identified by [key] so that all previously marked context windows clear their locked
     * metadata flag.
     *
     * @param key The lock bundle identifier to update.
     */
    fun unlockKeyBundle(key: String)
    {
        applyLockState(key, false)
    }

    /**
     * Suspend-safe wrapper around [unlockKeyBundle].
     *
     * @param key The lock bundle identifier to update.
     */
    suspend fun unlockKeyBundleWithMutex(key: String)
    {
        lockMutex.withLock {
            unlockKeyBundle(key)
        }
    }

    /**
     * Updates the stored bundle and every affected context window metadata entry according to [lockState].
     *
     * @param key The lock bundle identifier to update.
     * @param lockState Desired lock state to persist.
     */
    private fun applyLockState(key: String, lockState: Boolean)
    {
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
            bundle.isGlobal -> ContextBank.getPageKeys().toSet()
            else -> setOf(key)
        }

        for(page in pagesToUpdate)
        {
            val pageWindow = ContextBank.getContextFromBank(page, false)
            pageWindow.metaData["isLocked"] = lockState
        }
    }

    
}
