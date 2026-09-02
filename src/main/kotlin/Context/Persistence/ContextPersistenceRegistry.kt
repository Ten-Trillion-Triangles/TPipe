package com.TTT.Context.Persistence

/**
 * Process-local registry for the optional ContextBank persistence backend.
 * The registry contains behavior only; it never stores provider credentials.
 */
object ContextPersistenceRegistry {
    @Volatile
    private var backend: ContextPersistenceBackend? = null

    /** Install [newBackend] as the process-wide remote persistence backend.
     *
     * @param newBackend Backend to install.
     */
    fun set(newBackend: ContextPersistenceBackend)
    {
        backend = newBackend
    }

    /** Return the configured backend, if any. */
    fun get(): ContextPersistenceBackend? = backend

    /** Remove the configured backend. */
    fun clear()
    {
        backend = null
    }
}
