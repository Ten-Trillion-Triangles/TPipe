package com.TTT.P2P

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipeline.DistributionGrid
import com.TTT.Pipeline.DistributionGridRoutingPolicy
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.Pipeline
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.RuntimeState
import com.TTT.Util.cloneInstance
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
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
        override var killSwitch: KillSwitch? = null

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

        try
        {
            P2PRegistry.register(
                factory = {
                    object : P2PInterface
                    {
                        override var killSwitch: KillSwitch? = null
                        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
                        {
                            val childAgent = CountingInterface()
                            P2PRegistry.register(childAgent, childTransport, buildDescriptor("cleanup-child"), buildRequirements())
                            return P2PResponse(output = MultimodalContent("done"))
                        }
                    }
                },
                transport = parentTransport,
                descriptor = descriptor,
                requirements = requirements
            )

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

    @Test
    fun testConcurrentIsolatedExecutionProducesIndependentResults()
    {
        val transport = buildTransport("concurrent-isolated")
        val descriptor = buildDescriptor("concurrent-isolated")
        val requirements = buildRequirements()
        val agent = CountingInterface()

        try
        {
            P2PRegistry.register(agent, transport, descriptor, requirements, P2PConcurrencyMode.ISOLATED)

            val responses = runBlocking {
                (1..10).map {
                    async {
                        P2PRegistry.executeP2pRequest(P2PRequest().apply {
                            this.transport = transport
                            prompt.addText("request-$it")
                        })
                    }
                }.awaitAll()
            }

            //Every clone starts fresh — all 10 should see count=1.
            responses.forEachIndexed { i, response ->
                assertEquals("count=1", response.output?.text, "Concurrent isolated request $i should see count=1")
            }
            assertEquals(0, agent.requestCount, "Template should never be mutated by concurrent isolated requests")
        }
        finally
        {
            P2PRegistry.remove(transport)
        }
    }

    @Test
    fun testConcurrentFactoryCallsFactoryPerRequest()
    {
        val transport = buildTransport("concurrent-factory")
        val descriptor = buildDescriptor("concurrent-factory")
        val requirements = buildRequirements()
        val factoryCallCount = AtomicInteger(0)

        try
        {
            P2PRegistry.register(
                factory = {
                    factoryCallCount.incrementAndGet()
                    CountingInterface()
                },
                transport = transport,
                descriptor = descriptor,
                requirements = requirements
            )

            val responses = runBlocking {
                (1..10).map {
                    async {
                        P2PRegistry.executeP2pRequest(P2PRequest().apply {
                            this.transport = transport
                            prompt.addText("request-$it")
                        })
                    }
                }.awaitAll()
            }

            assertEquals(10, factoryCallCount.get(), "Factory should be called once per concurrent request")
            responses.forEach { response ->
                assertNotNull(response.output, "Each concurrent factory response should have output")
            }
        }
        finally
        {
            P2PRegistry.remove(transport)
        }
    }

    @Test
    fun testConcurrentIsolatedChildAgentCleanup()
    {
        val parentTransport = buildTransport("concurrent-cleanup-parent")
        val descriptor = buildDescriptor("concurrent-cleanup-parent")
        val requirements = buildRequirements()
        val childTransports = (1..5).map { buildTransport("concurrent-cleanup-child-$it") }

        try
        {
            P2PRegistry.register(
                factory = {
                    object : P2PInterface
                    {
                        override var killSwitch: KillSwitch? = null
                        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
                        {
                            val index = request.prompt.text.substringAfterLast("-").toIntOrNull() ?: 0
                            if(index in 1..5)
                            {
                                val childTransport = childTransports[index - 1]
                                P2PRegistry.register(CountingInterface(), childTransport, buildDescriptor("child-$index"), buildRequirements())
                            }
                            return P2PResponse(output = MultimodalContent("done-$index"))
                        }
                    }
                },
                transport = parentTransport,
                descriptor = descriptor,
                requirements = requirements
            )

            runBlocking {
                (1..5).map { i ->
                    async {
                        P2PRegistry.executeP2pRequest(P2PRequest().apply {
                            transport = parentTransport
                            prompt.addText("request-$i")
                        })
                    }
                }.awaitAll()
            }

            //All child agents should be cleaned up after concurrent isolated execution.
            childTransports.forEachIndexed { i, childTransport ->
                val stillRegistered = runBlocking {
                    val response = P2PRegistry.executeP2pRequest(P2PRequest().apply {
                        transport = childTransport
                        prompt.addText("probe")
                    })
                    response.rejection == null
                }
                assertFalse(stillRegistered, "Child agent $i should be cleaned up after concurrent isolated execution")
            }
        }
        finally
        {
            P2PRegistry.remove(parentTransport)
            childTransports.forEach { P2PRegistry.remove(it) }
        }
    }

    @Test
    fun testConcurrentSharedModeAccumulatesState()
    {
        val transport = buildTransport("concurrent-shared")
        val descriptor = buildDescriptor("concurrent-shared")
        val requirements = buildRequirements()
        val agent = CountingInterface()

        try
        {
            P2PRegistry.register(agent, transport, descriptor, requirements)

            runBlocking {
                (1..5).map {
                    async {
                        P2PRegistry.executeP2pRequest(P2PRequest().apply {
                            this.transport = transport
                            prompt.addText("request-$it")
                        })
                    }
                }.awaitAll()
            }

            assertEquals(5, agent.requestCount, "SHARED mode should route all concurrent requests to the same instance")
        }
        finally
        {
            P2PRegistry.remove(transport)
        }
    }

    /**
     * DummyPipe for real container tests — echoes input. Must NOT be private so reflection can instantiate it.
     */
    class DummyPipe : Pipe()
    {
        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String = promptInjector
        override suspend fun generateContent(content: MultimodalContent): MultimodalContent = content
    }

    @Test
    fun testClonePipelineWithPipes()
    {
        val original = Pipeline().apply {
            pipelineName = "test-pipeline"
            add(DummyPipe().setPipeName("pipe-1").setContextWindowSize(4096).setMaxTokens(512).autoTruncateContext())
            add(DummyPipe().setPipeName("pipe-2").setContextWindowSize(8192))
        }

        val clone = cloneInstance(original)

        assertEquals("test-pipeline", clone.pipelineName, "Pipeline name should be cloned")
        assertEquals(2, clone.getPipes().size, "Pipe count should match")
        assertEquals("pipe-1", clone.getPipes()[0].pipeName, "First pipe name should match")
        assertEquals("pipe-2", clone.getPipes()[1].pipeName, "Second pipe name should match")
        assertNotSame(original, clone, "Clone should be a different instance")
        assertNotSame(original.getPipes()[0], clone.getPipes()[0], "Pipes should be independent instances")

        //Mutate original — clone should be unaffected.
        original.pipelineName = "mutated"
        assertEquals("test-pipeline", clone.pipelineName, "Clone should be independent of original mutations")
    }

    @Test
    fun testCloneJunctionWithParticipants()
    {
        val moderator = Pipeline().apply {
            pipelineName = "moderator"
            add(DummyPipe().setPipeName("mod-pipe"))
        }
        val participant = Pipeline().apply {
            pipelineName = "worker"
            add(DummyPipe().setPipeName("worker-pipe"))
        }

        val original = com.TTT.Pipeline.Junction()
            .setModerator(moderator)
            .addParticipant("worker-1", participant)

        val clone = cloneInstance(original)

        assertNotSame(original, clone, "Junction clone should be a different instance")
        //The val participantBindings list should be populated on the clone.
        //We verify by checking getPipelinesFromInterface which reads from the bindings.
        assertTrue(clone.getPipelinesFromInterface().isNotEmpty(), "Clone should have pipelines from cloned bindings")
    }

    @Test
    fun testIsolatedModeWithRealPipeline()
    {
        val transport = buildTransport("isolated-real-pipeline")
        val descriptor = buildDescriptor("isolated-real-pipeline")
        val requirements = buildRequirements()

        val pipeline = Pipeline().apply {
            pipelineName = "real-pipeline"
            add(DummyPipe().setPipeName("echo-pipe").setContextWindowSize(8192).setMaxTokens(512).autoTruncateContext())
        }

        try
        {
            P2PRegistry.register(pipeline, transport, descriptor, requirements, P2PConcurrencyMode.ISOLATED)

            val responses = runBlocking {
                val r1 = P2PRegistry.executeP2pRequest(P2PRequest().apply {
                    this.transport = transport
                    prompt.addText("hello")
                })
                val r2 = P2PRegistry.executeP2pRequest(P2PRequest().apply {
                    this.transport = transport
                    prompt.addText("world")
                })
                listOf(r1, r2)
            }

            assertNotNull(responses[0].output, "First response should have output")
            assertNotNull(responses[1].output, "Second response should have output")
            assertNull(responses[0].rejection, "First response should not be rejected")
            assertNull(responses[1].rejection, "Second response should not be rejected")
        }
        finally
        {
            P2PRegistry.remove(transport)
        }
    }

    @Test
    fun testIsolatedExecutionRestoresOverwrittenRegistryEntries()
    {
        val agentTransport = buildTransport("overwrite-test-agent")
        val bystander = buildTransport("overwrite-test-bystander")
        val descriptor = buildDescriptor("overwrite-test-agent")
        val requirements = buildRequirements()

        //Register a bystander agent that the isolated execution will overwrite.
        val bystanderAgent = CountingInterface()
        P2PRegistry.register(bystanderAgent, bystander, buildDescriptor("overwrite-test-bystander"), buildRequirements())

        try
        {
            P2PRegistry.register(
                factory = {
                    object : P2PInterface
                    {
                        override var killSwitch: KillSwitch? = null
                        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
                        {
                            //Overwrite the bystander's registration during isolated execution.
                            P2PRegistry.register(CountingInterface(), bystander, buildDescriptor("overwrite-test-bystander"), buildRequirements())
                            return P2PResponse(output = MultimodalContent("done"))
                        }
                    }
                },
                transport = agentTransport,
                descriptor = descriptor,
                requirements = requirements
            )

            runBlocking {
                P2PRegistry.executeP2pRequest(P2PRequest().apply {
                    transport = agentTransport
                    prompt.addText("test")
                })
            }

            //The bystander's original registration should be restored after isolated execution.
            runBlocking {
                val response = P2PRegistry.executeP2pRequest(P2PRequest().apply {
                    transport = bystander
                    prompt.addText("verify")
                })
                assertNull(response.rejection, "Bystander should still be registered after isolated execution")
            }
            assertEquals(1, bystanderAgent.requestCount, "Bystander should be the ORIGINAL instance, not the overwritten one")
        }
        finally
        {
            P2PRegistry.remove(agentTransport)
            P2PRegistry.remove(bystander)
        }
    }

    @Test
    fun testCloneManifoldWithWorkers()
    {
        val manager = Pipeline().apply {
            pipelineName = "manager"
            add(DummyPipe().setPipeName("manager-pipe").setJsonOutput(com.TTT.P2P.AgentRequest()).setContextWindowSize(8192).setMaxTokens(512).autoTruncateContext())
        }
        val worker1 = Pipeline().apply { pipelineName = "worker-1"; add(DummyPipe().setPipeName("w1-pipe")) }
        val worker2 = Pipeline().apply { pipelineName = "worker-2"; add(DummyPipe().setPipeName("w2-pipe")) }

        val original = Manifold()
        original.setManagerPipeline(manager)
        original.addWorkerPipeline(worker1)
        original.addWorkerPipeline(worker2)

        val clone = cloneInstance(original)

        assertNotSame(original, clone, "Manifold clone should be a different instance")
        assertEquals("manager", clone.getManagerPipeline().pipelineName, "Manager pipeline name should be cloned")
        assertEquals(2, clone.getWorkerPipelines().size, "Worker pipeline count should match")
        assertNotSame(original.getManagerPipeline(), clone.getManagerPipeline(), "Manager pipeline should be independent")
    }

    @Test
    fun testCloneDistributionGridWithConfig()
    {
        val router = Pipeline().apply { pipelineName = "router"; add(DummyPipe().setPipeName("router-pipe")) }
        val worker = Pipeline().apply { pipelineName = "worker"; add(DummyPipe().setPipeName("worker-pipe")) }

        val original = DistributionGrid()
            .setRouter(router)
            .setWorker(worker)
            .setMaxHops(8)
            .setRoutingPolicy(DistributionGridRoutingPolicy(maxHopCount = 4))

        val clone = cloneInstance(original)

        assertNotSame(original, clone, "DistributionGrid clone should be a different instance")
        assertEquals(8, clone.getMaxHops(), "MaxHops should be cloned")
        assertEquals(4, clone.getRoutingPolicy().maxHopCount, "Routing policy maxHopCount should be cloned")
        assertTrue(clone.getPipelinesFromInterface().isNotEmpty(), "Clone should have pipelines from cloned bindings")
    }

    /**
     * Test helper with one config field and one @RuntimeState field.
     */
    class RuntimeStateTestClass
    {
        var configField: String = ""
        @RuntimeState var runtimeCounter: Int = 0
    }

    @Test
    fun testRuntimeStateFieldsAreAtDefaultsAfterClone()
    {
        val original = RuntimeStateTestClass()
        original.configField = "hello"
        original.runtimeCounter = 42

        val clone = cloneInstance(original)

        assertEquals("hello", clone.configField, "Config field should be cloned")
        assertEquals(0, clone.runtimeCounter, "RuntimeState field should be at default (0), not carried from template (42)")
    }

    /**
     * Class with no no-arg constructor — cloneInstance should throw.
     */
    class NoDefaultConstructor(val required: String)

    @Test
    fun testCloneFailureThrowsIllegalStateException()
    {
        val instance = NoDefaultConstructor("test")
        assertFailsWith<IllegalStateException> {
            cloneInstance(instance)
        }
    }
}
