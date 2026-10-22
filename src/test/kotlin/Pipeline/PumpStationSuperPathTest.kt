package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SuperPath] — the bundle-of-paths container that exposes N contained
 * [PathObject]s behind a single dispatchable path. Covers:
 *  - envelope ops (list / describe / invoke) and the unknown-target correction reply
 *  - schema sniffing (unique match, disambiguation, no-match listing fallback)
 *  - customRouter precedence
 *  - flag propagation and routedVia metadata on routed inner results
 *  - station menu isolation (contained paths are never individually registered)
 *  - SuperPathRouted events on every resolution branch
 *  - recursion depth guard
 *  - DSL build-time validation (contained path with no mechanism)
 */
class PumpStationSuperPathTest
{
    //=====================================Helpers=====================================

    /**
     * Builds a contained [PathObject] with an execution function that records calls,
     * plus an optional sniffable required-key schema.
     */
    private fun innerPath(
        name: String,
        schema: String = "",
        callCount: IntArray? = null,
        returnText: String = "inner result: $name",
        passPipeline: Boolean = false
    ): PathObject
    {
        val p = PathObject()
        p.pathName = name
        p.pathDescription = "Inner path: $name"
        p.pathSchema = schema
        p.setExecutionFunction { _, _, _, _ ->
            callCount?.set(0, callCount[0] + 1)
            MultimodalContent(text = returnText).apply { this.passPipeline = passPipeline }
        }
        return p
    }

    private fun superPathWithTwo(station: PumpStation): SuperPath
    {
        val a = innerPath("webSearch", schema = """{"required":["query"]}""")
        val b = innerPath("factCheck", schema = """{"required":["claim"]}""")
        val sp = SuperPath()
        sp.pathName = "researchTools"
        sp.pathDescription = "Bundles research tools."
        sp.addPath(a)
        sp.addPath(b)
        return sp
    }

    //=====================================Envelope: list=====================================

    @Test
    fun listOpReturnsNamesOfContainedPaths()
    {
        val station = buildTestStation()
        val sp = superPathWithTwo(station)
        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver { events.add(it) }

        val result = runBlocking {
            sp.execute(MultimodalContent(text = """{"op":"list"}"""), station, null, "")
        }
        assertTrue(result.text.contains("webSearch"), "listing should name webSearch; got: ${result.text}")
        assertTrue(result.text.contains("factCheck"), "listing should name factCheck; got: ${result.text}")

        val routed = events.filterIsInstance<SuperPathRouted>().single()
        assertEquals("list", routed.op)
        assertNull(routed.targetPathName)
        assertTrue(routed.success)
        assertEquals("researchTools", routed.superPathName)
    }

    //=====================================Envelope: describe=====================================

    @Test
    fun describeOpSurfacesContainedPathSchema()
    {
        val station = buildTestStation()
        val a = innerPath("webSearch", schema = """{"required":["query"]}""")
        val b = innerPath("factCheck")
        val sp = SuperPath()
        sp.pathName = "researchTools"
        sp.addPath(a)
        sp.addPath(b)

        val result = runBlocking {
            sp.execute(MultimodalContent(text = """{"op":"describe","pathName":"webSearch"}"""), station, null, "")
        }
        // The describe reply carries the PathDescriptionData of the target, including its
        // schema (escaped inside the serialized inputSchema string).
        assertTrue(result.text.contains("query"), "describe reply should surface the target schema; got: ${result.text}")
        assertTrue(result.text.contains("webSearch"))
        assertTrue(result.text.contains("invoke"), "describe reply should hint the follow-up invoke op")
    }

    //=====================================Envelope: invoke=====================================

    @Test
    fun invokeOpRoutesIntoInnerPathAndPropagatesFlags()
    {
        val station = buildTestStation()
        val counts = IntArray(1)
        val target = innerPath("webSearch", callCount = counts, passPipeline = true)
        val sp = SuperPath()
        sp.pathName = "researchTools"
        sp.addPath(target)

        val result = runBlocking {
            sp.execute(MultimodalContent(text = """{"op":"invoke","pathName":"webSearch","args":{"query":"tpipe"}}"""), station, null, "")
        }
        assertEquals(1, counts[0], "the invoke op must call the inner path exactly once")
        assertEquals("inner result: webSearch", result.text, "the routed result should be the inner path's output")
        assertTrue(result.passPipeline, "inner passPipeline flag must be carried on the routed result")
        assertEquals("researchTools/webSearch", result.metadata["routedVia"])
        assertEquals(SuperPath.OP_INVOKE, result.metadata["superPathOp"])
    }

    @Test
    fun invokeUnknownTargetTeachesValidNames()
    {
        val station = buildTestStation()
        val sp = superPathWithTwo(station)

        val result = runBlocking {
            sp.execute(MultimodalContent(text = """{"op":"invoke","pathName":"nope"}"""), station, null, "")
        }
        assertTrue(result.text.contains("nope"), "correction reply should echo the attempted name")
        assertTrue(result.text.contains("webSearch"), "correction reply should list valid names")
        assertTrue(result.text.contains("factCheck"))
    }

    //=====================================Schema sniffing=====================================

    @Test
    fun sniffRoutesUniqueMatch()
    {
        val station = buildTestStation()
        val counts = IntArray(1)
        val a = innerPath("webSearch", schema = """{"required":["query"]}""", callCount = counts)
        val b = innerPath("factCheck", schema = """{"required":["claim"]}""", callCount = counts)
        val sp = SuperPath()
        sp.pathName = "researchTools"
        sp.addPath(a)
        sp.addPath(b)

        val result = runBlocking {
            // Envelope-free input that uniquely matches webSearch's required key.
            sp.execute(MultimodalContent(text = """{"query":"hello world"}"""), station, null, "")
        }
        assertEquals(1, counts[0], "sniffed unique match should invoke the inner path once")
        assertTrue(result.text.contains("webSearch"), "routed result should be the inner path's output; got: ${result.text}")
    }

    @Test
    fun sniffAmbiguousProducesDisambiguation()
    {
        val station = buildTestStation()
        val a = innerPath("webSearch", schema = """{"required":["query"]}""")
        val b = innerPath("factCheck", schema = """{"required":["query"]}""")
        val sp = SuperPath()
        sp.pathName = "researchTools"
        sp.addPath(a)
        sp.addPath(b)

        val result = runBlocking {
            sp.execute(MultimodalContent(text = """{"query":"shared key"}"""), station, null, "")
        }
        assertTrue(result.text.contains("webSearch"), "disambiguation should name both candidates")
        assertTrue(result.text.contains("factCheck"))
        assertTrue(result.text.contains("multiple"), "disambiguation should explain the ambiguity; got: ${result.text}")
    }

    @Test
    fun sniffNoMatchFallsBackToListing()
    {
        val station = buildTestStation()
        val sp = superPathWithTwo(station)

        val result = runBlocking {
            sp.execute(MultimodalContent(text = """{"unrelatedKey":1}"""), station, null, "")
        }
        assertTrue(result.text.contains("webSearch"), "no-match reply should fall back to the listing; got: ${result.text}")
        assertFalse(result.passPipeline)
    }

    //=====================================Custom router=====================================

    @Test
    fun customRouterWinsOverEnvelope()
    {
        val station = buildTestStation()
        val a = innerPath("webSearch", callCount = IntArray(1))
        val b = innerPath("factCheck", callCount = IntArray(1))
        val sp = SuperPath()
        sp.pathName = "researchTools"
        sp.addPath(a)
        sp.addPath(b)
        sp.customRouter = { _, _, _, _ -> b }

        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver { events.add(it) }

        // op=list would normally return the listing, but the custom router wins.
        val result = runBlocking {
            sp.execute(MultimodalContent(text = """{"op":"list"}"""), station, null, "")
        }
        assertTrue(result.text.contains("factCheck"), "custom router should route to factCheck even over op=list")
        assertEquals("customRouter", events.filterIsInstance<SuperPathRouted>().single().op)
    }

    //=====================================Station menu isolation=====================================

    @Test
    fun containedPathsAreNotRegisteredOnTheStation()
    {
        val station = buildTestStation()
        val a = innerPath("webSearch")
        val b = innerPath("factCheck")
        val sp = SuperPath()
        sp.pathName = "researchTools"
        sp.addPath(a)
        sp.addPath(b)
        station.addPath(sp)

        assertNotNull(station.getPath("researchTools"), "the SuperPath itself must be registered")
        assertNull(station.getPath("webSearch"), "contained paths must NOT be individually registered")
        assertNull(station.getPath("factCheck"))

        val menu = station.getVisiblePathDescriptorsForDispatch()
        val names = menu.paths.map { it.name }
        assertTrue("researchTools" in names)
        assertFalse("webSearch" in names, "webSearch must not appear in the dispatch menu")
        assertFalse("factCheck" in names)
    }

    //=====================================Recursion=====================================

    @Test
    fun nestedSuperPathWithinDepthLimitInitializes()
    {
        runBlocking {
            val inner = innerPath("webSearch", callCount = IntArray(1))
            val mid = SuperPath()
            mid.pathName = "midBundle"
            mid.addPath(inner)

            val outer = SuperPath()
            outer.pathName = "outerBundle"
            outer.addPath(mid)

            // Depth 2 is within MAX_RECURSION_DEPTH=4.
            val desc = outer.init()
            assertEquals("outerBundle", desc.name)
        }
    }

    @Test
    fun recursionBeyondDepthLimitThrows()
    {
        runBlocking {
            // Build a depth-5 chain: s1 > s2 > s3 > s4 > s5 > leaf (5 SuperPath levels).
            val leaf = innerPath("leaf")
            val s5 = SuperPath().apply { pathName = "s5"; addPath(leaf) }
            val s4 = SuperPath().apply { pathName = "s4"; addPath(s5) }
            val s3 = SuperPath().apply { pathName = "s3"; addPath(s4) }
            val s2 = SuperPath().apply { pathName = "s2"; addPath(s3) }
            val s1 = SuperPath().apply { pathName = "s1"; addPath(s2) }

            val ex = Assert.assertThrows(IllegalArgumentException::class.java)
            {
                runBlocking { s1.init() }
            }
            assertTrue(ex.message!!.contains("depth"), "depth error should mention the depth limit; got: ${ex.message}")
        }
    }

    //=====================================DSL build-time validation=====================================

    @Test
    fun dslBuildRequiresContainedPathMechanism()
    {
        val ex = Assert.assertThrows(IllegalArgumentException::class.java)
        {
            pumpStation("super-dsl-invalid")
            {
                judgeAgent = Pipeline()
                dispatchAgent = Pipeline()
                superPath("researchTools")
                {
                    description = "Bundles research tools."
                    path("webSearch")
                    {
                        description = "Searches the web."
                        // No mechanism — build-time error expected.
                    }
                }
            }
        }
        assertTrue(ex.message!!.contains("no execution mechanism"), "build error should cite the missing mechanism; got: ${ex.message}")
    }

    @Test
    fun dslBuildStoresSuperPathInMenu()
    {
        val station = pumpStation("super-dsl-valid")
        {
            judgeAgent = Pipeline()
            dispatchAgent = Pipeline()
            superPath("researchTools")
            {
                description = "Bundles research tools."
                listingMessage = "Available: webSearch, factCheck — query me to use them."
                path("webSearch")
                {
                    description = "Searches the web."
                    setInternalAgent(SgTestAgent(agentTag = "ws"))
                }
                path("factCheck")
                {
                    description = "Verifies a claim."
                    setExecutionFunction { _, _, _, _ -> MultimodalContent(text = "verified") }
                }
            }
        }

        val sp = station.getPath("researchTools")
        assertNotNull(sp, "the SuperPath should be registered on the station")
        val spSuper = sp as? SuperPath
        assertNotNull(spSuper, "the registered path should be a SuperPath")
        assertEquals(2, spSuper!!.containedPaths.size)
        assertNotNull(spSuper.defaultListingMessage, "listingMessage should round-trip through the DSL")
        // The auto-generated protocol schema is the cache-stable menu line.
        assertTrue(spSuper.pathSchema.contains("describe"), "protocol schema should document the query ops; got: ${spSuper.pathSchema}")
    }
}
