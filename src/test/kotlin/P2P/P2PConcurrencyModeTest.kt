package com.TTT.P2P

import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.Transport
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Tests for P2P concurrency mode registration and isolated execution.
 */
class P2PConcurrencyModeTest
{
    /**
     * Stateful test container with no-arg constructor that increments a counter on each request.
     * The no-arg constructor is required for reflection-based cloning.
     */
    private class CountingInterface : P2PInterface
    {
        var requestCount = 0

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
        {
            requestCount++
            return P2PResponse(output = MultimodalContent("count=$requestCount"))
        }
    }

    private fun buildTransport(address: String): P2PTransport
    {
        return P2PTransport(transportMethod = Transport.Tpipe, transportAddress = address)
    }

    private fun buildDescriptor(name: String): P2PDescriptor
    {
        return P2PDescriptor(
            agentName = name,
            agentDescription = "Test agent",
            transport = buildTransport(name),
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
    }

    private fun buildRequirements(): P2PRequirements
    {
        return P2PRequirements(allowExternalConnections = true)
    }

    @Test
    fun testSharedIsDefaultConcurrencyMode()
    {
        val transport = buildTransport("shared-default")
        val descriptor = buildDescriptor("shared-default")
        val requirements = buildRequirements()
        val agent = CountingInterface()

        try
        {
            P2PRegistry.register(agent, transport, descriptor, requirements)
            runBlocking {
                P2PRegistry.executeP2pRequest(P2PRequest().apply {
                    this.transport = transport
                    prompt.addText("first")
                })
                P2PRegistry.executeP2pRequest(P2PRequest().apply {
                    this.transport = transport
                    prompt.addText("second")
                })
            }
            assertEquals(2, agent.requestCount, "SHARED mode should route both requests to the same instance")
        }
        finally
        {
            P2PRegistry.remove(transport)
        }
    }

    @Test
    fun testIsolatedModeProducesIndependentState()
    {
        val transport = buildTransport("isolated-test")
        val descriptor = buildDescriptor("isolated-test")
        val requirements = buildRequirements()
        val agent = CountingInterface()

        try
        {
            P2PRegistry.register(agent, transport, descriptor, requirements, P2PConcurrencyMode.ISOLATED)

            val responses = runBlocking {
                val r1 = P2PRegistry.executeP2pRequest(P2PRequest().apply {
                    this.transport = transport
                    prompt.addText("first")
                })
                val r2 = P2PRegistry.executeP2pRequest(P2PRequest().apply {
                    this.transport = transport
                    prompt.addText("second")
                })
                listOf(r1, r2)
            }

            //Each clone starts fresh so both should see count=1.
            assertEquals("count=1", responses[0].output?.text, "First isolated request should see count=1")
            assertEquals("count=1", responses[1].output?.text, "Second isolated request should see count=1")
            assertEquals(0, agent.requestCount, "Template instance should never be mutated")
        }
        finally
        {
            P2PRegistry.remove(transport)
        }
    }

    @Test
    fun testFactoryModeCallsFactoryPerRequest()
    {
        val transport = buildTransport("factory-test")
        val descriptor = buildDescriptor("factory-test")
        val requirements = buildRequirements()
        var factoryCallCount = 0

        try
        {
            P2PRegistry.register(
                factory = {
                    factoryCallCount++
                    CountingInterface()
                },
                transport = transport,
                descriptor = descriptor,
                requirements = requirements
            )

            runBlocking {
                P2PRegistry.executeP2pRequest(P2PRequest().apply {
                    this.transport = transport
                    prompt.addText("first")
                })
                P2PRegistry.executeP2pRequest(P2PRequest().apply {
                    this.transport = transport
                    prompt.addText("second")
                })
            }

            assertEquals(2, factoryCallCount, "Factory should be called once per request")
        }
        finally
        {
            P2PRegistry.remove(transport)
        }
    }

    @Test
    fun testIsolatedModeCleanupChildAgents()
    {
        val parentTransport = buildTransport("cleanup-parent")
        val childTransport = buildTransport("cleanup-child")
        val descriptor = buildDescriptor("cleanup-parent")
        val requirements = buildRequirements()

        //Agent that registers a child during executeP2PRequest.
        val agent = object : P2PInterface
        {
            override fun getP2pTransport(): P2PTransport = parentTransport
            override fun getP2pDescription(): P2PDescriptor = descriptor
            override fun getP2pRequirements(): P2PRequirements = requirements

            override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
            {
                val childAgent = CountingInterface()
                P2PRegistry.register(childAgent, childTransport, buildDescriptor("cleanup-child"), buildRequirements())
                return P2PResponse(output = MultimodalContent("done"))
            }
        }

        try
        {
            P2PRegistry.register(agent, parentTransport, descriptor, requirements, P2PConcurrencyMode.ISOLATED)

            runBlocking {
                P2PRegistry.executeP2pRequest(P2PRequest().apply {
                    transport = parentTransport
                    prompt.addText("test")
                })
            }

            //Child agent should have been cleaned up after isolated execution.
            val childStillRegistered = runBlocking {
                val response = P2PRegistry.executeP2pRequest(P2PRequest().apply {
                    transport = childTransport
                    prompt.addText("should-fail")
                })
                response.rejection == null
            }
            assertFalse(childStillRegistered, "Child agents registered during isolated execution should be cleaned up")
        }
        finally
        {
            P2PRegistry.remove(parentTransport)
            P2PRegistry.remove(childTransport)
        }
    }
}
