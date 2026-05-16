package com.TTT.Native

import com.TTT.Context.ContextWindow

/**
 * Handle representing a TPipe ContextWindow.
 *
 * ContextWindow is the per-run memory system with token budgeting,
 * lorebook selection, and context manipulation.
 */
class ContextHandle(
    val contextWindow: ContextWindow
) {
    /**
     * Get lorebook keys as a list of strings.
     */
    fun getLoreBookKeys(): List<String> = contextWindow.loreBookKeys.keys.toList()
    
    /**
     * Get context elements count.
     */
    fun getContextElementsCount(): Int = contextWindow.contextElements.size
    
    /**
     * Get conversation history size.
     */
    fun getConverseHistorySize(): Int = contextWindow.converseHistory.history.size
    
    /**
     * Get context window version.
     */
    fun getVersion(): Long = contextWindow.version
    
    /**
     * Get context as JSON string.
     */
    fun getContextJson(): String {
        return """{"loreBookKeys":${contextWindow.loreBookKeys.size},"contextElements":${contextWindow.contextElements.size},"converseHistory":${contextWindow.converseHistory.history.size},"version":${contextWindow.version}}"""
    }
}