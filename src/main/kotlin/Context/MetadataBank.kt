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
     * Per-page advisory mutexes for atomic read-modify-write on a single
     * page (used by [emplaceSuspend]). NOT for semantic content gating —
     * a concurrency primitive only, mirroring `ContextBank.pageMutexes`.
     * Lazily allocated on first reference.
     */
    private val metaMutexes = ConcurrentHashMap<String, Mutex>()

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
    /**
     * Replace the page at [key] with [value] (blocking). Forwards to the
     * canonical [setMetaSuspend] via `runBlocking`. Uses `ConcurrentHashMap`'s
     * own concurrency for the reference-assign — no per-page lock needed
     * because the operation is single-statement.
     */
    fun setMeta(key: String, value: Map<Any, Any>)
    {
        runBlocking { setMetaSuspend(key, value) }
    }

    /**
     * Coroutine-native setMeta. The canonical path. Use this from any
     * `suspend` context; the blocking [setMeta] exists for legacy callers.
     */
    suspend fun setMetaSuspend(key: String, value: Map<Any, Any>)
    {
        bank[key] = value
    }

    /**
     * Look up the page at [key] (blocking). Returns `null` for unknown keys.
     * Forwards to the canonical [getMetaSuspend] via `runBlocking`.
     */
    fun getMeta(key: String): Map<Any, Any>?
    {
        return runBlocking { getMetaSuspend(key) }
    }

    /**
     * Coroutine-native getMeta. The canonical path.
     */
    suspend fun getMetaSuspend(key: String): Map<Any, Any>?
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

    /**
     * Resolve the per-page mutex for [key], lazily allocating on first
     * call. Returns the same Mutex for the same key across the JVM's life.
     */
    private fun getMetaMutex(key: String): Mutex
    {
        return metaMutexes.computeIfAbsent(key) { Mutex() }
    }

    /**
     * Merge [value] into the page at [key] (blocking). If the key has no
     * page yet, this behaves like [setMeta]. If the page exists, the new
     * map's entries are merged in: keys present in both pages are
     * overwritten by the incoming value; keys only in the existing page
     * are preserved. The full merge is atomic per-page.
     */
    fun emplace(key: String, value: Map<Any, Any>)
    {
        runBlocking { emplaceSuspend(key, value) }
    }

    /**
     * Coroutine-native emplace. Holds the per-page mutex briefly while
     * building the merged map and assigning to the substrate.
     */
    suspend fun emplaceSuspend(key: String, value: Map<Any, Any>)
    {
        getMetaMutex(key).withLock {
            val existing = bank[key]
            val merged: Map<Any, Any> =
                if (existing == null)
                {
                    value
                }
                else
                {
                    val mut = HashMap<Any, Any>(existing)
                    mut.putAll(value)
                    mut
                }
            bank[key] = merged
        }
    }
}
