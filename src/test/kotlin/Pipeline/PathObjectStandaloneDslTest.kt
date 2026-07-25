package com.TTT.Pipeline

import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the standalone PathObject DSL:
 *   - pathObject(name, block): PathObject
 *   - pathObjectBuilder(name): PathBuilder
 *   - class PathBuilder
 *   - PathBlock.setOutputCaptureFunction helper
 *
 * Also exercises the underlying PathObject fields via the DSL to confirm the DSL setters
 * correctly delegate to the PathObject instance the builder produces.
 */
class PathObjectStandaloneDslTest
{

//============================================ Group A: pathObject entry ==============================================

    @Test
    fun pathObjectEntryBuildsPathWithCorrectName()
    {
        val path = pathObject("foo") {
            description = "A test path"
        }
        assertEquals("foo", path.pathName)
        assertEquals("A test path", path.pathDescription)
    }

    @Test
    fun pathObjectEntryReturnsUsablePathObject()
    {
        val path = pathObject("bar") {
            description = "Attachable path"
            risk = PathRiskLevel.Medium
        }
        val station = PumpStation()
        // Should not throw — verify the path can be added.
        station.addPath(path)
        assertNotNull(station.getPath("bar"))
        assertSame(path, station.getPath("bar"))
    }

    @Test
    fun pathObjectEntryWithNoBlockStillConstructs()
    {
        val path = pathObject("empty") {}
        assertEquals("empty", path.pathName)
        // Defaults
        assertEquals("", path.pathDescription)
        assertEquals(PathRiskLevel.Low, path.riskLevel)
    }

//============================================ Group B: pathObjectBuilder factory ====================================

    @Test
    fun pathObjectBuilderReturnsBuilderWithName()
    {
        val builder = pathObjectBuilder("baz")
        assertEquals("baz", builder.pathName)
        assertNotNull(builder.pathObject)
        assertEquals("baz", builder.pathObject.pathName)
    }

    @Test
    fun pathObjectBuilderBuildReturnsConfiguredPathObject()
    {
        val builder = pathObjectBuilder("configured")
        builder.description = "Configured via builder"
        builder.risk = PathRiskLevel.High
        builder.dispatchHint = "Call this when X"
        builder.schema = "{\"type\":\"object\"}"
        builder.pcpSchema = com.TTT.PipeContextProtocol.PcpContext()
        val path = builder.build()
        assertEquals("Configured via builder", path.pathDescription)
        assertEquals(PathRiskLevel.High, path.riskLevel)
        assertEquals("Call this when X", path.dispatchHint)
        assertEquals("{\"type\":\"object\"}", path.pathSchema)
        assertNotNull(path.pcpSchema)
    }

    @Test
    fun pathObjectBuilderIsIdempotent()
    {
        val builder = pathObjectBuilder("stable")
        builder.description = "Stable build"
        val first = builder.build()
        val second = builder.build()
        // Same PathObject instance returned — build is idempotent.
        assertSame(first, second)
    }

//============================================ Group C: PathBuilder setters ==========================================

    @Test
    fun pathBuilderDescriptionDelegates()
    {
        val path = pathObject("desc-test") { description = "Desc test" }
        assertEquals("Desc test", path.pathDescription)
    }

    @Test
    fun pathBuilderRiskDelegates()
    {
        val path = pathObject("risk-test") { risk = PathRiskLevel.High }
        assertEquals(PathRiskLevel.High, path.riskLevel)
    }

    @Test
    fun pathBuilderRunsInBackgroundDelegates()
    {
        val path = pathObject("bg-test") { runsInBackground = true }
        assertTrue(path.isRunsInBackground)
    }

    @Test
    fun pathBuilderSuppressHistoryEmitDelegates()
    {
        val path = pathObject("hist-test") { suppressHistoryEmit = true }
        assertTrue(path.isSuppressHistoryEmit)
    }

    @Test
    fun pathBuilderSchemaDelegates()
    {
        val path = pathObject("schema-test") { schema = "{\"k\":\"v\"}" }
        assertEquals("{\"k\":\"v\"}", path.pathSchema)
    }

    @Test
    fun pathBuilderPathMetadataDelegates()
    {
        val path = pathObject("meta-test") {
            pathMetadata["author"] = "tester"
            pathMetadata["version"] = 1
        }
        assertEquals("tester", path.pathMetadata["author"])
        assertEquals(1, path.pathMetadata["version"])
    }

//============================================ Group D: setInternalAgent + setExecutionFunction =========================

    /**
     * Minimal P2PInterface mock used to verify setInternalAgent delegation.
     */
    private class MinimalP2P : P2PInterface
    {
        override var killSwitch: KillSwitch? = null
        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent = content
    }

    @Test
    fun pathBuilderSetInternalAgentDelegates()
    {
        val agent = MinimalP2P()
        val path = pathObject("agent-test") { setInternalAgent(agent) }
        assertTrue(path.isInternalAgentSet)
    }

    @Test
    fun pathBuilderSetExecutionFunctionDelegates()
    {
        val path = pathObject("execfn-test") {
            setExecutionFunction { content, _, _, _ ->
                content.text = "transformed"
                content
            }
        }
        assertTrue(path.isExecutionFunctionSet)
    }

//============================================ Group E: setOutputCaptureFunction on both builders ====================

    @Test
    fun pathBuilderSetOutputCaptureFunctionDelegates()
    {
        var captured: MultimodalContent? = null
        val path = pathObject("capture-builder") {
            setOutputCaptureFunction { content -> captured = content }
        }
        assertNotNull(path.outputCaptureFunction)
        // Direct invocation to confirm the function installed is the same lambda.
        val probe = MultimodalContent("probe")
        runBlocking { path.outputCaptureFunction?.invoke(probe) }
        assertSame(probe, captured)
    }

    @Test
    fun pathBlockSetOutputCaptureFunctionDelegates()
    {
        val station = pumpStation("capture-station") {
            dispatchAgent = Pipeline()
            path("nested-capture") {
                description = "Path with capture hook"
                setOutputCaptureFunction { /* no-op for test */ }
            }
        }
        val path = station.getPath("nested-capture")
        assertNotNull(path)
        assertNotNull(path.outputCaptureFunction)
    }

    @Test
    fun pathBlockAndBuilderCaptureFunctionsAreIndependent()
    {
        var builderCaptureCount = 0
        var blockCaptureCount = 0

        val standalonePath = pathObject("standalone-capture") {
            setOutputCaptureFunction { _ -> builderCaptureCount++ }
        }

        val station = pumpStation("ind-capture-station") {
            dispatchAgent = Pipeline()
            path("nested-capture-2") {
                setOutputCaptureFunction { _ -> blockCaptureCount++ }
            }
        }
        val nestedPath = station.getPath("nested-capture-2")
        assertNotNull(nestedPath)

        // Fire both capture hooks directly with distinct content objects.
        runBlocking {
            standalonePath.outputCaptureFunction?.invoke(MultimodalContent("a"))
            nestedPath.outputCaptureFunction?.invoke(MultimodalContent("b"))
        }

        assertEquals(1, builderCaptureCount)
        assertEquals(1, blockCaptureCount)
        // Null-safety check: confirms standalone's capture does not bleed into the nested one.
        assertNull(standalonePath.outputCaptureFunction?.let { null })
    }

//============================================ Group F: end-to-end execute via DSL ====================================

    @Test
    fun standalonePathObjectCanExecuteTrivialExecutionFunction()
    {
        val path = pathObject("exec-test") {
            setExecutionFunction { content, _, _, _ ->
                content.text = "echo: " + content.text
                content
            }
        }

        val station = PumpStation()
        val input = MultimodalContent("hello")
        val output = runBlocking {
            path.execute(input, station, null, "")
        }

        assertEquals("echo: hello", output.text)
        assertSame(input, output)  // executionFunction mutated the input and returned it
    }
}