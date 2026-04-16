package com.TTT.Pipeline

import com.TTT.Context.ContextWindow
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceEventType
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.PipeError
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.deepCopy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the Phase 4 local execution core.
 */
class DistributionGridExecutionCoreTest
{
    /**
     * Verifies that `execute(...)` and `executeLocal(...)` share the same normalized local runtime path.
     */
    @Test
    fun executeAndExecuteLocalShareTheSamePath()
    {
        runBlocking {
            val router = ExecutionInterface("router") { content ->
                content.addText("router")
                content
            }
            val worker = ExecutionInterface("worker") { content ->
                content.addText("worker")
                content
            }
            val grid = initializedGrid(router, worker)

            val directResult = grid.execute(MultimodalContent(text = "start"))
            val localResult = grid.executeLocal(MultimodalContent(text = "start"))

            assertEquals("start\n\nrouter\n\nworker", directResult.text)
            assertEquals(directResult.text, localResult.text)
            assertTrue(directResult.passPipeline)
            assertTrue(localResult.passPipeline)
            assertEquals(2, router.localExecutionCount)
            assertEquals(2, worker.localExecutionCount)

            val envelope = directResult.metadata["distributionGridEnvelope"] as? DistributionGridEnvelope
            val outcome = directResult.metadata["distributionGridOutcome"] as? DistributionGridOutcome
            assertNotNull(envelope)
            assertNotNull(outcome)
            assertEquals(1, envelope.hopHistory.size)
        }
    }

    /**
     * Verifies that inbound P2P execution deep-copies prompt and context before reusing the local runtime path.
     */
    @Test
    fun executeP2PRequestDeepCopiesInboundPromptAndContext()
    {
        runBlocking {
            val router = ExecutionInterface("router") { content ->
                content.addText("router")
                content.context.contextElements.add("router-context")
                content
            }
            val worker = ExecutionInterface("worker") { content ->
                content.addText("worker")
                content.context.contextElements.add("worker-context")
                content
            }
            val grid = initializedGrid(router, worker)

            val originalPrompt = MultimodalContent(text = "original")
            val originalContext = ContextWindow().apply {
                contextElements.add("request-context")
            }

            val response = grid.executeP2PRequest(
                P2PRequest(
                    transport = P2PTransport(
                        transportMethod = Transport.Http,
                        transportAddress = "https://peer.example/grid"
                    ),
                    prompt = originalPrompt,
                    context = originalContext
                )
            )

            assertNotNull(response)
            assertNotNull(response.output)
            assertEquals("original", originalPrompt.text)
            assertTrue(originalPrompt.context.contextElements.isEmpty())
            assertEquals(listOf("request-context"), originalContext.contextElements)
            assertEquals(
                listOf("request-context", "router-context", "worker-context"),
                response.output!!.context.contextElements
            )
        }
    }

    /**
     * Verifies that direct execution fails fast when the shell has not been initialized.
     */
    @Test
    fun executeFailsFastWhenShellIsNotInitialized()
    {
        runBlocking {
            val grid = DistributionGrid()
            grid.setRouter(ExecutionInterface("router") { it })
            grid.setWorker(ExecutionInterface("worker") { it })

            val result = grid.execute(MultimodalContent(text = "not ready"))
            val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

            assertTrue(result.terminatePipeline)
            assertFalse(result.passPipeline)
            assertNotNull(result.pipeError)
            assertNotNull(failure)
            assertEquals(DistributionGridFailureKind.VALIDATION_FAILURE, failure.kind)
        }
    }

    /**
     * Verifies that dirtying the shell after init forces execution to fail fast until init is run again.
     */
    @Test
    fun executeFailsFastWhenShellWasDirtiedAfterInit()
    {
        runBlocking {
            val grid = initializedGrid(
                ExecutionInterface("router") { it },
                ExecutionInterface("worker") { it }
            )

            grid.setMaxHops(24)
            val result = grid.execute(MultimodalContent(text = "dirty shell"))
            val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

            assertTrue(result.terminatePipeline)
            assertNotNull(failure)
            assertEquals(DistributionGridFailureKind.VALIDATION_FAILURE, failure.kind)
        }
    }

    /**
     * Verifies that a router return directive finalizes locally without worker dispatch.
     */
    @Test
    fun routerReturnDirectiveSkipsWorkerDispatch()
    {
        runBlocking {
            val router = ExecutionInterface("router") { content ->
                content.metadata["distributionGridDirective"] = DistributionGridDirective(
                    kind = DistributionGridDirectiveKind.RETURN_TO_SENDER,
                    notes = "Return immediately"
                )
                content.addText("router")
                content
            }
            val worker = ExecutionInterface("worker") { content ->
                content.addText("worker")
                content
            }
            val grid = initializedGrid(router, worker)

            val result = grid.execute(MultimodalContent(text = "start"))
            val outcome = result.metadata["distributionGridOutcome"] as? DistributionGridOutcome

            assertTrue(result.passPipeline)
            assertEquals("start\n\nrouter", result.text)
            assertEquals(1, router.localExecutionCount)
            assertEquals(0, worker.localExecutionCount)
            assertNotNull(outcome)
            assertEquals(DistributionGridReturnMode.RETURN_TO_SENDER, outcome.returnMode)
        }
    }

    /**
     * Verifies that a router rejection terminates locally and records failure metadata.
     */
    @Test
    fun routerRejectDirectiveTerminatesLocally()
    {
        runBlocking {
            val router = ExecutionInterface("router") { content ->
                content.metadata["distributionGridDirective"] = DistributionGridDirective(
                    kind = DistributionGridDirectiveKind.REJECT,
                    rejectReason = "Rejected locally"
                )
                content
            }
            val worker = ExecutionInterface("worker") { it }
            val grid = initializedGrid(router, worker)

            val result = grid.execute(MultimodalContent(text = "reject me"))
            val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

            assertTrue(result.terminatePipeline)
            assertNotNull(result.pipeError)
            assertNotNull(failure)
            assertEquals(DistributionGridFailureKind.POLICY_REJECTED, failure.kind)
            assertEquals(0, worker.localExecutionCount)
        }
    }

    /**
     * Verifies that unsupported remote-routing directives are rejected in Phase 4.
     */
    @Test
    fun unsupportedRemoteDirectiveFailsLocally()
    {
        runBlocking {
            val router = ExecutionInterface("router") { content ->
                content.metadata["distributionGridDirective"] = DistributionGridDirective(
                    kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                    targetPeerId = "remote-peer"
                )
                content
            }
            val worker = ExecutionInterface("worker") { it }
            val grid = initializedGrid(router, worker)

            val result = grid.execute(MultimodalContent(text = "handoff"))
            val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

            assertTrue(result.terminatePipeline)
            assertNotNull(failure)
            assertEquals(DistributionGridFailureKind.ROUTING_FAILURE, failure.kind)
            assertEquals(0, worker.localExecutionCount)
        }
    }

    /**
     * Verifies that a child worker pipe error is preserved on the terminal output.
     */
    @Test
    fun preservesChildPipeErrorFromWorker()
    {
        runBlocking {
            val router = ExecutionInterface("router") { it }
            val worker = ExecutionInterface("worker") { content ->
                content.addText("worker-failed")
                content.terminatePipeline = true
                content.pipeError = PipeError(
                    exception = IllegalStateException("worker failed"),
                    eventType = TraceEventType.PIPE_FAILURE,
                    phase = com.TTT.Debug.TracePhase.EXECUTION,
                    pipeName = "worker",
                    pipeId = "worker"
                )
                content
            }
            val grid = initializedGrid(router, worker)

            val result = grid.execute(MultimodalContent(text = "start"))
            val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

            assertTrue(result.terminatePipeline)
            assertNotNull(result.pipeError)
            assertEquals("worker failed", result.pipeError!!.message)
            assertNotNull(failure)
            assertEquals(DistributionGridFailureKind.WORKER_FAILURE, failure.kind)
        }
    }

    /**
     * Verifies the ordered local DITL hook flow and outcome transformation hook.
     */
    @Test
    fun invokesLocalHooksInOrder()
    {
        runBlocking {
            val calls = mutableListOf<String>()
            val router = ExecutionInterface("router") { content ->
                calls.add("router")
                content.addText("router")
                content
            }
            val worker = ExecutionInterface("worker") { content ->
                calls.add("worker")
                content.addText("worker")
                content
            }
            val grid = initializedGrid(router, worker)
                .setBeforeRouteHook { envelope ->
                    calls.add("beforeRoute")
                    envelope.executionNotes.add("beforeRoute")
                    envelope
                }
                .setBeforeLocalWorkerHook { envelope ->
                    calls.add("beforeLocalWorker")
                    envelope.executionNotes.add("beforeLocalWorker")
                    envelope
                }
                .setAfterLocalWorkerHook { envelope ->
                    calls.add("afterLocalWorker")
                    envelope.executionNotes.add("afterLocalWorker")
                    envelope
                }
                .setOutcomeTransformationHook { content, _ ->
                    calls.add("outcomeTransformation")
                    content.addText("final")
                    content
                }

            val result = grid.execute(MultimodalContent(text = "start"))

            assertEquals(
                listOf(
                    "beforeRoute",
                    "router",
                    "beforeLocalWorker",
                    "worker",
                    "afterLocalWorker",
                    "outcomeTransformation"
                ),
                calls
            )
            assertEquals("start\n\nrouter\n\nworker\n\nfinal", result.text)
        }
    }

    /**
     * Verifies that failure hooks run on the local failure path.
     */
    @Test
    fun invokesFailureHookOnLocalFailure()
    {
        runBlocking {
            val calls = mutableListOf<String>()
            val router = ExecutionInterface("router") { content ->
                content.metadata["distributionGridDirective"] = DistributionGridDirective(
                    kind = DistributionGridDirectiveKind.REJECT,
                    rejectReason = "Nope"
                )
                content
            }
            val worker = ExecutionInterface("worker") { it }
            val grid = initializedGrid(router, worker)
                .setFailureHook { envelope ->
                    calls.add("failureHook")
                    envelope.executionNotes.add("failure hook ran")
                    envelope
                }

            val result = grid.execute(MultimodalContent(text = "fail"))
            val envelope = result.metadata["distributionGridEnvelope"] as? DistributionGridEnvelope

            assertEquals(listOf("failureHook"), calls)
            assertNotNull(envelope)
            assertTrue(envelope.executionNotes.contains("failure hook ran"))
        }
    }

    /**
     * Verifies that local execution trace events are emitted when tracing is enabled.
     */
    @Test
    fun emitsPhaseFourLocalExecutionTraceEvents()
    {
        runBlocking {
            val grid = DistributionGrid()
                .enableTracing()
                .setRouter(ExecutionInterface("router") { it })
                .setWorker(ExecutionInterface("worker") { it })

            grid.init()
            grid.execute(MultimodalContent(text = "trace"))

            val eventTypes = PipeTracer.getTrace(readGridId(grid)).map { it.eventType }

            assertTrue(eventTypes.contains(TraceEventType.DISTRIBUTION_GRID_START))
            assertTrue(eventTypes.contains(TraceEventType.DISTRIBUTION_GRID_ROUTER_DECISION))
            assertTrue(eventTypes.contains(TraceEventType.DISTRIBUTION_GRID_LOCAL_WORKER_DISPATCH))
            assertTrue(eventTypes.contains(TraceEventType.DISTRIBUTION_GRID_LOCAL_WORKER_RESPONSE))
            assertTrue(eventTypes.contains(TraceEventType.DISTRIBUTION_GRID_SUCCESS))
            assertTrue(eventTypes.contains(TraceEventType.DISTRIBUTION_GRID_END))
        }
    }

    /**
     * Verifies that inbound P2P execution returns a boundary rejection when the shell is not ready.
     */
    @Test
    fun executeP2PRequestReturnsBoundaryRejectionWhenShellIsNotReady()
    {
        runBlocking {
            val grid = DistributionGrid()

            val response = grid.executeP2PRequest(
                P2PRequest(prompt = MultimodalContent(text = "not ready"))
            )

            assertNotNull(response)
            assertNull(response.output)
            assertNotNull(response.rejection)
        }
    }

    /**
     * Build and initialize a simple local-only grid for execution tests.
     *
     * @param router Router test double.
     * @param worker Worker test double.
     * @return Initialized grid ready for Phase 4 execution.
     */
    private suspend fun initializedGrid(
        router: ExecutionInterface,
        worker: ExecutionInterface
    ): DistributionGrid
    {
        val grid = DistributionGrid()
        grid.setRouter(router)
        grid.setWorker(worker)
        grid.init()
        return grid
    }

    /**
     * Read the private grid id for trace assertions.
     *
     * @param grid Grid under test.
     * @return Private grid id.
     */
    private fun readGridId(grid: DistributionGrid): String
    {
        val field = grid.javaClass.getDeclaredField("gridId")
        field.isAccessible = true
        return field.get(grid) as String
    }

    /**
     * P2P-backed execution double with configurable local behavior.
     *
     * @param name Stable test identity.
     * @param behavior Local execution behavior to apply.
     */
    private class ExecutionInterface(
        private val name: String,
        private val behavior: suspend (MultimodalContent) -> MultimodalContent
    ) : P2PInterface
    {
        private var descriptor: P2PDescriptor? = null
        private var requirements: P2PRequirements? = null
        private var transport: P2PTransport? = null
        private var containerRef: Any? = null
        override var killSwitch: KillSwitch? = null

        var localExecutionCount: Int = 0
            private set

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
        }

        override fun getP2pDescription(): P2PDescriptor?
        {
            return descriptor
        }

        override fun setP2pTransport(transport: P2PTransport)
        {
            this.transport = transport
        }

        override fun getP2pTransport(): P2PTransport?
        {
            return transport
        }

        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
        }

        override fun getP2pRequirements(): P2PRequirements?
        {
            return requirements
        }

        override fun getContainerObject(): Any?
        {
            return containerRef
        }

        override fun setContainerObject(container: Any)
        {
            containerRef = container
        }

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            localExecutionCount += 1
            return behavior(content)
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            return P2PResponse(output = behavior(request.prompt.deepCopy()))
        }
    }
}
