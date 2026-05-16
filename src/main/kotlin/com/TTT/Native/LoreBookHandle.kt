package com.TTT.Native

import com.TTT.Context.LoreBook

/**
 * Handle representing a TPipe LoreBook entry.
 *
 * LoreBook stores keyed context in a NovelAI-like system.
 * Each entry has a key, value, weight, and optional linking/aliasing.
 */
class LoreBookHandle(
    var loreBook: LoreBook
) {
    /**
     * Get the lorebook key.
     */
    fun getKey(): String = loreBook.key
    
    /**
     * Set the lorebook key.
     */
    fun setKey(key: String) {
        loreBook.key = key
    }
    
    /**
     * Get the lorebook value/context.
     */
    fun getValue(): String = loreBook.value
    
    /**
     * Set the lorebook value/context.
     */
    fun setValue(value: String) {
        loreBook.value = value
    }
    
    /**
     * Get the weight.
     */
    fun getWeight(): Int = loreBook.weight
    
    /**
     * Set the weight.
     */
    fun setWeight(weight: Int) {
        loreBook.weight = weight
    }
    
    /**
     * Get linked keys.
     */
    fun getLinkedKeys(): List<String> = loreBook.linkedKeys.toList()
    
    /**
     * Add a linked key.
     */
    fun addLinkedKey(key: String) {
        if (!loreBook.linkedKeys.contains(key)) {
            loreBook.linkedKeys.add(key)
        }
    }
    
    /**
     * Get alias keys.
     */
    fun getAliasKeys(): List<String> = loreBook.aliasKeys.toList()
    
    /**
     * Add an alias key.
     */
    fun addAliasKey(key: String) {
        if (!loreBook.aliasKeys.contains(key)) {
            loreBook.aliasKeys.add(key)
        }
    }
    
    /**
     * Get required keys.
     */
    fun getRequiredKeys(): List<String> = loreBook.requiredKeys.toList()
    
    /**
     * Add a required key.
     */
    fun addRequiredKey(key: String) {
        if (!loreBook.requiredKeys.contains(key)) {
            loreBook.requiredKeys.add(key)
        }
    }
    
    /**
     * Combine this lorebook with another.
     */
    fun combine(otherHandle: LoreBookHandle) {
        loreBook.combineValue(otherHandle.loreBook)
    }
    
    /**
     * Serialize to JSON string.
     */
    fun toJson(): String {
        return """{"key":"${loreBook.key.escapeJson()}","value":"${loreBook.value.escapeJson()}","weight":${loreBook.weight}}"""
    }
    
    private fun String.escapeJson(): String {
        return this.replace("\\", "\\\\")
                 .replace("\"", "\\\"")
                 .replace("\n", "\\n")
                 .replace("\r", "\\r")
                 .replace("\t", "\\t")
    }
}