package com.TTT.Context

import com.TTT.Config.TPipeConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Bundle of lock metadata tracked by [ContextLock].
 *
 * @param keys Lorebook keys affected by the lock.
 * @param pages Page keys affected by the lock.
 * @param isGlobal True when the lock applies to every page.
 * @param isLocked True when the bundle is currently active.
 * @param isPageKey True when the lock targets a page rather than a lorebook key.
 * @param passthroughFunction Optional callback that can bypass a lorebook-key lock.
 */
data class KeyBundle(
    var keys: MutableList<String> = mutableListOf(),
    var pages: MutableList<String> = mutableListOf(),
    var isGlobal: Boolean = false,
    var isLocked: Boolean = false,
    var isPageKey: Boolean = false,
    var passthroughFunction: (() -> Boolean)? = null
)

/**
 * Core class for providing locking mechanisms to the TPipe context system. Provides the ability to lock [ContextBank]
 * page keys, as well as [LoreBook] keys. Either globally or by page key.
 */
object ContextLock
{
    /**
     * Map of keys to the locks bound to them. This allows tracking of alias keys, affected pages, and passthrough
     * functions.
     */
    private val locks = ConcurrentHashMap<String, KeyBundle>()

    /**
     * Mutex used for ensuring thread safety in any locking or unlocking operations occurring in coroutines.
     */
    val lockMutex = Mutex()

    /**
     * Normalize the externally supplied lock key for storage and lookup.
     *
     * @param key Raw lock identifier supplied by the caller.
     * @return Lower-cased lock identifier.
     */
    private fun normalizeKey(key: String): String
    {
        return key.lowercase()
    }

    /**
     * Parse the caller-provided page-key list into individual page keys.
     *
     * @param pageKeys Comma-separated page-key string.
     * @return Individual page keys with blanks removed.
     */
    private fun parsePageKeys(pageKeys: String): List<String>
    {
        return pageKeys.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Register a new lock bundle for the provided key.
     * This is a blocking compatibility wrapper and should not be used from coroutine-heavy internal code.
     * Use [addLockSuspend] internally instead.
     *
     * @param key The lorebook key or bundle identifier being locked.
     * @param pageKeys The comma-separated page key list that the lock applies to; empty means global.
     * @param isPageKey True when the key itself identifies a page rather than a lorebook entry.
     * @param lockState Whether the bundle should be marked as locked; defaults to true.
     * @param passthroughFunction Optional async check invoked before bypassing the lock.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    fun addLock(
        key: String,
        pageKeys: String,
        isPageKey: Boolean,
        lockState: Boolean = true,
        passthroughFunction: (() -> Boolean)? = null,
        skipRemote: Boolean = false
    )
    {
        runBlocking {
            addLockSuspend(key, pageKeys, isPageKey, lockState, passthroughFunction, skipRemote)
        }
    }

    /**
     * Suspend-safe lock registration used by internal coroutine code.
     *
     * @param key The lorebook key or bundle identifier being locked.
     * @param pageKeys The comma-separated page key list that the lock applies to; empty means global.
     * @param isPageKey True when the key itself identifies a page rather than a lorebook entry.
     * @param lockState Whether the bundle should be marked as locked; defaults to true.
     * @param passthroughFunction Optional async check invoked before bypassing the lock.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun addLockSuspend(
        key: String,
        pageKeys: String,
        isPageKey: Boolean,
        lockState: Boolean = true,
        passthroughFunction: (() -> Boolean)? = null,
        skipRemote: Boolean = false
    )
    {
        if(!skipRemote && (TPipeConfig.remoteMemoryEnabled || TPipeConfig.useRemoteMemoryGlobally))
        {
            MemoryClient.addLock(LockRequest(key, pageKeys, isPageKey, lockState))
                .requireSuccess("add remote lock '$key'")
        }

        val affectedPages = if(pageKeys.isEmpty())
        {
            ContextBank.getPageKeysSuspend(skipRemote = true)
        }
        else
        {
            parsePageKeys(pageKeys)
        }

        val newKeyBundle = KeyBundle().apply {
            isGlobal = pageKeys.isEmpty()
            isLocked = lockState
            this.passthroughFunction = passthroughFunction

            if(isPageKey)
            {
                this.isPageKey = true
                pages.addAll(affectedPages.ifEmpty { listOf(key) })
                return@apply
            }

            for(page in affectedPages)
            {
                val pageWindow = ContextBank.withContextWindowReferenceSuspend(page, skipRemote = true) { contextWindow ->
                    contextWindow.metaData["isLocked"] = lockState
                }
                val lorebook = pageWindow.findLoreBookEntry(key)

                if(lorebook != null)
                {
                    keys.add(lorebook.key)
                }

                pages.add(page)
            }
        }

        locks[normalizeKey(key)] = newKeyBundle
    }

    /**
     * Thread-safe wrapper around [addLockSuspend] that acquires [lockMutex].
     *
     * @param key The lorebook key or bundle identifier being locked.
     * @param pageKeys The comma-separated page key list that the lock applies to; empty means global.
     * @param isPageKey True when the key itself identifies a page rather than a lorebook entry.
     * @param lockState Whether the bundle should be marked as locked; defaults to true.
     * @param passthroughFunction Optional async check invoked before bypassing the lock.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun addLockWithMutex(
        key: String,
        pageKeys: String,
        isPageKey: Boolean,
        lockState: Boolean = true,
        passthroughFunction: (() -> Boolean)? = null,
        skipRemote: Boolean = false
    )
    {
        lockMutex.withLock {
            addLockSuspend(key, pageKeys, isPageKey, lockState, passthroughFunction, skipRemote)
        }
    }

    /**
     * Remove a lock that was previously registered via [addLock].
     * This is a blocking compatibility wrapper and should not be used from coroutine-heavy internal code.
     * Use [removeLockSuspend] internally instead.
     *
     * @param key The same identifier that was passed to [addLock].
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    fun removeLock(key: String, skipRemote: Boolean = false)
    {
        runBlocking {
            removeLockSuspend(key, skipRemote)
        }
    }

    /**
     * Suspend-safe lock removal used by internal coroutine code.
     *
     * @param key The same identifier that was passed to [addLock].
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun removeLockSuspend(key: String, skipRemote: Boolean = false)
    {
        if(!skipRemote && (TPipeConfig.remoteMemoryEnabled || TPipeConfig.useRemoteMemoryGlobally))
        {
            when(val removeResult = MemoryClient.removeLock(key))
            {
                is MemoryOperationResult.Success -> Unit
                is MemoryOperationResult.Failure ->
                {
                    if(removeResult.error.errorType != MemoryErrorType.notFound)
                    {
                        throw MemoryRemoteException("remove remote lock '$key'", removeResult)
                    }
                }
            }
        }

        val bundle = locks.remove(normalizeKey(key)) ?: return
        if(bundle.isPageKey)
        {
            return
        }

        val pagesToClear = when
        {
            bundle.pages.isNotEmpty() -> bundle.pages.toSet()
            bundle.isGlobal -> ContextBank.getPageKeysSuspend(skipRemote = true).toSet()
            else -> parsePageKeys(key).toSet()
        }

        for(page in pagesToClear)
        {
            ContextBank.withContextWindowReferenceSuspend(page, skipRemote = true) { pageWindow ->
                pageWindow.metaData.remove("isLocked")
            }
        }
    }

    /**
     * Thread-safe wrapper around [removeLockSuspend].
     *
     * @param key The same identifier that was passed to [addLock].
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun removeLockWithMutex(key: String, skipRemote: Boolean = false)
    {
        lockMutex.withLock {
            removeLockSuspend(key, skipRemote)
        }
    }

    /**
     * Lock the bundle identified by [key].
     * This is a blocking compatibility wrapper and should not be used from coroutine-heavy internal code.
     * Use [lockKeyBundleSuspend] internally instead.
     *
     * @param key The lock bundle identifier to update.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    fun lockKeyBundle(key: String, skipRemote: Boolean = false)
    {
        runBlocking {
            lockKeyBundleSuspend(key, skipRemote)
        }
    }

    /**
     * Suspend-safe wrapper around [lockKeyBundle] for coroutine code.
     *
     * @param key The lock bundle identifier to update.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun lockKeyBundleSuspend(key: String, skipRemote: Boolean = false)
    {
        applyLockStateSuspend(key, true, skipRemote)
    }

    /**
     * Suspend-safe wrapper around [lockKeyBundleSuspend] that holds [lockMutex].
     *
     * @param key The lock bundle identifier to update.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun lockKeyBundleWithMutex(key: String, skipRemote: Boolean = false)
    {
        lockMutex.withLock {
            lockKeyBundleSuspend(key, skipRemote)
        }
    }

    /**
     * Unlock the bundle identified by [key].
     * This is a blocking compatibility wrapper and should not be used from coroutine-heavy internal code.
     * Use [unlockKeyBundleSuspend] internally instead.
     *
     * @param key The lock bundle identifier to update.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    fun unlockKeyBundle(key: String, skipRemote: Boolean = false)
    {
        runBlocking {
            unlockKeyBundleSuspend(key, skipRemote)
        }
    }

    /**
     * Suspend-safe wrapper around [unlockKeyBundle] for coroutine code.
     *
     * @param key The lock bundle identifier to update.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun unlockKeyBundleSuspend(key: String, skipRemote: Boolean = false)
    {
        applyLockStateSuspend(key, false, skipRemote)
    }

    /**
     * Suspend-safe wrapper around [unlockKeyBundleSuspend] that holds [lockMutex].
     *
     * @param key The lock bundle identifier to update.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    suspend fun unlockKeyBundleWithMutex(key: String, skipRemote: Boolean = false)
    {
        lockMutex.withLock {
            unlockKeyBundleSuspend(key, skipRemote)
        }
    }

    /**
     * Update the stored bundle and every affected context-window metadata entry according to [lockState].
     *
     * @param key The lock bundle identifier to update.
     * @param lockState Desired lock state to persist.
     * @param skipRemote If true, skip remote delegation even if configured.
     */
    private suspend fun applyLockStateSuspend(key: String, lockState: Boolean, skipRemote: Boolean = false)
    {
        if(!skipRemote && (TPipeConfig.remoteMemoryEnabled || TPipeConfig.useRemoteMemoryGlobally))
        {
            when(val updateResult = MemoryClient.updateLockState(key, lockState))
            {
                is MemoryOperationResult.Success -> Unit
                is MemoryOperationResult.Failure ->
                {
                    if(updateResult.error.errorType == MemoryErrorType.notFound)
                    {
                        val localBundle = locks[normalizeKey(key)] ?: return
                        MemoryClient.addLock(
                            LockRequest(
                                key = key,
                                pageKeys = localBundle.pages.joinToString(","),
                                isPageKey = localBundle.isPageKey,
                                lockState = lockState
                            )
                        ).requireSuccess("recreate remote lock '$key'")
                    }
                    else
                    {
                        throw MemoryRemoteException("update remote lock '$key'", updateResult)
                    }
                }
            }
        }

        val bundle = locks[normalizeKey(key)] ?: return
        bundle.isLocked = lockState

        if(bundle.isPageKey)
        {
            return
        }

        val pagesToUpdate = when
        {
            bundle.pages.isNotEmpty() -> bundle.pages.toSet()
            bundle.isGlobal -> ContextBank.getPageKeysSuspend(skipRemote = true).toSet()
            else -> parsePageKeys(key).toSet()
        }

        for(page in pagesToUpdate)
        {
            ContextBank.withContextWindowReferenceSuspend(page, skipRemote = true) { pageWindow ->
                pageWindow.metaData["isLocked"] = lockState
            }
        }
    }

    /**
     * Get the [KeyBundle] for a specific key using direct map access.
     *
     * @param key The lorebook key or bundle identifier to retrieve.
     * @return [KeyBundle] if found, null otherwise.
     */
    fun getKeyBundle(key: String): KeyBundle?
    {
        return locks[normalizeKey(key)]
    }

    /**
     * Check if a specific key is currently locked using local lock state only.
     * This avoids blocking coroutine-heavy callers that invoke sync selection code.
     *
     * @param key The lorebook key to check.
     * @param skipRemote Ignored for local compatibility checks.
     * @return True if the key exists and is locked, false otherwise.
     */
    fun isKeyLocked(key: String, skipRemote: Boolean = false): Boolean
    {
        return locks[normalizeKey(key)]?.isLocked ?: false
    }

    /**
     * Suspend-safe key-lock lookup that can consult remote state when needed.
     *
     * @param key The lorebook key to check.
     * @param skipRemote If true, skip remote lookup even if configured.
     * @return True if the key exists and is locked, false otherwise.
     */
    suspend fun isKeyLockedSuspend(key: String, skipRemote: Boolean = false): Boolean
    {
        val localState = isKeyLocked(key, skipRemote)
        if(localState)
        {
            return true
        }

        if(!skipRemote && (TPipeConfig.remoteMemoryEnabled || TPipeConfig.useRemoteMemoryGlobally))
        {
            return MemoryClient.isKeyLocked(key).requireValue("check remote key lock '$key'")
        }

        return false
    }

    /**
     * Check if a specific page key is locked using local lock state only.
     * This avoids blocking coroutine-heavy callers that invoke sync selection code.
     *
     * @param pageKey The page key to check.
     * @param skipRemote Ignored for local compatibility checks.
     * @return True if the page exists as a locked page key, false otherwise.
     */
    fun isPageLocked(pageKey: String, skipRemote: Boolean = false): Boolean
    {
        val bundle = locks[normalizeKey(pageKey)]
        return bundle?.isPageKey == true && bundle.isLocked
    }

    /**
     * Suspend-safe page-lock lookup that can consult remote state when needed.
     *
     * @param pageKey The page key to check.
     * @param skipRemote If true, skip remote lookup even if configured.
     * @return True if the page exists as a locked page key, false otherwise.
     */
    suspend fun isPageLockedSuspend(pageKey: String, skipRemote: Boolean = false): Boolean
    {
        val localState = isPageLocked(pageKey, skipRemote)
        if(localState)
        {
            return true
        }

        if(!skipRemote && (TPipeConfig.remoteMemoryEnabled || TPipeConfig.useRemoteMemoryGlobally))
        {
            return MemoryClient.isPageLocked(pageKey).requireValue("check remote page lock '$pageKey'")
        }

        return false
    }

    /**
     * Get all locked lorebook keys that affect a specific [ContextWindow].
     *
     * @param contextWindow The context window to get locked keys for.
     * @param pageKey Optional page key for page-specific lock filtering.
     * @param skipRemote Ignored for local retrieval but kept for API consistency.
     * @return Set of locked lorebook key names.
     */
    fun getLockedKeysForContext(contextWindow: ContextWindow, pageKey: String? = null, skipRemote: Boolean = false): Set<String>
    {
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
     *
     * @param skipRemote Ignored for local retrieval but kept for API consistency.
     * @return Locally tracked lock keys.
     */
    fun getLockKeys(skipRemote: Boolean = true): Set<String>
    {
        return locks.keys.toSet()
    }

    /**
     * Suspend-safe lock-key retrieval that can merge remote state when needed.
     *
     * @param skipRemote If true, skip remote keys even if configured.
     * @return All visible lock keys.
     */
    suspend fun getLockKeysSuspend(skipRemote: Boolean = false): Set<String>
    {
        if(!skipRemote && (TPipeConfig.remoteMemoryEnabled || TPipeConfig.useRemoteMemoryGlobally))
        {
            return locks.keys + MemoryClient.getLockKeys().requireValue("list remote lock keys")
        }

        return locks.keys
    }

    /**
     * Enable or disable remote locking operations.
     *
     * @param enabled True to enable remote locking.
     */
    fun enableRemoteLocking(enabled: Boolean)
    {
        TPipeConfig.remoteMemoryEnabled = enabled
    }
}
