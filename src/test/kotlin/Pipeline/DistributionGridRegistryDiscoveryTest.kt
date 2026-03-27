package com.TTT.Pipeline

import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.P2PError
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.deepCopy
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Focused coverage for Phase 6 registry discovery and membership behavior.
 */
class DistributionGridRegistryDiscoveryTest
{
    private companion object
    {
        private const val GRID_RPC_FRAME_PREFIX = "TPipe-DistributionGrid-RPC::1"
        private const val REGISTRY_ADVERTISEMENT_PAYLOAD_TYPE = "DistributionGridRegistryAdvertisement"
        private const val REGISTRY_QUERY_RESULT_PAYLOAD_TYPE = "DistributionGridRegistryQueryResult"
    }

    /**
     * Verifies that bootstrap registries can be configured, probed, and cached as verified discovery roots.
     */
    @Test
    fun bootstrapRegistryProbeCachesVerifiedAdvertisement()
    {
        runBlocking {
            val registryGrid = registryGrid(
                nodeId = "dedicated-registry-node",
                transportAddress = "dedicated-registry-address",
                registryId = "bootstrap-registry",
                mode = DistributionGridRegistryMode.DEDICATED,
                bootstrapTrusted = true
            )

            try
            {
                val clientGrid = initializedGrid(
                    nodeId = "registry-client-node",
                    transportAddress = "registry-client-address"
                )
                clientGrid.addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryGrid))
                clientGrid.init()

                val advertisements = clientGrid.probeTrustedRegistries()

                assertEquals(listOf("bootstrap-registry"), clientGrid.getBootstrapRegistryIds())
                assertEquals(listOf("bootstrap-registry"), clientGrid.getDiscoveredRegistryIds())
                assertEquals(1, advertisements.size)
                assertEquals(DistributionGridRegistryMode.DEDICATED, advertisements.first().metadata.mode)

                clientGrid.removeBootstrapRegistry("bootstrap-registry")
                assertTrue(clientGrid.getBootstrapRegistryIds().isEmpty())
            }

            finally
            {
                P2PRegistry.remove(registryGrid)
            }
        }
    }

    /**
     * Verifies that configured bootstrap roots remain usable even when their source advertisement is already stale.
     */
    @Test
    fun bootstrapRegistryRootsRemainUsableAfterSourceAdvertisementExpiry()
    {
        runBlocking {
            val registryGrid = registryGrid(
                nodeId = "expired-bootstrap-registry-node",
                transportAddress = "expired-bootstrap-registry-address",
                registryId = "expired-bootstrap-registry",
                mode = DistributionGridRegistryMode.DEDICATED,
                bootstrapTrusted = true
            )

            try
            {
                val clientGrid = initializedGrid(
                    nodeId = "expired-bootstrap-client-node",
                    transportAddress = "expired-bootstrap-client-address"
                )
                clientGrid.addBootstrapRegistry(
                    buildBootstrapRegistryAdvertisement(registryGrid).apply {
                        expiresAtEpochMillis = System.currentTimeMillis() - 1_000L
                    }
                )
                clientGrid.init()

                val advertisements = clientGrid.probeTrustedRegistries()

                assertEquals(1, advertisements.size)
                assertEquals(listOf("expired-bootstrap-registry"), clientGrid.getBootstrapRegistryIds())
                assertEquals(listOf("expired-bootstrap-registry"), clientGrid.getDiscoveredRegistryIds())
            }

            finally
            {
                P2PRegistry.remove(registryGrid)
            }
        }
    }

    /**
     * Verifies that a custom trust verifier can reject a probed registry advertisement.
     */
    @Test
    fun trustVerifierCanRejectRegistryProbe()
    {
        runBlocking {
            val registryGrid = registryGrid(
                nodeId = "rejected-registry-node",
                transportAddress = "rejected-registry-address",
                registryId = "rejected-registry",
                mode = DistributionGridRegistryMode.DEDICATED,
                bootstrapTrusted = true
            )

            try
            {
                val clientGrid = initializedGrid(
                    nodeId = "rejected-client-node",
                    transportAddress = "rejected-client-address"
                )
                clientGrid
                    .setTrustVerifier(object : DistributionGridTrustVerifier {
                        override suspend fun verifyRegistryAdvertisement(
                            candidate: DistributionGridRegistryAdvertisement,
                            trustedParents: List<DistributionGridRegistryAdvertisement>
                        ): DistributionGridFailure?
                        {
                            return DistributionGridFailure(
                                kind = DistributionGridFailureKind.TRUST_REJECTED,
                                reason = "registry rejected for test coverage",
                                retryable = false
                            )
                        }

                        override suspend fun verifyNodeAdvertisement(
                            registryAdvertisement: DistributionGridRegistryAdvertisement,
                            candidate: DistributionGridNodeAdvertisement
                        ): DistributionGridFailure?
                        {
                            return null
                        }
                    })
                    .addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryGrid))
                clientGrid.init()

                val advertisements = clientGrid.probeTrustedRegistries()

                assertTrue(advertisements.isEmpty())
                assertTrue(clientGrid.getDiscoveredRegistryIds().isEmpty())
            }

            finally
            {
                P2PRegistry.remove(registryGrid)
            }
        }
    }

    /**
     * Verifies that learned registries with blank trust domains are rejected rather than chained through a trusted parent.
     */
    @Test
    fun learnedRegistryWithBlankTrustDomainIsRejected()
    {
        runBlocking {
            val trustedRegistry = registryGrid(
                nodeId = "trusted-registry-node",
                transportAddress = "trusted-registry-address",
                registryId = "trusted-registry",
                mode = DistributionGridRegistryMode.DEDICATED,
                bootstrapTrusted = true
            )
            val candidateRegistry = registryGrid(
                nodeId = "candidate-registry-node",
                transportAddress = "candidate-registry-address",
                registryId = "candidate-registry",
                mode = DistributionGridRegistryMode.DEDICATED,
                bootstrapTrusted = false
            )

            try
            {
                val clientGrid = initializedGrid(
                    nodeId = "trust-reject-client-node",
                    transportAddress = "trust-reject-client-address"
                )
                val verifier = clientGrid.getTrustVerifier()
                val failure = verifier.verifyRegistryAdvertisement(
                    candidate = buildRegistryAdvertisement(
                        registryId = "candidate-registry",
                        transportAddress = "candidate-registry-address"
                    ).apply {
                        metadata.trustDomainId = ""
                        metadata.bootstrapTrusted = false
                    },
                    trustedParents = listOf(
                        buildRegistryAdvertisement(
                            registryId = "trusted-registry",
                            transportAddress = "trusted-registry-address"
                        )
                    )
                )

                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.TRUST_REJECTED, failure.kind)
                assertTrue(failure.reason.contains("trust domain"))
                assertTrue(clientGrid.getDiscoveredRegistryIds().isEmpty())
            }

            finally
            {
                P2PRegistry.remove(candidateRegistry)
                P2PRegistry.remove(trustedRegistry)
            }
        }
    }

    /**
     * Verifies that registration, renewal, and manual lease ticking keep registry memberships in sync.
     */
    @Test
    fun registerRenewAndTickKeepMembershipStateInSync()
    {
        runBlocking {
            val registryGrid = registryGrid(
                nodeId = "lease-registry-node",
                transportAddress = "lease-registry-address",
                registryId = "lease-registry",
                mode = DistributionGridRegistryMode.DEDICATED,
                bootstrapTrusted = true,
                defaultLeaseSeconds = 5
            )

            try
            {
                val clientGrid = initializedGrid(
                    nodeId = "lease-client-node",
                    transportAddress = "lease-client-address"
                )
                clientGrid.addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryGrid))
                clientGrid.init()

                val initialLease = clientGrid.registerWithRegistry("lease-registry", requestedLeaseSeconds = 5)
                assertNotNull(initialLease)
                assertEquals(listOf("lease-registry"), readRegistryMemberships(clientGrid))
                assertEquals(1, clientGrid.getActiveRegistryLeaseIds().size)

                val renewedLease = clientGrid.renewRegistryLease("lease-registry")
                assertNotNull(renewedLease)
                assertTrue(renewedLease.expiresAtEpochMillis >= initialLease.expiresAtEpochMillis)

                forceActiveLeaseExpiry(clientGrid, "lease-registry", System.currentTimeMillis() + 250L)
                val tickResults = clientGrid.tickRegistryMemberships(nowEpochMillis = System.currentTimeMillis())

                assertEquals(1, tickResults.size)
                assertEquals(listOf("lease-registry"), readRegistryMemberships(clientGrid))
            }

            finally
            {
                P2PRegistry.remove(registryGrid)
            }
        }
    }

    /**
     * Verifies that trust-domain-scoped registry queries do not accept a registry whose trust domain is blank.
     */
    @Test
    fun trustDomainFilteredQueriesRejectBlankRegistryTrustDomain()
    {
        runBlocking {
            val blankTrustRegistry = initializedGrid(
                nodeId = "blank-trust-registry-node",
                transportAddress = "blank-trust-registry-address",
                router = ExecutionInterface("blank-trust-router") { it },
                worker = ExecutionInterface("blank-trust-worker") { it },
                registryMetadata = DistributionGridRegistryMetadata(
                    registryId = "blank-trust-registry",
                    trustDomainId = "",
                    bootstrapTrusted = true,
                    leaseRequired = true,
                    defaultLeaseSeconds = 30,
                    supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
                    mode = DistributionGridRegistryMode.DEDICATED
                )
            )

            val candidate = DistributionGridNodeAdvertisement(
                descriptor = buildGridDescriptor(
                    nodeId = "blank-trust-node",
                    transportAddress = "blank-trust-node-address"
                ),
                metadata = DistributionGridNodeMetadata(
                    nodeId = "blank-trust-node",
                    roleCapabilities = mutableListOf("Worker"),
                    registryMemberships = mutableListOf("blank-trust-registry"),
                    supportedTransports = mutableListOf(Transport.Tpipe)
                ),
                registryId = "blank-trust-registry",
                attestationRef = "attestation:blank-trust-node",
                discoveredAtEpochMillis = System.currentTimeMillis(),
                expiresAtEpochMillis = System.currentTimeMillis() + 60_000L
            )

            assertFalse(
                matchesRegistryQuery(
                    grid = blankTrustRegistry,
                    advertisement = candidate,
                    query = DistributionGridRegistryQuery(
                        requiredCapabilities = mutableListOf("Worker"),
                        acceptedTransports = mutableListOf(Transport.Tpipe),
                        registryIds = mutableListOf("blank-trust-registry"),
                        trustDomainIds = mutableListOf("trust-domain-should-not-match"),
                        requireHealthy = true,
                        freshnessWindowSeconds = 300
                    )
                )
            )
        }
    }

    /**
     * Verifies that a fresh registration replaces any previous live lease for the same node instead of leaving stale registry state behind.
     */
    @Test
    fun reRegisterReplacesPreviousLeaseForSameNode()
    {
        runBlocking {
            val registryGrid = registryGrid(
                nodeId = "replace-registry-node",
                transportAddress = "replace-registry-address",
                registryId = "replace-registry",
                mode = DistributionGridRegistryMode.DEDICATED,
                bootstrapTrusted = true,
                defaultLeaseSeconds = 30
            )

            try
            {
                val clientGrid = initializedGrid(
                    nodeId = "replace-client-node",
                    transportAddress = "replace-client-address"
                )
                clientGrid.addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryGrid))
                clientGrid.init()

                val firstLease = clientGrid.registerWithRegistry("replace-registry", requestedLeaseSeconds = 30)
                assertNotNull(firstLease)
                assertEquals(listOf(firstLease.leaseId), readLocalRegistrationLeaseIds(registryGrid))
                assertEquals(firstLease.leaseId, readLocalRegistrationLeaseIdByNodeId(registryGrid, "replace-client-node"))

                val secondLease = clientGrid.registerWithRegistry("replace-registry", requestedLeaseSeconds = 30)
                assertNotNull(secondLease)
                assertTrue(secondLease.leaseId != firstLease.leaseId)
                assertEquals(listOf(secondLease.leaseId), readLocalRegistrationLeaseIds(registryGrid))
                assertEquals(secondLease.leaseId, readLocalRegistrationLeaseIdByNodeId(registryGrid, "replace-client-node"))
                assertFalse(readLocalRegistrationLeaseIds(registryGrid).contains(firstLease.leaseId))
                assertEquals(listOf("replace-client-node"), readLocalRegisteredNodeAdvertisementIds(registryGrid))
            }

            finally
            {
                P2PRegistry.remove(registryGrid)
            }
        }
    }

    /**
     * Verifies that expired registry advertisements are evicted before registry RPCs can reuse them.
     */
    @Test
    fun expiredRegistryAdvertisementIsEvictedBeforeReuse()
    {
        runBlocking {
            val registryGrid = registryGrid(
                nodeId = "evict-registry-node",
                transportAddress = "evict-registry-address",
                registryId = "evict-registry",
                mode = DistributionGridRegistryMode.DEDICATED,
                bootstrapTrusted = true,
                defaultLeaseSeconds = 5
            )

            try
            {
                val clientGrid = initializedGrid(
                    nodeId = "evict-client-node",
                    transportAddress = "evict-client-address"
                )
                clientGrid.addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryGrid))
                clientGrid.init()

                assertNotNull(clientGrid.probeTrustedRegistries())
                assertEquals(listOf("evict-registry"), clientGrid.getDiscoveredRegistryIds())

                forceDiscoveredRegistryExpiry(
                    grid = clientGrid,
                    registryId = "evict-registry",
                    expiresAtEpochMillis = System.currentTimeMillis() - 1_000L
                )

                assertNotNull(clientGrid.registerWithRegistry("evict-registry", requestedLeaseSeconds = 5))
                assertNotNull(clientGrid.renewRegistryLease("evict-registry"))
                assertTrue(
                    clientGrid.queryRegistries(
                        DistributionGridRegistryQuery(
                            requiredCapabilities = mutableListOf("Worker"),
                            acceptedTransports = mutableListOf(Transport.Tpipe),
                            registryIds = mutableListOf("evict-registry"),
                            requireHealthy = true,
                            freshnessWindowSeconds = 300
                        )
                    ).isNotEmpty()
                )
                assertEquals(listOf("evict-registry"), clientGrid.getBootstrapRegistryIds())
            }

            finally
            {
                P2PRegistry.remove(registryGrid)
            }
        }
    }

    /**
     * Verifies that registry queries cache verified node advertisements and the runtime can hand off to them in registry-only mode.
     */
    @Test
    fun queryCachesDiscoveredNodesAndRegistryOnlyModeCanHandoffToThem()
    {
        runBlocking {
            val registryGrid = registryGrid(
                nodeId = "mixed-registry-node",
                transportAddress = "mixed-registry-address",
                registryId = "mixed-registry",
                mode = DistributionGridRegistryMode.MIXED_ROLE,
                bootstrapTrusted = true
            )
            val remoteWorkerGrid = remoteWorkerGrid(
                nodeId = "discovered-remote-node",
                transportAddress = "discovered-remote-address"
            )

            try
            {
                remoteWorkerGrid.addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryGrid))
                remoteWorkerGrid.init()
                val remoteLease = remoteWorkerGrid.registerWithRegistry("mixed-registry", requestedLeaseSeconds = 30)
                assertNotNull(remoteLease)
                assertEquals(listOf("mixed-registry"), readRegistryMemberships(remoteWorkerGrid))

                val senderGrid = initializedGrid(
                    nodeId = "registry-sender-node",
                    transportAddress = "registry-sender-address",
                    router = ExecutionInterface("sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetNodeId = "discovered-remote-node"
                        )
                        content
                    },
                    worker = ExecutionInterface("sender-worker") { content ->
                        content.addText("sender-worker")
                        content
                    }
                )
                senderGrid
                    .setDiscoveryMode(DistributionGridPeerDiscoveryMode.REGISTRY_ONLY)
                    .addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryGrid))
                senderGrid.init()

                val discoveredNodes = senderGrid.queryRegistries(
                    DistributionGridRegistryQuery(
                        requiredCapabilities = mutableListOf("Worker"),
                        acceptedTransports = mutableListOf(Transport.Tpipe),
                        registryIds = mutableListOf("mixed-registry"),
                        requireHealthy = true,
                        freshnessWindowSeconds = 300
                    )
                )

                assertEquals(1, discoveredNodes.size)
                assertEquals(listOf("discovered-remote-node"), senderGrid.getDiscoveredNodeIds())

                val result = senderGrid.execute(MultimodalContent(text = "start"))

                assertTrue(result.passPipeline)
                assertTrue(result.text.contains("remote-worker"))
                assertFalse(result.text.contains("sender-worker"))
            }

            finally
            {
                P2PRegistry.remove(remoteWorkerGrid)
                P2PRegistry.remove(registryGrid)
            }
        }
    }

    /**
     * Verifies that the same node id learned from two different registries stays registry-scoped and remains routable after one registry is removed.
     */
    @Test
    fun duplicateNodeIdsRemainScopedByRegistry()
    {
        runBlocking {
            val registryAGrid = registryGrid(
                nodeId = "shared-registry-a-node",
                transportAddress = "shared-registry-a-address",
                registryId = "shared-registry-a",
                mode = DistributionGridRegistryMode.DEDICATED,
                bootstrapTrusted = true
            )
            val registryBGrid = registryGrid(
                nodeId = "shared-registry-b-node",
                transportAddress = "shared-registry-b-address",
                registryId = "shared-registry-b",
                mode = DistributionGridRegistryMode.DEDICATED,
                bootstrapTrusted = true
            )
            val remoteWorkerA = remoteWorkerGrid(
                nodeId = "shared-remote-node",
                transportAddress = "shared-remote-a-address",
                workerLabel = "remote-worker-a"
            )
            val remoteWorkerB = remoteWorkerGrid(
                nodeId = "shared-remote-node",
                transportAddress = "shared-remote-b-address",
                workerLabel = "remote-worker-b"
            )

            try
            {
                remoteWorkerA.addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryAGrid))
                remoteWorkerA.init()
                assertNotNull(remoteWorkerA.registerWithRegistry("shared-registry-a", requestedLeaseSeconds = 30))

                remoteWorkerB.addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryBGrid))
                remoteWorkerB.init()
                assertNotNull(remoteWorkerB.registerWithRegistry("shared-registry-b", requestedLeaseSeconds = 30))

                val senderGrid = initializedGrid(
                    nodeId = "shared-sender-node",
                    transportAddress = "shared-sender-address",
                    router = ExecutionInterface("shared-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetNodeId = "shared-remote-node"
                        )
                        content
                    },
                    worker = ExecutionInterface("shared-worker") { content ->
                        content.addText("shared-worker")
                        content
                    }
                )
                senderGrid
                    .setDiscoveryMode(DistributionGridPeerDiscoveryMode.REGISTRY_ONLY)
                    .addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryAGrid))
                    .addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryBGrid))
                senderGrid.init()

                senderGrid.queryRegistries(
                    DistributionGridRegistryQuery(
                        requiredCapabilities = mutableListOf("Worker"),
                        acceptedTransports = mutableListOf(Transport.Tpipe),
                        registryIds = mutableListOf("shared-registry-a", "shared-registry-b"),
                        requireHealthy = true,
                        freshnessWindowSeconds = 300
                    )
                )

                val discoveredEntries = readDiscoveredNodeCacheEntries(senderGrid)
                assertEquals(2, discoveredEntries.size)
                assertEquals(
                    setOf(
                        "shared-registry-a" to "shared-remote-node",
                        "shared-registry-b" to "shared-remote-node"
                    ),
                    discoveredEntries.toSet()
                )

                senderGrid.removeBootstrapRegistry("shared-registry-b")
                assertEquals(
                    listOf(
                        "shared-registry-a" to "shared-remote-node"
                    ),
                    readDiscoveredNodeCacheEntries(senderGrid)
                )

                val resolvedAdvertisement = resolveFreshDiscoveredNodeAdvertisement(senderGrid, "shared-remote-node")

                assertNotNull(resolvedAdvertisement)
                assertEquals("shared-registry-a", resolvedAdvertisement.registryId)
                assertEquals("shared-remote-node", resolvedAdvertisement.metadata.nodeId)
                assertEquals("shared-remote-a-address", resolvedAdvertisement.descriptor!!.transport.transportAddress)
            }

            finally
            {
                P2PRegistry.remove(remoteWorkerB)
                P2PRegistry.remove(remoteWorkerA)
                P2PRegistry.remove(registryBGrid)
                P2PRegistry.remove(registryAGrid)
            }
        }
    }

    /**
     * Verifies that registry-only discovery refuses explicit-peer-only fallback when no discovered node is available.
     */
    @Test
    fun registryOnlyModeIgnoresExplicitPeerFallback()
    {
        runBlocking {
            val explicitPeer = remoteWorkerGrid(
                nodeId = "explicit-only-peer-node",
                transportAddress = "explicit-only-peer-address"
            )

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "registry-only-sender-node",
                    transportAddress = "registry-only-sender-address",
                    router = ExecutionInterface("sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = "Tpipe::explicit-only-peer-address"
                        )
                        content
                    },
                    worker = ExecutionInterface("sender-worker") { it }
                )
                senderGrid
                    .setDiscoveryMode(DistributionGridPeerDiscoveryMode.REGISTRY_ONLY)
                    .addPeerDescriptor(explicitPeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(result.terminatePipeline)
                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.ROUTING_FAILURE, failure.kind)
            }

            finally
            {
                P2PRegistry.remove(explicitPeer)
            }
        }
    }

    /**
     * Verifies that a discovered node is evicted from the routing cache once its lease has expired.
     */
    @Test
    fun expiredDiscoveredNodeIsEvictedBeforeRouting()
    {
        runBlocking {
            val registryGrid = registryGrid(
                nodeId = "stale-registry-node",
                transportAddress = "stale-registry-address",
                registryId = "stale-registry",
                mode = DistributionGridRegistryMode.DEDICATED,
                bootstrapTrusted = true
            )
            val remoteWorkerGrid = remoteWorkerGrid(
                nodeId = "stale-remote-node",
                transportAddress = "stale-remote-address"
            )

            try
            {
                remoteWorkerGrid.addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryGrid))
                remoteWorkerGrid.init()
                assertNotNull(remoteWorkerGrid.registerWithRegistry("stale-registry", requestedLeaseSeconds = 30))

                val senderGrid = initializedGrid(
                    nodeId = "stale-sender-node",
                    transportAddress = "stale-sender-address",
                    router = ExecutionInterface("stale-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetNodeId = "stale-remote-node"
                        )
                        content
                    },
                    worker = ExecutionInterface("stale-worker") { it }
                )
                senderGrid
                    .setDiscoveryMode(DistributionGridPeerDiscoveryMode.REGISTRY_ONLY)
                    .addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryGrid))
                senderGrid.init()

                assertEquals(
                    listOf("stale-remote-node"),
                    senderGrid.queryRegistries(
                        DistributionGridRegistryQuery(
                            requiredCapabilities = mutableListOf("Worker"),
                            acceptedTransports = mutableListOf(Transport.Tpipe),
                            registryIds = mutableListOf("stale-registry"),
                            requireHealthy = true,
                            freshnessWindowSeconds = 300
                        )
                    ).map { it.metadata.nodeId }
                )

                forceDiscoveredNodeExpiry(
                    grid = senderGrid,
                    nodeId = "stale-remote-node",
                    expiresAtEpochMillis = System.currentTimeMillis() - 1_000L
                )

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(result.terminatePipeline)
                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.ROUTING_FAILURE, failure.kind)
                assertTrue(senderGrid.getDiscoveredNodeIds().isEmpty())
            }

            finally
            {
                P2PRegistry.remove(remoteWorkerGrid)
                P2PRegistry.remove(registryGrid)
            }
        }
    }

    /**
     * Verifies that removing a bootstrap registry also clears the discovered nodes learned from it.
     */
    @Test
    fun removingBootstrapRegistryEvictsDiscoveredNodes()
    {
        runBlocking {
            val registryGrid = registryGrid(
                nodeId = "revoked-registry-node",
                transportAddress = "revoked-registry-address",
                registryId = "revoked-registry",
                mode = DistributionGridRegistryMode.DEDICATED,
                bootstrapTrusted = true
            )
            val remoteWorkerGrid = remoteWorkerGrid(
                nodeId = "revoked-remote-node",
                transportAddress = "revoked-remote-address"
            )

            try
            {
                remoteWorkerGrid.addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryGrid))
                remoteWorkerGrid.init()
                assertNotNull(remoteWorkerGrid.registerWithRegistry("revoked-registry", requestedLeaseSeconds = 30))

                val senderGrid = initializedGrid(
                    nodeId = "revoked-sender-node",
                    transportAddress = "revoked-sender-address",
                    router = ExecutionInterface("revoked-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetNodeId = "revoked-remote-node"
                        )
                        content
                    },
                    worker = ExecutionInterface("revoked-worker") { it }
                )
                senderGrid
                    .setDiscoveryMode(DistributionGridPeerDiscoveryMode.REGISTRY_ONLY)
                    .addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryGrid))
                senderGrid.init()

                senderGrid.queryRegistries(
                    DistributionGridRegistryQuery(
                        requiredCapabilities = mutableListOf("Worker"),
                        acceptedTransports = mutableListOf(Transport.Tpipe),
                        registryIds = mutableListOf("revoked-registry"),
                        requireHealthy = true,
                        freshnessWindowSeconds = 300
                    )
                )
                assertEquals(listOf("revoked-remote-node"), senderGrid.getDiscoveredNodeIds())

                senderGrid.removeBootstrapRegistry("revoked-registry")
                assertTrue(senderGrid.getDiscoveredNodeIds().isEmpty())

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(result.terminatePipeline)
                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.VALIDATION_FAILURE, failure.kind)
            }

            finally
            {
                P2PRegistry.remove(remoteWorkerGrid)
                P2PRegistry.remove(registryGrid)
            }
        }
    }

    /**
     * Verifies that discovered nodes are cached under the resolved node id even when the registry only fills it in on the descriptor.
     */
    @Test
    fun discoveredNodeCachesUnderResolvedDescriptorNodeId()
    {
        runBlocking {
            val resolvedNodeGrid = remoteWorkerGrid(
                nodeId = "resolved-node",
                transportAddress = "resolved-node-address"
            )
            val registryTransport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = "canonical-registry-address"
            )
            val registryDescriptor = buildGridDescriptor(
                nodeId = "canonical-registry-node",
                transportAddress = "canonical-registry-address"
            ).apply {
                distributionGridMetadata!!.actsAsRegistry = true
            }
            val registryResponder = RegistryResponderInterface { _ ->
                val candidate = DistributionGridNodeAdvertisement(
                    descriptor = resolvedNodeGrid.getP2pDescription()!!.deepCopy(),
                    metadata = DistributionGridNodeMetadata(),
                    registryId = "canonical-registry",
                    attestationRef = "attestation:resolved-node",
                    discoveredAtEpochMillis = System.currentTimeMillis(),
                    expiresAtEpochMillis = System.currentTimeMillis() + 60_000L
                )
                val result = DistributionGridRegistryQueryResult(
                    registryId = "canonical-registry",
                    accepted = true,
                    candidates = mutableListOf(candidate)
                )
                P2PResponse(
                    output = MultimodalContent(
                        text = buildGridRpcPrompt(
                            DistributionGridRpcMessage(
                                messageType = DistributionGridRpcMessageType.QUERY_REGISTRY,
                                senderNodeId = "canonical-registry-node",
                                targetId = "registry-query-client",
                                protocolVersion = DistributionGridProtocolVersion(),
                                payloadType = REGISTRY_QUERY_RESULT_PAYLOAD_TYPE,
                                payloadJson = serialize(result)
                            )
                        )
                    )
                )
            }

            try
            {
                P2PRegistry.register(
                    registryResponder,
                    registryTransport,
                    registryDescriptor,
                    P2PRequirements(
                        allowExternalConnections = true,
                        acceptedContent = mutableListOf(SupportedContentTypes.text)
                    )
                )

                val senderGrid = initializedGrid(
                    nodeId = "canonical-sender-node",
                    transportAddress = "canonical-sender-address",
                    router = ExecutionInterface("canonical-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetNodeId = "resolved-node"
                        )
                        content
                    },
                    worker = ExecutionInterface("canonical-worker") { it }
                )
                senderGrid
                    .setDiscoveryMode(DistributionGridPeerDiscoveryMode.REGISTRY_ONLY)
                    .addBootstrapRegistry(
                        buildRegistryAdvertisement(
                            registryId = "canonical-registry",
                            transportAddress = "canonical-registry-address"
                        )
                    )
                senderGrid.init()

                val discoveredNodes = senderGrid.queryRegistries(
                    DistributionGridRegistryQuery(
                        requiredCapabilities = mutableListOf("Worker"),
                        acceptedTransports = mutableListOf(Transport.Tpipe),
                        registryIds = mutableListOf("canonical-registry"),
                        requireHealthy = true,
                        freshnessWindowSeconds = 300
                    )
                )

                assertEquals(1, discoveredNodes.size)
                assertEquals("resolved-node", discoveredNodes.first().metadata.nodeId)
                assertEquals(listOf("resolved-node"), senderGrid.getDiscoveredNodeIds())

                val result = senderGrid.execute(MultimodalContent(text = "start"))

                assertTrue(result.passPipeline)
                assertTrue(result.text.contains("remote-worker"))
                assertFalse(result.text.contains("canonical-worker"))
            }

            finally
            {
                P2PRegistry.remove(registryResponder)
                P2PRegistry.remove(resolvedNodeGrid)
            }
        }
    }

    /**
     * Verifies that malformed registry lease replies are rejected before they can mutate active lease state.
     */
    @Test
    fun malformedRegistryLeaseRepliesAreRejectedWithoutCaching()
    {
        runBlocking {
            val registryTransport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = "malformed-registry-address"
            )
            val registryDescriptor = buildGridDescriptor(
                nodeId = "malformed-registry-node",
                transportAddress = "malformed-registry-address"
            ).apply {
                distributionGridMetadata!!.actsAsRegistry = true
            }
            val registryResponder = RegistryResponderInterface { request ->
                P2PResponse(
                    output = MultimodalContent(
                        text = buildGridRpcPrompt(
                            DistributionGridRpcMessage(
                                messageType = DistributionGridRpcMessageType.QUERY_REGISTRY,
                                senderNodeId = "malformed-registry-node",
                                targetId = request.transport.transportAddress,
                                protocolVersion = DistributionGridProtocolVersion(),
                                payloadType = REGISTRY_QUERY_RESULT_PAYLOAD_TYPE,
                                payloadJson = "{}"
                            )
                        )
                    )
                )
            }

            try
            {
                P2PRegistry.register(
                    registryResponder,
                    registryTransport,
                    registryDescriptor,
                    P2PRequirements(
                        allowExternalConnections = true,
                        acceptedContent = mutableListOf(SupportedContentTypes.text)
                    )
                )

                val senderGrid = initializedGrid(
                    nodeId = "malformed-client-node",
                    transportAddress = "malformed-client-address"
                )
                senderGrid.addBootstrapRegistry(
                    buildRegistryAdvertisement(
                        registryId = "malformed-registry",
                        transportAddress = "malformed-registry-address"
                    )
                )
                senderGrid.init()

                assertNull(senderGrid.registerWithRegistry("malformed-registry", requestedLeaseSeconds = 30))
                assertTrue(senderGrid.getActiveRegistryLeaseIds().isEmpty())

                val seededLease = DistributionGridRegistrationLease(
                    leaseId = "seed-lease",
                    nodeId = "malformed-client-node",
                    registryId = "malformed-registry",
                    grantedLeaseSeconds = 30,
                    expiresAtEpochMillis = System.currentTimeMillis() + 60_000L,
                    renewalRequired = true
                )
                putActiveRegistryLease(
                    grid = senderGrid,
                    registryId = "malformed-registry",
                    lease = seededLease
                )

                val renewedLease = senderGrid.renewRegistryLease("malformed-registry")

                assertNull(renewedLease)
                val persistedLease = readActiveRegistryLease(senderGrid, "malformed-registry")
                assertNotNull(persistedLease)
                assertEquals("seed-lease", persistedLease.leaseId)
                assertEquals("malformed-client-node", persistedLease.nodeId)
                assertEquals("malformed-registry", persistedLease.registryId)
            }

            finally
            {
                P2PRegistry.remove(registryResponder)
            }
        }
    }

    /**
     * Verifies that descriptor-less registrations without a caller return address are rejected instead of self-advertising the registry.
     */
    @Test
    fun descriptorlessRegistrationWithoutReturnAddressIsRejected()
    {
        runBlocking {
            val registryGrid = registryGrid(
                nodeId = "fallback-registry-node",
                transportAddress = "fallback-registry-address",
                registryId = "fallback-registry",
                mode = DistributionGridRegistryMode.DEDICATED,
                bootstrapTrusted = true
            )

            try
            {
                val request = P2PRequest(
                    transport = P2PTransport(
                        transportMethod = Transport.Tpipe,
                        transportAddress = "fallback-registry-address"
                    ),
                    returnAddress = P2PTransport(),
                    prompt = MultimodalContent(
                        text = buildGridRpcPrompt(
                            DistributionGridRpcMessage(
                                messageType = DistributionGridRpcMessageType.REGISTER_NODE,
                                senderNodeId = "fallback-client-node",
                                targetId = "fallback-registry",
                                protocolVersion = DistributionGridProtocolVersion(),
                                payloadType = "DistributionGridRegistrationRequest",
                                payloadJson = serialize(
                                    DistributionGridRegistrationRequest(
                                        leaseId = "",
                                        descriptor = null,
                                        metadata = DistributionGridNodeMetadata(
                                            nodeId = "fallback-client-node",
                                            supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
                                            roleCapabilities = mutableListOf("Worker"),
                                            supportedTransports = mutableListOf(Transport.Tpipe),
                                            requiresHandshake = true,
                                            defaultTracePolicy = DistributionGridTracePolicy(),
                                            defaultRoutingPolicy = DistributionGridRoutingPolicy(),
                                            actsAsRegistry = false
                                        ),
                                        requestedLeaseSeconds = 30,
                                        healthSummary = "healthy",
                                        restateCapabilities = true
                                    )
                                )
                            )
                        )
                    )
                )

                val response = P2PRegistry.externalP2PCall(request)

                assertNotNull(response.rejection)
                assertEquals(P2PError.configuration, response.rejection!!.errorType)
                assertTrue(readLocalRegisteredNodeAdvertisementIds(registryGrid).isEmpty())
            }

            finally
            {
                P2PRegistry.remove(registryGrid)
            }
        }
    }

    /**
     * Verifies that registry probe replies with the wrong wrapper type are ignored instead of being cached.
     */
    @Test
    fun misframedRegistryProbeResponsesAreIgnored()
    {
        runBlocking {
            val registryGrid = registryGrid(
                nodeId = "probe-registry-node",
                transportAddress = "probe-registry-address",
                registryId = "probe-registry",
                mode = DistributionGridRegistryMode.DEDICATED,
                bootstrapTrusted = true
            )
            val probeResponder = RegistryResponderInterface { _ ->
                val advertisement = buildBootstrapRegistryAdvertisement(registryGrid)
                P2PResponse(
                    output = MultimodalContent(
                        text = buildGridRpcPrompt(
                            DistributionGridRpcMessage(
                                messageType = DistributionGridRpcMessageType.QUERY_REGISTRY,
                                senderNodeId = "probe-registry-node",
                                targetId = "probe-client-node",
                                protocolVersion = DistributionGridProtocolVersion(),
                                payloadType = REGISTRY_QUERY_RESULT_PAYLOAD_TYPE,
                                payloadJson = serialize(advertisement)
                            )
                        )
                    )
                )
            }

            try
            {
                P2PRegistry.register(
                    probeResponder,
                    P2PTransport(
                        transportMethod = Transport.Tpipe,
                        transportAddress = "probe-registry-address"
                    ),
                    buildGridDescriptor(
                        nodeId = "probe-registry-node",
                        transportAddress = "probe-registry-address"
                    ).apply {
                        distributionGridMetadata!!.actsAsRegistry = true
                    },
                    P2PRequirements(
                        allowExternalConnections = true,
                        acceptedContent = mutableListOf(SupportedContentTypes.text)
                    )
                )

                val clientGrid = initializedGrid(
                    nodeId = "probe-client-node",
                    transportAddress = "probe-client-address"
                )
                clientGrid.addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryGrid))
                clientGrid.init()

                val advertisements = clientGrid.probeTrustedRegistries()

                assertTrue(advertisements.isEmpty())
                assertTrue(clientGrid.getDiscoveredRegistryIds().isEmpty())
            }

            finally
            {
                P2PRegistry.remove(probeResponder)
                P2PRegistry.remove(registryGrid)
            }
        }
    }

    /**
     * Verifies that registry query replies with the wrong wrapper type are ignored instead of being cached.
     */
    @Test
    fun misframedRegistryQueryResponsesAreIgnored()
    {
        runBlocking {
            val registryGrid = registryGrid(
                nodeId = "query-registry-node",
                transportAddress = "query-registry-address",
                registryId = "query-registry",
                mode = DistributionGridRegistryMode.DEDICATED,
                bootstrapTrusted = true
            )
            val remoteWorkerGrid = remoteWorkerGrid(
                nodeId = "query-remote-node",
                transportAddress = "query-remote-address"
            )
            val queryResponder = RegistryResponderInterface { _ ->
                val candidate = DistributionGridNodeAdvertisement(
                    descriptor = remoteWorkerGrid.getP2pDescription()!!.deepCopy(),
                    metadata = remoteWorkerGrid.getP2pDescription()!!.distributionGridMetadata!!.deepCopy(),
                    registryId = "query-registry",
                    attestationRef = "attestation:query-remote-node",
                    discoveredAtEpochMillis = System.currentTimeMillis(),
                    expiresAtEpochMillis = System.currentTimeMillis() + 60_000L
                )
                val result = DistributionGridRegistryQueryResult(
                    registryId = "query-registry",
                    accepted = true,
                    candidates = mutableListOf(candidate)
                )
                P2PResponse(
                    output = MultimodalContent(
                        text = buildGridRpcPrompt(
                            DistributionGridRpcMessage(
                                messageType = DistributionGridRpcMessageType.PROBE_REGISTRY,
                                senderNodeId = "query-registry-node",
                                targetId = "query-client-node",
                                protocolVersion = DistributionGridProtocolVersion(),
                                payloadType = REGISTRY_ADVERTISEMENT_PAYLOAD_TYPE,
                                payloadJson = serialize(result)
                            )
                        )
                    )
                )
            }

            try
            {
                P2PRegistry.register(
                    queryResponder,
                    P2PTransport(
                        transportMethod = Transport.Tpipe,
                        transportAddress = "query-registry-address"
                    ),
                    buildGridDescriptor(
                        nodeId = "query-registry-node",
                        transportAddress = "query-registry-address"
                    ).apply {
                        distributionGridMetadata!!.actsAsRegistry = true
                    },
                    P2PRequirements(
                        allowExternalConnections = true,
                        acceptedContent = mutableListOf(SupportedContentTypes.text)
                    )
                )

                val clientGrid = initializedGrid(
                    nodeId = "query-client-node",
                    transportAddress = "query-client-address"
                )
                clientGrid.addBootstrapRegistry(buildBootstrapRegistryAdvertisement(registryGrid))
                clientGrid.init()

                val discoveredNodes = clientGrid.queryRegistries(
                    DistributionGridRegistryQuery(
                        requiredCapabilities = mutableListOf("Worker"),
                        acceptedTransports = mutableListOf(Transport.Tpipe),
                        registryIds = mutableListOf("query-registry"),
                        requireHealthy = true,
                        freshnessWindowSeconds = 300
                    )
                )

                assertTrue(discoveredNodes.isEmpty())
                assertTrue(clientGrid.getDiscoveredNodeIds().isEmpty())
            }

            finally
            {
                P2PRegistry.remove(queryResponder)
                P2PRegistry.remove(remoteWorkerGrid)
                P2PRegistry.remove(registryGrid)
            }
        }
    }

    private suspend fun initializedGrid(
        nodeId: String,
        transportAddress: String,
        router: ExecutionInterface = ExecutionInterface("router") { it },
        worker: ExecutionInterface = ExecutionInterface("worker") { content ->
            content.addText("worker")
            content
        },
        registryMetadata: DistributionGridRegistryMetadata? = null
    ): DistributionGrid
    {
        val grid = DistributionGrid()
        grid.setP2pTransport(
            P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = transportAddress
            )
        )
        grid.setP2pDescription(
            buildGridDescriptor(
                nodeId = nodeId,
                transportAddress = transportAddress
            )
        )
        grid.setP2pRequirements(
            P2PRequirements(
                allowExternalConnections = true,
                acceptedContent = mutableListOf(SupportedContentTypes.text)
            )
        )
        grid.setRouter(router)
        grid.setWorker(worker)
        if(registryMetadata != null)
        {
            grid.setRegistryMetadata(registryMetadata)
        }
        grid.init()
        return grid
    }

    private suspend fun registryGrid(
        nodeId: String,
        transportAddress: String,
        registryId: String,
        mode: DistributionGridRegistryMode,
        bootstrapTrusted: Boolean,
        defaultLeaseSeconds: Int = 30
    ): DistributionGrid
    {
        val grid = initializedGrid(
            nodeId = nodeId,
            transportAddress = transportAddress,
            registryMetadata = DistributionGridRegistryMetadata(
                registryId = registryId,
                trustDomainId = "trust-domain-$registryId",
                bootstrapTrusted = bootstrapTrusted,
                leaseRequired = true,
                defaultLeaseSeconds = defaultLeaseSeconds,
                supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
                mode = mode
            )
        )
        P2PRegistry.register(grid)
        return grid
    }

    private suspend fun remoteWorkerGrid(
        nodeId: String,
        transportAddress: String,
        workerLabel: String = "remote-worker"
    ): DistributionGrid
    {
        val grid = initializedGrid(
            nodeId = nodeId,
            transportAddress = transportAddress,
            router = ExecutionInterface("remote-router") { it },
            worker = ExecutionInterface("remote-worker") { content ->
                content.addText(workerLabel)
                content
            }
        )
        P2PRegistry.register(grid)
        return grid
    }

    private fun buildGridDescriptor(
        nodeId: String,
        transportAddress: String
    ): P2PDescriptor
    {
        return P2PDescriptor(
            agentName = nodeId,
            agentDescription = "DistributionGrid registry discovery test node",
            transport = P2PTransport(
                transportMethod = Transport.Tpipe,
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
                supportedTransports = mutableListOf(Transport.Tpipe),
                requiresHandshake = true,
                defaultTracePolicy = DistributionGridTracePolicy(),
                defaultRoutingPolicy = DistributionGridRoutingPolicy(),
                actsAsRegistry = false
            )
        )
    }

    private fun buildBootstrapRegistryAdvertisement(
        grid: DistributionGrid
    ): DistributionGridRegistryAdvertisement
    {
        return DistributionGridRegistryAdvertisement(
            transport = grid.getP2pTransport()!!.deepCopy(),
            metadata = grid.getRegistryMetadata()!!.deepCopy(),
            attestationRef = "bootstrap:${grid.getRegistryMetadata()!!.registryId}",
            discoveredAtEpochMillis = System.currentTimeMillis(),
            expiresAtEpochMillis = System.currentTimeMillis() + 60_000L
        )
    }

    private fun buildRegistryAdvertisement(
        registryId: String,
        transportAddress: String,
        mode: DistributionGridRegistryMode = DistributionGridRegistryMode.DEDICATED
    ): DistributionGridRegistryAdvertisement
    {
        return DistributionGridRegistryAdvertisement(
            transport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = transportAddress
            ),
            metadata = DistributionGridRegistryMetadata(
                registryId = registryId,
                trustDomainId = "trust-domain-$registryId",
                bootstrapTrusted = true,
                leaseRequired = true,
                defaultLeaseSeconds = 30,
                supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
                mode = mode
            ),
            attestationRef = "bootstrap:$registryId",
            discoveredAtEpochMillis = System.currentTimeMillis(),
            expiresAtEpochMillis = System.currentTimeMillis() + 60_000L
        )
    }

    private fun readRegistryMemberships(grid: DistributionGrid): List<String>
    {
        return grid.getP2pDescription()!!.distributionGridMetadata!!.registryMemberships.toList()
    }

    private fun forceActiveLeaseExpiry(
        grid: DistributionGrid,
        registryId: String,
        expiresAtEpochMillis: Long
    )
    {
        val field = grid.javaClass.getDeclaredField("activeRegistryLeasesById")
        field.isAccessible = true
        val leases = field.get(grid) as MutableMap<String, DistributionGridRegistrationLease>
        val lease = leases[registryId]
        assertNotNull(lease)
        lease.expiresAtEpochMillis = expiresAtEpochMillis
        leases[registryId] = lease
    }

    private fun forceDiscoveredNodeExpiry(
        grid: DistributionGrid,
        nodeId: String,
        expiresAtEpochMillis: Long,
        registryId: String? = null
    )
    {
        val field = grid.javaClass.getDeclaredField("discoveredNodeAdvertisementsById")
        field.isAccessible = true
        val advertisements = field.get(grid) as MutableMap<Any, DistributionGridNodeAdvertisement>
        val entry = advertisements.entries.firstOrNull { (key, advertisement) ->
            val keyNodeIdField = key.javaClass.getDeclaredField("nodeId")
            keyNodeIdField.isAccessible = true
            val keyNodeId = keyNodeIdField.get(key) as? String

            val keyRegistryIdField = key.javaClass.getDeclaredField("registryId")
            keyRegistryIdField.isAccessible = true
            val keyRegistryId = keyRegistryIdField.get(key) as? String

            keyNodeId == nodeId &&
                (registryId == null || keyRegistryId == registryId || advertisement.registryId == registryId)
        }
        val advertisement = entry?.value
        assertNotNull(advertisement)
        advertisement.expiresAtEpochMillis = expiresAtEpochMillis
        if(advertisement.lease != null)
        {
            advertisement.lease!!.expiresAtEpochMillis = expiresAtEpochMillis
        }
        if(entry != null)
        {
            advertisements[entry.key] = advertisement
        }
    }

    private fun forceDiscoveredRegistryExpiry(
        grid: DistributionGrid,
        registryId: String,
        expiresAtEpochMillis: Long
    )
    {
        val field = grid.javaClass.getDeclaredField("discoveredRegistriesById")
        field.isAccessible = true
        val registries = field.get(grid) as MutableMap<String, DistributionGridRegistryAdvertisement>
        val advertisement = registries[registryId]
        assertNotNull(advertisement)
        advertisement.expiresAtEpochMillis = expiresAtEpochMillis
        registries[registryId] = advertisement
    }

    private fun putActiveRegistryLease(
        grid: DistributionGrid,
        registryId: String,
        lease: DistributionGridRegistrationLease
    )
    {
        val field = grid.javaClass.getDeclaredField("activeRegistryLeasesById")
        field.isAccessible = true
        val leases = field.get(grid) as MutableMap<String, DistributionGridRegistrationLease>
        leases[registryId] = lease
    }

    private fun readActiveRegistryLease(
        grid: DistributionGrid,
        registryId: String
    ): DistributionGridRegistrationLease?
    {
        val field = grid.javaClass.getDeclaredField("activeRegistryLeasesById")
        field.isAccessible = true
        val leases = field.get(grid) as MutableMap<String, DistributionGridRegistrationLease>
        return leases[registryId]
    }

    private fun readLocalRegisteredNodeAdvertisementIds(grid: DistributionGrid): List<String>
    {
        val field = grid.javaClass.getDeclaredField("localRegisteredNodeAdvertisementsById")
        field.isAccessible = true
        val advertisements = field.get(grid) as MutableMap<String, DistributionGridNodeAdvertisement>
        return advertisements.keys.toList()
    }

    private fun readLocalRegistrationLeaseIds(grid: DistributionGrid): List<String>
    {
        val field = grid.javaClass.getDeclaredField("localRegistrationLeasesById")
        field.isAccessible = true
        val leases = field.get(grid) as MutableMap<String, DistributionGridRegistrationLease>
        return leases.keys.toList()
    }

    private fun readLocalRegistrationLeaseIdByNodeId(
        grid: DistributionGrid,
        nodeId: String
    ): String?
    {
        val field = grid.javaClass.getDeclaredField("localRegistrationLeaseIdsByNodeId")
        field.isAccessible = true
        val leasesByNode = field.get(grid) as MutableMap<String, String>
        return leasesByNode[nodeId]
    }

    private fun readDiscoveredNodeCacheEntries(grid: DistributionGrid): List<Pair<String, String>>
    {
        val field = grid.javaClass.getDeclaredField("discoveredNodeAdvertisementsById")
        field.isAccessible = true
        val advertisements = field.get(grid) as MutableMap<*, DistributionGridNodeAdvertisement>
        return advertisements.values.map { advertisement ->
            advertisement.registryId to advertisement.metadata.nodeId
        }
    }

    private fun resolveFreshDiscoveredNodeAdvertisement(
        grid: DistributionGrid,
        nodeId: String
    ): DistributionGridNodeAdvertisement?
    {
        val method = grid.javaClass.getDeclaredMethod("resolveFreshDiscoveredNodeAdvertisement", String::class.java)
        method.isAccessible = true
        return method.invoke(grid, nodeId) as? DistributionGridNodeAdvertisement
    }

    private fun matchesRegistryQuery(
        grid: DistributionGrid,
        advertisement: DistributionGridNodeAdvertisement,
        query: DistributionGridRegistryQuery
    ): Boolean
    {
        val method = grid.javaClass.getDeclaredMethod(
            "matchesRegistryQuery",
            DistributionGridNodeAdvertisement::class.java,
            DistributionGridRegistryQuery::class.java
        )
        method.isAccessible = true
        return method.invoke(grid, advertisement, query) as Boolean
    }

    private fun buildGridRpcPrompt(rpcMessage: DistributionGridRpcMessage): String
    {
        return "$GRID_RPC_FRAME_PREFIX\n${serialize(rpcMessage)}"
    }

    private class RegistryResponderInterface(
        private val behavior: suspend (P2PRequest) -> P2PResponse
    ) : P2PInterface
    {
        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
        {
            return behavior(request)
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
    }
}
