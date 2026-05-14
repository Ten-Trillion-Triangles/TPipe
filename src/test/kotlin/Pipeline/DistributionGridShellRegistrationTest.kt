package com.TTT.Pipeline

import com.TTT.Debug.TraceConfig
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.KillSwitch
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
 * Regression coverage for the Phase 2 `DistributionGrid` shell and registration semantics.
 */
class DistributionGridShellRegistrationTest
{
    /**
     * Verifies that grid-level P2P identity state is stored and returned correctly.
     */
    @Test
    fun storesGridLevelP2pIdentity()
    {
        val grid = DistributionGrid()
        val descriptor = buildDescriptor(
            name = "grid-node",
            transportMethod = Transport.Tpipe,
            transportAddress = "grid-node"
        )
        val transport = P2PTransport(
            transportMethod = Transport.Http,
            transportAddress = "https://grid.example/node"
        )
        val requirements = P2PRequirements(
            allowExternalConnections = true,
            acceptedContent = mutableListOf(SupportedContentTypes.text)
        )
        val parentContainer = Any()

        grid.setP2pDescription(descriptor)
        grid.setP2pTransport(transport)
        grid.setP2pRequirements(requirements)
        grid.setContainerObject(parentContainer)

        assertSame(descriptor, grid.getP2pDescription())
        assertSame(transport, grid.getP2pTransport())
        assertSame(requirements, grid.getP2pRequirements())
        assertSame(parentContainer, grid.getContainerObject())
    }

    /**
     * Verifies that router and worker bindings synthesize safe local defaults and adopt the grid as their container
     * when they were previously unbound.
     */
    @Test
    fun synthesizesSafeDefaultsForRouterAndWorkerBindings()
    {
        val grid = DistributionGrid()
        val router = RecordingP2PInterface("router")
        val worker = RecordingP2PInterface("worker")

        grid.setRouter(router)
        grid.setWorker(worker)

        val routerDescriptor = router.getP2pDescription()
        val workerDescriptor = worker.getP2pDescription()
        val routerRequirements = router.getP2pRequirements()
        val workerRequirements = worker.getP2pRequirements()

        assertNotNull(routerDescriptor)
        assertNotNull(workerDescriptor)
        assertNotNull(routerRequirements)
        assertNotNull(workerRequirements)
        assertSame(grid, router.getContainerObject())
        assertSame(grid, worker.getContainerObject())
        assertEquals("router", grid.getRouterBindingKey())
        assertEquals("worker", grid.getWorkerBindingKey())
        assertEquals(Transport.Tpipe, routerDescriptor.transport.transportMethod)
        assertEquals(Transport.Tpipe, workerDescriptor.transport.transportMethod)
        assertTrue(routerDescriptor.agentName.startsWith("distribution-grid-router-"))
        assertTrue(workerDescriptor.agentName.startsWith("distribution-grid-worker-"))
        assertFalse(routerDescriptor.requiresAuth)
        assertFalse(routerDescriptor.usesConverse)
        assertFalse(routerRequirements.allowExternalConnections)
        assertFalse(workerRequirements.allowExternalConnections)
        assertEquals(ContextProtocol.none, routerDescriptor.contextProtocol)
        assertEquals(ContextProtocol.none, workerDescriptor.contextProtocol)
    }

    /**
     * Verifies that router and worker rebinding replaces the prior binding cleanly.
     */
    @Test
    fun replacesRouterAndWorkerBindings()
    {
        val grid = DistributionGrid()
        val firstRouter = RecordingP2PInterface("router-1")
        val secondRouter = RecordingP2PInterface("router-2")
        val firstWorker = RecordingP2PInterface("worker-1")
        val secondWorker = RecordingP2PInterface("worker-2")

        grid.setRouter(firstRouter)
        grid.setWorker(firstWorker)
        grid.setRouter(secondRouter)
        grid.setWorker(secondWorker)

        assertSame(grid, secondRouter.getContainerObject())
        assertSame(grid, secondWorker.getContainerObject())
        assertEquals("router", grid.getRouterBindingKey())
        assertEquals("worker", grid.getWorkerBindingKey())
        assertTrue(secondRouter.getP2pDescription()!!.agentName.startsWith("distribution-grid-router-"))
        assertTrue(secondWorker.getP2pDescription()!!.agentName.startsWith("distribution-grid-worker-"))
    }

    /**
     * Verifies that local peers get stable canonical keys and duplicate keys are rejected.
     */
    @Test
    fun addsLocalPeersAndRejectsDuplicates()
    {
        val grid = DistributionGrid()
        val peer = RecordingP2PInterface("peer-a")
        val duplicatePeer = RecordingP2PInterface("peer-b")

        peer.setP2pDescription(
            buildDescriptor(
                name = "peer-a",
                transportMethod = Transport.Tpipe,
                transportAddress = "peer-a"
            )
        )
        peer.setP2pTransport(P2PTransport(transportMethod = Transport.Tpipe, transportAddress = "peer-a"))

        duplicatePeer.setP2pDescription(
            buildDescriptor(
                name = "peer-b",
                transportMethod = Transport.Tpipe,
                transportAddress = "peer-a"
            )
        )
        duplicatePeer.setP2pTransport(P2PTransport(transportMethod = Transport.Tpipe, transportAddress = "peer-a"))

        grid.addPeer(peer)

        assertEquals(listOf("${Transport.Tpipe}::peer-a"), grid.getLocalPeerKeys())
        assertSame(grid, peer.getContainerObject())
        assertFailsWith<IllegalArgumentException> {
            grid.addPeer(duplicatePeer)
        }
    }

    /**
     * Verifies that external peer descriptors are stored by canonical key and duplicate keys are rejected.
     */
    @Test
    fun addsExternalPeerDescriptorsAndRejectsDuplicates()
    {
        val grid = DistributionGrid()
        val descriptor = buildDescriptor(
            name = "external-peer",
            transportMethod = Transport.Http,
            transportAddress = "https://grid.example/external"
        )
        val duplicateDescriptor = buildDescriptor(
            name = "external-peer-duplicate",
            transportMethod = Transport.Http,
            transportAddress = "https://grid.example/external"
        )

        grid.addPeerDescriptor(descriptor)

        assertEquals(listOf("${Transport.Http}::https://grid.example/external"), grid.getExternalPeerKeys())
        assertFailsWith<IllegalArgumentException> {
            grid.addPeerDescriptor(duplicateDescriptor)
        }
    }

    /**
     * Verifies that local peers can be replaced in place and both local and external peers can be removed by key.
     */
    @Test
    fun replacesAndRemovesPeersByKey()
    {
        val grid = DistributionGrid()
        val originalPeer = RecordingP2PInterface("peer-a")
        val replacementPeer = RecordingP2PInterface("peer-b")
        val externalDescriptor = buildDescriptor(
            name = "external-peer",
            transportMethod = Transport.Http,
            transportAddress = "https://grid.example/external"
        )

        originalPeer.setP2pDescription(
            buildDescriptor(
                name = "peer-a",
                transportMethod = Transport.Tpipe,
                transportAddress = "peer-a"
            )
        )
        originalPeer.setP2pTransport(P2PTransport(transportMethod = Transport.Tpipe, transportAddress = "peer-a"))

        grid.addPeer(originalPeer)
        grid.addPeerDescriptor(externalDescriptor)

        val peerKey = grid.getLocalPeerKeys().single()
        val externalKey = grid.getExternalPeerKeys().single()

        grid.replacePeer(peerKey, replacementPeer)

        assertEquals(listOf(peerKey), grid.getLocalPeerKeys())
        assertSame(grid, replacementPeer.getContainerObject())
        assertEquals("peer-a", replacementPeer.getP2pTransport()!!.transportAddress)

        grid.removePeer(peerKey)
        grid.removePeer(externalKey)

        assertTrue(grid.getLocalPeerKeys().isEmpty())
        assertTrue(grid.getExternalPeerKeys().isEmpty())
        assertFailsWith<IllegalArgumentException> {
            grid.removePeer(peerKey)
        }
    }

    /**
     * Verifies that configuration setters store the shell state needed for later phases.
     */
    @Test
    fun storesConfigurationState()
    {
        val grid = DistributionGrid()
        val routingPolicy = DistributionGridRoutingPolicy(
            completionReturnMode = DistributionGridReturnMode.RETURN_TO_ORIGIN,
            maxHopCount = 24
        )
        val memoryPolicy = DistributionGridMemoryPolicy(
            outboundTokenBudget = 4096,
            maxExecutionNotes = 5
        )
        val durableStore = RecordingDurableStore()
        val traceConfig = TraceConfig(enabled = true, includeMetadata = false)

        grid.setDiscoveryMode(DistributionGridPeerDiscoveryMode.EXPLICIT_ONLY)
        grid.setRoutingPolicy(routingPolicy)
        grid.setMemoryPolicy(memoryPolicy)
        grid.setDurableStore(durableStore)
        grid.setMaxHops(24)
        grid.enableTracing(traceConfig)

        assertEquals(DistributionGridPeerDiscoveryMode.EXPLICIT_ONLY, grid.getDiscoveryMode())
        assertEquals(routingPolicy, grid.getRoutingPolicy())
        assertEquals(memoryPolicy, grid.getMemoryPolicy())
        assertSame(durableStore, grid.getDurableStore())
        assertEquals(24, grid.getMaxHops())
        assertTrue(grid.isTracingEnabled())

        grid.disableTracing()
        assertFalse(grid.isTracingEnabled())
    }

    /**
     * Helper used to build a minimal descriptor for registration tests.
     *
     * @param name Descriptor agent name.
     * @param transportMethod Transport method assigned to the descriptor.
     * @param transportAddress Transport address assigned to the descriptor.
     * @return Minimal descriptor suitable for shell-registration tests.
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
     * Test double used to verify `DistributionGrid` registration behavior without execution semantics.
     *
     * @param name Stable identity used only for test readability.
     */
    private class RecordingP2PInterface(
        private val name: String
    ) : P2PInterface
    {
        private var descriptor: P2PDescriptor? = null
        private var requirements: P2PRequirements? = null
        private var transport: P2PTransport? = null
        private var containerRef: Any? = null
    private var parentInterface: P2PInterface? = null
        override var killSwitch: KillSwitch? = null

    override fun setParentInterface(parent: P2PInterface)
    {
        // no-op
    }
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

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            return P2PResponse(output = MultimodalContent(text = name))
        }
    }

    /**
     * Simple durable-store fake used to verify that the shell stores the configured durable-store reference.
     */
    private class RecordingDurableStore : DistributionGridDurableStore
    {
        override suspend fun saveState(state: DistributionGridDurableState)
        {
        }

        override suspend fun loadState(taskId: String): DistributionGridDurableState?
        {
            return null
        }

        override suspend fun resumeState(taskId: String): DistributionGridDurableState?
        {
            return null
        }

        override suspend fun clearState(taskId: String): Boolean
        {
            return true
        }

        override suspend fun archiveState(taskId: String): Boolean
        {
            return true
        }
    }
}
