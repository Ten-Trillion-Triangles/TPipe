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
 * These tests verify:
 * 1. BRANCH_PIPE_TRIGGERED event captures branch pipe model metadata
 * 2. Branch pipe execution properly shows new model and settings
 * 3. Trace verbosity levels properly filter events
 */
class TraceRegressionTest {

    private lateinit var pipelineId: String

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
     * REGRESSION TEST: BRANCH_PIPE_TRIGGERED should include branch pipe model metadata
     *
     * CURRENT BEHAVIOR (Line 5922 Pipe.kt):
     *   trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.POST_PROCESSING)
     *   - No metadata passed about branch pipe model
     *
     * EXPECTED BEHAVIOR:
     *   trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.POST_PROCESSING,
     *         metadata = mapOf(
     *             "branchModel" to branchPipe?.model ?: "not_set",
     *             "branchProvider" to branchPipe?.provider?.name ?: "unknown",
     *             "branchPipeName" to branchPipe?.pipeName ?: "unknown"
     *         ))
     *
     * TEST RESULT: FAILS - regression exists
     */
    @Test
    fun `BRANCH_PIPE_TRIGGERED should include branch pipe model metadata`() = runBlocking {
        // Given: branch pipe with specific model
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

        // When: execute pipe (which will trigger branch pipe after validation failure)
        mainPipe.execute(MultimodalContent(text = "Test input"))

        // Then: BRANCH_PIPE_TRIGGERED event should have branch model metadata
        val trace = PipeTracer.getTrace(pipelineId)
        val branchTriggerEvent = trace.find { it.eventType == TraceEventType.BRANCH_PIPE_TRIGGERED }

        assertNotNull(branchTriggerEvent, "BRANCH_PIPE_TRIGGERED event should be traced")

        // REGRESSION: This will FAIL because no metadata is passed at line 5922
        assertTrue(branchTriggerEvent!!.metadata.containsKey("branchModel"),
            "BRANCH_PIPE_TRIGGERED should include branchModel metadata")
        assertTrue(branchTriggerEvent.metadata.containsKey("branchProvider"),
            "BRANCH_PIPE_TRIGGERED should include branchProvider metadata")
        assertTrue(branchTriggerEvent.metadata.containsKey("branchPipeName"),
            "BRANCH_PIPE_TRIGGERED should include branchPipeName metadata")

        // Verify values
        assertEquals("claude-3-5-sonnet-latest", branchTriggerEvent.metadata["branchModel"])
    }

    /**
     * REGRESSION TEST: Branch pipe PIPE_START should show the branch pipe's model, not parent
     *
     * When branch pipe executes, its PIPE_START should show the branch pipe's model.
     * Currently the model info is captured at DEBUG level but only for PIPE_START of main pipe.
     */
    @Test
    fun `Branch pipe PIPE_START should show branch pipe model at DEBUG level`() = runBlocking {
        // Given: branch pipe with specific model
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

        // When
        mainPipe.execute(MultimodalContent(text = "Input"))

        // Then: Branch pipe's PIPE_START should show branch's model
        val trace = PipeTracer.getTrace(pipelineId)

        // Find all PIPE_START events
        val pipeStartEvents = trace.filter { it.eventType == TraceEventType.PIPE_START }

        // At DEBUG level, we should see model info. The branch pipe's PIPE_START should show its model.
        val branchStartEvents = pipeStartEvents.filter { it.pipeName == "SpecificModelBranch" }

        assertTrue(branchStartEvents.isNotEmpty(), "Branch pipe should emit PIPE_START event")

        // At DEBUG level, model should be captured
        val branchStart = branchStartEvents.first()
        assertTrue(branchStart.metadata.containsKey("model"),
            "PIPE_START at DEBUG level should include model metadata")
    }

    /**
     * TEST: Verify BRANCH_PIPE_TRIGGERED is INTERNAL priority (only visible at DEBUG)
     *
     * This is EXPECTED BEHAVIOR - not a regression.
     * Test verifies the priority mapping is working correctly.
     */
    @Test
    fun `BRANCH_PIPE_TRIGGERED has INTERNAL priority and is not visible at VERBOSE`() {
        // Verify priority mapping
        val priority = EventPriorityMapper.getPriority(TraceEventType.BRANCH_PIPE_TRIGGERED)
        assertEquals(TraceEventPriority.INTERNAL, priority, "BRANCH_PIPE_TRIGGERED should be INTERNAL priority")

        // Verify visibility
        assertFalse(EventPriorityMapper.shouldTrace(TraceEventType.BRANCH_PIPE_TRIGGERED, TraceDetailLevel.VERBOSE),
            "BRANCH_PIPE_TRIGGERED should NOT be visible at VERBOSE level")
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.BRANCH_PIPE_TRIGGERED, TraceDetailLevel.DEBUG),
            "BRANCH_PIPE_TRIGGERED should be visible at DEBUG level")
    }

    /**
     * TEST: Verify VALIDATION_FAILURE properly captures validator output
     *
     * CURRENT BEHAVIOR (Line 5912 Pipe.kt):
     *   trace(TraceEventType.VALIDATION_FAILURE, TracePhase.VALIDATION, validatorPipeContent,
     *         metadata = mapOf(
     *             "reason" to "Validator pipe returned content with terminate flag",
     *             "validatorPipeOutput" to validatorPipeContent.text
     *         ))
     *
     * This appears to capture validatorPipeOutput - need to verify if it's working.
     */
    @Test
    fun `VALIDATION_FAILURE should include validator pipe output when terminate flag set`() = runBlocking {
        // Given: validator pipe that returns shouldTerminate=true
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

        // When
        mainPipe.execute(MultimodalContent(text = "Test"))

        // Then
        val trace = PipeTracer.getTrace(pipelineId)
        val validationFailure = trace.find { it.eventType == TraceEventType.VALIDATION_FAILURE }

        assertNotNull(validationFailure, "VALIDATION_FAILURE should be traced")

        // The current code (line 5915) does include validatorPipeOutput
        assertTrue(validationFailure!!.metadata.containsKey("validatorPipeOutput"),
            "VALIDATION_FAILURE should include validatorPipeOutput metadata")
        assertEquals("INVALID OUTPUT: Not JSON", validationFailure.metadata["validatorPipeOutput"])
    }

    /**
     * REGRESSION TEST: When validator pipe throws exception, PIPE_FAILURE is traced not VALIDATION_FAILURE
     *
     * CURRENT BEHAVIOR (Line 5733 Pipe.kt):
     *   catch(e: Exception) {
     *       trace(TraceEventType.PIPE_FAILURE, TracePhase.VALIDATION, generatedContent, error = e)
     *       ...
     *   }
     *
     * This is WRONG - should be VALIDATION_FAILURE because it's a validation error.
     */
    @Test
    fun `Validator pipe exception should trace VALIDATION_FAILURE not PIPE_FAILURE`() = runBlocking {
        // Given: validator pipe that throws
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

        // When
        mainPipe.execute(MultimodalContent(text = "Test"))

        // Then: Should have VALIDATION_FAILURE but CURRENTLY only has PIPE_FAILURE
        val trace = PipeTracer.getTrace(pipelineId)

        val validationFailure = trace.find { it.eventType == TraceEventType.VALIDATION_FAILURE }
        val pipeFailure = trace.find { it.eventType == TraceEventType.PIPE_FAILURE }

        // REGRESSION: validationPipe exception traces PIPE_FAILURE instead of VALIDATION_FAILURE
        assertNotNull(validationFailure,
            "Validator pipe exception should trace VALIDATION_FAILURE (currently missing)")
        assertNotNull(pipeFailure,
            "Pipe failure should be traced for exception case")

        // Verify the error is captured
        if (pipeFailure != null) {
            assertTrue(pipeFailure.metadata.containsKey("error") ||
                       pipeFailure.error != null,
                "PIPE_FAILURE should include error information")
        }
    }
}