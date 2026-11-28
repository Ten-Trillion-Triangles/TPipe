package com.TTT.P2P

import com.TTT.Pipe.DummyPipe
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Pipeline.Connector
import com.TTT.Pipeline.DistributionGrid
import com.TTT.Pipeline.Junction
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.MultiConnector
import com.TTT.Pipeline.PathObject
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.PumpStation
import com.TTT.Pipeline.PumpStationGatewayConfig
import com.TTT.Pipeline.Splitter
import com.TTT.Pipeline.distributionGrid
import com.TTT.Pipeline.junction
import com.TTT.Pipeline.manifold
import com.TTT.Pipeline.pathObject
import com.TTT.Pipeline.pumpStation
import com.TTT.Util.constructPipeFromTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies DITL message hooks (`messageFunction` / `setMessageFunction`) and
 * [P2PInterface.sendMessage] across all TPipe executable classes and DSLs.
 */
class MessageHookTest
{
    private data class GateSignal(val gateName: String)
    private data class GateAck(val gateName: String, val stageIndex: Int)

    private class MinimalP2P : P2PInterface
    {
        override var killSwitch: KillSwitch? = null
    }

    @Test
    fun defaultInterfaceNoOpReturnsNull() = runBlocking {
        val minimal: P2PInterface = MinimalP2P()
        assertNull(minimal.sendMessage("hello"))
    }

    @Test
    fun allCoreImplementorsRoundTripAndClear() = runBlocking {
        // Pipe (via DummyPipe)
        val pipe = DummyPipe()
        assertNull(pipe.sendMessage("ping"))
        pipe.setMessageFunction { msg, self ->
            assertSame(pipe, self)
            "pipe:$msg"
        }
        assertEquals("pipe:ping", pipe.sendMessage("ping"))
        pipe.setMessageFunction(null)
        assertNull(pipe.sendMessage("ping"))

        // Pipeline
        val pipeline = Pipeline()
        assertNull(pipeline.sendMessage("ping"))
        pipeline.setMessageFunction { msg, self ->
            assertSame(pipeline, self)
            "pipeline:$msg"
        }
        assertEquals("pipeline:ping", pipeline.sendMessage("ping"))
        pipeline.setMessageFunction(null)
        assertNull(pipeline.sendMessage("ping"))

        // Connector
        val connector = Connector()
        assertNull(connector.sendMessage("ping"))
        connector.setMessageFunction { msg, self ->
            assertSame(connector, self)
            "connector:$msg"
        }
        assertEquals("connector:ping", connector.sendMessage("ping"))
        connector.setMessageFunction(null)
        assertNull(connector.sendMessage("ping"))

        // MultiConnector
        val multiConnector = MultiConnector()
        assertNull(multiConnector.sendMessage("ping"))
        multiConnector.setMessageFunction { msg, self ->
            assertSame(multiConnector, self)
            "multiConnector:$msg"
        }
        assertEquals("multiConnector:ping", multiConnector.sendMessage("ping"))
        multiConnector.setMessageFunction(null)
        assertNull(multiConnector.sendMessage("ping"))

        // Splitter
        val splitter = Splitter()
        assertNull(splitter.sendMessage("ping"))
        splitter.setMessageFunction { msg, self ->
            assertSame(splitter, self)
            "splitter:$msg"
        }
        assertEquals("splitter:ping", splitter.sendMessage("ping"))
        splitter.setMessageFunction(null)
        assertNull(splitter.sendMessage("ping"))

        // Manifold
        val manifold = Manifold()
        assertNull(manifold.sendMessage("ping"))
        manifold.setMessageFunction { msg, self ->
            assertSame(manifold, self)
            "manifold:$msg"
        }
        assertEquals("manifold:ping", manifold.sendMessage("ping"))
        manifold.setMessageFunction(null)
        assertNull(manifold.sendMessage("ping"))

        // Junction
        val junction = Junction()
        assertNull(junction.sendMessage("ping"))
        junction.setMessageFunction { msg, self ->
            assertSame(junction, self)
            "junction:$msg"
        }
        assertEquals("junction:ping", junction.sendMessage("ping"))
        junction.setMessageFunction(null)
        assertNull(junction.sendMessage("ping"))

        // DistributionGrid
        val grid = DistributionGrid()
        assertNull(grid.sendMessage("ping"))
        grid.setMessageFunction { msg, self ->
            assertSame(grid, self)
            "grid:$msg"
        }
        assertEquals("grid:ping", grid.sendMessage("ping"))
        grid.setMessageFunction(null)
        assertNull(grid.sendMessage("ping"))

        // PumpStation
        val station = PumpStation()
        assertNull(station.sendMessage("ping"))
        station.setMessageFunction { msg, self ->
            assertSame(station, self)
            "station:$msg"
        }
        assertEquals("station:ping", station.sendMessage("ping"))
        station.setMessageFunction(null)
        assertNull(station.sendMessage("ping"))

        // PathObject
        val pathObj = PathObject()
        assertNull(pathObj.sendMessage("ping"))
        pathObj.setMessageFunction { msg, self ->
            assertSame(pathObj, self)
            "path:$msg"
        }
        assertEquals("path:ping", pathObj.sendMessage("ping"))
        pathObj.setMessageFunction(null)
        assertNull(pathObj.sendMessage("ping"))
    }

    @Test
    fun polymorphicDispatchAndNullReturn() = runBlocking {
        val agents: List<P2PInterface> = listOf(
            DummyPipe().setMessageFunction { msg, _ -> if(msg == "drop") null else 42 },
            Pipeline().setMessageFunction { _, _ -> listOf("a", "b") },
            PumpStation().setMessageFunction { msg, _ -> msg }
        )

        assertEquals(42, agents[0].sendMessage("keep"))
        assertNull(agents[0].sendMessage("drop"))
        assertEquals(listOf("a", "b"), agents[1].sendMessage("list"))
        assertEquals("echo", agents[2].sendMessage("echo"))
    }

    @Test
    fun pipeContainerPtrDelegation() = runBlocking {
        val station = PumpStation().setMessageFunction { msg, _ -> "delegated:$msg" }
        val proxyPipe = DummyPipe().setContainerPtr(station)

        // Unbound pipe delegates through containerPtr
        assertEquals("delegated:hello", proxyPipe.sendMessage("hello"))

        // Pipe with its own messageFunction handles locally instead of delegating
        proxyPipe.setMessageFunction { msg, _ -> "local:$msg" }
        assertEquals("local:hello", proxyPipe.sendMessage("hello"))
    }

    @Test
    fun constructPipeFromTemplateRespectsCopyFunctionsFlag() = runBlocking {
        val template = DummyPipe().setMessageFunction { msg, _ -> "cloned:$msg" }

        val copiedWithFunctions = constructPipeFromTemplate<DummyPipe>(template, copyFunctions = true)
        assertEquals("cloned:hi", copiedWithFunctions?.sendMessage("hi"))

        val copiedWithoutFunctions = constructPipeFromTemplate<DummyPipe>(template, copyFunctions = false)
        assertNull(copiedWithoutFunctions?.sendMessage("hi"))
    }

    @Test
    fun dslWiringAcrossContainersAndPaths() = runBlocking {
        // Standalone path DSL
        val standalonePath = pathObject("standalone") {
            setExecutionFunction { content, _, _, _ -> content }
            setMessageFunction { msg, p -> "${p.pathName}:$msg" }
        }
        assertEquals("standalone:ok", standalonePath.sendMessage("ok"))

        // PumpStation + path + reservePath DSL
        val station = pumpStation("dsl-station") {
            dispatchAgent = Pipeline()
            messageFunction = { msg, s -> "station:${s.getTaskState().turnIndex}:$msg" }
            path("p1") {
                setExecutionFunction { content, _, _, _ -> content }
                setMessageFunction { msg, p -> "p1:${p.pathName}:$msg" }
            }
            reservePath("r1") {
                setInternalAgent(MinimalP2P())
                setMessageFunction { msg, p -> "r1:${p.pathName}:$msg" }
            }
        }
        assertEquals("station:0:ping", station.sendMessage("ping"))
        assertEquals("p1:p1:ping", station.getPath("p1")?.sendMessage("ping"))

        // Manifold DSL (messageFunction both before and after worker{})
        val managerPipe = DummyPipe().setPipeName("mgr").setJsonOutput(AgentRequest()).setTokenBudget(
            TokenBudgetSettings(contextWindowSize = 4096, userPromptSize = 1024, maxTokens = 256)
        )
        val workerPipe = DummyPipe().setPipeName("wrk").setContextWindowSize(2048).autoTruncateContext()
        val builtManifold = manifold {
            manager {
                pipeline {
                    add(managerPipe)
                }
            }
            worker("wrk") {
                pipeline {
                    add(workerPipe)
                }
            }
            messageFunction { msg, m ->
                "manifold:${m.getWorkerComponents().size}:$msg"
            }
        }
        assertEquals("manifold:1:ping", builtManifold.sendMessage("ping"))

        // Junction DSL
        val builtJunction = junction {
            moderator(MinimalP2P())
            participant("p1", MinimalP2P())
            messageFunction { msg, _ -> "junction-dsl:$msg" }
        }
        assertEquals("junction-dsl:ping", builtJunction.sendMessage("ping"))

        // DistributionGrid DSL
        val builtGrid = distributionGrid {
            p2p {
                agentName("grid-msg-test")
                description("grid message hook test")
                transportAddress("grid-msg-test")
                transportMethod(Transport.Tpipe)
                acceptedContent(SupportedContentTypes.text)
                maxTokens(1024)
            }
            router {
                component(Pipeline())
            }
            worker {
                component(Pipeline())
            }
            hooks {
                messageFunction { msg, _ -> "grid-dsl:$msg" }
            }
        }
        assertEquals("grid-dsl:ping", builtGrid.sendMessage("ping"))
    }

    @Test
    fun apexToPumpStationGatewayGlueViaP2PInterface() = runBlocking {
        val station = pumpStation("apex-glue") {
            dispatchAgent = Pipeline()
            messageFunction = { message, harness ->
                when(message)
                {
                    is GateSignal -> {
                        harness.signalGateway(message.gateName)
                        GateAck(message.gateName, harness.getGatewayStageIndex())
                    }
                    else -> null
                }
            }
            path("noop") {
                setExecutionFunction { content, _, _, _ -> content }
            }
        }
        station.setGateway(PumpStationGatewayConfig(name = "todo-check"))

        val p2pAgent: P2PInterface = station
        val ack = p2pAgent.sendMessage(GateSignal("todo-check"))

        assertTrue(station.isGatewayRequested("todo-check"), "Gateway should be signaled by messageFunction")
        assertEquals(GateAck("todo-check", 0), ack)
    }
}
