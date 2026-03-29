package com.TTT

import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.InMemoryP2PHostedRegistryStore
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PHostedListingKind
import com.TTT.P2P.P2PHostedListingMetadata
import com.TTT.P2P.P2PHostedRegistry
import com.TTT.P2P.P2PHostedRegistryClient
import com.TTT.P2P.P2PHostedRegistryListing
import com.TTT.P2P.P2PHostedRegistryPublishRequest
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.P2PTrustedRegistryAdmissionPolicy
import com.TTT.P2P.P2PTrustedRegistrySource
import com.TTT.P2P.SupportedContentTypes
import com.TTT.PipeContextProtocol.Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class P2PRegistryTrustedRegistrySourceTest
{
    @Test
    fun trustedSourceAutoPullImportsAgentListings()
    {
        runBlocking {
            val transport = P2PTransport(Transport.Tpipe, "trusted-source-auto-pull")
            val hostedRegistry = hostedRegistry(transport)

            try
            {
                registerHostedRegistry(hostedRegistry, transport)
                publishAgentListing(transport, "trusted-agent", "Trusted Agent")

                P2PRegistry.addTrustedRegistrySource(
                    P2PTrustedRegistrySource(
                        sourceId = "trusted-source",
                        transport = transport,
                        autoPullOnRegister = true
                    )
                )

                assertTrue(P2PRegistry.listClientAgents().any { it.agentName == "trusted-agent" })
                assertTrue(P2PRegistry.requestTemplates.containsKey("trusted-agent"))
            }
            finally
            {
                P2PRegistry.clearTrustedRegistryImportState()
                P2PRegistry.remove(transport)
            }
        }
    }

    @Test
    fun trustedSourceAdmissionFilterRejectsListingsBeforeImport()
    {
        runBlocking {
            val transport = P2PTransport(Transport.Tpipe, "trusted-source-filter")
            val hostedRegistry = hostedRegistry(transport)

            try
            {
                registerHostedRegistry(hostedRegistry, transport)
                publishAgentListing(transport, "blocked-agent", "Blocked Agent")

                P2PRegistry.addTrustedRegistrySource(
                    P2PTrustedRegistrySource(
                        sourceId = "trusted-filter-source",
                        transport = transport,
                        admissionFilter = { _, listing ->
                            if(listing.publicDescriptor?.agentName == "blocked-agent") {
                                "blocked by test filter"
                            } else ""
                        }
                    )
                )

                P2PRegistry.pullTrustedRegistrySources()

                assertFalse(P2PRegistry.listClientAgents().any { it.agentName == "blocked-agent" })
            }
            finally
            {
                P2PRegistry.clearTrustedRegistryImportState()
                P2PRegistry.remove(transport)
            }
        }
    }

    @Test
    fun trustedSourceRefreshRemovesStaleImportedListings()
    {
        runBlocking {
            val transport = P2PTransport(Transport.Tpipe, "trusted-source-refresh")
            val hostedRegistry = hostedRegistry(transport)

            try
            {
                registerHostedRegistry(hostedRegistry, transport)
                val published = publishAgentListing(transport, "refresh-agent", "Refresh Agent")

                P2PRegistry.addTrustedRegistrySource(
                    P2PTrustedRegistrySource(
                        sourceId = "refresh-source",
                        transport = transport,
                        autoPullOnRegister = true
                    )
                )
                assertTrue(P2PRegistry.listClientAgents().any { it.agentName == "refresh-agent" })

                P2PHostedRegistryClient.removeListing(
                    transport = transport,
                    request = com.TTT.P2P.P2PHostedRegistryRemoveRequest(
                        listingId = published.listing!!.listingId,
                        leaseId = published.lease!!.leaseId
                    ),
                    authBody = "publisher-token"
                )

                P2PRegistry.pullTrustedRegistrySources()

                assertFalse(P2PRegistry.listClientAgents().any { it.agentName == "refresh-agent" })
            }
            finally
            {
                P2PRegistry.clearTrustedRegistryImportState()
                P2PRegistry.remove(transport)
            }
        }
    }

    @Test
    fun trustedSourceCollisionsAreRejectedAndRecorded()
    {
        runBlocking {
            val transport = P2PTransport(Transport.Tpipe, "trusted-source-collision")
            val hostedRegistry = hostedRegistry(transport)

            try
            {
                registerHostedRegistry(hostedRegistry, transport)
                publishAgentListing(transport, "collision-agent", "Collision Agent")

                P2PRegistry.loadAgents(
                    listOf(
                        P2PDescriptor(
                            agentName = "collision-agent",
                            agentDescription = "Manual agent",
                            transport = P2PTransport(Transport.Http, "https://example.com/manual"),
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
                    )
                )

                P2PRegistry.addTrustedRegistrySource(
                    P2PTrustedRegistrySource(
                        sourceId = "collision-source",
                        transport = transport,
                        autoPullOnRegister = true
                    )
                )

                val collisions = P2PRegistry.getTrustedRegistryImportCollisions()
                assertEquals(1, collisions.size)
                assertEquals("collision-agent", collisions.first().agentName)
            }
            finally
            {
                P2PRegistry.clearTrustedRegistryImportState()
                P2PRegistry.remove(transport)
            }
        }
    }

    @Test
    fun trustedSourceAutoRefreshTickerIsOptionalAndCanRefresh()
    {
        runBlocking {
            val transport = P2PTransport(Transport.Tpipe, "trusted-source-ticker")
            val hostedRegistry = hostedRegistry(transport)

            try
            {
                registerHostedRegistry(hostedRegistry, transport)
                val published = publishAgentListing(transport, "ticker-agent", "Ticker Agent")

                P2PRegistry.addTrustedRegistrySource(
                    P2PTrustedRegistrySource(
                        sourceId = "ticker-source",
                        transport = transport,
                        autoPullOnRegister = true
                    )
                )
                assertTrue(P2PRegistry.listClientAgents().any { it.agentName == "ticker-agent" })

                P2PRegistry.startTrustedRegistryAutoRefresh(50L)
                assertTrue(P2PRegistry.isTrustedRegistryAutoRefreshRunning())

                P2PHostedRegistryClient.removeListing(
                    transport = transport,
                    request = com.TTT.P2P.P2PHostedRegistryRemoveRequest(
                        listingId = published.listing!!.listingId,
                        leaseId = published.lease!!.leaseId
                    ),
                    authBody = "publisher-token"
                )

                delay(160L)

                assertFalse(P2PRegistry.listClientAgents().any { it.agentName == "ticker-agent" })
                P2PRegistry.stopTrustedRegistryAutoRefresh()
                assertFalse(P2PRegistry.isTrustedRegistryAutoRefreshRunning())
            }
            finally
            {
                P2PRegistry.clearTrustedRegistryImportState()
                P2PRegistry.remove(transport)
            }
        }
    }

    @Test
    fun trustedSourceAdmissionPolicyCanRequireVerificationAndExposeProvenance()
    {
        runBlocking {
            val transport = P2PTransport(Transport.Tpipe, "trusted-source-provenance")
            val hostedRegistry = hostedRegistry(transport)

            try
            {
                registerHostedRegistry(hostedRegistry, transport)
                publishAgentListing(
                    transport = transport,
                    agentName = "verified-agent",
                    title = "Verified Agent",
                    attestationRef = "signed-agent"
                )

                P2PRegistry.addTrustedRegistrySource(
                    P2PTrustedRegistrySource(
                        sourceId = "verified-source",
                        transport = transport,
                        autoPullOnRegister = true,
                        admissionPolicy = P2PTrustedRegistryAdmissionPolicy(
                            requireVerificationEvidence = true,
                            sourceLabel = "public-registry"
                        )
                    )
                )

                val imported = P2PRegistry.listTrustedImportedAgents()
                assertEquals(1, imported.size)
                assertEquals("verified-agent", imported.first().agentName)
                assertEquals("signed-agent", imported.first().attestationRef)
                assertEquals("public-registry", imported.first().sourceLabel)
            }
            finally
            {
                P2PRegistry.clearTrustedRegistryImportState()
                P2PRegistry.remove(transport)
            }
        }
    }

    private fun hostedRegistry(transport: P2PTransport): P2PHostedRegistry
    {
        return P2PHostedRegistry(
            registryName = transport.transportAddress,
            transport = transport,
            store = InMemoryP2PHostedRegistryStore()
        )
    }

    private fun registerHostedRegistry(hostedRegistry: P2PHostedRegistry, transport: P2PTransport)
    {
        P2PRegistry.register(
            agent = hostedRegistry,
            transport = transport,
            descriptor = hostedRegistry.getP2pDescription(),
            requirements = hostedRegistry.getP2pRequirements()
        )
    }

    private suspend fun publishAgentListing(
        transport: P2PTransport,
        agentName: String,
        title: String,
        attestationRef: String = ""
    ): com.TTT.P2P.P2PHostedRegistryMutationResult
    {
        return P2PHostedRegistryClient.publishListing(
            transport = transport,
            authBody = "publisher-token",
            request = P2PHostedRegistryPublishRequest(
                requestedLeaseSeconds = 300,
                listing = P2PHostedRegistryListing(
                    kind = P2PHostedListingKind.AGENT,
                    metadata = P2PHostedListingMetadata(
                        title = title,
                        summary = "$title summary",
                        categories = mutableListOf("agents/general")
                    ),
                    publicDescriptor = P2PDescriptor(
                        agentName = agentName,
                        agentDescription = title,
                        transport = P2PTransport(
                            transportMethod = Transport.Http,
                            transportAddress = "https://example.com/$agentName"
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
                        requestTemplate = P2PRequest()
                    ),
                    attestationRef = attestationRef
                )
            )
        )
    }
}
