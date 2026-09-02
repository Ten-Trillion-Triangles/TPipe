package com.TTT.AgentCore.runtime

import com.TTT.P2P.P2PInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Session isolation policy for the AgentCore runtime host. */
enum class AgentCoreSessionMode {
    /** Create one TPipe root per runtime session id. */
    ISOLATED,

    /** Reuse one TPipe root for all runtime session ids in this host. */
    SHARED
}

/** Context supplied when a new runtime session root is created. */
data class AgentCoreSessionContext(
    val sessionId: String,
    val protocol: AgentCoreRuntimeProtocol,
    val createdAt: Long,
    val requestCorrelationId: String = UUID.randomUUID().toString(),
    val approvedRequestHeaders: Map<String, String> = emptyMap(),
    val threadId: String? = null,
    val runId: String? = null,
    /** Registry used to tie external AgentCore data-plane sessions to this root. */
    val sessionRegistry: AgentCoreSessionRegistry? = null
)

/** Factory for the TPipe root associated with an AgentCore session. */
fun interface AgentCoreSessionFactory {
    /** Create the root interface that will handle [context]'s requests. */
    suspend fun create(context: AgentCoreSessionContext): P2PInterface
}

/**
 * Owns runtime session roots and serializes requests within each session.
 * Different sessions use independent locks and may execute concurrently.
 */
class AgentCoreSessionRegistry(
    private val mode: AgentCoreSessionMode,
    private val factory: AgentCoreSessionFactory,
    private val now: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private val entries = mutableMapOf<String, Entry>()
    private val entriesMutex = Mutex()
    private var sharedEntry: Entry? = null
    private var closed = false
    private val sessionCleanups = mutableMapOf<String, MutableMap<String, suspend () -> Unit>>()

    /** Run [block] against the session root while holding its execution lock. */
    suspend fun <T> withSession(
        sessionId: String,
        protocol: AgentCoreRuntimeProtocol,
        block: suspend (P2PInterface) -> T
    ): T = withSession(
        sessionId,
        protocol,
        UUID.randomUUID().toString(),
        emptyMap(),
        null,
        null,
        block
    )

    /** Run a request with non-secret correlation and explicitly approved headers. */
    suspend fun <T> withSession(
        sessionId: String,
        protocol: AgentCoreRuntimeProtocol,
        requestCorrelationId: String,
        approvedRequestHeaders: Map<String, String>,
        block: suspend (P2PInterface) -> T
    ): T = withSession(
        sessionId,
        protocol,
        requestCorrelationId,
        approvedRequestHeaders,
        null,
        null,
        block
    )

    /** Run a request while retaining AG-UI or caller-supplied correlation. */
    suspend fun <T> withSession(
        sessionId: String,
        protocol: AgentCoreRuntimeProtocol,
        requestCorrelationId: String,
        approvedRequestHeaders: Map<String, String>,
        threadId: String?,
        runId: String?,
        block: suspend (P2PInterface) -> T
    ): T {
        val entry = entriesMutex.withLock {
            check(!closed) { "AgentCore session registry is closed." }
            val selected = when (mode) {
                AgentCoreSessionMode.ISOLATED -> entries[sessionId] ?: createEntry(
                    sessionId,
                    protocol,
                    requestCorrelationId,
                    approvedRequestHeaders,
                    threadId,
                    runId
                ).also {
                    entries[sessionId] = it
                }
                AgentCoreSessionMode.SHARED -> sharedEntry ?: createEntry(
                    sessionId,
                    protocol,
                    requestCorrelationId,
                    approvedRequestHeaders,
                    threadId,
                    runId
                ).also { sharedEntry = it }
            }
            selected.pendingRequests++
            if (selected.pendingRequests == 1) {
                selected.idle = CompletableDeferred()
            }
            selected.lastActivity = now()
            selected
        }
        try {
            return entry.executionMutex.withLock {
                check(!entry.closeRequested) { "AgentCore session registry is closing." }
                block(entry.root)
            }
        } finally {
            entriesMutex.withLock {
                entry.pendingRequests--
                entry.lastActivity = now()
                if (entry.pendingRequests == 0) {
                    entry.idle.complete(Unit)
                }
            }
        }
    }

    /** Evict idle, inactive sessions and return the ids removed. */
    suspend fun evictIdle(olderThan: Long): List<String> {
        val (evicted, roots, cleanups) = entriesMutex.withLock {
            val isolatedEntries = entries
                .filterValues { it.pendingRequests == 0 && it.lastActivity < olderThan }
                .toList()
            val isolated = isolatedEntries.map { it.first }
            val shared = sharedEntry
            val evictShared = shared?.pendingRequests == 0 && shared.lastActivity < olderThan
            val evictedRoots = buildList {
                addAll(isolatedEntries.map { it.second.root })
                if (evictShared) add(requireNotNull(shared).root)
            }
            isolated.forEach(entries::remove)
            val ids = buildList {
                addAll(isolated)
                if (evictShared) {
                    sharedEntry = null
                    add(requireNotNull(shared).sessionId)
                }
            }
            val callbacks = if(evictShared)
            {
                // Shared mode maps many logical caller ids onto one root. All
                // callbacks belong to that root, even when their registration
                // id is not the canonical id returned to the caller.
                sessionCleanups.values.flatMap { it.values }.also { sessionCleanups.clear() }
            }
            else
            {
                ids.flatMap { sessionCleanups.remove(it)?.values.orEmpty() }
            }
            Triple(ids, evictedRoots, callbacks)
        }
        roots.forEach { root -> root.clearStreamingCallbacksRecursive() }
        cleanups.forEach { cleanup -> runCatching { cleanup() } }
        return evicted
    }

    /** Register an owner cleanup invoked when that runtime session is evicted or closed. */
    suspend fun registerSessionCleanup(
        sessionId: String,
        key: String,
        cleanup: suspend () -> Unit
    ) {
        val runImmediately = entriesMutex.withLock {
            if (closed) {
                true
            }
            else {
                sessionCleanups.getOrPut(sessionId) { mutableMapOf() }[key] = cleanup
                false
            }
        }
        if (runImmediately) {
            runCatching { cleanup() }
        }
    }

    /** Return the number of isolated sessions currently retained. */
    suspend fun size(): Int = entriesMutex.withLock {
        if (mode == AgentCoreSessionMode.SHARED) if (sharedEntry == null) 0 else 1 else entries.size
    }

    /** Return whether any retained session currently has queued or active work. */
    suspend fun isBusy(): Boolean = entriesMutex.withLock {
        if (mode == AgentCoreSessionMode.SHARED) {
            sharedEntry?.pendingRequests?.let { it > 0 } == true
        }
        else {
            entries.values.any { it.pendingRequests > 0 }
        }
    }

    /**
     * Close the registry, reject queued work, abort active roots, and wait for
     * admitted requests to leave their per-session locks.
     *
     * A bounded wait keeps shutdown safe when a user-owned root cannot be
     * aborted cooperatively. Requests already admitted before close retain
     * their captured entry and finish their accounting in [finally].
     */
    suspend fun closeSuspend(timeoutMillis: Long = 5_000L) {
        require(timeoutMillis >= 0) { "Session registry close timeout cannot be negative." }
        val (retained, active, cleanups) = entriesMutex.withLock {
            if (closed) {
                return@withLock Triple(emptyList(), emptyList(), emptyList())
            }
            closed = true
            val allEntries = buildList {
                addAll(entries.values)
                sharedEntry?.let { shared -> if (none { it === shared }) add(shared) }
            }
            val activeEntries = allEntries.filter { it.pendingRequests > 0 }
            allEntries.forEach { entry -> entry.closeRequested = true }
            entries.clear()
            sharedEntry = null
            val callbacks = sessionCleanups.values.flatMap { it.values }
            sessionCleanups.clear()
            Triple(allEntries, activeEntries, callbacks)
        }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        cleanups.forEach { cleanup ->
            val remainingMillis = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
            withTimeoutOrNull(remainingMillis) { runCatching { cleanup() } }
        }
        active.forEach { entry ->
            runCatching { entry.root.abortRecursive() }
        }
        retained.forEach { entry -> entry.root.clearStreamingCallbacksRecursive() }
        retained.forEach { entry ->
            val remainingMillis = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
            withTimeoutOrNull(remainingMillis) { entry.idle.await() }
        }
    }

    /** Drop all session references and coordinate cleanup of admitted work. */
    override fun close() {
        runBlocking { closeSuspend() }
    }

    private suspend fun createEntry(
        sessionId: String,
        protocol: AgentCoreRuntimeProtocol,
        requestCorrelationId: String,
        approvedRequestHeaders: Map<String, String>,
        threadId: String?,
        runId: String?
    ): Entry {
        val root = factory.create(
            AgentCoreSessionContext(
                sessionId = sessionId,
                protocol = protocol,
                createdAt = now(),
                requestCorrelationId = requestCorrelationId,
                approvedRequestHeaders = approvedRequestHeaders,
                threadId = threadId,
                runId = runId,
                sessionRegistry = this@AgentCoreSessionRegistry
            )
        )
        return Entry(
            sessionId = sessionId,
            root = root,
            executionMutex = Mutex(),
            pendingRequests = 0,
            lastActivity = now(),
            idle = CompletableDeferred<Unit>().apply { complete(Unit) }
        )
    }

    private data class Entry(
        val sessionId: String,
        val root: P2PInterface,
        val executionMutex: Mutex,
        var pendingRequests: Int,
        var lastActivity: Long,
        var idle: CompletableDeferred<Unit>,
        var closeRequested: Boolean = false
    )
}
