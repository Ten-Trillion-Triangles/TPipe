package com.TTT.Debug

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Regression tests for tracing system issues identified in trace analysis.
 *
 * Setup notes: each test wires a raw [Pipe] without going through a
 * [com.TTT.Pipeline.Pipeline] wrapper, so the test has to manually enable tracing
 * on every pipe it instantiates ([Pipe.enableTracing]) and bind the test trace id
 * with [Pipe.addTraceId]. Without that, the per-pipe early return at the top of
 * [Pipe.trace] drops every event.
 *
 * These tests verify:
 * 1. BRANCH_PIPE_TRIGGERED event captures branch pipe model metadata
 * 2. Branch pipe execution properly shows new model and settings
 * 3. Trace verbosity levels properly filter events
 * 4. VALIDATION_FAILURE captures validator pipe output
 * 5. Validator-pipe exceptions trace VALIDATION_FAILURE (not PIPE_FAILURE)
 */
class TraceRegressionTest {

    private lateinit var pipelineId: String
    private val traceConfig = TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG)

    @BeforeEach
    fun setup() {
        PipeTracer.enable()
        PipeTracer.startTrace("test-regression").also { pipelineId = "test-regression" }
    }

    // Mock pipe for testing
    class MockBranchPipe(
        name: String = "MockBranch",
        val responseText: String = "Branch Success",
        val branchModel: String = "test-branch-model",
        val branchTemp: Double = 0.7
    ) : Pipe() {
        init {
            pipeName = name
            model = branchModel
            this.temperature = branchTemp
        }

        override fun truncateModuleContext(): Pipe = this

        override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
            return MultimodalContent(text = responseText)
        }

        override suspend fun generateText(promptInjector: String): String = responseText
    }

    /**
     * Helper: turn tracing on for the supplied pipes and bind them to [pipelineId].
     * Both pieces are required - [Pipe.enableTracing] flips the per-pipe guard, and
     * [Pipe.addTraceId] registers the trace bucket the event should be routed to.
     */
    private fun enableTracingOn(vararg pipes: Pipe) {
        pipes.forEach { it.enableTracing(traceConfig).addTraceId(pipelineId) }
    }

    /**
     * REGRESSION TEST: BRANCH_PIPE_TRIGGERED should include branch pipe model metadata
     *
     * CURRENT BEHAVIOR (Pipe.kt:6390, branch-pipe path):
     *     trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.POST_PROCESSING)
     *     - No branch-specific metadata passed; the trace only carries the main pipe's
     *       model/provider via the DEBUG-level enrichment in Pipe.buildMetadataForLevel.
     *
     * EXPECTED BEHAVIOR: BRANCH_PIPE_TRIGGERED should identify the branch pipe that
     * is being invoked, so a debugger can correlate the trigger to the actual model
     * that ran. Required metadata keys: branchModel, branchProvider, branchPipeName.
     */
    @Test
    fun `BRANCH_PIPE_TRIGGERED should include branch pipe model metadata`() = runBlocking {
        val branchPipe = MockBranchPipe(
            name = "TestBranchPipe",
            responseText = "Branch Output",
            branchModel = "claude-3-5-sonnet-latest",
            branchTemp = 0.9
        )

        val mainPipe = object : Pipe() {
            init {
                pipeName = "MainPipe"
                model = "main-model"
            }
            override fun truncateModuleContext(): Pipe = this
            override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
                return MultimodalContent(text = "Main output")
            }
            override suspend fun generateText(promptInjector: String): String = "Main output"
        }

        mainPipe setBranchPipe branchPipe
        mainPipe.setValidatorFunction { false } // Force validation failure -> trigger branch
        enableTracingOn(mainPipe, branchPipe)

        // When: execute pipe (which will trigger branch pipe after validation failure)
        mainPipe.execute(MultimodalContent(text = "Test input"))

        // Then: BRANCH_PIPE_TRIGGERED event should have branch model metadata.
        // Filter to POST_PROCESSING-phase triggers (line 6390 path) so the test
        // is targeting the specific regression the KDoc describes.
        val trace = PipeTracer.getTrace(pipelineId)
        val branchTriggerEvent = trace.find {
            it.eventType == TraceEventType.BRANCH_PIPE_TRIGGERED &&
            it.phase == TracePhase.POST_PROCESSING
        }

        assertNotNull(branchTriggerEvent, "BRANCH_PIPE_TRIGGERED (POST_PROCESSING) should be traced")

        // REGRESSION: these keys are not populated by the current trace call.
        assertTrue(branchTriggerEvent!!.metadata.containsKey("branchModel"),
            "BRANCH_PIPE_TRIGGERED should include branchModel metadata")
        assertTrue(branchTriggerEvent.metadata.containsKey("branchProvider"),
            "BRANCH_PIPE_TRIGGERED should include branchProvider metadata")
        assertTrue(branchTriggerEvent.metadata.containsKey("branchPipeName"),
            "BRANCH_PIPE_TRIGGERED should include branchPipeName metadata")

        // Verify values
        assertEquals("claude-3-5-sonnet-latest", branchTriggerEvent.metadata["branchModel"])
        assertEquals("TestBranchPipe", branchTriggerEvent.metadata["branchPipeName"])
    }

    /**
     * REGRESSION TEST: Branch pipe PIPE_START should show the branch pipe's model
     *
     * When the branch pipe executes, its PIPE_START should record the branch pipe's
     * own model/provider (the values used for the actual LLM call), not the parent
     * pipe's. Pipe.buildMetadataForLevel fills in `model` and `provider` automatically
     * at DEBUG level - the test confirms that path emits the branch pipe's values
     * when the branch pipe is the one calling trace(...).
     */
    @Test
    fun `Branch pipe PIPE_START should show branch pipe model at DEBUG level`() = runBlocking {
        val branchPipe = MockBranchPipe(
            name = "SpecificModelBranch",
            branchModel = "bedrock-nova-pro",
            branchTemp = 1.2
        )

        val mainPipe = object : Pipe() {
            init {
                pipeName = "ParentPipe"
                model = "parent-model"
            }
            override fun truncateModuleContext(): Pipe = this
            override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
                return MultimodalContent(text = "Parent output")
            }
            override suspend fun generateText(promptInjector: String): String = "Parent output"
        }

        mainPipe setBranchPipe branchPipe
        mainPipe.setValidatorFunction { false } // Force branch trigger
        enableTracingOn(mainPipe, branchPipe)

        // When
        mainPipe.execute(MultimodalContent(text = "Input"))

        // Then: Branch pipe's PIPE_START should show branch's model
        val trace = PipeTracer.getTrace(pipelineId)

        // Find all PIPE_START events
        val pipeStartEvents = trace.filter { it.eventType == TraceEventType.PIPE_START }

        // At DEBUG level, we should see model info. The branch pipe's PIPE_START should show its model.
        val branchStartEvents = pipeStartEvents.filter { it.pipeName == "SpecificModelBranch" }

        assertTrue(branchStartEvents.isNotEmpty(), "Branch pipe should emit PIPE_START event")

        // At DEBUG level, model should be captured and should be the branch pipe's model.
        val branchStart = branchStartEvents.first()
        assertTrue(branchStart.metadata.containsKey("model"),
            "PIPE_START at DEBUG level should include model metadata")
        assertEquals("bedrock-nova-pro", branchStart.metadata["model"],
            "Branch pipe's PIPE_START should record the branch pipe's own model")
    }

    /**
     * TEST: Verify BRANCH_PIPE_TRIGGERED is INTERNAL priority (only visible at DEBUG)
     *
     * This is EXPECTED BEHAVIOR - not a regression.
     * Test verifies the priority mapping is working correctly.
     */
    @Test
    fun `BRANCH_PIPE_TRIGGERED has INTERNAL priority and is not visible at VERBOSE`() {
        val priority = EventPriorityMapper.getPriority(TraceEventType.BRANCH_PIPE_TRIGGERED)
        assertEquals(TraceEventPriority.INTERNAL, priority, "BRANCH_PIPE_TRIGGERED should be INTERNAL priority")

        assertFalse(EventPriorityMapper.shouldTrace(TraceEventType.BRANCH_PIPE_TRIGGERED, TraceDetailLevel.VERBOSE),
            "BRANCH_PIPE_TRIGGERED should NOT be visible at VERBOSE level")
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.BRANCH_PIPE_TRIGGERED, TraceDetailLevel.DEBUG),
            "BRANCH_PIPE_TRIGGERED should be visible at DEBUG level")
    }

    /**
     * TEST: Verify VALIDATION_FAILURE properly captures validator output
     *
     * CURRENT BEHAVIOR (Pipe.kt:6380-6386):
     *     trace(TraceEventType.VALIDATION_FAILURE, TracePhase.VALIDATION, validatorPipeContent,
     *           metadata = mapOf(
     *               "reason" to "Validator pipe returned content with terminate flag",
     *               "validatorPipeOutput" to validatorPipeContent.text
     *           ))
     *
     * This path covers the case where a validator pipe returned content with
     * `terminatePipeline = true`. The trace should record the validator's output
     * so a debugger can see what the validator rejected.
     */
    @Test
    fun `VALIDATION_FAILURE should include validator pipe output when terminate flag set`() = runBlocking {
        val validatorPipe = object : Pipe() {
            init {
                pipeName = "TestValidator"
                model = "validator-model"
            }
            override fun truncateModuleContext(): Pipe = this
            override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
                val result = MultimodalContent(text = "INVALID OUTPUT: Not JSON")
                result.terminatePipeline = true // Signal validation failure
                return result
            }
            override suspend fun generateText(promptInjector: String): String = "INVALID OUTPUT: Not JSON"
        }

        val mainPipe = object : Pipe() {
            init {
                pipeName = "MainPipe"
                model = "main-model"
            }
            override fun truncateModuleContext(): Pipe = this
            override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
                return MultimodalContent(text = "Main output")
            }
            override suspend fun generateText(promptInjector: String): String = "Main output"
        }

        mainPipe.validatorPipe = validatorPipe
        enableTracingOn(mainPipe, validatorPipe)

        // When
        mainPipe.execute(MultimodalContent(text = "Test"))

        // Then
        val trace = PipeTracer.getTrace(pipelineId)
        val validationFailure = trace.find { it.eventType == TraceEventType.VALIDATION_FAILURE }

        assertNotNull(validationFailure, "VALIDATION_FAILURE should be traced")

        assertTrue(validationFailure!!.metadata.containsKey("validatorPipeOutput"),
            "VALIDATION_FAILURE should include validatorPipeOutput metadata")
        assertEquals("INVALID OUTPUT: Not JSON", validationFailure.metadata["validatorPipeOutput"])
    }

    /**
     * REGRESSION TEST: When the validator pipe throws, the catch block in the main
     * pipe's executeMultimodal (Pipe.kt:6199-6203) should trace VALIDATION_FAILURE
     * (because the failure happened during validation) and NOT PIPE_FAILURE.
     *
     * CURRENT BEHAVIOR (Pipe.kt:6201):
     *     catch(e: Exception) {
     *         trace(TraceEventType.PIPE_FAILURE, TracePhase.VALIDATION, generatedContent, error = e)
     *         validatorPipeContent = generatedContent
     *     }
     *
     * This is wrong - the failure is a validation failure, not a pipe failure.
     * The dedicated VALIDATION_FAILURE event type exists for exactly this case.
     *
     * Note: this test wraps mainPipe.execute in a try/catch because of a separate
     * coroutine-scope issue in the validator pipe's executeMultimodal that lets the
     * exception escape even though Pipe.kt:6077 catches it. That issue is out of
     * scope for this regression test - the trace content is what matters here.
     */
    @Test
    fun `Validator pipe exception should trace VALIDATION_FAILURE not PIPE_FAILURE`() = runBlocking {
        val validatorPipe = object : Pipe() {
            init {
                pipeName = "ThrowingValidator"
                model = "validator-model"
            }
            override fun truncateModuleContext(): Pipe = this
            override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
                throw RuntimeException("Validator crashed")
            }
            override suspend fun generateText(promptInjector: String): String {
                throw RuntimeException("Validator crashed")
            }
        }

        val mainPipe = object : Pipe() {
            init {
                pipeName = "MainPipe"
                model = "main-model"
            }
            override fun truncateModuleContext(): Pipe = this
            override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
                return MultimodalContent(text = "Main output")
            }
            override suspend fun generateText(promptInjector: String): String = "Main output"
        }

        mainPipe.validatorPipe = validatorPipe
        enableTracingOn(mainPipe, validatorPipe)

        // When - wrapped in try/catch to survive a coroutine-scope re-throw in the
        // validator pipe's executeMultimodal that is unrelated to the regression
        // we are testing here.
        try
        {
            mainPipe.execute(MultimodalContent(text = "Test"))
        }
        catch(e: Exception)
        {
            // Swallow - the trace is the source of truth for this regression.
        }

        // Then: catch the actual regression - the main pipe's catch block (line 6201)
        // traces PIPE_FAILURE in VALIDATION phase when it should trace VALIDATION_FAILURE.
        val trace = PipeTracer.getTrace(pipelineId)

        // The regression: a PIPE_FAILURE in VALIDATION phase attributed to the main pipe.
        val pipeFailureInValidationFromMain = trace.find {
            it.eventType == TraceEventType.PIPE_FAILURE &&
            it.phase == TracePhase.VALIDATION &&
            it.pipeName == "MainPipe"
        }

        assertNull(pipeFailureInValidationFromMain,
            "Validator-pipe exception should NOT produce PIPE_FAILURE in VALIDATION phase " +
            "from the main pipe (Pipe.kt:6201). Use VALIDATION_FAILURE instead.")

        // Sanity: the validation failure itself is recorded somewhere as VALIDATION_FAILURE.
        val validationFailure = trace.find { it.eventType == TraceEventType.VALIDATION_FAILURE }
        assertNotNull(validationFailure,
            "VALIDATION_FAILURE should be traced when the validator pipe throws")
    }
}
