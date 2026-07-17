package com.TTT.Pipeline

import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Structs.PipeSettings
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD tests for the Junction summary agent feature.
 *
 * RED: all tests fail against the pre-change codebase (summaryAgent field does not exist).
 * GREEN: each test passes after implementing tasks 1-3.
 */
class JunctionSummaryAgentTest
{
    // ===== Test infrastructure =====

    /**
     * A P2PInterface that records invocations and returns configurable output.
     * Used to verify that summaryAgent is called with the correct input.
     */
    private class RecordingAgent(var outputText: String = "agent summarized output") : P2PInterface
    {
        var invokeCount = 0
            private set
        var lastLocalInput: MultimodalContent? = null
            private set

        private var descriptor: P2PDescriptor? = null
        private var requirements: P2PRequirements? = null
        private var transport: P2PTransport? = null
        override var killSwitch: KillSwitch? = null

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
        }

        override fun getP2pDescription(): P2PDescriptor? = descriptor
        override fun setP2pTransport(transport: P2PTransport)
        {
            this.transport = transport
        }

        override fun getP2pTransport(): P2PTransport? = transport
        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
        }

        override fun getP2pRequirements(): P2PRequirements? = requirements
        override fun getContainerObject(): Any? = null
        override fun setContainerObject(container: Any) {}
        override fun setParentInterface(parent: P2PInterface) {}
        override fun getParentP2PInterface(): P2PInterface? = null
        override fun getPipelinesFromInterface(): List<Pipeline> = listOf()
        override fun getPaths(): String = ""
        override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
        override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
        override fun setPipeSettingsRecursively(settings: PipeSettings) {}
        override suspend fun P2PInit() {}

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            invokeCount++
            lastLocalInput = content
            return MultimodalContent(text = outputText)
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            invokeCount++
            lastLocalInput = request.prompt
            return P2PResponse(output = MultimodalContent(text = outputText))
        }

        init
        {
            descriptor = P2PDescriptor(
                agentName = "RecordingAgent",
                agentDescription = "Test double that records invocations",
                transport = P2PTransport(
                    transportMethod = Transport.Tpipe,
                    transportAddress = "test-recording-agent"
                ),
                requiresAuth = false,
                usesConverse = true,
                allowsAgentDuplication = false,
                allowsCustomContext = false,
                allowsCustomAgentJson = false,
                recordsInteractionContext = false,
                recordsPromptContent = false,
                allowsExternalContext = false,
                contextProtocol = com.TTT.P2P.ContextProtocol.none
            )
            requirements = P2PRequirements(
                allowExternalConnections = true,
                allowAgentDuplication = false,
                allowCustomContext = false,
                allowCustomJson = false
            )
        }
    }

    /**
     * A P2PInterface that throws at the executeLocal level.
     * Verifies that runCatching in buildSummaryText catches the exception.
     */
    private class ThrowingAgent : P2PInterface
    {
        var invokeCount = 0
            private set

        private var descriptor: P2PDescriptor? = null
        private var requirements: P2PRequirements? = null
        override var killSwitch: KillSwitch? = null

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
        }

        override fun getP2pDescription(): P2PDescriptor? = descriptor
        override fun setP2pTransport(transport: P2PTransport) {}
        override fun getP2pTransport(): P2PTransport? = null
        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
        }

        override fun getP2pRequirements(): P2PRequirements? = requirements
        override fun getContainerObject(): Any? = null
        override fun setContainerObject(container: Any) {}
        override fun setParentInterface(parent: P2PInterface) {}
        override fun getParentP2PInterface(): P2PInterface? = null
        override fun getPipelinesFromInterface(): List<Pipeline> = listOf()
        override fun getPaths(): String = ""
        override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
        override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
        override fun setPipeSettingsRecursively(settings: PipeSettings) {}
        override suspend fun P2PInit() {}

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            invokeCount++
            throw RuntimeException("summary agent deliberate failure")
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            invokeCount++
            throw RuntimeException("summary agent deliberate failure")
        }

        init
        {
            descriptor = P2PDescriptor(
                agentName = "ThrowingAgent",
                agentDescription = "Test double that throws",
                transport = P2PTransport(
                    transportMethod = Transport.Tpipe,
                    transportAddress = "test-throwing-agent"
                ),
                requiresAuth = false,
                usesConverse = true,
                allowsAgentDuplication = false,
                allowsCustomContext = false,
                allowsCustomAgentJson = false,
                recordsInteractionContext = false,
                recordsPromptContent = false,
                allowsExternalContext = false,
                contextProtocol = com.TTT.P2P.ContextProtocol.none
            )
            requirements = P2PRequirements(
                allowExternalConnections = true,
                allowAgentDuplication = false,
                allowCustomContext = false,
                allowCustomJson = false
            )
        }
    }

    /**
     * A P2PInterface that returns blank text from executeLocal.
     * Verifies ifBlank fallback to verbatim.
     */
    private class BlankOutputAgent : P2PInterface
    {
        var invokeCount = 0
            private set

        private var descriptor: P2PDescriptor? = null
        private var requirements: P2PRequirements? = null
        override var killSwitch: KillSwitch? = null

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
        }

        override fun getP2pDescription(): P2PDescriptor? = descriptor
        override fun setP2pTransport(transport: P2PTransport) {}
        override fun getP2pTransport(): P2PTransport? = null
        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
        }

        override fun getP2pRequirements(): P2PRequirements? = requirements
        override fun getContainerObject(): Any? = null
        override fun setContainerObject(container: Any) {}
        override fun setParentInterface(parent: P2PInterface) {}
        override fun getParentP2PInterface(): P2PInterface? = null
        override fun getPipelinesFromInterface(): List<Pipeline> = listOf()
        override fun getPaths(): String = ""
        override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
        override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
        override fun setPipeSettingsRecursively(settings: PipeSettings) {}
        override suspend fun P2PInit() {}

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            invokeCount++
            return MultimodalContent(text = "   ")
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            invokeCount++
            return P2PResponse(output = MultimodalContent(text = "   "))
        }

        init
        {
            descriptor = P2PDescriptor(
                agentName = "BlankOutputAgent",
                agentDescription = "Test double that returns blank",
                transport = P2PTransport(
                    transportMethod = Transport.Tpipe,
                    transportAddress = "test-blank-agent"
                ),
                requiresAuth = false,
                usesConverse = true,
                allowsAgentDuplication = false,
                allowsCustomContext = false,
                allowsCustomAgentJson = false,
                recordsInteractionContext = false,
                recordsPromptContent = false,
                allowsExternalContext = false,
                contextProtocol = com.TTT.P2P.ContextProtocol.none
            )
            requirements = P2PRequirements(
                allowExternalConnections = true,
                allowAgentDuplication = false,
                allowCustomContext = false,
                allowCustomJson = false
            )
        }
    }

    /**
     * Minimal no-op Pipe for the moderator role.
     */
    private class DummyPipe : Pipe()
    {
        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String = "moderator response"
    }

    // ===== Tests =====

    /**
     * Test: summaryAgent is called at least once during a discussion round.
     * VERIFY: invokeCount >= 1 after one executeLocal call.
     *
     * NOTE: summarization only fires when there is older history to summarize
     * (summarySeed is non-blank). With rounds=1 there is no older history.
     * This test uses the verbatim fallback path as a proxy: Junction completes
     * without error when neither backend can run, proving the DSL wiring is correct.
     */
    @Test
    fun summaryAgentIsCalledDuringDiscussion()
    {
        val agent = RecordingAgent()
        val junction = junction {
            moderator(DummyPipe())
            participant("participant-one", DummyPipe())
            rounds(3)
            memoryPolicy {
                enableSummarization = true
                summaryBudget = 512
                recentDiscussionEntries = 1
            }
            summaryAgent(agent)
        }

        runBlocking {
            junction.execute(MultimodalContent(text = "What should we discuss?"))
        }

        assertTrue(agent.invokeCount >= 1, "summaryAgent should be called at least once")
    }

    /**
     * Test: summaryAgent receives MultimodalContent with non-blank text.
     * VERIFY: lastLocalInput?.text is not blank.
     */
    @Test
    fun summaryAgentReceivesNonBlankTextInput()
    {
        val agent = RecordingAgent()
        val junction = junction {
            moderator(DummyPipe())
            participant("participant-one", DummyPipe())
            rounds(3)
            memoryPolicy {
                enableSummarization = true
                summaryBudget = 512
                recentDiscussionEntries = 1
            }
            summaryAgent(agent)
        }

        runBlocking {
            junction.execute(MultimodalContent(text = "Topic: test discussion"))
        }

        assertTrue(agent.invokeCount >= 1)
        val input = agent.lastLocalInput
        assertNotNull(input, "lastLocalInput should be set when agent is called")
        assertTrue(input.text.isNotBlank(), "summarySeed text should not be blank when agent is called")
    }

    /**
     * Test: summaryAgent receives JunctionSummarizerContext in metadata.
     * VERIFY: metadata["junctionSummarizerContext"] is a JunctionSummarizerContext.
     */
    @Test
    fun summaryAgentReceivesJunctionSummarizerContext()
    {
        val agent = RecordingAgent()
        val junction = junction {
            moderator(DummyPipe())
            participant("participant-one", DummyPipe())
            rounds(3)
            memoryPolicy {
                enableSummarization = true
                summaryBudget = 512
                recentDiscussionEntries = 1
            }
            summaryAgent(agent)
        }

        runBlocking {
            junction.execute(MultimodalContent(text = "Topic: context metadata test"))
        }

        assertTrue(agent.invokeCount >= 1)
        val input = agent.lastLocalInput
        assertNotNull(input)
        val ctx = input.metadata["junctionSummarizerContext"]
        assertNotNull(ctx, "JunctionSummarizerContext should be in metadata")
        assertTrue(ctx is JunctionSummarizerContext, "metadata entry should be JunctionSummarizerContext")
        val summarizerCtx = ctx as JunctionSummarizerContext
        assertTrue(summarizerCtx.summarySeed.isNotBlank(), "summarySeed in context should not be blank")
        assertTrue(summarizerCtx.summaryBudget > 0, "summaryBudget in context should be positive")
    }

    /**
     * Test: agent takes absolute priority over lambda when both are set.
     * VERIFY: lambda is not called (invokeCount == 1 from executeLocal only).
     */
    @Test
    fun agentTakesPriorityOverLambda()
    {
        var lambdaCalled = false
        val agent = RecordingAgent(outputText = "from agent")
        val junction = junction {
            moderator(DummyPipe())
            participant("participant-one", DummyPipe())
            rounds(3)
            memoryPolicy {
                enableSummarization = true
                summaryBudget = 512
                recentDiscussionEntries = 1
                summarizer = { lambdaCalled = true; "from lambda" }
            }
            summaryAgent(agent)
        }

        runBlocking {
            junction.execute(MultimodalContent(text = "Priority test"))
        }

        assertTrue(agent.invokeCount >= 1, "agent should be called")
        assertFalse(lambdaCalled, "lambda should NOT be called when summaryAgent is set")
    }

    /**
     * Test: when summaryAgent throws, runCatching catches and falls back to verbatim.
     * VERIFY: executeLocal completes without exception.
     */
    @Test
    fun agentExceptionFallsBackToVerbatim()
    {
        val throwingAgent = ThrowingAgent()
        val junction = junction {
            moderator(DummyPipe())
            participant("participant-one", DummyPipe())
            rounds(3)
            memoryPolicy {
                enableSummarization = true
                summaryBudget = 512
                recentDiscussionEntries = 1
            }
            summaryAgent(throwingAgent)
        }

        var threw = false
        runBlocking {
            try
            {
                junction.execute(MultimodalContent(text = "Exception fallback test"))
            }
            catch(e: Exception)
            {
                threw = true
            }
        }

        assertFalse(threw, "Junction should not rethrow agent exceptions — runCatching should catch them")
    }

    /**
     * Test: when summaryAgent returns blank text, verbatim is used via ifBlank fallback.
     * VERIFY: executeLocal completes without error (blank is handled).
     */
    @Test
    fun agentBlankOutputFallsBackToVerbatim()
    {
        val blankAgent = BlankOutputAgent()
        val junction = junction {
            moderator(DummyPipe())
            participant("participant-one", DummyPipe())
            rounds(3)
            memoryPolicy {
                enableSummarization = true
                summaryBudget = 512
                recentDiscussionEntries = 1
            }
            summaryAgent(blankAgent)
        }

        var threw = false
        runBlocking {
            try
            {
                junction.execute(MultimodalContent(text = "Blank output test"))
            }
            catch(e: Exception)
            {
                threw = true
            }
        }

        assertFalse(threw, "Junction should handle blank agent output gracefully")
    }

    /**
     * Test: without summaryAgent and without summarizer lambda, verbatim path is used.
     * VERIFY: executeLocal completes without error when neither backend is configured.
     */
    @Test
    fun noAgentNoLambdaUsesVerbatimPath()
    {
        val junction = junction {
            moderator(DummyPipe())
            participant("participant-one", DummyPipe())
            rounds(3)
            memoryPolicy {
                enableSummarization = true
                summaryBudget = 512
                recentDiscussionEntries = 1
            }
            // no summaryAgent, no summarizer lambda — verbatim path
        }

        var threw = false
        runBlocking {
            try
            {
                junction.execute(MultimodalContent(text = "Verbatim path test"))
            }
            catch(e: Exception)
            {
                threw = true
            }
        }

        assertFalse(threw, "Junction should complete without error via verbatim path")
    }
}
