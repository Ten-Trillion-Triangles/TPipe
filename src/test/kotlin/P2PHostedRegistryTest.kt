package com.TTT

import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.DefaultP2PHostedRegistryPolicy
import com.TTT.P2P.FileBackedP2PHostedRegistryStore
import com.TTT.P2P.InMemoryP2PHostedRegistryStore
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PHostedModerationState
import com.TTT.P2P.P2PHostedListingKind
import com.TTT.P2P.P2PHostedListingMetadata
import com.TTT.P2P.P2PHostedRegistryAuditQuery
import com.TTT.P2P.P2PHostedRegistry
import com.TTT.P2P.P2PHostedRegistryClient
import com.TTT.P2P.P2PHostedRegistryListing
import com.TTT.P2P.P2PHostedRegistryModerateRequest
import com.TTT.P2P.P2PHostedRegistryPolicySettings
import com.TTT.P2P.P2PHostedRegistryPublishRequest
import com.TTT.P2P.P2PHostedRegistryQuery
import com.TTT.P2P.P2PHostedRegistryAuditAction
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.PipeContextProtocol.Transport
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
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
                store = InMemoryP2PHostedRegistryStore(),
                policy = authenticatedPolicy()
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
                assertTrue(
                    published.rejectionReason.contains("auth", ignoreCase = true) ||
                        published.rejectionReason.contains("denied", ignoreCase = true)
                )
            }

            finally
            {
                P2PRegistry.remove(transport)
            }
        }
    }

    @Test
    fun fileBackedHostedRegistryPersistsListingsAcrossRestart()
    {
        runBlocking {
            val tempFile = Files.createTempFile("hosted-registry-store", ".json")
            val transport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = "durable-hosted-registry"
            )

            val firstInstance = P2PHostedRegistry(
                registryName = "durable-hosted-registry",
                transport = transport,
                store = FileBackedP2PHostedRegistryStore(tempFile.toString()),
                policy = authenticatedPolicy()
            )

            try
            {
                P2PRegistry.register(
                    agent = firstInstance,
                    transport = transport,
                    descriptor = firstInstance.getP2pDescription(),
                    requirements = firstInstance.getP2pRequirements()
                )

                val published = P2PHostedRegistryClient.publishListing(
                    transport = transport,
                    authBody = "publisher-token",
                    request = P2PHostedRegistryPublishRequest(
                        requestedLeaseSeconds = 300,
                        listing = P2PHostedRegistryListing(
                            kind = P2PHostedListingKind.AGENT,
                            metadata = P2PHostedListingMetadata(title = "Durable Agent"),
                            publicDescriptor = P2PDescriptor(
                                agentName = "durable-agent",
                                agentDescription = "Durable listing",
                                transport = P2PTransport(
                                    transportMethod = Transport.Http,
                                    transportAddress = "https://example.com/durable-agent"
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
            }
            finally
            {
                P2PRegistry.remove(transport)
            }

            val secondInstance = P2PHostedRegistry(
                registryName = "durable-hosted-registry",
                transport = transport,
                store = FileBackedP2PHostedRegistryStore(tempFile.toString()),
                policy = authenticatedPolicy()
            )

            try
            {
                P2PRegistry.register(
                    agent = secondInstance,
                    transport = transport,
                    descriptor = secondInstance.getP2pDescription(),
                    requirements = secondInstance.getP2pRequirements()
                )

                val result = P2PHostedRegistryClient.searchListings(
                    transport = transport,
                    query = P2PHostedRegistryQuery(textQuery = "durable")
                )
                assertTrue(result.accepted, result.rejectionReason)
                assertEquals(1, result.totalCount)

                val info = P2PHostedRegistryClient.getRegistryInfo(transport)
                assertNotNull(info)
                assertEquals("file-json", info.durableStoreKind)
                assertEquals(1, info.listingCount)
            }
            finally
            {
                P2PRegistry.remove(transport)
                Files.deleteIfExists(tempFile)
            }
        }
    }

    @Test
    fun hostedRegistrySupportsModerationAndAuditForOperators()
    {
        runBlocking {
            val transport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = "moderated-hosted-registry"
            )
            val hostedRegistry = P2PHostedRegistry(
                registryName = "moderated-hosted-registry",
                transport = transport,
                store = InMemoryP2PHostedRegistryStore(),
                policy = DefaultP2PHostedRegistryPolicy(
                    P2PHostedRegistryPolicySettings(
                        authMechanism = { it in allowedHostedRegistryTokens },
                        principalResolver = { token ->
                            when(token)
                            {
                                "publisher-token" -> "publisher-principal"
                                "operator-token" -> "operator-principal"
                                else -> token
                            }
                        },
                        operatorRefs = mutableSetOf("operator-principal")
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
                    authBody = "publisher-token",
                    request = P2PHostedRegistryPublishRequest(
                        listing = P2PHostedRegistryListing(
                            kind = P2PHostedListingKind.AGENT,
                            metadata = P2PHostedListingMetadata(title = "Moderated Agent"),
                            publicDescriptor = P2PDescriptor(
                                agentName = "moderated-agent",
                                agentDescription = "Moderated listing",
                                transport = P2PTransport(
                                    transportMethod = Transport.Http,
                                    transportAddress = "https://example.com/moderated-agent"
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
                assertTrue(published.accepted, published.rejectionReason)

                val moderated = P2PHostedRegistryClient.moderateListing(
                    transport = transport,
                    authBody = "operator-token",
                    request = P2PHostedRegistryModerateRequest(
                        listingId = published.listing!!.listingId,
                        moderationState = P2PHostedModerationState.HIDDEN,
                        reason = "Hidden by operator"
                    )
                )
                assertTrue(moderated.accepted, moderated.rejectionReason)

                val searchResult = P2PHostedRegistryClient.searchListings(
                    transport = transport,
                    query = P2PHostedRegistryQuery(textQuery = "Moderated Agent")
                )
                assertTrue(searchResult.accepted, searchResult.rejectionReason)
                assertEquals(0, searchResult.totalCount)

                val auditResult = P2PHostedRegistryClient.listAuditRecords(
                    transport = transport,
                    authBody = "operator-token",
                    query = P2PHostedRegistryAuditQuery(listingId = published.listing!!.listingId)
                )
                assertTrue(auditResult.accepted, auditResult.rejectionReason)
                assertTrue(auditResult.results.any {
                    it.action.name == "PUBLISH" && it.principalRef == "publisher-principal"
                })
                assertTrue(auditResult.results.any { it.action.name == "MODERATE" })
            }
            finally
            {
                P2PRegistry.remove(transport)
            }
        }
    }

    @Test
    fun hostedRegistryExposesStatusFacetsAndFilteredAuditViews()
    {
        runBlocking {
            val transport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = "observable-hosted-registry"
            )
            val hostedRegistry = P2PHostedRegistry(
                registryName = "observable-hosted-registry",
                transport = transport,
                store = InMemoryP2PHostedRegistryStore(),
                policy = DefaultP2PHostedRegistryPolicy(
                    P2PHostedRegistryPolicySettings(
                        authMechanism = { it in allowedHostedRegistryTokens },
                        operatorRefs = mutableSetOf("operator-token")
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
                    authBody = "publisher-token",
                    request = P2PHostedRegistryPublishRequest(
                        listing = P2PHostedRegistryListing(
                            kind = P2PHostedListingKind.AGENT,
                            metadata = P2PHostedListingMetadata(
                                title = "Facet Agent",
                                summary = "Facet summary",
                                categories = mutableListOf("catalog/agent"),
                                tags = mutableListOf("facet", "agent")
                            ),
                            publicDescriptor = P2PDescriptor(
                                agentName = "facet-agent",
                                agentDescription = "Facet capable agent",
                                transport = P2PTransport(
                                    transportMethod = Transport.Http,
                                    transportAddress = "https://example.com/facet-agent"
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
                                supportedContentTypes = mutableListOf(SupportedContentTypes.text)
                            ),
                            attestationRef = "verified-facet-agent"
                        )
                    )
                )
                assertTrue(published.accepted, published.rejectionReason)

                val status = P2PHostedRegistryClient.getRegistryStatus(transport)
                assertNotNull(status)
                assertEquals("observable-hosted-registry", status.registryName)
                assertEquals(1, status.stats.totalCount)
                assertEquals(1, status.stats.agentCount)

                val facets = P2PHostedRegistryClient.getSearchFacets(
                    transport = transport,
                    query = P2PHostedRegistryQuery(textQuery = "Facet")
                )
                assertTrue(facets.accepted, facets.rejectionReason)
                assertTrue(facets.categories.any { it.value == "catalog/agent" && it.count == 1 })
                assertTrue(facets.authRequirements.any { it.value == "true" && it.count == 1 })

                val exactSearch = P2PHostedRegistryClient.searchListings(
                    transport = transport,
                    query = P2PHostedRegistryQuery(exactTitle = "Facet Agent")
                )
                assertTrue(exactSearch.accepted, exactSearch.rejectionReason)
                assertEquals(1, exactSearch.totalCount)

                val titlePrefixSearch = P2PHostedRegistryClient.searchListings(
                    transport = transport,
                    query = P2PHostedRegistryQuery(titlePrefix = "Facet")
                )
                assertTrue(titlePrefixSearch.accepted, titlePrefixSearch.rejectionReason)
                assertEquals(1, titlePrefixSearch.totalCount)

                val audit = P2PHostedRegistryClient.listAuditRecords(
                    transport = transport,
                    authBody = "operator-token",
                    query = P2PHostedRegistryAuditQuery(
                        actions = mutableListOf(P2PHostedRegistryAuditAction.PUBLISH),
                        principalRef = "publisher-token",
                        listingKinds = mutableListOf(P2PHostedListingKind.AGENT)
                    )
                )
                assertTrue(audit.accepted, audit.rejectionReason)
                assertEquals(1, audit.totalCount)
                assertEquals(P2PHostedRegistryAuditAction.PUBLISH, audit.results.first().action)
            }
            finally
            {
                P2PRegistry.remove(transport)
            }
        }
    }

    @Test
    fun hostedRegistryCanRequireAuthForAllReadSurfaces()
    {
        runBlocking {
            val transport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = "private-read-hosted-registry"
            )
            val hostedRegistry = P2PHostedRegistry(
                registryName = "private-read-hosted-registry",
                transport = transport,
                store = InMemoryP2PHostedRegistryStore(),
                policy = DefaultP2PHostedRegistryPolicy(
                    P2PHostedRegistryPolicySettings(
                        requireAuthForRead = true,
                        requireAuthForWrite = true,
                        authMechanism = { it == "reader-token" || it == "publisher-token" }
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
                    authBody = "publisher-token",
                    request = P2PHostedRegistryPublishRequest(
                        listing = P2PHostedRegistryListing(
                            kind = P2PHostedListingKind.AGENT,
                            metadata = P2PHostedListingMetadata(title = "Private Read Agent"),
                            publicDescriptor = P2PDescriptor(
                                agentName = "private-read-agent",
                                agentDescription = "Private read coverage",
                                transport = P2PTransport(
                                    transportMethod = Transport.Http,
                                    transportAddress = "https://example.com/private-read-agent"
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
                assertTrue(published.accepted, published.rejectionReason)

                assertEquals(
                    null,
                    P2PHostedRegistryClient.getRegistryInfo(transport)
                )
                assertEquals(
                    null,
                    P2PHostedRegistryClient.getRegistryStatus(transport)
                )
                assertFalse(
                    P2PHostedRegistryClient.searchListings(
                        transport = transport,
                        query = P2PHostedRegistryQuery(textQuery = "Private")
                    ).accepted
                )
                assertFalse(
                    P2PHostedRegistryClient.getSearchFacets(
                        transport = transport,
                        query = P2PHostedRegistryQuery(textQuery = "Private")
                    ).accepted
                )
                assertFalse(
                    P2PHostedRegistryClient.getListing(
                        transport = transport,
                        listingId = published.listing!!.listingId
                    ).accepted
                )

                assertNotNull(
                    P2PHostedRegistryClient.getRegistryInfo(
                        transport = transport,
                        authBody = "reader-token"
                    )
                )
                assertTrue(
                    P2PHostedRegistryClient.searchListings(
                        transport = transport,
                        query = P2PHostedRegistryQuery(textQuery = "Private"),
                        authBody = "reader-token"
                    ).accepted
                )
            }
            finally
            {
                P2PRegistry.remove(transport)
            }
        }
    }

    private fun authenticatedPolicy(
        requireAuthForRead: Boolean = false,
        requireAuthForWrite: Boolean = true
    ): DefaultP2PHostedRegistryPolicy
    {
        return DefaultP2PHostedRegistryPolicy(
            P2PHostedRegistryPolicySettings(
                requireAuthForRead = requireAuthForRead,
                requireAuthForWrite = requireAuthForWrite,
                authMechanism = { it in allowedHostedRegistryTokens }
            )
        )
    }

    private companion object {
        private val allowedHostedRegistryTokens = setOf(
            "publisher-token",
            "operator-token",
            "reader-token"
        )
    }
}
