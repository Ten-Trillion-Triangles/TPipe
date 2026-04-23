package com.TTT.MCP.Server

import com.TTT.PipeContextProtocol.PcpContext
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsResult
import io.modelcontextprotocol.kotlin.sdk.types.Root
import java.io.File

class McpRootsProvider(private val pcpContext: PcpContext) {

    companion object {
        private const val ENV_VAR_ROOTS = "TPIPE_MCP_ROOTS"
    }

    fun listRoots(): ListRootsResult {
        val rootsEnv = System.getenv(ENV_VAR_ROOTS)
        if (rootsEnv.isNullOrBlank()) {
            return ListRootsResult(roots = emptyList())
        }

        val paths = rootsEnv.split(",").map { it.trim() }.filter { it.isNotBlank() }

        val validRoots = paths
            .mapNotNull { path -> validateAndCanonicalize(path) }
            .map { canonicalPath ->
                Root(
                    uri = "file://$canonicalPath",
                    name = File(canonicalPath).name
                )
            }

        return ListRootsResult(roots = validRoots)
    }

    private fun validateAndCanonicalize(path: String): String? {
        if (containsPathTraversal(path)) {
            return null
        }

        return try {
            val canonicalPath = File(path).canonicalPath

            if (containsPathTraversal(canonicalPath)) {
                return null
            }

            if (File(canonicalPath).exists()) {
                canonicalPath
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun containsPathTraversal(path: String): Boolean {
        val normalized = path.replace("\\", "/")
        return normalized.contains("/../") ||
               normalized.startsWith("../") ||
               normalized.endsWith("/..") ||
               normalized == ".."
    }
}