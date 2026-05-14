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
import com.TTT.Util.deepCopy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression coverage for the Phase 8 DistributionGrid DSL.
 */
class DistributionGridDslTest
{
    /**
     * Verifies that the DSL can assemble, initialize, and execute a fully configured grid node.
     */
    @Test
    fun buildsInitializedGridFromDsl()
    {
        runBlocking {
            val router = ExecutionInterface("router") { content ->
                content.addText("router")
                content.metadata["distributionGridDirective"] = DistributionGridDirective(
                    kind = DistributionGridDirectiveKind.RUN_LOCAL_WORKER
                )
                content
            }
            val worker = ExecutionInterface("worker") { content ->
                content.addText("worker")
                content
            }
            val helperPeer = ExecutionInterface("helper-peer") { content ->
                content.addText("helper")
                content
            }
            val durableStore = RecordingDurableStore()
            val trustVerifier = AllowAllTrustVerifier()
            val bootstrapRegistry = buildRegistryAdvertisement(
                registryId = "bootstrap-registry",
                transportAddress = "https://registry.example"
            )

            val grid = distributionGrid {
                p2p {
                    agentName("grid-node")
                    description("DSL-built grid")
                    transportAddress("grid-node")
                    transportMethod(Transport.Tpipe)
                    acceptedContent(SupportedContentTypes.text)
                    maxTokens(2048)
                }

                security {
                    requiresAuth(true)
                    recordsPromptContent(true)
                    allowExternalConnections(true)
                    authMechanism { authBody -> authBody == "grid-secret" }
                }

                router {
                    component(router)
                }

                worker {
                    component(worker)
                }

                peer("helper-peer") {
                    component(helperPeer)
                }

                peerDescriptor(
                    buildGridDescriptor(
                        nodeId = "remote-node",
                        transportAddress = "https://remote.example/node",
                        transportMethod = Transport.Http
                    )
                )

                discovery {
                    mode(DistributionGridPeerDiscoveryMode.HYBRID)
                    trustVerifier(trustVerifier)
                    bootstrapRegistry(bootstrapRegistry)
                    registryMetadata(
                        DistributionGridRegistryMetadata(
                            registryId = "local-registry",
                            trustDomainId = "primary-trust-domain",
                            mode = DistributionGridRegistryMode.MIXED_ROLE
                        )
                    )
                }

                routing {
                    allowRetrySamePeer(true)
                    maxRetryCount(2)
                    allowAlternatePeers(true)
                    maxHopCount(9)
                }

                memory {
                    outboundTokenBudget(1536)
                    redactContentSections(true)
                    summaryBudget(256)
                }

                durability {
                    store(durableStore)
                }

                tracing {
                    enabled(TraceConfig(enabled = true))
                }

                hooks {
                    beforeRoute { envelope ->
                        envelope.executionNotes.add("dsl-before-route")
                        envelope
                    }
                }

                operations {
                    maxHops(24)
                    rpcTimeoutMillis(45_000L)
                    maxSessionDurationSeconds(600)
                }
            }

            val result = grid.executeLocal(MultimodalContent(text = "start"))
            val envelope = result.metadata["distributionGridEnvelope"] as? DistributionGridEnvelope

            assertEquals("start\n\nrouter\n\nworker", result.text)
            assertNotNull(envelope)
            assertTrue(envelope.executionNotes.contains("dsl-before-route"))
            assertEquals(DistributionGridPeerDiscoveryMode.HYBRID, grid.getDiscoveryMode())
            assertEquals(24, grid.getMaxHops())
            assertEquals(45_000L, grid.getRpcTimeout())
            assertEquals(600, grid.getMaxSessionDuration())
            assertEquals(9, grid.getRoutingPolicy().maxHopCount)
            assertEquals(1536, grid.getMemoryPolicy().outboundTokenBudget)
            assertSame(durableStore, grid.getDurableStore())
            assertTrue(grid.isTracingEnabled())
            assertEquals(listOf("Tpipe::helper-peer"), grid.getLocalPeerKeys())
            assertEquals(listOf("Http::https://remote.example/node"), grid.getExternalPeerKeys())
            assertEquals(listOf("bootstrap-registry"), grid.getBootstrapRegistryIds())
            assertEquals("local-registry", grid.getRegistryMetadata()!!.registryId)
            assertSame(trustVerifier, grid.getTrustVerifier())
            assertEquals("grid-node", grid.getP2pDescription()!!.agentName)
            assertTrue(grid.getP2pDescription()!!.requiresAuth)
            assertTrue(grid.getP2pDescription()!!.recordsPromptContent)
            assertTrue(grid.getP2pRequirements()!!.allowExternalConnections)
            assertNotNull(grid.getP2pRequirements()!!.authMechanism)
        }
    }

    /**
     * Verifies that `buildSuspend()` produces an initialized grid with the same runtime behavior as `build()`.
     */
    @Test
    fun buildSuspendMatchesSynchronousBuild()
    {
        runBlocking {
            val syncGrid = buildSimpleGridDsl().build()
            val suspendGrid = buildSimpleGridDsl().buildSuspend()

            val syncResult = syncGrid.executeLocal(MultimodalContent(text = "sync"))
            val suspendResult = suspendGrid.executeLocal(MultimodalContent(text = "sync"))

            assertEquals(syncResult.text, suspendResult.text)
            assertEquals(syncGrid.getRoutingPolicy(), suspendGrid.getRoutingPolicy())
            assertEquals(syncGrid.getMemoryPolicy(), suspendGrid.getMemoryPolicy())
            assertTrue(syncGrid.isTracingEnabled())
            assertTrue(suspendGrid.isTracingEnabled())
        }
    }

    /**
     * Verifies that the DSL rejects missing mandatory roles and duplicate singleton blocks before runtime work begins.
     */
    @Test
    fun rejectsInvalidDslLayouts()
    {
        assertFailsWith<IllegalArgumentException> {
            distributionGrid {
                worker(ExecutionInterface("worker") { it })
            }
        }

        assertFailsWith<IllegalArgumentException> {
            distributionGrid {
                router(ExecutionInterface("router") { it })
                worker(ExecutionInterface("worker") { it })
                routing { maxHopCount(8) }
                routing { maxHopCount(16) }
            }
        }
    }

    /**
     * Verifies that a DSL-built grid and a raw-API-built grid produce the same local execution result.
     */
    @Test
    fun dslGridMatchesRawApiGridForLocalExecution()
    {
        runBlocking {
            val dslRouter = ExecutionInterface("dsl-router") { content ->
                content.addText("router")
                content
            }
            val dslWorker = ExecutionInterface("dsl-worker") { content ->
                content.addText("worker")
                content
            }
            val rawRouter = ExecutionInterface("raw-router") { content ->
                content.addText("router")
                content
            }
            val rawWorker = ExecutionInterface("raw-worker") { content ->
                content.addText("worker")
                content
            }

            val dslGrid = distributionGrid {
                router(dslRouter)
                worker(dslWorker)
                routing {
                    allowRetrySamePeer(true)
                    maxRetryCount(1)
                }
                memory {
                    outboundTokenBudget(2048)
                }
            }

            val rawGrid = DistributionGrid()
            rawGrid.setRouter(rawRouter)
            rawGrid.setWorker(rawWorker)
            rawGrid.setRoutingPolicy(
                DistributionGridRoutingPolicy(
                    allowRetrySamePeer = true,
                    maxRetryCount = 1
                )
            )
            rawGrid.setMemoryPolicy(
                DistributionGridMemoryPolicy(
                    outboundTokenBudget = 2048
                )
            )
            rawGrid.init()

            val dslResult = dslGrid.executeLocal(MultimodalContent(text = "start"))
            val rawResult = rawGrid.executeLocal(MultimodalContent(text = "start"))

            assertEquals(rawResult.text, dslResult.text)
            assertEquals(rawGrid.getRoutingPolicy(), dslGrid.getRoutingPolicy())
            assertEquals(rawGrid.getMemoryPolicy(), dslGrid.getMemoryPolicy())
        }
    }

    /**
     * Verifies that the `peer(component)` shorthand registers a local peer without requiring a key or block.
     */
    @Test
    fun peerComponentShorthandRegistersLocalPeer()
    {
        val peer = ExecutionInterface("shorthand-peer") { it }

        val grid = distributionGrid {
            router(ExecutionInterface("router") { it })
            worker(ExecutionInterface("worker") { it })
            peer(peer)
        }

        assertEquals(1, grid.getLocalPeerKeys().size)
    }

    private fun buildSimpleGridDsl(): DistributionGridBuilder<GridStage.Ready>
    {
        val router = ExecutionInterface("router") { content ->
            content.addText("router")
            content
        }
        val worker = ExecutionInterface("worker") { content ->
            content.addText("worker")
            content
        }

        return distributionGridBuilder()
            .p2p {
                agentName("simple-grid")
                transportAddress("simple-grid")
                transportMethod(Transport.Tpipe)
            }
            .router(router)
            .worker(worker)
            .routing {
                maxHopCount(7)
            }
            .memory {
                outboundTokenBudget(1024)
            }
            .tracing {
                enabled()
            }
    }

    private fun buildGridDescriptor(
        nodeId: String,
        transportAddress: String,
        transportMethod: Transport = Transport.Tpipe
    ): P2PDescriptor
    {
        return P2PDescriptor(
            agentName = nodeId,
            agentDescription = "DistributionGrid DSL test node",
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
            supportedContentTypes = mutableListOf(SupportedContentTypes.text),
            distributionGridMetadata = DistributionGridNodeMetadata(
                nodeId = nodeId,
                supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
                roleCapabilities = mutableListOf("Router", "Worker"),
                supportedTransports = mutableListOf(transportMethod),
                defaultTracePolicy = DistributionGridTracePolicy(),
                defaultRoutingPolicy = DistributionGridRoutingPolicy(),
                actsAsRegistry = false
            )
        )
    }

    private fun buildRegistryAdvertisement(
        registryId: String,
        transportAddress: String
    ): DistributionGridRegistryAdvertisement
    {
        return DistributionGridRegistryAdvertisement(
            transport = P2PTransport(
                transportMethod = Transport.Http,
                transportAddress = transportAddress
            ),
            metadata = DistributionGridRegistryMetadata(
                registryId = registryId,
                trustDomainId = "primary-trust-domain",
                mode = DistributionGridRegistryMode.DEDICATED
            ),
            discoveredAtEpochMillis = 1L,
            expiresAtEpochMillis = Long.MAX_VALUE
        )
    }

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

    private class AllowAllTrustVerifier : DistributionGridTrustVerifier
    {
        override suspend fun verifyRegistryAdvertisement(
            candidate: DistributionGridRegistryAdvertisement,
            trustedParents: List<DistributionGridRegistryAdvertisement>
        ): DistributionGridFailure?
        {
            return null
        }

        override suspend fun verifyNodeAdvertisement(
            registryAdvertisement: DistributionGridRegistryAdvertisement,
            candidate: DistributionGridNodeAdvertisement
        ): DistributionGridFailure?
        {
            return null
        }
    }

    private class ExecutionInterface(
        private val name: String,
        private val behavior: suspend (MultimodalContent) -> MultimodalContent
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

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            return behavior(content)
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            return P2PResponse(output = behavior(request.prompt.deepCopy()))
        }
    }
}
