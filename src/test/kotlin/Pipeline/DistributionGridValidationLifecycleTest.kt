package com.TTT.Pipeline

import com.TTT.Debug.PipeTracer
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.Transport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Regression coverage for the Phase 3 `DistributionGrid` validation, lifecycle, and shared-infrastructure slice.
 */
class DistributionGridValidationLifecycleTest
{
    /**
     * Verifies that `init()` requires a router binding.
     */
    @Test
    fun initFailsWithoutRouter()
    {
        runBlocking {
            val grid = DistributionGrid()
            grid.setWorker(PipelineBackedInterface("worker"))

            assertFailsWith<IllegalArgumentException> {
                grid.init()
            }
        }
    }

    /**
     * Verifies that `init()` requires a worker binding.
     */
    @Test
    fun initFailsWithoutWorker()
    {
        runBlocking {
            val grid = DistributionGrid()
            grid.setRouter(PipelineBackedInterface("router"))

            assertFailsWith<IllegalArgumentException> {
                grid.init()
            }
        }
    }

    /**
     * Verifies that `init()` synthesizes the outward node identity and explicit grid metadata when they are missing.
     */
    @Test
    fun initSynthesizesGridIdentityAndMetadata()
    {
        runBlocking {
            val grid = DistributionGrid()
            val router = PipelineBackedInterface("router")
            val worker = PipelineBackedInterface("worker")

            grid.setRouter(router)
            grid.setWorker(worker)
            grid.init()

            val descriptor = grid.getP2pDescription()
            val transport = grid.getP2pTransport()
            val requirements = grid.getP2pRequirements()

            assertNotNull(descriptor)
            assertNotNull(transport)
            assertNotNull(requirements)
            assertEquals(Transport.Tpipe, transport.transportMethod)
            assertTrue(descriptor.agentName.startsWith("distribution-grid-node-"))
            assertNotNull(descriptor.distributionGridMetadata)
            assertEquals(readGridId(grid), descriptor.distributionGridMetadata!!.nodeId)
            assertTrue(readInitialized(grid))
        }
    }

    /**
     * Verifies that local bindings already owned by another container are rejected during init.
     */
    @Test
    fun initRejectsForeignOwnedBinding()
    {
        runBlocking {
            val grid = DistributionGrid()
            val router = PipelineBackedInterface("router")
            val worker = PipelineBackedInterface("worker")
            val foreignOwner = LinkedContainer("foreign-owner")

            grid.setRouter(router)
            grid.setWorker(worker)
            router.setContainerObject(foreignOwner)

            assertFailsWith<IllegalArgumentException> {
                grid.init()
            }
        }
    }

    /**
     * Verifies that ancestry cycles are rejected before any execution phase exists.
     */
    @Test
    fun initRejectsContainerCycle()
    {
        runBlocking {
            val grid = DistributionGrid()
            val router = PipelineBackedInterface("router")
            val worker = PipelineBackedInterface("worker")

            grid.setRouter(router)
            grid.setWorker(worker)
            grid.setContainerObject(router)

            assertFailsWith<IllegalStateException> {
                grid.init()
            }
        }
    }

    /**
     * Verifies that ancestry chains deeper than the supported Phase 3 limit are rejected.
     */
    @Test
    fun initRejectsNestingDeeperThanEight()
    {
        runBlocking {
            val grid = DistributionGrid()
            val router = PipelineBackedInterface("router")
            val worker = PipelineBackedInterface("worker")

            grid.setRouter(router)
            grid.setWorker(worker)

            val wrappers = (1..8).map { index -> LinkedContainer("wrapper-$index") }
            grid.setContainerObject(wrappers.first())
            wrappers.zipWithNext().forEach { (current, next) ->
                current.setContainerObject(next)
            }

            assertFailsWith<IllegalStateException> {
                grid.init()
            }
        }
    }

    /**
     * Verifies that child pipeline discovery exposes router, worker, and local peers while excluding external peers.
     */
    @Test
    fun exposesOnlyLocalChildPipelines()
    {
        val grid = DistributionGrid()
        val router = PipelineBackedInterface("router")
        val worker = PipelineBackedInterface("worker")
        val peer = PipelineBackedInterface("peer")

        grid.setRouter(router)
        grid.setWorker(worker)
        grid.addPeer(peer)
        grid.addPeerDescriptor(
            buildDescriptor(
                name = "remote-peer",
                transportMethod = Transport.Http,
                transportAddress = "https://grid.example/peer"
            )
        )

        val pipelines = grid.getPipelinesFromInterface()

        assertEquals(3, pipelines.size)
        assertTrue(pipelines.contains(router.pipeline))
        assertTrue(pipelines.contains(worker.pipeline))
        assertTrue(pipelines.contains(peer.pipeline))
    }

    /**
     * Verifies that pause and resume only operate on shell state in Phase 3.
     */
    @Test
    fun pauseAndResumeManageShellState()
    {
        val grid = DistributionGrid()

        assertFalse(grid.canPause())
        assertFalse(grid.isPaused())

        grid.setRouter(PipelineBackedInterface("router"))
        grid.setWorker(PipelineBackedInterface("worker"))

        assertTrue(grid.canPause())
        grid.pause()
        assertTrue(grid.isPaused())
        grid.resume()
        assertFalse(grid.isPaused())
    }

    /**
     * Verifies that runtime clearing resets transient state while preserving shell configuration.
     */
    @Test
    fun clearRuntimeStatePreservesConfiguration()
    {
        runBlocking {
            val grid = DistributionGrid()
            val routingPolicy = DistributionGridRoutingPolicy(maxHopCount = 24)

            grid.setRouter(PipelineBackedInterface("router"))
            grid.setWorker(PipelineBackedInterface("worker"))
            grid.setRoutingPolicy(routingPolicy)
            grid.setMaxHops(24)
            grid.pause()
            grid.init()

            assertTrue(readInitialized(grid))
            assertFalse(grid.isPaused())

            grid.pause()
            assertTrue(grid.isPaused())
            grid.clearRuntimeState()

            assertFalse(readInitialized(grid))
            assertFalse(grid.isPaused())
            assertEquals("router", grid.getRouterBindingKey())
            assertEquals("worker", grid.getWorkerBindingKey())
            assertSame(routingPolicy, grid.getRoutingPolicy())
            assertEquals(24, grid.getMaxHops())
        }
    }

    /**
     * Verifies that clearing the trace removes the grid trace history without disabling tracing.
     */
    @Test
    fun clearTraceRemovesGridTraceHistory()
    {
        runBlocking {
            val grid = DistributionGrid()

            grid.enableTracing()
            grid.setRouter(PipelineBackedInterface("router"))
            grid.setWorker(PipelineBackedInterface("worker"))
            grid.init()

            val gridTraceId = readGridId(grid)
            assertTrue(PipeTracer.getTrace(gridTraceId).isNotEmpty())
            assertTrue(grid.isTracingEnabled())

            grid.clearTrace()

            assertTrue(PipeTracer.getTrace(gridTraceId).isEmpty())
            assertTrue(grid.isTracingEnabled())
        }
    }

    /**
     * Verifies that configuration mutations after init mark the shell dirty again.
     */
    @Test
    fun configMutationMarksShellDirtyAfterInit()
    {
        runBlocking {
            val grid = DistributionGrid()

            grid.setRouter(PipelineBackedInterface("router"))
            grid.setWorker(PipelineBackedInterface("worker"))
            grid.init()

            assertTrue(readInitialized(grid))
            grid.setMaxHops(32)
            assertFalse(readInitialized(grid))
        }
    }

    /**
     * Build a minimal descriptor for external-peer and identity tests.
     *
     * @param name Descriptor agent name.
     * @param transportMethod Transport method assigned to the descriptor.
     * @param transportAddress Transport address assigned to the descriptor.
     * @return Minimal descriptor suitable for validation tests.
     */
    private fun buildDescriptor(
        name: String,
        transportMethod: Transport,
        transportAddress: String
    ): P2PDescriptor
    {
        return P2PDescriptor(
            agentName = name,
            agentDescription = "Test descriptor for $name",
            transport = P2PTransport(
                transportMethod = transportMethod,
                transportAddress = transportAddress
            ),
            requiresAuth = false,
            usesConverse = false,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none,
            supportedContentTypes = mutableListOf(SupportedContentTypes.text)
        )
    }

    /**
     * Read the private grid identifier for trace assertions.
     *
     * @param grid Grid instance under test.
     * @return Private grid identifier.
     */
    private fun readGridId(grid: DistributionGrid): String
    {
        val field = grid.javaClass.getDeclaredField("gridId")
        field.isAccessible = true
        return field.get(grid) as String
    }

    /**
     * Read the private initialized flag for lifecycle assertions.
     *
     * @param grid Grid instance under test.
     * @return `true` when the shell is currently initialized.
     */
    private fun readInitialized(grid: DistributionGrid): Boolean
    {
        val field = grid.javaClass.getDeclaredField("initialized")
        field.isAccessible = true
        return field.getBoolean(grid)
    }

    /**
     * P2P test double that exposes one local pipeline and configurable container ancestry.
     *
     * @param name Stable identity used only for diagnostics inside test failures.
     */
    private class PipelineBackedInterface(
        private val name: String
    ) : P2PInterface
    {
        val pipeline = Pipeline().also { candidate ->
            candidate.pipelineName = name
        }

        private var descriptor: P2PDescriptor? = null
        private var requirements: P2PRequirements? = null
        private var transport: P2PTransport? = null
        private var containerRef: Any? = null

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

        override fun getPipelinesFromInterface(): List<Pipeline>
        {
            return listOf(pipeline)
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            return P2PResponse(output = MultimodalContent(text = name))
        }
    }

    /**
     * Minimal P2P container used to build ancestry chains in tests.
     *
     * @param name Stable name used only for readable diagnostics.
     */
    private class LinkedContainer(
        private val name: String
    ) : P2PInterface
    {
        private var containerRef: Any? = null
        private val descriptor = P2PDescriptor(
            agentName = name,
            agentDescription = "Linked container for $name",
            transport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = name
            ),
            requiresAuth = false,
            usesConverse = false,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none,
            supportedContentTypes = mutableListOf(SupportedContentTypes.text)
        )

        override fun getContainerObject(): Any?
        {
            return containerRef
        }

        override fun setContainerObject(container: Any)
        {
            containerRef = container
        }

        override fun getP2pDescription(): P2PDescriptor
        {
            return descriptor
        }
    }
}
