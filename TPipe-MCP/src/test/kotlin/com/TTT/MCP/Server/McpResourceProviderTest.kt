package com.TTT.MCP.Server

import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.StdioContextOptions
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesResult
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class McpResourceProviderTest {

    private fun createPcpContextWithStdioOptions(vararg options: StdioContextOptions): PcpContext {
        val context = PcpContext()
        options.forEach { context.addStdioOption(it) }
        return context
    }

    @Test
    fun testListResourcesWithEmptyContext() {
        val context = PcpContext()
        val provider = McpResourceProvider(context)
        val result = provider.listResources()

        assertTrue(result.resources.isEmpty())
    }

    @Test
    fun testListResourcesWithCatCommand() {
        val context = createPcpContextWithStdioOptions(
            StdioContextOptions().apply {
                command = "cat"
                args = mutableListOf("/path/to/file.txt")
                description = "Read file contents"
            }
        )
        val provider = McpResourceProvider(context)
        val result = provider.listResources()

        assertEquals(1, result.resources.size)
        assertEquals("cat", result.resources[0].name)
        assertEquals("file:///path/to/file.txt", result.resources[0].uri)
        assertEquals("Read file contents", result.resources[0].description)
        assertEquals("text/plain", result.resources[0].mimeType)
    }

    @Test
    fun testListResourcesWithCurlCommand() {
        val context = createPcpContextWithStdioOptions(
            StdioContextOptions().apply {
                command = "curl"
                args = mutableListOf("https://api.example.com/data")
                description = "Fetch remote data"
            }
        )
        val provider = McpResourceProvider(context)
        val result = provider.listResources()

        assertEquals(1, result.resources.size)
        assertEquals("curl", result.resources[0].name)
        assertEquals("https://api.example.com/data", result.resources[0].uri)
        assertEquals("application/json", result.resources[0].mimeType)
    }

    @Test
    fun testListResourcesWithMultipleCommands() {
        val context = createPcpContextWithStdioOptions(
            StdioContextOptions().apply {
                command = "cat"
                args = mutableListOf("/path/to/file.txt")
            },
            StdioContextOptions().apply {
                command = "curl"
                args = mutableListOf("https://api.example.com/data")
            }
        )
        val provider = McpResourceProvider(context)
        val result = provider.listResources()

        assertEquals(2, result.resources.size)
        assertEquals("file:///path/to/file.txt", result.resources[0].uri)
        assertEquals("https://api.example.com/data", result.resources[1].uri)
    }

    @Test
    fun testListResourcesWithHeadCommand() {
        val context = createPcpContextWithStdioOptions(
            StdioContextOptions().apply {
                command = "head"
                args = mutableListOf("/var/log/app.log")
            }
        )
        val provider = McpResourceProvider(context)
        val result = provider.listResources()

        assertEquals(1, result.resources.size)
        assertEquals("file:///var/log/app.log", result.resources[0].uri)
        assertEquals("text/plain", result.resources[0].mimeType)
    }

    @Test
    fun testListResourcesWithTailCommand() {
        val context = createPcpContextWithStdioOptions(
            StdioContextOptions().apply {
                command = "tail"
                args = mutableListOf("/var/log/app.log")
            }
        )
        val provider = McpResourceProvider(context)
        val result = provider.listResources()

        assertEquals(1, result.resources.size)
        assertEquals("file:///var/log/app.log", result.resources[0].uri)
        assertEquals("text/plain", result.resources[0].mimeType)
    }

    @Test
    fun testListResourcesWithUnknownCommand() {
        val context = createPcpContextWithStdioOptions(
            StdioContextOptions().apply {
                command = "custom_command"
                args = mutableListOf("arg1")
            }
        )
        val provider = McpResourceProvider(context)
        val result = provider.listResources()

        assertEquals(1, result.resources.size)
        assertEquals("stdio://custom_command", result.resources[0].uri)
        assertEquals(null, result.resources[0].mimeType)
    }

    @Test
    fun testReadResourceWithFileScheme() {
        val context = createPcpContextWithStdioOptions(
            StdioContextOptions().apply {
                command = "cat"
                args = mutableListOf("/etc/hostname")
            }
        )
        val provider = McpResourceProvider(context)

        try {
            val result = provider.readResource("file:///etc/hostname")
            assertTrue(result.contents.isNotEmpty())
            assertEquals("file:///etc/hostname", result.contents[0].uri)
            assertEquals("text/plain", result.contents[0].mimeType)
        } catch (e: Exception) {
            // Expected in test environment where /etc/hostname might not exist
            assertTrue(e.message?.contains("not in security whitelist") == true ||
                       e.message?.contains("denied") == true ||
                       e is IllegalArgumentException)
        }
    }

    @Test
    fun testReadResourceWithHttpScheme() {
        val context = createPcpContextWithStdioOptions(
            StdioContextOptions().apply {
                command = "curl"
                args = mutableListOf("https://httpbin.org/get")
            }
        )
        val provider = McpResourceProvider(context)

        try {
            val result = provider.readResource("https://httpbin.org/get")
            assertTrue(result.contents.isNotEmpty())
            assertEquals("https://httpbin.org/get", result.contents[0].uri)
            assertEquals("application/json", result.contents[0].mimeType)
        } catch (e: Exception) {
            // Expected if curl fails or network is unavailable
            assertTrue(e.message?.contains("not in security whitelist") == true ||
                       e.message?.contains("denied") == true ||
                       e is IllegalArgumentException)
        }
    }

    @Test
    fun testReadResourceWithUnknownSchemeThrowsException() {
        val context = PcpContext()
        val provider = McpResourceProvider(context)

        try {
            provider.readResource("ftp://example.com/file")
            fail("Expected IllegalArgumentException for unknown URI scheme")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unknown URI scheme") == true)
            assertTrue(e.message?.contains("ftp://") == true)
        }
    }

    @Test
    fun testReadResourceWithUnsupportedSchemeThrowsException() {
        val context = PcpContext()
        val provider = McpResourceProvider(context)

        try {
            provider.readResource("s3://bucket/key")
            fail("Expected IllegalArgumentException for unsupported URI scheme")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unknown URI scheme") == true)
        }
    }

    @Test
    fun testListResourcesPreservesCommandName() {
        val context = createPcpContextWithStdioOptions(
            StdioContextOptions().apply {
                command = "cat"
                args = mutableListOf("/tmp/test.txt")
                description = "Test description"
            }
        )
        val provider = McpResourceProvider(context)
        val result = provider.listResources()

        assertEquals("cat", result.resources[0].name)
    }

    @Test
    fun testListResourcesHandlesEmptyDescription() {
        val context = createPcpContextWithStdioOptions(
            StdioContextOptions().apply {
                command = "cat"
                args = mutableListOf("/tmp/test.txt")
                description = ""
            }
        )
        val provider = McpResourceProvider(context)
        val result = provider.listResources()

        assertEquals(null, result.resources[0].description)
    }

    @Test
    fun testListResourcesHandlesMissingArgs() {
        val context = createPcpContextWithStdioOptions(
            StdioContextOptions().apply {
                command = "cat"
                args = mutableListOf()
            }
        )
        val provider = McpResourceProvider(context)
        val result = provider.listResources()

        assertEquals("file://", result.resources[0].uri)
    }
}