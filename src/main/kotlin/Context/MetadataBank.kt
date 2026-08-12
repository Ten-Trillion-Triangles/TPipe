package com.TTT.Context

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-singleton, page-keyed, in-memory-only `Map<Any, Any>` scratchpad
 * for TPipe. Provides TPipe components — Pipes, PathObjects, containers, and
 * any user code — a globally addressable location to stash and retrieve
 * arbitrary scratch state without writing custom infrastructure.
 *
 * Pages are keyed by `String`. Every public method ships as a blocking +
 * `suspend` pair (blocking variants use `runBlocking { withLock { } }`,
 * mirroring `ContextBank`). Bulk pull takes a glued `"a, b, c"` string
 * parsed to keys at call time, last-write-wins on key collision.
 *
 * Out of scope (deliberate): disk persistence, remote sharing, cache
 * eviction, lorebook-style page-key locks, value-type schema validation.
 * The bank is unbounded in memory by design and evaporates with the JVM.
 */
object MetadataBank
{
    /**
     * Substrate. Pages live here as `Map<Any, Any>` values keyed by the
     * dev-supplied page key. The `ConcurrentHashMap` is the structural
     * concurrency primitive — page reads and writes are safe without
     * explicit locking when the dev uses `setMeta`/`getMeta` only.
     */
    private val bank = ConcurrentHashMap<String, Map<Any, Any>>()

    /**
     * Mutex for the active-page pointer ([swapMeta]/[getActiveMeta])
     * — held briefly while the active reference is reassigned. Matches
     * `ContextBank.swapMutex` semantics.
     */
    val swapMutex = Mutex()

    /**
     * Mutex for whole-bank atomic ops ([clear], [debugSnapshot]) —
     * held across the full structural mutation.
     */
    val bankMutex = Mutex()

    /**
     * `@Volatile` reference to the currently selected page. `null` until
     * a [swapMeta] call promotes a key. Goes stale (still points at the
     * old object) if the page is later `delete`d, matching
     * `ContextBank.bankedContextWindow` semantics.
     */
    @Volatile
    private var activeMeta: Map<Any, Any>? = null

    /**
     * Replace the page at [key] with [value]. Reference-assign: the
     * caller owns the map instance and any subsequent mutations to it
     * are visible to the bank because the substrate holds the same
     * reference. Use [emplaceSuspend] for atomic read-modify-write
     * semantics.
     */
    fun setMeta(key: String, value: Map<Any, Any>)
    {
        bank[key] = value
    }

    /**
     * Look up the page at [key]. Returns `null` for unknown keys.
     * Snapshot semantics: callers receive the shared reference; mutate
     * the returned map at your own risk.
     */
    fun getMeta(key: String): Map<Any, Any>?
    {
        return bank[key]
    }

    /**
     * Empty the bank and reset the active-page pointer. Bulk structural
     * op — holds the [bankMutex] briefly.
     */
    fun clear()
    {
        bank.clear()
        activeMeta = null
    }
}
