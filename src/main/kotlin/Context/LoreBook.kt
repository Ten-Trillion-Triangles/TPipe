package com.TTT.Context

import com.TTT.Serializers.IntCoercionSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Data class that is able to store keyed context in a similar and compadible way to NovelAI's Lorebook system
 * and other storyteller AI systems. Stores a weighted key and value pair that can be pushed into the context
 * window and TPipe's context system based on the weight of the key, and the available token space of the remaining
 * context window.
 */
@OptIn(ExperimentalSerializationApi::class)
@kotlinx.serialization.Serializable

data class LoreBook(@kotlinx.serialization.Transient val cinit:  Boolean = false)
{
    /**
     * The lorebook key. If this substring is found in any scanned text, the context window will have this appended
     * to it if the token budget fits, and the weight is not outweighed by another key.
     */
    @kotlinx.serialization.Serializable
    @kotlinx.serialization.EncodeDefault(EncodeDefault.Mode.ALWAYS)
    var key: String = ""

    /**
     * Context associated with the lorebook key. This value can be anything desired to be provided to the LLM.
     * Typically, this would be used to remember events, locations, npc's or other world related concepts that
     * we want an LLM to take into account when generating text.
     */
    @kotlinx.serialization.Serializable
    @kotlinx.serialization.EncodeDefault(EncodeDefault.Mode.ALWAYS)
    var value: String = ""

    /**
     * The weight of the context. A higher weight will cause the context to be given higher priority over other
     * contexts.
     */
    @Serializable(with = IntCoercionSerializer::class)
    var weight: Int = 0

     /**
     * Additional lorebook keys that are linked to this lorebook object. When this lorebook is read, these keys
     * should also be instantly invoked and attempted to be appended as context.
     */
     @OptIn(ExperimentalSerializationApi::class)
     @kotlinx.serialization.Serializable
     @kotlinx.serialization.EncodeDefault(EncodeDefault.Mode.ALWAYS)
    var linkedKeys = mutableListOf<String>()

     /**
     * Set of key strings that also will be treated as this lorebook's main key when being invoked.
     * Any strings that match these will count as a hit for this lorebook.
     */
     @kotlinx.serialization.Serializable
     @kotlinx.serialization.EncodeDefault(EncodeDefault.Mode.ALWAYS)
    var aliasKeys = mutableListOf<String>()

     /**
     * List of keys that must ALL be matched in the input text for this lorebook entry to be eligible for activation.
     * Empty list means no dependencies (always eligible). All specified keys must be present for activation.
     */
     @kotlinx.serialization.Serializable
     @kotlinx.serialization.EncodeDefault(EncodeDefault.Mode.ALWAYS)
    var requiredKeys = mutableListOf<String>()


    fun combineValue(other: LoreBook)
    {
        value = "$value ${other.value}"
        // Merge required keys, avoiding duplicates
        other.requiredKeys.forEach { key ->
            if(!requiredKeys.contains(key))
            {
                requiredKeys.add(key)
            }
        }
    }

    fun toMap() : Map<String, LoreBook>
    {
        return mapOf(key to this)
    }
}
