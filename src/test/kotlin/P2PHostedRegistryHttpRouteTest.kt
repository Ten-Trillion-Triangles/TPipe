package com.TTT

import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.InMemoryP2PHostedRegistryStore
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PHostedListingKind
import com.TTT.P2P.P2PHostedListingMetadata
import com.TTT.P2P.P2PHostedRegistry
import com.TTT.P2P.P2PHostedRegistryClient
import com.TTT.P2P.P2PHostedRegistryListing
import com.TTT.P2P.P2PHostedRegistryPolicySettings
import com.TTT.P2P.P2PHostedRegistryPublishRequest
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.P2P.DefaultP2PHostedRegistryPolicy
import com.TTT.PipeContextProtocol.Transport
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class P2PHostedRegistryHttpRouteTest
{
    @Test
    fun hostedRegistryIsReachableThroughDedicatedHttpRoute()
    {
        val port = ServerSocket(0).use { it.localPort }
        val baseUrl = "http://127.0.0.1:$port"
        val routeUrl = "$baseUrl/p2p/registry"
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1", module = module()).start(wait = false)

        val transport = P2PTransport(
            transportMethod = Transport.Http,
            transportAddress = routeUrl
        )
        val hostedRegistry = P2PHostedRegistry(
            registryName = "http-hosted-registry",
            transport = transport,
            store = InMemoryP2PHostedRegistryStore(),
            policy = DefaultP2PHostedRegistryPolicy(
                P2PHostedRegistryPolicySettings(
                    authMechanism = { token -> token == "publisher-token" || token == "http-route-token" }
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

            runBlocking {
                val published = P2PHostedRegistryClient.publishListing(
                    transport = P2PTransport(
                        transportMethod = Transport.Http,
                        transportAddress = baseUrl
                    ),
                    authBody = "publisher-token",
                    request = P2PHostedRegistryPublishRequest(
                        requestedLeaseSeconds = 300,
                        listing = P2PHostedRegistryListing(
                            kind = P2PHostedListingKind.AGENT,
                            metadata = P2PHostedListingMetadata(
                                title = "HTTP Hosted Agent",
                                summary = "Exposed over /p2p/registry",
                                categories = mutableListOf("agents/http")
                            ),
                            publicDescriptor = P2PDescriptor(
                                agentName = "http-hosted-agent",
                                agentDescription = "HTTP hosted agent",
                                transport = P2PTransport(
                                    transportMethod = Transport.Http,
                                    transportAddress = "https://example.com/http-hosted-agent"
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
                    ),
                    transportAuthBody = "http-route-token"
                )

                assertTrue(published.accepted, published.rejectionReason)

                val search = P2PHostedRegistryClient.searchAgentListings(
                    transport = P2PTransport(
                        transportMethod = Transport.Http,
                        transportAddress = baseUrl
                    )
                )

                assertTrue(search.accepted, search.rejectionReason)
                assertEquals(1, search.totalCount)
                assertEquals("http-hosted-agent", search.results.first().publicDescriptor!!.agentName)
            }
        }
        finally
        {
            P2PRegistry.remove(transport)
            server.stop()
        }
    }

    @Test
    fun hostedRegistryHttpRouteDoesNotApplyGlobalAuthAsABlanketGate()
    {
        val port = ServerSocket(0).use { it.localPort }
        val baseUrl = "http://127.0.0.1:$port"
        val routeUrl = "$baseUrl/p2p/registry"
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1", module = module()).start(wait = false)

        val transport = P2PTransport(
            transportMethod = Transport.Http,
            transportAddress = routeUrl
        )
        val hostedRegistry = P2PHostedRegistry(
            registryName = "http-auth-hosted-registry",
            transport = transport,
            store = InMemoryP2PHostedRegistryStore(),
            policy = DefaultP2PHostedRegistryPolicy(
                P2PHostedRegistryPolicySettings(
                    requireAuthForRead = true,
                    requireAuthForWrite = true,
                    authMechanism = { token -> token == "Bearer registry-reader" || token == "publisher-token" }
                )
            )
        )

        try
        {
            P2PRegistry.globalAuthMechanism = { false }
            P2PRegistry.register(
                agent = hostedRegistry,
                transport = transport,
                descriptor = hostedRegistry.getP2pDescription(),
                requirements = hostedRegistry.getP2pRequirements()
            )

            runBlocking {
                val published = P2PHostedRegistryClient.publishListing(
                    transport = P2PTransport(
                        transportMethod = Transport.Http,
                        transportAddress = baseUrl
                    ),
                    authBody = "publisher-token",
                    request = P2PHostedRegistryPublishRequest(
                        requestedLeaseSeconds = 300,
                        listing = P2PHostedRegistryListing(
                            kind = P2PHostedListingKind.AGENT,
                            metadata = P2PHostedListingMetadata(title = "HTTP Private Agent"),
                            publicDescriptor = P2PDescriptor(
                                agentName = "http-private-agent",
                                agentDescription = "HTTP private agent",
                                transport = P2PTransport(
                                    transportMethod = Transport.Http,
                                    transportAddress = "https://example.com/http-private-agent"
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

                val deniedInfo = P2PHostedRegistryClient.getRegistryInfo(
                    transport = P2PTransport(Transport.Http, baseUrl)
                )
                assertEquals(null, deniedInfo)

                val allowedInfo = P2PHostedRegistryClient.getRegistryInfo(
                    transport = P2PTransport(Transport.Http, baseUrl),
                    transportAuthBody = "Bearer registry-reader"
                )
                assertNotNull(allowedInfo)
                assertEquals("http-auth-hosted-registry", allowedInfo.registryName)
            }
        }
        finally
        {
            P2PRegistry.globalAuthMechanism = null
            P2PRegistry.remove(transport)
            server.stop()
        }
    }
}
