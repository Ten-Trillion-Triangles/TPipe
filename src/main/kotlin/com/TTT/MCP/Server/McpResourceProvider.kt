package com.TTT.MCP.Server

import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.PcpRegistry
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.PipeContextProtocol.StdioContextOptions
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesResult
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import kotlinx.coroutines.runBlocking

/**
 * Handles MCP resources/list and resources/read by bridging to PCP stdio options.
 *
 * resources/list: Enumerates pcpContext.stdioOptions as MCP resources
 * resources/read: Reads content via StdioExecutor for file:// and http:// URIs
 */
class McpResourceProvider(private val pcpContext: PcpContext) {

    /**
     * Handle resources/list request.
     * Returns all stdio options as MCP resources.
     */
    fun listResources(): ListResourcesResult {
        val resources = pcpContext.stdioOptions.map { option ->
            Resource(
                uri = mapCommandToUri(option.command, option.args),
                name = option.command,
                description = option.description.takeIf { it.isNotBlank() },
                mimeType = inferMimeType(option.command),
                annotations = null
            )
        }
        return ListResourcesResult(resources = resources)
    }

    /**
     * Handle resources/read request.
     * Maps URI to appropriate stdio command and executes via PcpRegistry.
     */
    fun readResource(uri: String): ReadResourceResult {
        val command = mapUriToCommand(uri)
        val args = extractArgs(uri, command)

        val pcpRequest = PcPRequest(
            stdioContextOptions = StdioContextOptions().apply {
                this.command = command
                this.args = args.toMutableList()
            }
        )

        val result = runBlocking {
            PcpRegistry.executeRequests(listOf(pcpRequest))
        }

        val content = result.results.firstOrNull()?.output ?: ""
        return ReadResourceResult(
            contents = listOf(
                BlobResourceContents(
                    uri = uri,
                    mimeType = inferMimeType(command),
                    blob = content
                )
            )
        )
    }

    private fun mapCommandToUri(command: String, args: List<String>): String {
        return when (command) {
            "cat", "head", "tail" -> "file://${args.firstOrNull() ?: ""}"
            "curl" -> args.firstOrNull() ?: "http://localhost"
            else -> "stdio://$command"
        }
    }

    private fun mapUriToCommand(uri: String): String {
        return when {
            uri.startsWith("file://") -> "cat"
            uri.startsWith("http://") || uri.startsWith("https://") -> "curl"
            else -> "echo"
        }
    }

    private fun extractArgs(uri: String, command: String): List<String> {
        return when (command) {
            "cat" -> listOf(uri.removePrefix("file://"))
            "curl" -> listOf(uri)
            else -> listOf(uri)
        }
    }

    private fun inferMimeType(command: String): String? {
        return when (command) {
            "cat", "head", "tail" -> "text/plain"
            "curl" -> "application/json"
            else -> null
        }
    }
}