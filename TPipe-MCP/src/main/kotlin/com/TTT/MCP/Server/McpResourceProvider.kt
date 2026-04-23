package com.TTT.MCP.Server

import com.TTT.MCP.Models.McpResource
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.PcpRegistry
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.PipeContextProtocol.Permissions
import com.TTT.PipeContextProtocol.StdioContextOptions
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesResult
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Handles MCP resources/list and resources/read by bridging to PCP stdio options.
 *
 * ## resources/list
 * Enumerates all [StdioContextOptions] from [PcpContext.stdioOptions] as MCP resources,
 * mapping shell commands (cat, curl) to appropriate URI schemes (file://, http://).
 *
 * ## resources/read
 * Reads content via [PcpRegistry.executeRequests] for file:// and http:// URIs,
 * executing cat or curl commands respectively.
 *
 * @param pcpContext The PCP context containing stdio options to expose as resources
 * @see [PcpToMcpConverter.convertStdioOptions] for the reverse conversion pattern
 */
class McpResourceProvider(private val pcpContext: PcpContext) {

    /**
     * Handles the resources/list request.
     *
     * Converts each [StdioContextOptions] from [PcpContext.stdioOptions] to an [McpResource]:
     * - cat/head/tail commands → file:// URI scheme
     * - curl commands → http:// URI scheme
     * - other commands → stdio:// URI scheme
     *
     * @return [ListResourcesResult] containing all stdio options as MCP resources
     * @see mapCommandToUri for the URI mapping logic
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
     * Handles the resources/read request.
     *
     * Maps the URI to an appropriate shell command and executes it via [PcpRegistry]:
     * - file://path → cat command
     * - http://... or https://... → curl command
     * - unknown schemes → returns an error
     *
     * @param uri The URI to read (file://, http://, or https://)
     * @return [ReadResourceResult] containing the resource contents as a blob
     * @throws IllegalArgumentException if the URI scheme is not supported
     */
    fun readResource(uri: String): ReadResourceResult {
        val command = mapUriToCommand(uri)
            ?: throw IllegalArgumentException("Unknown URI scheme: $uri. Supported schemes: file://, http://, https://")

        val args = extractArgs(uri, command, pcpContext)

        val pcpRequest = PcPRequest(
            stdioContextOptions = StdioContextOptions().apply {
                this.command = command
                this.args = args.toMutableList()
                this.permissions = mutableListOf(Permissions.Read)
            }
        )

        val result = runBlocking {
            PcpRegistry.executeRequests(listOf(pcpRequest), pcpContext)
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

    /**
     * Maps a shell command and arguments back to an appropriate URI scheme.
     *
     * @param command The shell command (cat, curl, head, tail, etc.)
     * @param args Command arguments
     * @return URI string with appropriate scheme prefix
     * @see mapResourceToCommand for the reverse mapping
     */
    private fun mapCommandToUri(command: String, args: List<String>): String {
        return when (command) {
            "cat", "head", "tail" -> "file://${args.firstOrNull() ?: ""}"
            "curl" -> args.firstOrNull() ?: "http://localhost"
            else -> "stdio://$command"
        }
    }

    /**
     * Maps a URI scheme to an appropriate shell command.
     *
     * @param uri The resource URI to map
     * @return The shell command, or null if the URI scheme is not supported
     */
    private fun mapUriToCommand(uri: String): String? {
        return when {
            uri.startsWith("file://") -> "cat"
            uri.startsWith("http://") || uri.startsWith("https://") -> "curl"
            else -> null
        }
    }

    /**
     * Extracts arguments from a URI for the specified command.
     *
     * @param uri The resource URI
     * @param command The shell command to execute
     * @param context The PCP context for access control validation
     * @return List of arguments for the command
     */
    private fun extractArgs(uri: String, command: String, context: PcpContext): List<String> {
        return when (command) {
            "cat" -> {
                val path = uri.removePrefix("file://")
                if (isPathAllowed(path, context)) listOf(path) else listOf("/dev/null")
            }
            "curl" -> listOf(uri)
            else -> listOf(uri)
        }
    }

    /**
     * Checks if a path is allowed to be accessed based on PCP context restrictions.
     *
     * @param path The file path to check
     * @param context The PCP context containing access control settings
     * @return true if the path is allowed, false otherwise
     */
    private fun isPathAllowed(path: String, context: PcpContext): Boolean
    {
        // If no restrictions are defined, allow all paths
        if (context.allowedDirectoryPaths.isEmpty() && context.forbiddenDirectoryPaths.isEmpty())
        {
            return true
        }

        val normalized = try
        {
            File(path).canonicalPath
        }
        catch (e: Exception)
        {
            return false
        }

        // Check forbidden paths first (deny takes precedence)
        if (context.forbiddenDirectoryPaths.any { forbidden ->
            normalized.startsWith(File(forbidden).canonicalPath)
        })
        {
            return false
        }

        // If allowed paths are specified, check against them
        if (context.allowedDirectoryPaths.isNotEmpty())
        {
            return context.allowedDirectoryPaths.any { allowed ->
                normalized.startsWith(File(allowed).canonicalPath)
            }
        }

        return true
    }

    /**
     * Infers the MIME type based on the shell command.
     *
     * @param command The shell command being executed
     * @return The appropriate MIME type, or null if unknown
     */
    private fun inferMimeType(command: String): String? {
        return when (command) {
            "cat", "head", "tail" -> "text/plain"
            "curl" -> "application/json"
            else -> null
        }
    }
}