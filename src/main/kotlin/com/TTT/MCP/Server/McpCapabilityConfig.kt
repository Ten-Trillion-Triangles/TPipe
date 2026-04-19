package com.TTT.MCP.Server

import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

/**
 * Feature gates and ServerCapabilities builder for MCP server configuration.
 * Controls which MCP capabilities are enabled/disabled for the server.
 *
 * @property toolsEnabled Whether tools capability is enabled
 * @property resourcesEnabled Whether resources capability is enabled
 * @property promptsEnabled Whether prompts capability is enabled
 * @property loggingEnabled Whether logging capability is enabled
 * @property completionsEnabled Whether completions capability is enabled
 */
data class McpCapabilityConfig(
    val toolsEnabled: Boolean = true,
    val resourcesEnabled: Boolean = true,
    val promptsEnabled: Boolean = true,
    val loggingEnabled: Boolean = false,
    val completionsEnabled: Boolean = false
) {
    /**
     * Build ServerCapabilities object based on the feature flags.
     *
     * @return ServerCapabilities with appropriate capability settings
     */
    fun buildServerCapabilities(): ServerCapabilities {
        return ServerCapabilities(
            tools = if (toolsEnabled) ServerCapabilities.Tools(listChanged = true) else null,
            resources = if (resourcesEnabled) ServerCapabilities.Resources(
                subscribe = null,
                listChanged = true
            ) else null,
            prompts = if (promptsEnabled) ServerCapabilities.Prompts(listChanged = true) else null,
            logging = if (loggingEnabled) ServerCapabilities.Logging else null,
            completions = if (completionsEnabled) ServerCapabilities.Completions else null
        )
    }
}