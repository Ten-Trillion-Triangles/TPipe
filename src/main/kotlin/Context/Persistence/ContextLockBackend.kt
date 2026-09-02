package com.TTT.Context.Persistence

import com.TTT.Context.LockRequest

/**
 * Optional capability for backends that provide TPipe's remote lock protocol.
 * Exact-memory backends such as AgentCore Memory intentionally do not need to
 * implement this interface because that service is not a distributed lock.
 */
interface ContextLockBackend {
    /** List lock identifiers visible to the backend.
     *
     * @return Lock identifiers visible to the backend.
     */
    suspend fun getLockKeys(): Set<String>

    /** Return whether a non-page lock is active.
     *
     * @param key Lock key.
     * @return Whether the key is locked.
     */
    suspend fun isKeyLocked(key: String): Boolean

    /** Return whether a page lock is active.
     *
     * @param pageKey Page key.
     * @return Whether the page is locked.
     */
    suspend fun isPageLocked(pageKey: String): Boolean

    /** Create or replace a lock.
     *
     * @param request Lock request.
     */
    suspend fun addLock(request: LockRequest)

    /** Remove a lock, returning whether a lock was present.
     *
     * @param key Lock key.
     * @return Whether a lock was removed.
     */
    suspend fun removeLock(key: String): Boolean

    /** Update a lock state, returning whether a lock was present.
     *
     * @param key Lock key.
     * @param lockState New lock state.
     * @return Whether a lock was updated.
     */
    suspend fun updateLockState(key: String, lockState: Boolean): Boolean
}
