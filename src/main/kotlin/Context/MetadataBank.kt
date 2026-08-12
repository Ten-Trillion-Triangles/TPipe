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
     * Mutex for the active-page pointer ([swapMetaSuspend]/[getActiveMetaSuspend])
     * — held briefly while the active reference is reassigned. Matches
     * `ContextBank.swapMutex` semantics.
     */
    val swapMutex = Mutex()

    /**
     * Mutex for whole-bank atomic ops ([clearSuspend], [debugSnapshotSuspend]) —
     * held across the full structural mutation.
     */
    val bankMutex = Mutex()

    /**
     * `@Volatile` reference to the currently selected page. `null` until
     * a [swapMetaSuspend] call promotes a key. Goes stale (still points at the
     * old object) if the page is later `deleteSuspend`d, matching
     * `ContextBank.bankedContextWindow` semantics.
     */
    @Volatile
    private var activeMeta: Map<Any, Any>? = null

    // -- setMeta / getMeta -------------------------------------------------

    /**
     * Replace the page at [key] with [value] (blocking). Forwards to the
     * canonical [setMetaSuspend] via `runBlocking`. Reference-assign:
     * the caller owns the map instance and any subsequent mutations are
     * visible to the bank because the substrate holds the same reference.
     */
    fun setMeta(key: String, value: Map<Any, Any>)
    {
        runBlocking { setMetaSuspend(key, value) }
    }

    /**
     * Coroutine-native setMeta. The canonical path. Use from any `suspend`
     * context; the blocking [setMeta] exists for legacy callers.
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

    // -- emplace (merge-into-page) -----------------------------------------

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

    // -- delete / exists / clear ------------------------------------------

    /**
     * Remove the page at [key] (blocking). Returns `true` if a page was
     * removed, `false` if no such page existed (idempotent semantics for
     * missing keys — by design; callers don't need to pre-check existence).
     */
    fun delete(key: String): Boolean = runBlocking { deleteSuspend(key) }

    /**
     * Coroutine-native delete.
     */
    suspend fun deleteSuspend(key: String): Boolean
    {
        return bank.remove(key) != null
    }

    /**
     * Probe whether [key] currently maps to a page (blocking). Cheap
     * structural check; uses the substrate's intrinsic membership test.
     */
    fun exists(key: String): Boolean = runBlocking { existsSuspend(key) }

    /**
     * Coroutine-native exists.
     */
    suspend fun existsSuspend(key: String): Boolean
    {
        return bank.containsKey(key)
    }

    /**
     * Empty the bank and reset the active-page pointer (blocking). Bulk
     * structural op — holds the [bankMutex] briefly. Pair with
     * [emplaceSuspend] if a concurrent writer might be active at call time.
     */
    fun clear()
    {
        runBlocking { clearSuspend() }
    }

    /**
     * Coroutine-native clear. Holds [bankMutex] for the full structural
     * mutation.
     */
    suspend fun clearSuspend()
    {
        bankMutex.withLock {
            bank.clear()
            activeMeta = null
        }
    }

    // -- active-page pointer ----------------------------------------------

    /**
     * Promote the page at [key] to the active-page pointer (blocking).
     * If [key] does not exist, the active pointer is set to `null`.
     */
    fun swapMeta(key: String) = runBlocking { swapMetaSuspend(key) }

    /**
     * Coroutine-native swapMeta. Holds [swapMutex] briefly to reassign
     * the volatile reference.
     */
    suspend fun swapMetaSuspend(key: String)
    {
        swapMutex.withLock {
            activeMeta = bank[key]
        }
    }

    /**
     * Read the active-page pointer (blocking). `null` until a [swapMetaSuspend]
     * call has promoted a key; goes stale (still points at the old object)
     * if the page is later [delete]d. Cheap non-blocking volatile read.
     */
    fun getActiveMeta(): Map<Any, Any>? = runBlocking { getActiveMetaSuspend() }

    /**
     * Coroutine-native getActiveMeta.
     */
    suspend fun getActiveMetaSuspend(): Map<Any, Any>?
    {
        return activeMeta
    }

    // -- bulk pull --------------------------------------------------------

    /**
     * Pull a sequence of pages by glued page-key string into [target]
     * (blocking). The [pageKeysGlued] string is parsed at call time:
     * `split(",").map(trim).filter(nonEmpty)`. For each parsed key, the
     * page is read via [getMetaSuspend] and `putAll`-merged into
     * [target]. Missing keys are skipped silently. Conflicting keys
     * resolve last-write-wins (later entries in the parsed list override
     * earlier ones), matching Kotlin's `MutableMap.putAll` semantics.
     */
    fun pullMetaPageKeysInto(target: MutableMap<Any, Any>, pageKeysGlued: String) =
        runBlocking { pullMetaPageKeysIntoSuspend(target, pageKeysGlued) }

    /**
     * Coroutine-native pull. The canonical path.
     */
    suspend fun pullMetaPageKeysIntoSuspend(
        target: MutableMap<Any, Any>,
        pageKeysGlued: String
    )
    {
        val keys = pageKeysGlued
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (key in keys)
        {
            val page = bank[key] ?: continue
            target.putAll(page)
        }
    }
}
