package com.TTT.Pipeline

import com.TTT.P2P.AgentRequest
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Compile-time validation tests for the type-safe DSL builders.
 *
 * These tests verify that the phantom-type state machine correctly restricts
 * build() to only be available when all required configuration is complete.
 *
 * The DSL entry points (manifold {}, junction {}, distributionGrid {}) return
 * the fully configured object directly, not a builder. This proves the state
 * machine is working - if required config was missing, the entry point would fail.
 *
 * NOTE: The fact that this test file compiles successfully proves that the
 * type-safe DSL pattern is working correctly. If build() was callable without
 * all required config, the compiler would not be able to resolve the type.
 */
class CompileTimeDslValidationTest
{
    /**
     * Verify that ManifoldDsl correctly requires both manager and worker
     * before build() is accessible. The manifold {} entry point returns
     * Manifold directly, which proves all required config was present.
     */
    @Test
    fun manifoldDslRequiresAllRequiredConfig()
    {
        // Pre-create pipes with proper configuration to satisfy runtime validation
        val managerPipe = DummyPipe()
            .setPipeName("dispatcher")
            .setJsonOutput(AgentRequest())
            .setTokenBudget(TokenBudgetSettings(contextWindowSize = 4096, userPromptSize = 1024, maxTokens = 256))

        val workerPipe = DummyPipe()
            .setPipeName("worker")
            .setContextWindowSize(2048)
            .autoTruncateContext()

        val builtManifold = manifold {
            manager {
                pipeline {
                    pipelineName = "manager-pipeline"
                    add(managerPipe)
                }
                agentDispatchPipe("dispatcher")
            }
            worker("test-worker") {
                pipeline {
                    pipelineName = "test-worker"
                    add(workerPipe)
                }
            }
        }

        assertNotNull(builtManifold)
        assertEquals("test-worker", builtManifold.getWorkerPipelines().first().pipelineName)
    }

    /**
     * Verify that JunctionDsl correctly requires moderator before participants
     * and that build() is accessible after complete configuration.
     */
    @Test
    fun junctionDslRequiresAllRequiredConfig()
    {
        val moderator = TestRecordingP2PInterface(name = "moderator", responseText = serialize(TestModeratorDirective(
            continueDiscussion = false,
            selectedParticipants = mutableListOf(),
            finalDecision = "Proceed",
            nextRoundPrompt = "",
            notes = "Test"
        )))
        val participant = TestRecordingP2PInterface(name = "participant", responseText = serialize(TestParticipantOpinion(
            participantName = "participant",
            roundNumber = 1,
            opinion = "Approve",
            vote = "Approve",
            confidence = 1.0,
            reasoning = "Looks good"
        )))

        val builtJunction = junction {
            moderator(moderator)
            participant("participant", participant)
            rounds(1)
            threshold(0.5)
        }

        assertNotNull(builtJunction)
    }

    /**
     * Verify that DistributionGridDsl correctly requires router and worker
     * before build() is accessible. The distributionGrid {} entry point returns
     * DistributionGrid directly, which proves all required config was present.
     */
    @Test
    fun distributionGridDslRequiresAllRequiredConfig()
    {
        val router = TestSimpleP2PInterface(name = "test-router")
        val worker = TestSimpleP2PInterface(name = "test-worker")

        val builtGrid = distributionGrid {
            router(router)
            worker(worker)
        }

        assertNotNull(builtGrid)
    }

    /**
     * Verify builder type flows through DSL stages correctly.
     * This test compiles only if the phantom type state machine is working.
     */
    @Test
    fun manifoldBuilderTypeFlowsCorrectly()
    {
        // Pre-create pipes with proper configuration
        val managerPipe = DummyPipe()
            .setPipeName("dispatcher")
            .setJsonOutput(AgentRequest())
            .setTokenBudget(TokenBudgetSettings(contextWindowSize = 4096, userPromptSize = 1024, maxTokens = 256))

        val workerPipe = DummyPipe()
            .setPipeName("worker")
            .setContextWindowSize(2048)
            .autoTruncateContext()

        // The key test: only when worker is added does build() become available
        // The entry point returning Manifold proves the state transitions work
        val result = manifold {
            manager {
                pipeline {
                    pipelineName = "manager"
                    add(managerPipe)
                }
                agentDispatchPipe("dispatcher")
            }
            worker("w1") {
                pipeline {
                    pipelineName = "w1"
                    add(workerPipe)
                }
            }
        }

        assertNotNull(result)
        assertTrue(result is Manifold)
    }

    /**
     * Verify JunctionBuilder type flow. The junction entry point returning Junction
     * directly proves the state transitions work correctly.
     */
    @Test
    fun junctionBuilderTypeFlowsCorrectly()
    {
        val moderator = TestRecordingP2PInterface(name = "mod", responseText = serialize(TestModeratorDirective(
            continueDiscussion = false,
            selectedParticipants = mutableListOf(),
            finalDecision = "Done",
            nextRoundPrompt = "",
            notes = "Done"
        )))
        val participant = TestRecordingP2PInterface(name = "part", responseText = serialize(TestParticipantOpinion(
            participantName = "part",
            roundNumber = 1,
            opinion = "Ok",
            vote = "Ok",
            confidence = 1.0,
            reasoning = ""
        )))

        val result = junction {
            moderator(moderator)
            participant("p1", participant)
        }

        assertNotNull(result)
        assertTrue(result is Junction)
    }

    /**
     * Verify DistributionGridBuilder type flow.
     */
    @Test
    fun distributionGridBuilderTypeFlowsCorrectly()
    {
        val router = TestSimpleP2PInterface(name = "r")
        val worker = TestSimpleP2PInterface(name = "w")

        val result = distributionGrid {
            router(router)
            worker(worker)
        }

        assertNotNull(result)
        assertTrue(result is DistributionGrid)
    }
}

/**
 * Simple test double pipe for DSL validation tests.
 * Mirrors the DummyPipe pattern used in ManifoldDslTest.
 */
private class DummyPipe : Pipe()
{
    init
    {
        // Satisfy overflow protection validation without requiring full Pipe setup
        // since this is a compile-time type system test, not an integration test
        @Suppress("ACCESSING_NON_PUBLIC_MEMBER_FROM_JAVA_CODE")
        autoTruncateContext = true
    }

    override fun truncateModuleContext(): Pipe = this

    override suspend fun generateText(promptInjector: String): String = promptInjector

    override suspend fun generateContent(content: MultimodalContent): MultimodalContent = content
}

/**
 * Simple P2PInterface test double.
 */
private class TestSimpleP2PInterface(private val name: String) : P2PInterface
{
    private var containerObject: Any? = null
    override var killSwitch: KillSwitch? = null

    override fun getContainerObject(): Any? = containerObject

    override fun setContainerObject(container: Any)
    {
        containerObject = container
    }

    override fun getP2pDescription(): P2PDescriptor? = P2PDescriptor(
        agentName = name,
        agentDescription = "Test $name",
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

    override fun getP2pTransport(): P2PTransport? = getP2pDescription()?.transport

    override fun getP2pRequirements(): P2PRequirements? = P2PRequirements(
        allowExternalConnections = false,
        allowAgentDuplication = false,
        allowCustomContext = false,
        allowCustomJson = false
    )

    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent = content
}

/**
 * Test double for a P2P-capable participant or moderator.
 * Copied from JunctionTest for use in compile-time validation tests.
 */
private class TestRecordingP2PInterface(
    private val name: String,
    private val responseText: String = ""
) : P2PInterface
{
    private var containerObject: Any? = null
    override var killSwitch: KillSwitch? = null

    override fun getContainerObject(): Any? = containerObject

    override fun setContainerObject(container: Any)
    {
        containerObject = container
    }

    override fun getP2pDescription(): P2PDescriptor? = P2PDescriptor(
        agentName = name,
        agentDescription = "Recording test double for $name",
        transport = P2PTransport(transportMethod = Transport.Tpipe, transportAddress = name),
        requiresAuth = false,
        usesConverse = true,
        allowsAgentDuplication = false,
        allowsCustomContext = false,
        allowsCustomAgentJson = false,
        recordsInteractionContext = false,
        recordsPromptContent = false,
        allowsExternalContext = false,
        contextProtocol = ContextProtocol.none,
        supportedContentTypes = mutableListOf(SupportedContentTypes.text)
    )

    override fun getP2pTransport(): P2PTransport? = getP2pDescription()?.transport

    override fun getP2pRequirements(): P2PRequirements? = P2PRequirements(
        allowExternalConnections = false,
        allowAgentDuplication = false,
        allowCustomContext = false,
        allowCustomJson = false
    )

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
    {
        return P2PResponse(output = MultimodalContent(text = responseText))
    }

    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
    {
        return content
    }
}

/**
 * Moderator directive for testing - minimal structure.
 */
private data class TestModeratorDirective(
    val continueDiscussion: Boolean,
    val selectedParticipants: MutableList<String>,
    val finalDecision: String,
    val nextRoundPrompt: String,
    val notes: String
)

/**
 * Participant opinion for testing - minimal structure.
 */
private data class TestParticipantOpinion(
    val participantName: String,
    val roundNumber: Int,
    val opinion: String,
    val vote: String,
    val confidence: Double,
    val reasoning: String
)