package com.TTT.Native

import com.TTT.Native.HandleRegistry
import com.TTT.Native.HandleTypes

/**
 * Collection handle for ordered lists (TPipe_ListHandle).
 *
 * Implements the builder pattern:
 * 1. Create with TPipe_List_create() → returns TPipe_ListHandle
 * 2. Configure: setCapacity(size)
 * 3. Add items: addItem(item)
 * 4. Build: build() → commits to HandleRegistry, returns handle
 *
 * Items are stored as uint64_t handle IDs (opaque handles).
 * List contents can be strings, integers, or nested handles.
 */
class ListHandle private constructor(
    private val items: MutableList<Long> = mutableListOf(),
    private var capacity: Int = 16,
    private var built: Boolean = false
) {
    /**
     * Set the list capacity hint.
     * @return this (for chaining)
     */
    fun setCapacity(capacity: Int): ListHandle {
        if (built) throw IllegalStateException("Cannot modify after build()")
        this.capacity = capacity.coerceAtLeast(0)
        return this
    }

    /**
     * Append an item (uint64_t handle) to the list.
     * @return this (for chaining)
     */
    fun addItem(item: Long): ListHandle {
        if (built) throw IllegalStateException("Cannot modify after build()")
        if (items.size >= capacity) throw IllegalStateException("Capacity exceeded")
        items.add(item)
        return this
    }

    /**
     * Append a string item to the list.
     */
    fun addString(text: String): ListHandle {
        if (built) throw IllegalStateException("Cannot modify after build()")
        if (items.size >= capacity) throw IllegalStateException("Capacity exceeded")
        // Create a ContentHandle from the string and register it
        val content = ContentHandle(text)
        val handle = HandleRegistry.allocate(HandleTypes.CONTENT, content)
        items.add(handle)
        return this
    }

    /**
     * Build the list — commit to HandleRegistry.
     * Returns the registered handle ID.
     */
    fun build(): Long {
        if (built) throw IllegalStateException("Already built")
        built = true
        return HandleRegistry.allocate(HandleTypes.LIST, this)
    }

    /**
     * Get item at index.
     */
    fun get(index: Int): Long? {
        return items.getOrNull(index)
    }

    /**
     * Get list size.
     */
    fun size(): Int = items.size

    /**
     * Check if list is empty.
     */
    fun isEmpty(): Boolean = items.isEmpty()

    companion object {
        /**
         * Create a new empty list handle builder.
         */
        fun create(): ListHandle = ListHandle()
    }
}
