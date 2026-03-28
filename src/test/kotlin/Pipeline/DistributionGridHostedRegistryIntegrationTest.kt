package com.TTT.Pipeline

import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.DefaultP2PHostedRegistryPolicy
import com.TTT.P2P.InMemoryP2PHostedRegistryStore
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PHostedListingKind
import com.TTT.P2P.P2PHostedListingMetadata
import com.TTT.P2P.P2PHostedRegistry
import com.TTT.P2P.P2PHostedRegistryClient
import com.TTT.P2P.P2PHostedRegistryListing
import com.TTT.P2P.P2PHostedRegistryPolicySettings
import com.TTT.P2P.P2PHostedRegistryPublishRequest
import com.TTT.P2P.P2PHostedRegistryQuery
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.Transport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DistributionGridHostedRegistryIntegrationTest
{
    @Test
    fun autoPullBootstrapCatalogsAdmitsTrustedGridRegistryListings()
    {
        runBlocking {
            val hostedRegistryTransport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = "grid-bootstrap-hosted-registry"
            )
            val hostedRegistry = P2PHostedRegistry(
                registryName = "grid-bootstrap-hosted-registry",
                transport = hostedRegistryTransport,
                store = InMemoryP2PHostedRegistryStore(),
                policy = DefaultP2PHostedRegistryPolicy(
                    P2PHostedRegistryPolicySettings(
                        requireAuthForRead = false,
                        requireAuthForWrite = false,
                        allowAnonymousPublish = true
                    )
                )
            )

            try
            {
                P2PRegistry.register(
                    agent = hostedRegistry,
                    transport = hostedRegistryTransport,
                    descriptor = hostedRegistry.getP2pDescription(),
                    requirements = hostedRegistry.getP2pRequirements()
                )

                val published = P2PHostedRegistryClient.publishListing(
                    transport = hostedRegistryTransport,
                    request = P2PHostedRegistryPublishRequest(
                        listing = P2PHostedRegistryListing(
                            kind = P2PHostedListingKind.GRID_REGISTRY,
                            metadata = P2PHostedListingMetadata(
                                title = "Trusted Bootstrap Registry",
                                summary = "Publishes a bootstrap-trusted grid registry advertisement.",
                                categories = mutableListOf("grid/registry"),
                                tags = mutableListOf("bootstrap", "registry")
                            ),
                            gridRegistryAdvertisement = DistributionGridRegistryAdvertisement(
                                transport = P2PTransport(
                                    transportMethod = Transport.Http,
                                    transportAddress = "https://grid.example/registry"
                                ),
                                metadata = DistributionGridRegistryMetadata(
                                    registryId = "public-bootstrap-registry",
                                    trustDomainId = "public-grid",
                                    bootstrapTrusted = true,
                                    mode = DistributionGridRegistryMode.DEDICATED
                                ),
                                attestationRef = "attested-bootstrap-registry",
                                discoveredAtEpochMillis = System.currentTimeMillis(),
                                expiresAtEpochMillis = System.currentTimeMillis() + 60_000L
                            )
                        )
                    )
                )
                assertTrue(published.accepted, published.rejectionReason)

                val grid = simpleGrid(
                    nodeId = "bootstrap-grid-node",
                    transportAddress = "bootstrap-grid-node",
                    autoPullBootstrapCatalog = DistributionGridBootstrapCatalogSource(
                        sourceId = "public-bootstrap-source",
                        transport = hostedRegistryTransport,
                        query = P2PHostedRegistryQuery(
                            listingKinds = mutableListOf(P2PHostedListingKind.GRID_REGISTRY),
                            categories = mutableListOf("grid/registry")
                        ),
                        autoPullOnInit = true
                    )
                )

                grid.init()

                assertEquals(listOf("public-bootstrap-source"), grid.getBootstrapCatalogSourceIds())
                assertEquals(listOf("public-bootstrap-registry"), grid.getDiscoveredRegistryIds())
            }

            finally
            {
                P2PRegistry.remove(hostedRegistryTransport)
            }
        }
    }

    @Test
    fun gridCanPublishPublicNodeListingsToHostedRegistry()
    {
        runBlocking {
            val hostedRegistryTransport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = "grid-node-publish-registry"
            )
            val hostedRegistry = P2PHostedRegistry(
                registryName = "grid-node-publish-registry",
                transport = hostedRegistryTransport,
                store = InMemoryP2PHostedRegistryStore()
            )

            try
            {
                P2PRegistry.register(
                    agent = hostedRegistry,
                    transport = hostedRegistryTransport,
                    descriptor = hostedRegistry.getP2pDescription(),
                    requirements = hostedRegistry.getP2pRequirements()
                )

                val grid = simpleGrid(
                    nodeId = "public-grid-node",
                    transportAddress = "public-grid-node"
                )
                grid.init()

                val publishResult = grid.publishPublicNodeListing(
                    transport = hostedRegistryTransport,
                    options = DistributionGridPublicListingOptions(
                        title = "Public Grid Node",
                        summary = "Published through DistributionGrid convenience helpers.",
                        categories = mutableListOf("grid/node"),
                        tags = mutableListOf("worker", "public"),
                        requestedLeaseSeconds = 300
                    ),
                    authBody = "publisher-token"
                )

                assertTrue(publishResult.accepted, publishResult.rejectionReason)
                assertNotNull(publishResult.listing)
                assertEquals(P2PHostedListingKind.GRID_NODE, publishResult.listing!!.kind)
                assertEquals("public-grid-node", publishResult.listing!!.gridNodeAdvertisement!!.metadata.nodeId)

                val searchResult = P2PHostedRegistryClient.searchListings(
                    transport = hostedRegistryTransport,
                    query = P2PHostedRegistryQuery(
                        listingKinds = mutableListOf(P2PHostedListingKind.GRID_NODE),
                        textQuery = "Public Grid Node"
                    )
                )

                assertTrue(searchResult.accepted, searchResult.rejectionReason)
                assertEquals(1, searchResult.totalCount)
                assertEquals("public-grid-node", searchResult.results.first().gridNodeAdvertisement!!.metadata.nodeId)
            }

            finally
            {
                P2PRegistry.remove(hostedRegistryTransport)
            }
        }
    }

    private fun simpleGrid(
        nodeId: String,
        transportAddress: String,
        autoPullBootstrapCatalog: DistributionGridBootstrapCatalogSource? = null
    ): DistributionGrid
    {
        val router = GridExecutionInterface("router") { content ->
            content.metadata["distributionGridDirective"] = DistributionGridDirective(
                kind = DistributionGridDirectiveKind.RUN_LOCAL_WORKER
            )
            content
        }
        val worker = GridExecutionInterface("worker") { content ->
            content.addText("worker")
            content
        }

        val grid = DistributionGrid()
        grid.setRouter(router)
        grid.setWorker(worker)
        grid.setP2pTransport(
            P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = transportAddress
            )
        )

        grid.setP2pDescription(
            P2PDescriptor(
                agentName = nodeId,
                agentDescription = "Grid node $nodeId",
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
                    supportedTransports = mutableListOf(Transport.Tpipe),
                    roleCapabilities = mutableListOf("worker")
                )
            )
        )
        grid.setP2pRequirements(
            P2PRequirements(
                allowExternalConnections = true
            )
        )

        autoPullBootstrapCatalog?.let { source ->
            grid.addBootstrapCatalogSource(source)
        }
        return grid
    }
}

private class GridExecutionInterface(
    private val name: String,
    private val handler: suspend (MultimodalContent) -> MultimodalContent
) : P2PInterface
{
    private var containerObject: Any? = null

    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
    {
        return handler(content)
    }

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
    {
        return P2PResponse(output = handler(request.prompt))
    }

    override fun getP2pDescription(): P2PDescriptor
    {
        return P2PDescriptor(
            agentName = name,
            agentDescription = name,
            transport = P2PTransport(Transport.Tpipe, name),
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

    override fun getContainerObject(): Any?
    {
        return containerObject
    }

    override fun setContainerObject(container: Any)
    {
        containerObject = container
    }
}
