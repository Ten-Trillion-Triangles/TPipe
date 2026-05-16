package com.TTT.Native

/**
 * Collection handle for key-value maps (TPipe_MapHandle).
 *
 * Implements the builder pattern:
 * 1. Create with TPipe_Map_create() → returns TPipe_MapHandle
 * 2. Set entries: set(key, value), setString(key, value)
 * 3. Build: build() → commits to HandleRegistry, returns handle
 *
 * Keys are always strings. Values are uint64_t handle IDs.
 */
class MapHandle private constructor(
    private val entries: MutableMap<String, Long> = mutableMapOf(),
    private var built: Boolean = false
) {
    /**
     * Set a key-value entry using an existing handle.
     */
    fun set(key: String, value: Long): MapHandle {
        if (built) throw IllegalStateException("Cannot modify after build()")
        entries[key] = value
        return this
    }

    /**
     * Set a key-string value entry.
     * Creates a ContentHandle internally.
     */
    fun setString(key: String, value: String): MapHandle {
        if (built) throw IllegalStateException("Cannot modify after build()")
        val content = ContentHandle(value)
        val handle = HandleRegistry.allocate(HandleTypes.CONTENT, content)
        entries[key] = handle
        return this
    }

    /**
     * Get value for key.
     * Returns null if key doesn't exist.
     */
    fun get(key: String): Long? = entries[key]

    /**
     * Check if key exists.
     */
    fun has(key: String): Boolean = entries.containsKey(key)

    /**
     * Get number of entries.
     */
    fun size(): Int = entries.size

    /**
     * Check if map is empty.
     */
    fun isEmpty(): Boolean = entries.isEmpty()

    /**
     * Build the map — commit to HandleRegistry.
     */
    fun build(): Long {
        if (built) throw IllegalStateException("Already built")
        built = true
        return HandleRegistry.allocate(HandleTypes.MAP, this)
    }

    companion object {
        fun create(): MapHandle = MapHandle()
    }
}
