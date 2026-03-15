package com.TTT.Config

import java.util.concurrent.ConcurrentHashMap

/**
 * Universal registry for authentication tokens used by TPipe's remote services.
 * Maps destination addresses (URLs, program paths, agent names) to tokens.
 *
 * This registry allows for automated credential injection when connecting to 
 * remote services that have [com.TTT.P2P.P2PRegistry.globalAuthMechanism] enabled.
 */
object AuthRegistry {
    private val tokenMap = ConcurrentHashMap<String, String>()

    /**
     * Register an authentication token for a specific service address.
     * @param address Service URL, program path, or agent name.
     * @param token Authentication token or secret to be used.
     */
    fun registerToken(address: String, token: String) {
        tokenMap[address.trim().lowercase()] = token
    }

    /**
     * Retrieve a token for a given address.
     * Tries exact match first, then falls back to prefix matching (useful for base URLs).
     *
     * @param address Service address to lookup.
     * @return Token if found, otherwise an empty string.
     */
    fun getToken(address: String): String {
        val key = address.trim().lowercase()
        
        // Try exact match
        tokenMap[key]?.let { return it }
        
        // Try prefix match for URLs (shortest key that matches as a prefix)
        val sortedPrefixes = tokenMap.keys
            .filter { key.startsWith(it) }
            .sortedBy { it.length }
            
        sortedPrefixes.lastOrNull()?.let { return tokenMap[it]!! }
        
        return ""
    }

    /**
     * Remove a token for a given address.
     */
    fun removeToken(address: String) {
        tokenMap.remove(address.trim().lowercase())
    }

    /**
     * Clear all stored tokens.
     */
    fun clear() {
        tokenMap.clear()
    }
}
