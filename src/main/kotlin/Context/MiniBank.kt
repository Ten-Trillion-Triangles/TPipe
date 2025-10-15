package com.TTT.Context

/**
 * Mini container for context bank like storage. Allows for multiple page keys to be stored at once when pulling from
 * global context. This solves the brick wall issue of needing separation of multiple context sources all being provided
 * to the llm as context. Such as a prior chapter for a story, and then the officially added content to the story.
 */
@kotlinx.serialization.Serializable
data class MiniBank(var contextMap: MutableMap<String, ContextWindow> = mutableMapOf<String, ContextWindow>()) {
    
    /**
     * Merge another MiniBank into this one, combining context windows by key.
     * @param other The MiniBank to merge from
     */
    fun merge(other: MiniBank, emplaceLorebookKeys: Boolean = true, appendKeys: Boolean = false)
    {
        other.contextMap.forEach { (key, contextWindow) ->
            if (contextMap.containsKey(key)) {
                contextMap[key]?.merge(contextWindow, emplaceLorebookKeys, appendKeys)
            } else {
                contextMap[key] = contextWindow
            }
        }
    }

    fun isEmpty(): Boolean
    {
        return contextMap.isEmpty()
    }
}
