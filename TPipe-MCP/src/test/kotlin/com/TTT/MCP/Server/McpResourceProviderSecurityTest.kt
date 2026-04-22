package com.TTT.MCP.Server

import com.TTT.PipeContextProtocol.PcpContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

class McpResourceProviderSecurityTest {

    @Test
    fun testIsPathAllowed_pathInsideAllowedSet_returnsTrue() {
        val tempDir = createTempDir()
        try {
            val context = PcpContext().apply {
                allowedDirectoryPaths.add(tempDir.absolutePath)
            }
            val provider = McpResourceProvider(PcpContext())

            val isAllowed = callIsPathAllowed(provider, tempDir.absolutePath, context)
            assertTrue(isAllowed)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testIsPathAllowed_pathOutsideAllowedSet_returnsFalse() {
        val tempDir = createTempDir()
        val otherDir = createTempDir()
        try {
            val context = PcpContext().apply {
                allowedDirectoryPaths.add(tempDir.absolutePath)
            }
            val provider = McpResourceProvider(PcpContext())

            val isAllowed = callIsPathAllowed(provider, otherDir.absolutePath, context)
            assertFalse(isAllowed)
        } finally {
            tempDir.deleteRecursively()
            otherDir.deleteRecursively()
        }
    }

    @Test
    fun testPathTraversalPrevention_withParentDirectoryTraversal() {
        val tempDir = createTempDir()
        val parentDir = tempDir.parentFile ?: throw AssertionError("No parent dir")
        try {
            val context = PcpContext().apply {
                allowedDirectoryPaths.add(tempDir.absolutePath)
            }
            val provider = McpResourceProvider(PcpContext())

            val traversalPath = "${tempDir.absolutePath}/../${parentDir.name}/.."
            val isAllowed = callIsPathAllowed(provider, traversalPath, context)
            assertFalse(isAllowed)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSymlinkTraversalPrevention_withCanonicalPathCheck() {
        val tempDir = createTempDir()
        val targetDir = createTempDir()
        try {
            val symlink = File(tempDir, "symlink_to_target")
            symlink.delete()
            symlink.createSymlink(targetDir)

            val context = PcpContext().apply {
                allowedDirectoryPaths.add(tempDir.absolutePath)
            }
            val provider = McpResourceProvider(PcpContext())

            val isAllowed = callIsPathAllowed(provider, symlink.absolutePath, context)
            assertFalse(isAllowed)
        } finally {
            tempDir.deleteRecursively()
            targetDir.deleteRecursively()
        }
    }

    @Test
    fun testMultiplePaths_viaPathSeparator_areAllChecked() {
        val tempDir1 = createTempDir()
        val tempDir2 = createTempDir()
        try {
            val context = PcpContext().apply {
                allowedDirectoryPaths.add(tempDir1.absolutePath)
                allowedDirectoryPaths.add(tempDir2.absolutePath)
            }
            val provider = McpResourceProvider(PcpContext())

            assertTrue(callIsPathAllowed(provider, tempDir1.absolutePath, context))
            assertTrue(callIsPathAllowed(provider, tempDir2.absolutePath, context))
        } finally {
            tempDir1.deleteRecursively()
            tempDir2.deleteRecursively()
        }
    }

    @Test
    fun testExtractArgs_sanitizationToDevNull_forDisallowedPaths() {
        val tempDir = createTempDir()
        val disallowedDir = createTempDir()
        try {
            val context = PcpContext().apply {
                allowedDirectoryPaths.add(tempDir.absolutePath)
            }
            val provider = McpResourceProvider(PcpContext())

            val args = callExtractArgs(provider, "file://${disallowedDir.absolutePath}/file.txt", "cat", context)
            assertEquals(listOf("/dev/null"), args)
        } finally {
            tempDir.deleteRecursively()
            disallowedDir.deleteRecursively()
        }
    }

    @Test
    fun testExtractArgs_allowedPath_returnsActualPath() {
        val tempDir = createTempDir()
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("test content")
        try {
            val context = PcpContext().apply {
                allowedDirectoryPaths.add(tempDir.absolutePath)
            }
            val provider = McpResourceProvider(PcpContext())

            val args = callExtractArgs(provider, "file://${testFile.absolutePath}", "cat", context)
            assertEquals(listOf(testFile.absolutePath), args)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFileScheme_catCommand_withAllowedPaths() {
        val tempDir = createTempDir()
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("test content")
        try {
            val context = PcpContext().apply {
                allowedDirectoryPaths.add(tempDir.absolutePath)
            }
            val provider = McpResourceProvider(PcpContext())

            val catArgs = callExtractArgs(provider, "file://${testFile.absolutePath}", "cat", context)
            assertEquals(listOf(testFile.absolutePath), catArgs)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFileScheme_catCommand_withDisallowedPath() {
        val tempDir = createTempDir()
        val disallowedDir = createTempDir()
        try {
            val context = PcpContext().apply {
                allowedDirectoryPaths.add(tempDir.absolutePath)
            }
            val provider = McpResourceProvider(PcpContext())

            val args = callExtractArgs(
                provider,
                "file://${disallowedDir.absolutePath}/secret.txt",
                "cat",
                context
            )
            assertEquals(listOf("/dev/null"), args)
        } finally {
            tempDir.deleteRecursively()
            disallowedDir.deleteRecursively()
        }
    }

    private fun callIsPathAllowed(provider: McpResourceProvider, path: String, context: PcpContext): Boolean {
        val method = McpResourceProvider::class.java.getDeclaredMethod("isPathAllowed", String::class.java, PcpContext::class.java)
        method.isAccessible = true
        return method.invoke(provider, path, context) as Boolean
    }

    private fun callExtractArgs(provider: McpResourceProvider, uri: String, command: String, context: PcpContext): List<String> {
        val method = McpResourceProvider::class.java.getDeclaredMethod("extractArgs", String::class.java, String::class.java, PcpContext::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(provider, uri, command, context) as List<String>
    }

    private fun createTempDir(): File {
        val dir = java.io.File.createTempFile("mcp_security_test_", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    private fun File.createSymlink(target: File) {
        val linkPath = this.absolutePath
        java.nio.file.Files.createSymbolicLink(
            java.nio.file.Paths.get(linkPath),
            java.nio.file.Paths.get(target.absolutePath)
        )
    }
}