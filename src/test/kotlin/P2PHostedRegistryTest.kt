package com.TTT

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
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.PipeContextProtocol.Transport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class P2PHostedRegistryTest
{
    @Test
    fun hostedRegistryPublishesSearchesAndSanitizesAgentListings()
    {
        runBlocking {
            val transport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = "hosted-registry-test"
            )
            val hostedRegistry = P2PHostedRegistry(
                registryName = "hosted-registry-test",
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

                val published = P2PHostedRegistryClient.publishListing(
                    transport = transport,
                    authBody = "publisher-token",
                    request = P2PHostedRegistryPublishRequest(
                        requestedLeaseSeconds = 600,
                        listing = P2PHostedRegistryListing(
                            kind = P2PHostedListingKind.AGENT,
                            metadata = P2PHostedListingMetadata(
                                title = "Research Agent",
                                summary = "Finds relevant agents for research-heavy apps.",
                                categories = mutableListOf("research/agent"),
                                tags = mutableListOf("research", "search")
                            ),
                            publicDescriptor = P2PDescriptor(
                                agentName = "research-agent",
                                agentDescription = "Searches research sources and summarizes results.",
                                transport = P2PTransport(
                                    transportMethod = Transport.Http,
                                    transportAddress = "https://example.com/research-agent",
                                    transportAuthBody = "should-not-leak"
                                ),
                                requiresAuth = true,
                                usesConverse = false,
                                allowsAgentDuplication = false,
                                allowsCustomContext = false,
                                allowsCustomAgentJson = false,
                                recordsInteractionContext = false,
                                recordsPromptContent = false,
                                allowsExternalContext = false,
                                contextProtocol = ContextProtocol.none,
                                supportedContentTypes = mutableListOf(SupportedContentTypes.text),
                                requestTemplate = P2PRequest().apply {
                                    authBody = "secret-auth"
                                    prompt.addText("public system prompt")
                                }
                            )
                        )
                    )
                )

                assertTrue(published.accepted, published.rejectionReason)
                assertNotNull(published.listing)
                assertEquals("", published.listing!!.publicDescriptor!!.transport.transportAuthBody)
                assertEquals("", published.listing!!.publicDescriptor!!.requestTemplate!!.authBody)

                val searchResult = P2PHostedRegistryClient.searchListings(
                    transport = transport,
                    query = P2PHostedRegistryQuery(
                        textQuery = "research",
                        categories = mutableListOf("research/agent"),
                        tags = mutableListOf("search")
                    )
                )

                assertTrue(searchResult.accepted, searchResult.rejectionReason)
                assertEquals(1, searchResult.totalCount)
                assertEquals("research-agent", searchResult.results.first().publicDescriptor!!.agentName)

                val loaded = P2PHostedRegistryClient.pullListingsToLocalRegistry(
                    transport = transport,
                    query = P2PHostedRegistryQuery(textQuery = "research-agent")
                )
                assertTrue(loaded.accepted, loaded.rejectionReason)

                val importedTemplate = P2PRegistry.requestTemplates["research-agent"]
                assertNotNull(importedTemplate)
                assertEquals("", importedTemplate.authBody)
            }

            finally
            {
                P2PRegistry.remove(transport)
            }
        }
    }

    @Test
    fun hostedRegistryCanAllowAnonymousPublishWhenHostPolicyPermitsIt()
    {
        runBlocking {
            val transport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = "anonymous-hosted-registry"
            )
            val hostedRegistry = P2PHostedRegistry(
                registryName = "anonymous-hosted-registry",
                transport = transport,
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
                    transport = transport,
                    descriptor = hostedRegistry.getP2pDescription(),
                    requirements = hostedRegistry.getP2pRequirements()
                )

                val published = P2PHostedRegistryClient.publishListing(
                    transport = transport,
                    request = P2PHostedRegistryPublishRequest(
                        requestedLeaseSeconds = 300,
                        listing = P2PHostedRegistryListing(
                            kind = P2PHostedListingKind.AGENT,
                            metadata = P2PHostedListingMetadata(
                                title = "Anonymous Listing",
                                summary = "Published without caller auth.",
                                categories = mutableListOf("public"),
                                tags = mutableListOf("anonymous")
                            ),
                            publicDescriptor = P2PDescriptor(
                                agentName = "anonymous-agent",
                                agentDescription = "Anonymous public listing.",
                                transport = P2PTransport(
                                    transportMethod = Transport.Http,
                                    transportAddress = "https://example.com/anonymous-agent"
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
                        )
                    )
                )

                assertTrue(published.accepted, published.rejectionReason)

                val searchResult = P2PHostedRegistryClient.searchListings(
                    transport = transport,
                    query = P2PHostedRegistryQuery(textQuery = "anonymous")
                )
                assertTrue(searchResult.accepted)
                assertEquals(1, searchResult.totalCount)
            }

            finally
            {
                P2PRegistry.remove(transport)
            }
        }
    }

    @Test
    fun hostedRegistryRejectsUnauthenticatedPublishByDefault()
    {
        runBlocking {
            val transport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = "default-auth-registry"
            )
            val hostedRegistry = P2PHostedRegistry(
                registryName = "default-auth-registry",
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

                val published = P2PHostedRegistryClient.publishListing(
                    transport = transport,
                    request = P2PHostedRegistryPublishRequest(
                        listing = P2PHostedRegistryListing(
                            kind = P2PHostedListingKind.AGENT,
                            metadata = P2PHostedListingMetadata(title = "Denied"),
                            publicDescriptor = P2PDescriptor(
                                agentName = "denied-agent",
                                agentDescription = "Should not publish",
                                transport = P2PTransport(
                                    transportMethod = Transport.Http,
                                    transportAddress = "https://example.com/denied"
                                ),
                                requiresAuth = false,
                                usesConverse = false,
                                allowsAgentDuplication = false,
                                allowsCustomContext = false,
                                allowsCustomAgentJson = false,
                                recordsInteractionContext = false,
                                recordsPromptContent = false,
                                allowsExternalContext = false,
                                contextProtocol = ContextProtocol.none
                            )
                        )
                    )
                )

                assertFalse(published.accepted)
                assertTrue(published.rejectionReason.contains("denied", ignoreCase = true))
            }

            finally
            {
                P2PRegistry.remove(transport)
            }
        }
    }
}
