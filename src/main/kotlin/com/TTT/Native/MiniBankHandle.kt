package com.TTT.Native

import com.TTT.Context.MiniBank
import com.TTT.Context.ContextWindow

/**
 * Handle representing a TPipe MiniBank.
 *
 * MiniBank is a multi-page context container allowing complex storage
 * to be sandboxed to a pipeline. Solves the brick wall issue of needing
 * separation of multiple context sources.
 *
 * @param miniBank The underlying TPipe MiniBank instance
 */
class MiniBankHandle(
    val miniBank: MiniBank
) {
    /**
     * Check if the mini bank is empty.
     */
    fun isEmpty(): Boolean = miniBank.isEmpty()
    
    /**
     * Clear all context pages.
     */
    fun clear() {
        miniBank.clear()
    }
    
    /**
     * Get the number of context pages.
     */
    fun pageCount(): Int = miniBank.contextMap.size
    
    /**
     * Get context page keys.
     */
    fun getPageKeys(): List<String> = miniBank.contextMap.keys.toList()
    
    /**
     * Get or create a context window for a given page key.
     */
    fun getOrCreatePage(key: String): ContextWindow {
        return miniBank.contextMap.getOrPut(key) { ContextWindow() }
    }
    
    /**
     * Merge another mini bank into this one.
     */
    fun merge(otherHandle: MiniBankHandle, 
              emplaceLorebookKeys: Boolean = true, 
              appendKeys: Boolean = false,
              emplaceConverseHistory: Boolean = false,
              onlyEmplaceIfNull: Boolean = false) {
        miniBank.merge(otherHandle.miniBank, emplaceLorebookKeys, appendKeys, emplaceConverseHistory, onlyEmplaceIfNull)
    }
}