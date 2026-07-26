package com.TTT.Serialization

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Context.ContextWindow
import com.TTT.Context.LoreBook
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.HttpContextOptions
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.PipeContextProtocol.Permissions
import com.TTT.PipeContextProtocol.PythonContext
import com.TTT.PipeContextProtocol.StdioContextOptions
import com.TTT.PipeContextProtocol.StdioExecutionMode
import com.TTT.PipeContextProtocol.TPipeContextOptions
import com.TTT.Pipeline.DistributionGridNodeMetadata
import com.TTT.Pipeline.DistributionGridProtocolVersion
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Pins the wire-format contract for the model families that drive every tool call
 * and prompt payload in TPipe. The Kotlin 2.3 readiness sweep uses these as the
 * canary surface: any kotlinx.serialization behavior change in K2.3 should
 * surface here first, before the LLM prompt paths.
 */
class TpipeSerializationBoundaryTest
{
    @Test
    fun `ContextWindow with nested ConverseHistory round-trips with body-level mutable state preserved`() {
        val window = ContextWindow().apply {
            loreBookKeys["alpha"] = LoreBook().apply { key = "alpha"; value = "a" }
            contextElements.add("first")
            converseHistory = ConverseHistory(mutableListOf(
                ConverseData(role = ConverseRole.user, content = MultimodalContent("hello"))
            ))
        }
        val json = serialize(window, encodedefault = true)
        val back = deserialize<ContextWindow>(json)!!
        assertEquals(1, back.loreBookKeys.size)
        assertEquals("a", back.loreBookKeys["alpha"]!!.value)
        assertEquals(listOf("first"), back.contextElements)
        assertEquals(1, back.converseHistory.history.size)
        assertEquals(ConverseRole.user, back.converseHistory.history.first().role)
    }

    @Test
    fun `MultimodalContent with text and transient body-level vars round-trips`() {
        // `passPipeline` is a `@Transient` body-level var on MultimodalContent (see
        // BinaryContent.kt:152); it does NOT survive serialization and is left at its
        // default value `false` on the deserialized side. The serialization contract
        // is: text + nested types round-trip; transient body-level state is dropped.
        val original = MultimodalContent(text = "hello")
        original.passPipeline = true
        val json = serialize(original, encodedefault = true)
        val back = deserialize<MultimodalContent>(json)!!
        assertEquals("hello", back.text)
        // passPipeline is @Transient, so it must be the default value after round trip.
        assertEquals(false, back.passPipeline,
            "passPipeline is @Transient and must default to false after round trip")
    }

    @Test
    fun `PcPRequest with all four context options round-trips`() {
        val request = PcPRequest(
            stdioContextOptions = StdioContextOptions().apply { command = "echo"; args = mutableListOf("hi") },
            tPipeContextOptions = TPipeContextOptions().apply { functionName = "fn" },
            httpContextOptions = HttpContextOptions().apply { baseUrl = "https://x.example.com" },
            pythonContextOptions = PythonContext().apply { pythonPath = "/usr/bin/python3" }
        )
        val json = serialize(request, encodedefault = true)
        val back = deserialize<PcPRequest>(json)!!
        assertEquals("echo", back.stdioContextOptions.command)
        assertEquals(listOf("hi"), back.stdioContextOptions.args)
        assertEquals("fn", back.tPipeContextOptions.functionName)
        assertEquals("https://x.example.com", back.httpContextOptions.baseUrl)
        assertEquals("/usr/bin/python3", back.pythonContextOptions.pythonPath)
    }

    @Test
    fun `StdioContextOptions with permissions and execution mode round-trips`() {
        val opts = StdioContextOptions().apply {
            command = "ls"
            args = mutableListOf("-la")
            permissions = mutableListOf(Permissions.Read, Permissions.Execute)
            executionMode = StdioExecutionMode.ONE_SHOT
            timeoutMs = 5000
        }
        val json = serialize(opts, encodedefault = true)
        val back = deserialize<StdioContextOptions>(json)!!
        assertEquals("ls", back.command)
        assertEquals(listOf("-la"), back.args)
        assertEquals(listOf(Permissions.Read, Permissions.Execute), back.permissions)
        assertEquals(StdioExecutionMode.ONE_SHOT, back.executionMode)
        assertEquals(5000, back.timeoutMs)
    }

    @Test
    fun `DistributionGridNodeMetadata and DistributionGridProtocolVersion round-trip with nested collections`() {
        val metadata = DistributionGridNodeMetadata(
            nodeId = "node-1",
            supportedProtocolVersions = mutableListOf(
                DistributionGridProtocolVersion(major = 1, minor = 0, patch = 0)
            )
        )
        val json = serialize(metadata, encodedefault = true)
        val back = deserialize<DistributionGridNodeMetadata>(json)!!
        assertEquals("node-1", back.nodeId)
        assertEquals(1, back.supportedProtocolVersions.size)
        assertEquals(1, back.supportedProtocolVersions[0].major)
    }
}
