package com.TTT

import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.InMemoryP2PHostedRegistryStore
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PHostedListingKind
import com.TTT.P2P.P2PHostedListingMetadata
import com.TTT.P2P.P2PHostedRegistry
import com.TTT.P2P.P2PHostedRegistryListing
import com.TTT.P2P.P2PHostedRegistryPublishRequest
import com.TTT.P2P.P2PHostedRegistryQuery
import com.TTT.P2P.P2PHostedRegistryTools
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class P2PHostedRegistryToolsTest
{
    @Test
    fun hostedRegistryToolsCanSearchAndExposeTrustedGridRegistries()
    {
        runBlocking {
            val transport = P2PTransport(transportMethod = Transport.Tpipe, transportAddress = "hosted-registry-tools")
            val hostedRegistry = P2PHostedRegistry(
                registryName = "hosted-registry-tools",
                transport = transport,
                store = InMemoryP2PHostedRegistryStore()
            )

            try
            {
                P2PRegistry.register(
                    agent = hostedRegistry,
                    transport = transport,
                    descriptor = hostedRegistry.getP2pDescription(),
                    requirements = hostedRegistry.getP2pRequirements()
                )

                val publish = P2PHostedRegistryTools.publishP2pRegistryListing(
                    transportAddress = transport.transportAddress,
                    listingJson = serialize(
                        P2PHostedRegistryListing(
                            kind = P2PHostedListingKind.GRID_REGISTRY,
                            metadata = P2PHostedListingMetadata(
                                title = "Hosted Grid Registry",
                                summary = "Registry tool surface",
                                categories = mutableListOf("grid/registry"),
                                tags = mutableListOf("grid", "registry")
                            ),
                            gridRegistryAdvertisement = com.TTT.Pipeline.DistributionGridRegistryAdvertisement(
                                transport = P2PTransport(
                                    transportMethod = Transport.Http,
                                    transportAddress = "https://grid.example/tools"
                                ),
                                metadata = com.TTT.Pipeline.DistributionGridRegistryMetadata(
                                    registryId = "hosted-grid-registry",
                                    trustDomainId = "public-grid",
                                    bootstrapTrusted = true,
                                    mode = com.TTT.Pipeline.DistributionGridRegistryMode.DEDICATED
                                ),
                                attestationRef = "trusted-grid-attestation",
                                discoveredAtEpochMillis = System.currentTimeMillis(),
                                expiresAtEpochMillis = System.currentTimeMillis() + 60_000L
                            )
                        )
                    ),
                    authBody = "publisher-token"
                )

                assertTrue(publish.accepted, publish.rejectionReason)

                val search = P2PHostedRegistryTools.searchP2pRegistryListings(
                    transportAddress = transport.transportAddress,
                    queryJson = serialize(
                        P2PHostedRegistryQuery(
                            listingKinds = mutableListOf(P2PHostedListingKind.GRID_REGISTRY),
                            textQuery = "Hosted Grid Registry"
                        )
                    )
                )
                assertTrue(search.accepted, search.rejectionReason)
                assertEquals(1, search.totalCount)

                val trusted = P2PHostedRegistryTools.listTrustedGridRegistries(
                    transportAddress = transport.transportAddress,
                    queryJson = serialize(
                        P2PHostedRegistryQuery(
                            categories = mutableListOf("grid/registry")
                        )
                    )
                )
                assertEquals(1, trusted.size)
                assertEquals("hosted-grid-registry", trusted.first().metadata.registryId)
            }

            finally
            {
                P2PRegistry.remove(transport)
            }
        }
    }

    @Test
    fun hostedRegistryToolsRegisterAndEnableAddsExpectedPcpFunctions()
    {
        val context = PcpContext()
        P2PHostedRegistryTools.registerAndEnable(context, allowWriteTools = true)

        assertNotNull(context.tpipeOptions.find { it.functionName == "search_p2p_registry_listings" })
        assertNotNull(context.tpipeOptions.find { it.functionName == "get_p2p_registry_listing" })
        assertNotNull(context.tpipeOptions.find { it.functionName == "list_trusted_grid_registries" })
        assertNotNull(context.tpipeOptions.find { it.functionName == "publish_p2p_registry_listing" })
        assertNotNull(context.tpipeOptions.find { it.functionName == "renew_p2p_registry_listing" })
        assertNotNull(context.tpipeOptions.find { it.functionName == "remove_p2p_registry_listing" })
    }
}
