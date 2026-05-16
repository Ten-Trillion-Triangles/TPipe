package com.TTT.Native

import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Context.ConverseData
import com.TTT.Pipe.MultimodalContent

/**
 * Handle representing a TPipe ConverseHistory.
 *
 * ConverseHistory stores a user-to-agent conversation history as a list
 * of ConverseData turns, each with a role and MultimodalContent.
 */
class ConverseHistoryHandle(
    val converseHistory: ConverseHistory
) {
    /**
     * Add a conversation turn.
     */
    fun add(role: ConverseRole, contentHandle: ContentHandle) {
        val mc = contentHandle.toMultimodalContent()
        converseHistory.add(role, mc)
    }
    
    /**
     * Add a conversation turn from content handle with string role.
     */
    fun add(roleString: String, contentHandle: ContentHandle) {
        val role = try {
            ConverseRole.valueOf(roleString)
        } catch (e: Exception) {
            ConverseRole.user
        }
        add(role, contentHandle)
    }
    
    /**
     * Get the number of conversation turns.
     */
    fun size(): Int = converseHistory.history.size
    
    /**
     * Check if history is empty.
     */
    fun isEmpty(): Boolean = converseHistory.history.isEmpty()
    
    /**
     * Clear the conversation history.
     */
    fun clear() {
        converseHistory.history.clear()
    }
    
    /**
     * Get a conversation turn at index.
     */
    fun get(index: Int): ConverseData? {
        return if (index >= 0 && index < converseHistory.history.size) {
            converseHistory.history[index]
        } else null
    }
    
    /**
     * Get all turns as JSON string.
     */
    fun toJson(): String {
        val turns = converseHistory.history.map { data ->
            """{"role":"${data.role.name}","content":"${data.content.text.escapeJson()}"}"""
        }
        return """{"history":[${turns.joinToString(",")}]}"""
    }
    
    private fun String.escapeJson(): String {
        return this.replace("\\", "\\\\")
                 .replace("\"", "\\\"")
                 .replace("\n", "\\n")
                 .replace("\r", "\\r")
                 .replace("\t", "\\t")
    }
}