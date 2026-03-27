package com.TTT

import com.TTT.Context.Dictionary
import com.TTT.P2P.*
import com.TTT.Pipe.*
import com.TTT.PipeContextProtocol.Transport
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class TestP2PInterface(
    private val transport: P2PTransport? = null,
    private val descriptor: P2PDescriptor? = null,
    private val requirements: P2PRequirements? = null,
    private val container: Any? = null
) : P2PInterface {
    override fun getP2pTransport(): P2PTransport? = transport
    override fun getP2pDescription(): P2PDescriptor? = descriptor
    override fun getP2pRequirements(): P2PRequirements? = requirements
    override fun getContainerObject(): Any? = container

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse {
        return P2PResponse(output = MultimodalContent().apply { addText("Success") })
    }
}

class CapturingP2PInterface(
    private val transport: P2PTransport,
    private val descriptor: P2PDescriptor,
    private val requirements: P2PRequirements,
    private val container: Any? = null
) : P2PInterface {
    var lastRequest: P2PRequest? = null

    override fun getP2pTransport(): P2PTransport? = transport
    override fun getP2pDescription(): P2PDescriptor? = descriptor
    override fun getP2pRequirements(): P2PRequirements? = requirements
    override fun getContainerObject(): Any? = container

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse {
        lastRequest = request
        return P2PResponse(output = MultimodalContent().apply { addText("Success") })
    }
}

class P2PRegistryRequirementsTest {

    @BeforeTest
    fun setup() {
        // P2PRegistry is a singleton, we might need to clear it if possible
        // but it doesn't have a clear method. We'll just be careful.
    }

    @Test
    fun testConverseInputRequirement() = runBlocking {
        val requirements = P2PRequirements(requireConverseInput = true, allowExternalConnections = true)
        val agent = TestP2PInterface()

        // 1. Missing converse input
        val requestNoConverse = P2PRequest().apply {
            prompt.addText("Just plain text")
        }
        val (result1, rejection1) = P2PRegistry.checkAgentRequirements(requestNoConverse, requirements, agent)
        assertFalse(result1, "Should reject when converse input is missing")
        assertEquals(P2PError.prompt, rejection1?.errorType)

        // 2. Valid ConverseData
        val requestWithConverse = P2PRequest().apply {
            prompt.addText("""{"role": "user", "content": {"text": "hello", "binaryContent": [], "terminatePipeline": false}}""")
        }
        val (result2, rejection2) = P2PRegistry.checkAgentRequirements(requestWithConverse, requirements, agent)
        assertTrue(result2, "Should accept valid ConverseData. Reason: ${rejection2?.reason}")

        // 3. Valid ConverseHistory
        val requestWithHistory = P2PRequest().apply {
            prompt.addText("""{"history": [{"role": "user", "content": {"text": "hello", "binaryContent": [], "terminatePipeline": false}}]}""")
        }
        val (result3, _) = P2PRegistry.checkAgentRequirements(requestWithHistory, requirements, agent)
        assertTrue(result3, "Should accept valid ConverseHistory")

        // 4. Embedded converse data (noisy prompt)
        val requestWithNoisyConverse = P2PRequest().apply {
            prompt.addText("""Some noise before {"role": "user", "content": {"text": "hello", "binaryContent": [], "terminatePipeline": false}} and after""")
        }
        val (result4, _) = P2PRegistry.checkAgentRequirements(requestWithNoisyConverse, requirements, agent)
        assertTrue(result4, "Should accept embedded ConverseData")
    }

    @Test
    fun testExternalConnectionsRequirement() = runBlocking {
        val requirements = P2PRequirements(allowExternalConnections = false)
        val agent = TestP2PInterface()

        val localTransport = P2PTransport(transportAddress = "local-agent")
        val externalTransport = P2PTransport(transportAddress = "external-agent")

        // Register a "local" agent to the registry
        P2PRegistry.register(TestP2PInterface(), localTransport,
            P2PDescriptor("Local", "Desc", localTransport, false, false, false, false, false, false, false, false, ContextProtocol.none),
            P2PRequirements()
        )

        try {
            // 1. Request from unregistered (external) address
            val requestExternal = P2PRequest().apply {
                returnAddress = externalTransport
            }
            val (result1, rejection1) = P2PRegistry.checkAgentRequirements(requestExternal, requirements, agent)
            assertFalse(result1, "Should reject external connection")
            assertEquals(P2PError.transport, rejection1?.errorType)

            // 2. Request from registered (local) address
            val requestLocal = P2PRequest().apply {
                returnAddress = localTransport
            }
            val (result2, _) = P2PRegistry.checkAgentRequirements(requestLocal, requirements, agent)
            assertTrue(result2, "Should accept local connection")

            // 3. Allow external connections = true
            val requirementsAllowExternal = P2PRequirements(allowExternalConnections = true)
            val (result3, _) = P2PRegistry.checkAgentRequirements(requestExternal, requirementsAllowExternal, agent)
            assertTrue(result3, "Should accept external connection when allowExternalConnections is true")

        } finally {
            P2PRegistry.remove(localTransport)
        }
    }

    @Test
    fun testAgentDuplicationRequirement() = runBlocking {
        val requirements = P2PRequirements(allowAgentDuplication = false, allowExternalConnections = true)
        val agent = TestP2PInterface()

        // 1. Request requiring duplication (outputSchema)
        val requestOutputSchema = P2PRequest().apply {
            outputSchema = CustomJsonSchema()
        }
        val (result1, rejection1) = P2PRegistry.checkAgentRequirements(requestOutputSchema, requirements, agent)
        assertFalse(result1, "Should reject request with outputSchema when duplication not allowed")
        assertEquals(P2PError.json, rejection1?.errorType)

        // 2. Request requiring duplication (inputSchema)
        val requestInputSchema = P2PRequest().apply {
            inputSchema = CustomJsonSchema()
        }
        val (result2, _) = P2PRegistry.checkAgentRequirements(requestInputSchema, requirements, agent)
        assertFalse(result2, "Should reject request with inputSchema when duplication not allowed")

        // 3. Request requiring duplication (contextExplanationMessage)
        val requestContextExp = P2PRequest().apply {
            contextExplanationMessage = "Use this context"
        }
        val (result3, _) = P2PRegistry.checkAgentRequirements(requestContextExp, requirements, agent)
        assertFalse(result3, "Should reject request with contextExplanationMessage when duplication not allowed")

        // 4. Allowed duplication
        val requirementsAllowDup = P2PRequirements(allowAgentDuplication = true, allowExternalConnections = true)
        val (result4, _) = P2PRegistry.checkAgentRequirements(requestOutputSchema, requirementsAllowDup, agent)
        assertTrue(result4, "Should accept request with outputSchema when duplication is allowed")
    }

    @Test
    fun testTokenLimitRequirement() = runBlocking {
        // Setting up requirements with token counting settings
        val requirements = P2PRequirements(
            maxTokens = 5,
            tokenCountingSettings = TruncationSettings(
                countSubWordsInFirstWord = false,
                favorWholeWords = false,
                splitForNonWordChar = false,
                nonWordSplitCount = 100 // Large value so we don't count by chars easily
            ),
            allowExternalConnections = true
        )
        val agent = TestP2PInterface()

        // 1. Within limit
        val requestSmall = P2PRequest().apply {
            prompt.addText("Short") // 1 word, 1 token
        }
        val (result1, _) = P2PRegistry.checkAgentRequirements(requestSmall, requirements, agent)
        assertTrue(result1, "Should accept request within token limit")

        // 2. Exceeds limit
        val requestLarge = P2PRequest().apply {
            prompt.addText("one two three four five six seven") // 7 words, 7 tokens
        }
        val (result2, rejection2) = P2PRegistry.checkAgentRequirements(requestLarge, requirements, agent)

        assertFalse(result2, "Should reject request exceeding token limit")
        assertEquals(P2PError.prompt, rejection2?.errorType)
    }

    @Test
    fun testMultiPageContextRequirement() = runBlocking {
        val requirements = P2PRequirements(allowMultiPageContext = false, allowExternalConnections = true)
        val agent = TestP2PInterface()

        // 1. Request with multi-page indicator
        val requestMultiPage = P2PRequest().apply {
            contextExplanationMessage = "Using pageKey here"
        }
        val (result1, rejection1) = P2PRegistry.checkAgentRequirements(requestMultiPage, requirements, agent)
        // Rejects with P2PError.json because contextExplanationMessage triggers duplication check first
        assertFalse(result1, "Should reject multi-page request when not allowed")
        assertEquals(P2PError.json, rejection1?.errorType)

        // 2. Request without multi-page indicator
        val requestNormal = P2PRequest().apply {
            prompt.addText("hello")
            contextExplanationMessage = ""
        }
        val (result2, _) = P2PRegistry.checkAgentRequirements(requestNormal, requirements, agent)
        assertTrue(result2, "Should accept normal request when multi-page not allowed")
    }

    @Test
    fun testMultiPageBudgetStrategyRequirement() = runBlocking {
        // Test that strategy mismatch between pipe and requirements is caught
        val requirements = P2PRequirements(
            multiPageBudgetSettings = TokenBudgetSettings(
                multiPageBudgetStrategy = MultiPageBudgetStrategy.WEIGHTED_SPLIT
            ),
            allowExternalConnections = true,
            allowAgentDuplication = true // Need this to pass duplication check
        )

        // Agent (Pipe) has a different strategy
        val pipe = TestTokenPipe("test-pipe")
        pipe.setMultiPageBudgetStrategy(MultiPageBudgetStrategy.EQUAL_SPLIT)

        val request = P2PRequest().apply {
            contextExplanationMessage = "multiPage" // Trigger multi-page detection
        }

        val (result, rejection) = P2PRegistry.checkAgentRequirements(request, requirements, pipe)
        assertFalse(result, "Should reject strategy mismatch")
        assertEquals(P2PError.configuration, rejection?.errorType)
    }

    @Test
    fun testSendP2pRequestPreservesWorkerTemplateWhenReturnAddressOverrideIsApplied()
    {
        runBlocking {
            val managerTransport = P2PTransport(transportMethod = Transport.Tpipe, transportAddress = "manager-return")
            val workerTransport = P2PTransport(transportMethod = Transport.Tpipe, transportAddress = "templated-worker")

            val workerDescriptor = P2PDescriptor(
                agentName = "templated-worker",
                agentDescription = "Templated worker",
                transport = workerTransport,
                requiresAuth = false,
                usesConverse = true,
                allowsAgentDuplication = true,
                allowsCustomContext = false,
                allowsCustomAgentJson = false,
                recordsInteractionContext = false,
                recordsPromptContent = false,
                allowsExternalContext = false,
                contextProtocol = ContextProtocol.pcp,
                requestTemplate = P2PRequest().apply {
                    authBody = "expected-auth"
                    outputSchema = CustomJsonSchema()
                }
            )
            val workerRequirements = P2PRequirements(
                allowExternalConnections = false,
                requireConverseInput = true,
                allowAgentDuplication = true,
                authMechanism = { authBody -> authBody == "expected-auth" }
            )
            val workerAgent = CapturingP2PInterface(workerTransport, workerDescriptor, workerRequirements)

            try {
                P2PRegistry.register(TestP2PInterface(transport = managerTransport), managerTransport,
                    P2PDescriptor("manager", "Manager", managerTransport, false, true, false, false, false, false, false, false, ContextProtocol.pcp),
                    P2PRequirements(allowExternalConnections = true)
                )
                P2PRegistry.register(workerAgent, workerTransport, workerDescriptor, workerRequirements)

                val response = P2PRegistry.sendP2pRequest(
                    request = AgentRequest(
                        agentName = "templated-worker",
                        promptSchema = InputSchema.json,
                        prompt = """{"history":[{"role":"user","content":{"text":"hello","binaryContent":[],"terminatePipeline":false}}]}"""
                    ),
                    returnAddressOverride = managerTransport
                )

                assertNotNull(response.output)
                assertEquals("Success", response.output?.text)
                assertNotNull(workerAgent.lastRequest)
                assertEquals("expected-auth", workerAgent.lastRequest!!.authBody)
                assertEquals(managerTransport, workerAgent.lastRequest!!.returnAddress)
                assertNotNull(workerAgent.lastRequest!!.outputSchema)
                assertEquals("", workerDescriptor.requestTemplate!!.returnAddress.transportAddress)
            } finally {
                P2PRegistry.remove(workerTransport)
                P2PRegistry.remove(managerTransport)
            }
        }
    }

    @Test
    fun testReturnAddressOverrideDoesNotMutateSharedWorkerTemplate()
    {
        runBlocking {
            val managerTransportA = P2PTransport(transportMethod = Transport.Tpipe, transportAddress = "manager-a")
            val managerTransportB = P2PTransport(transportMethod = Transport.Tpipe, transportAddress = "manager-b")
            val workerTransport = P2PTransport(transportMethod = Transport.Tpipe, transportAddress = "templated-worker")

            val sharedTemplate = P2PRequest().apply {
                authBody = "expected-auth"
            }
            val workerDescriptor = P2PDescriptor(
                agentName = "templated-worker",
                agentDescription = "Templated worker",
                transport = workerTransport,
                requiresAuth = false,
                usesConverse = true,
                allowsAgentDuplication = false,
                allowsCustomContext = false,
                allowsCustomAgentJson = false,
                recordsInteractionContext = false,
                recordsPromptContent = false,
                allowsExternalContext = false,
                contextProtocol = ContextProtocol.pcp,
                requestTemplate = sharedTemplate
            )
            val workerRequirements = P2PRequirements(
                allowExternalConnections = false,
                requireConverseInput = true,
                authMechanism = { authBody -> authBody == "expected-auth" }
            )
            val workerAgent = CapturingP2PInterface(workerTransport, workerDescriptor, workerRequirements)

            try {
                P2PRegistry.register(TestP2PInterface(transport = managerTransportA), managerTransportA,
                    P2PDescriptor("manager-a", "Manager A", managerTransportA, false, true, false, false, false, false, false, false, ContextProtocol.pcp),
                    P2PRequirements(allowExternalConnections = true)
                )
                P2PRegistry.register(TestP2PInterface(transport = managerTransportB), managerTransportB,
                    P2PDescriptor("manager-b", "Manager B", managerTransportB, false, true, false, false, false, false, false, false, ContextProtocol.pcp),
                    P2PRequirements(allowExternalConnections = true)
                )
                P2PRegistry.register(workerAgent, workerTransport, workerDescriptor, workerRequirements)

                val request = AgentRequest(
                    agentName = "templated-worker",
                    promptSchema = InputSchema.json,
                    prompt = """{"history":[{"role":"user","content":{"text":"hello","binaryContent":[],"terminatePipeline":false}}]}"""
                )

                val responseA = P2PRegistry.sendP2pRequest(
                    request = request,
                    returnAddressOverride = managerTransportA
                )
                assertNotNull(responseA.output)
                assertEquals(managerTransportA, workerAgent.lastRequest!!.returnAddress)
                assertEquals("", sharedTemplate.returnAddress.transportAddress)

                val responseB = P2PRegistry.sendP2pRequest(
                    request = request,
                    returnAddressOverride = managerTransportB
                )
                assertNotNull(responseB.output)
                assertEquals(managerTransportB, workerAgent.lastRequest!!.returnAddress)
                assertEquals("", sharedTemplate.returnAddress.transportAddress)
            } finally {
                P2PRegistry.remove(workerTransport)
                P2PRegistry.remove(managerTransportA)
                P2PRegistry.remove(managerTransportB)
            }
        }
    }

    @Test
    fun testRegistrationFailsWhenRequiresAuthButNoMechanism()
    {
        val transport = P2PTransport(transportMethod = Transport.Tpipe, transportAddress = "no-auth-agent")
        val descriptor = P2PDescriptor(
            agentName = "no-auth-agent",
            agentDescription = "Agent with requiresAuth but no mechanism",
            transport = transport,
            requiresAuth = true,
            usesConverse = false,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.pcp
        )
        val requirements = P2PRequirements(allowExternalConnections = true, authMechanism = null)
        val agent = TestP2PInterface(transport, descriptor, requirements)
        val originalGlobalAuth = P2PRegistry.globalAuthMechanism

        try {
            P2PRegistry.globalAuthMechanism = null
            assertFailsWith<IllegalArgumentException> {
                P2PRegistry.register(agent, transport, descriptor, requirements)
            }

            // Succeeds with local mechanism
            val reqWithAuth = P2PRequirements(allowExternalConnections = true, authMechanism = { it == "ok" })
            P2PRegistry.register(agent, transport, descriptor, reqWithAuth)
            P2PRegistry.remove(transport)

            // Succeeds with global mechanism
            P2PRegistry.globalAuthMechanism = { it == "global" }
            P2PRegistry.register(agent, transport, descriptor, requirements)
            P2PRegistry.remove(transport)
        } finally {
            P2PRegistry.globalAuthMechanism = originalGlobalAuth
            P2PRegistry.remove(transport)
        }
    }

    @Test
    fun testGlobalAuthMechanismEnforcementFallback() = runBlocking {
        val workerTransport = P2PTransport(transportMethod = Transport.Tpipe, transportAddress = "global-auth-worker")
        val workerDescriptor = P2PDescriptor(
            agentName = "global-auth-worker",
            agentDescription = "Worker relying on global auth",
            transport = workerTransport,
            requiresAuth = true,
            usesConverse = false,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.pcp
        )
        val workerRequirements = P2PRequirements(
            allowExternalConnections = true,
            authMechanism = null
        )
        val workerAgent = TestP2PInterface(workerTransport, workerDescriptor, workerRequirements)
        val originalGlobalAuth = P2PRegistry.globalAuthMechanism

        try {
            P2PRegistry.globalAuthMechanism = { authBody -> authBody == "global-secret" }
            P2PRegistry.register(workerAgent, workerTransport, workerDescriptor, workerRequirements)

            val validRequest = P2PRequest().apply {
                transport = workerTransport
                authBody = "global-secret"
                prompt.addText("hello")
            }
            val validResponse = P2PRegistry.executeP2pRequest(validRequest)
            assertNull(validResponse.rejection, "Valid global auth credentials should pass")

            val invalidRequest = P2PRequest().apply {
                transport = workerTransport
                authBody = "wrong-secret"
                prompt.addText("hello")
            }
            val invalidResponse = P2PRegistry.executeP2pRequest(invalidRequest)
            assertNotNull(invalidResponse.rejection, "Invalid global auth credentials should be rejected")
            assertEquals(P2PError.auth, invalidResponse.rejection!!.errorType)
        } finally {
            P2PRegistry.globalAuthMechanism = originalGlobalAuth
            P2PRegistry.remove(workerTransport)
        }
    }

    @Test
    fun testAcceptedContentTypesRequirement() = runBlocking {
        val requirements = P2PRequirements(
            acceptedContent = mutableListOf(SupportedContentTypes.image),
            allowExternalConnections = true
        )
        val agent = TestP2PInterface()

        // 1. Supported content type (image)
        val requestImage = P2PRequest().apply {
            prompt.addBinary("fake-image-data".toByteArray(), "image/png")
        }
        val (result1, _) = P2PRegistry.checkAgentRequirements(requestImage, requirements, agent)
        assertTrue(result1, "Should accept supported content type")

        // 2. Unsupported content type (video)
        val requestVideo = P2PRequest().apply {
            prompt.addBinary("fake-video-data".toByteArray(), "video/mp4")
        }
        val (result2, rejection2) = P2PRegistry.checkAgentRequirements(requestVideo, requirements, agent)
        assertFalse(result2, "Should reject unsupported content type")
        assertEquals(P2PError.content, rejection2?.errorType)
    }

    @Test
    fun testAuthMechanismRequirement() = runBlocking {
        var authCalled = false
        val requirements = P2PRequirements(
            authMechanism = { authBody ->
                authCalled = true
                authBody == "secret-token"
            },
            allowExternalConnections = true
        )
        val agent = TestP2PInterface()

        // 1. Valid auth
        val requestValidAuth = P2PRequest().apply {
            authBody = "secret-token"
        }
        val (result1, _) = P2PRegistry.checkAgentRequirements(requestValidAuth, requirements, agent)
        assertTrue(result1, "Should accept valid auth")
        assertTrue(authCalled, "Auth mechanism should have been called")

        // 2. Invalid auth
        authCalled = false
        val requestInvalidAuth = P2PRequest().apply {
            authBody = "wrong-token"
        }
        val (result2, rejection2) = P2PRegistry.checkAgentRequirements(requestInvalidAuth, requirements, agent)
        assertFalse(result2, "Should reject invalid auth")
        assertEquals(P2PError.auth, rejection2?.errorType)
        assertTrue(authCalled, "Auth mechanism should have been called")
    }
}
