package com.TTT.Context

/**
 * Mini container for context bank like storage. Allows for multiple page keys to be stored at once when pulling from
 * global context. This solves the brick wall issue of needing separation of multiple context sources all being provided
 * to the llm as context. Such as a prior chapter for a story, and then the officially added content to the story.
 */
@kotlinx.serialization.Serializable
data class MiniBank(var contextMap: MutableMap<String, ContextWindow> = mutableMapOf<String, ContextWindow>())
{
    
    /**
     * Merge another MiniBank into this one, combining context windows by key.
     * @param other The MiniBank to merge from
     * @param emplaceLorebookKeys Weather to outright replace existing lorebook keys when a key of that name is found.
     * This is useful for lorebook schemes where the llm has been ordered to append, or update the value of the key
     * as context continues forward. Generally, this is the most likely case so this value will be defaulted to
     * true.
     * @param appendKeys Alternate key scheme where the contents of existing keys gets appended by the new key.
     * This is useful in cases where you don't want to allow an automatic llm scanner agent to stomp existing values
     * and only add new content to a lorebook key's context. If true, this will ignore emplaceLorebookKeys normal
     * behavior.
     * @param emplaceConverseHistory Whether to merge converse history from the source context windows. When false,
     * converse history is not merged at all. When true, behavior depends on onlyEmplaceIfNull parameter.
     * @param onlyEmplaceIfNull When used with emplaceConverseHistory=true, controls merge behavior. If true,
     * converse history is only copied when the target context window has empty conversation history. If false,
     * the entire target conversation history is replaced with the source conversation history. This parameter
     * is ignored when emplaceConverseHistory=false.
     */
    fun merge(other: MiniBank, 
              emplaceLorebookKeys: Boolean = true, 
              appendKeys: Boolean = false,
              emplaceConverseHistory: Boolean = false,
              onlyEmplaceIfNull: Boolean = false)
    {
        other.contextMap.forEach { (key, contextWindow) ->
            if(contextMap.containsKey(key))
            {
                contextMap[key]?.merge(contextWindow, emplaceLorebookKeys, appendKeys, emplaceConverseHistory, onlyEmplaceIfNull)
            }
            else
            {
                contextMap[key] = contextWindow
            }
        }
    }

    fun isEmpty(): Boolean
    {
        return contextMap.isEmpty()
    }

    fun clear()
    {
        contextMap.clear()
    }
}
