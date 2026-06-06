package com.TTT.Native

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Global handle registry mapping uint64_t handle IDs to handle objects.
 * Thread-safe for concurrent addRef/release from multiple IsolateThreads.
 */
object HandleRegistry {

    /** Maximum number of handles (65536 — TPIPE_MAX_HANDLE_COUNT). */
    const val MAX_HANDLE_COUNT = 65536

    /** Maximum reference count per handle (65535 — TPIPE_MAX_REFCOUNT). */
    const val MAX_REFCOUNT = 65535

    /** Atomic counter for generating unique handle IDs. */
    private val nextHandleId = AtomicInteger(1)

    /** Map from handleId (low 56 bits) to HandleEntry. */
    private val handles = ConcurrentHashMap<Int, HandleEntry>()

    /** Count of open handles. */
    private val handleCount = AtomicInteger(0)

    /**
     * Handle state transitions:
     * ACTIVE → RELEASED (on final release)
     * RELEASED handles are removed from the map immediately.
     */
    enum class HandleState { ACTIVE, RELEASED }

    /**
     * Internal handle entry stored in the registry.
     */
    data class HandleEntry(
        val type: Int,           // High 8 bits of handle (handle type discriminator)
        val data: Any?,          // The actual Kotlin object (ContentHandle, PipeHandle, etc.)
        val refCount: AtomicInteger, // Current reference count
        var state: HandleState,       // ACTIVE or RELEASED (mutable for final release transition)
        val createdAt: Long     // Timestamp for diagnostics
    )

    /**
     * Allocate a new handle for the given type and data.
     * Returns the uint64_t handle value (type in high 8 bits, ID in low 56 bits).
     * Returns -1 if handle limit exceeded.
     */
    fun allocate(type: Int, data: Any): Long {
        if (handleCount.get() >= MAX_HANDLE_COUNT) {
            return -1 // TPIPE_ERR_HANDLE_LIMIT
        }
        val id = nextHandleId.getAndIncrement()
        if (id.toLong() >= 0x00FFFFFFFFFFFFFFL) {
            return -1 // Overflow — beyond 56-bit ID space
        }
        val entry = HandleEntry(type, data, AtomicInteger(1), HandleState.ACTIVE, System.nanoTime())
        handles[id] = entry
        handleCount.incrementAndGet()
        // Encode: type in high 8 bits, id in low 56 bits
        return (((type.toLong() and 0xFFL) shl 56) or id.toLong())
    }

    /**
     * Add a reference to the handle.
     * Returns 0 on success; negative error code on failure.
     */
    fun addRef(handle: Long): Int {
        val id = (handle and 0x00FFFFFFFFFFFFFFL).toInt()
        val entry = handles[id] ?: return TPipeBootstrap.TPIPE_ERR_INVALID_HANDLE
        if (entry.state == HandleState.RELEASED) {
            return TPipeBootstrap.TPIPE_ERR_INVALID_HANDLE
        }
        val current = entry.refCount.get()
        if (current >= MAX_REFCOUNT) {
            return TPipeBootstrap.TPIPE_ERR_REFCOUNT_OVERFLOW
        }
        entry.refCount.incrementAndGet()
        return 0
    }

    /**
     * Release a reference to the handle.
     * On final release (refcount goes to 0), the handle is freed.
     * Returns 0 on success; negative error code on failure.
     */
    fun release(handle: Long): Int {
        val id = (handle and 0x00FFFFFFFFFFFFFFL).toInt()
        val entry = handles[id] ?: return TPipeBootstrap.TPIPE_ERR_INVALID_HANDLE
        if (entry.state == HandleState.RELEASED) {
            return TPipeBootstrap.TPIPE_ERR_INVALID_HANDLE
        }
        val current = entry.refCount.get()
        if (current <= 0) {
            return TPipeBootstrap.TPIPE_ERR_INVALID_HANDLE
        }
        if (entry.refCount.decrementAndGet() == 0) {
            // Final release — sanitize sensitive data before freeing (GAP-15)
            sanitize(entry)
            // Transition to RELEASED and remove from map
            entry.state = HandleState.RELEASED
            handles.remove(id)
            handleCount.decrementAndGet()
        }
        return 0
    }

    /**
     * Sanitize sensitive string fields on handle data before release (GAP-15).
     * Zeros out potentially sensitive strings to prevent memory forensics.
     */
    private fun sanitize(entry: HandleEntry) {
        when (val data = entry.data) {
            is PipeSettingsHandle -> {
                // Sensitive: system prompt, structured-output schema, stop sequences
                data.systemPrompt = null
                data.jsonOutput = null
                data.stopSequences = null
            }
            is ContentHandle -> {
                // Sensitive: text payload, error traces, attached context/miniBank,
                // model reasoning, jump pointers
                data.text = ""
                data.errorMessage = null
                data.context = null
                data.miniBank = null
                data.modelReasoning = null
                data.jump = null
                // Sanitize each attached binary content
                for (bh in data.binaryContent) {
                    bh.sanitize()
                }
                data.binaryContent.clear()
            }
            is BinaryHandle -> {
                // Phase 4: zero the four sensitive payload fields. We overwrite
                // bytes in place (preserves array size to avoid leaking the size
                // of the secret) and null the string-typed payloads.
                data.bytes?.fill(0)
                data.base64Data = null
                data.cloudRef = null
                data.textDocRef = null
            }
            is ConverseHistoryHandle -> {
                // Phase 4: clear the entire conversation history so a heap
                // dump after release cannot recover past turns. The handle
                // itself remains valid; only the conversation is gone.
                data.converseHistory.history.clear()
            }
            // Phase 4 scope: PipeHandle, PipelineHandle, ManifoldHandle,
            // JunctionHandle, ConnectorHandle, SplitterHandle, P2PHandle,
            // LoreBookHandle currently have no exposed sensitive fields
            // on the handle itself — the wrapped objects hold the state.
            // The wrapped objects are still in the JVM heap; a full
            // defense-in-depth sanitizer would need per-class hooks.
        }
    }

    /**
     * Get current reference count.
     * Returns refcount or negative error code.
     */
    fun getRefCount(handle: Long): Int {
        val id = (handle and 0x00FFFFFFFFFFFFFFL).toInt()
        val entry = handles[id] ?: return TPipeBootstrap.TPIPE_ERR_INVALID_HANDLE
        return entry.refCount.get()
    }

    /**
     * Check if handle is valid (exists and not RELEASED).
     * Returns true if valid, false otherwise.
     */
    fun isValid(handle: Long): Boolean {
        val id = (handle and 0x00FFFFFFFFFFFFFFL).toInt()
        val entry = handles[id] ?: return false
        return entry.state == HandleState.ACTIVE
    }

    /**
     * Get handle data.
     * Returns the Kotlin object or null.
     */
    fun getData(handle: Long): Any? {
        val id = (handle and 0x00FFFFFFFFFFFFFFL).toInt()
        return handles[id]?.data
    }

    /**
     * Get handle type (high 8 bits of handle value).
     */
    fun getType(handle: Long): Int {
        return ((handle shr 56) and 0xFF).toInt()
    }

    /**
     * Close all handles (used during shutdown).
     */
    fun closeAll() {
        handles.clear()
        handleCount.set(0)
    }
}