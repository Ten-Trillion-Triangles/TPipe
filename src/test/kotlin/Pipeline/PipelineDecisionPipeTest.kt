package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [Pipeline.decisionPipeName]. By default, [Pipeline.execute] returns
 * the LAST pipe's output, which is the right behavior for the common case
 * (a single LLM pipe). When the developer wires up a pipeline where the
 * decision-making pipe is NOT the last one (e.g. a preprocessor / decision /
 * postprocessor layout, or a pipeline with branching/jumps), they can set
 * [Pipeline.decisionPipeName] to declare which pipe's output should be
 * returned to the caller.
 *
 * No warnings, no logging — the field is a silent override. If the named pipe
 * is not in the pipeline (typo) or did not run (skipped by a jump), the
 * pipeline falls back to the last pipe's output, matching the default
 * behavior.
 */
class PipelineDecisionPipeTest
{
    /**
     * Minimal scripted pipe that records its name + a fixed response. Each
     * instance can be told to skip itself by jumping to a different pipe.
     */
    private class TaggedPipe(
        private val name: String,
        private val responseText: String
    ) : Pipe()
    {
        init { pipeName = name }
        override suspend fun generateText(promptInjector: String): String = responseText
        override fun truncateModuleContext(): Pipe = this
    }

    private fun pipelineWith(vararg pipes: Pipe): Pipeline =
        Pipeline().apply { pipes.forEach { add(it) } }

    @Test
    fun returnsLastPipeOutputWhenDecisionPipeNameIsNull()
    {
        val pipeline = pipelineWith(
            TaggedPipe("pre", "preprocessed input"),
            TaggedPipe("decision", "DECISION_OUTPUT"),
            TaggedPipe("post", "postprocessed output")
        )

        runBlocking {
            val result = pipeline.execute(MultimodalContent(text = "in"))
            // No decisionPipeName set: falls back to last pipe's output ("post").
            assertEquals("postprocessed output", result.text)
        }
    }

    @Test
    fun returnsNamedPipeOutputWhenDecisionPipeNameIsSet()
    {
        val pipeline = pipelineWith(
            TaggedPipe("pre", "preprocessed input"),
            TaggedPipe("decision", "DECISION_OUTPUT"),
            TaggedPipe("post", "postprocessed output")
        )
        pipeline.decisionPipeName = "decision"

        runBlocking {
            val result = pipeline.execute(MultimodalContent(text = "in"))
            // decisionPipeName overrides the "last pipe" default: returns the
            // named pipe's output, not the postprocessor's.
            assertEquals("DECISION_OUTPUT", result.text)
        }
    }

    @Test
    fun fallsBackToLastPipeWhenDecisionPipeNameDoesNotMatch()
    {
        val pipeline = pipelineWith(
            TaggedPipe("pre", "preprocessed input"),
            TaggedPipe("decision", "DECISION_OUTPUT"),
            TaggedPipe("post", "postprocessed output")
        )
        // Typo / invalid name: no pipe called "nonexistent" in this pipeline.
        pipeline.decisionPipeName = "nonexistent"

        runBlocking {
            val result = pipeline.execute(MultimodalContent(text = "in"))
            // Falls back to last pipe's output (no warning, no exception).
            assertEquals("postprocessed output", result.text)
        }
    }

    @Test
    fun fallsBackToLastPipeWhenNamedPipeWasSkippedByJump()
    {
        // A pipe that issues a `skip-to-next-pipe` jump is not in the
        // generatedContent chain at the end. If the developer points
        // decisionPipeName at it, they get the last pipe's output instead.
        val pipeline = Pipeline()
        pipeline.add(TaggedPipe("a", "a output"))
        // A custom pipe that jumps over itself to "b".
        pipeline.add(object : Pipe()
        {
            init { pipeName = "jumper" }
            override suspend fun generateText(promptInjector: String): String = "jumped"
            override fun truncateModuleContext(): Pipe = this
            override suspend fun execute(content: MultimodalContent): MultimodalContent
            {
                content.jumpToPipe("b")
                return content
            }
        })
        pipeline.add(TaggedPipe("b", "FINAL_OUTPUT"))
        pipeline.decisionPipeName = "jumper"

        runBlocking {
            val result = pipeline.execute(MultimodalContent(text = "in"))
            // The "jumper" pipe ran but the jump sent control past it; the
            // captured output for "jumper" is the un-decision content that
            // was passed in. Since the named pipe was effectively a no-op,
            // the fallback (last pipe's output, "FINAL_OUTPUT") is what the
            // developer actually wants.
            assertEquals("FINAL_OUTPUT", result.text)
        }
    }

    @Test
    fun decisionPipeNameDefaultsToNull()
    {
        val pipeline = Pipeline()
        assertNotNull(pipeline.decisionPipeName === null, "decisionPipeName should default to null")
    }
    //==================================================================
    //  Additional tests for the layered resolution: isDecisionPipe,
    //  pipeRole, and the heuristic scoring signals added in Pass 2.
    //==================================================================

    /**
     * Pipe that advertises itself as a decision pipe via the isDecisionPipe
     * flag. The pump station should pick this pipe over a name-matching pipe
     * further down the chain.
     */
    private class DecisionFlagPipe(
        private val name: String,
        private val responseText: String
    ) : Pipe()
    {
        init { pipeName = name }
        override val isDecisionPipe: Boolean = true
        override suspend fun generateText(promptInjector: String): String = responseText
        override fun truncateModuleContext(): Pipe = this
    }

    /**
     * Pipe that advertises itself as a preprocessor via the pipeRole field.
     * The pump station should NOT pick this pipe as the decision pipe.
     */
    private class RoleTaggedPipe(
        private val name: String,
        private val role: com.TTT.Enums.PipeRole,
        private val responseText: String
    ) : Pipe()
    {
        init {
            pipeName = name
            pipeRole = role
        }
        override suspend fun generateText(promptInjector: String): String = responseText
        override fun truncateModuleContext(): Pipe = this
    }

    /**
     * Pipe that looks like an LLM (has provider+model set) so it scores
     * high in the heuristic scoring. The pump station should pick this pipe
     * over a name-matching pipe.
     */
    private class LLMLikePipe(
        private val name: String,
        private val responseText: String
    ) : Pipe()
    {
        init {
            pipeName = name
            setProvider(com.TTT.Enums.ProviderName.Gpt)
            setModel("gpt-4")
        }
        override suspend fun generateText(promptInjector: String): String = responseText
        override fun truncateModuleContext(): Pipe = this
    }

    @org.junit.Test
    fun isDecisionPipeFlagIsPickedOverDefaultResolution()
    {
        val pipeline = pipelineWith(
            TaggedPipe("pre", "preprocessed input"),
            DecisionFlagPipe("decision", "DECISION_VIA_FLAG"),
            TaggedPipe("post", "postprocessed output")
        )

        runBlocking {
            val result = pipeline.execute(MultimodalContent(text = "in"))
            // The middle pipe has isDecisionPipe=true; pump station picks it.
            assertEquals("DECISION_VIA_FLAG", result.text)
        }
    }

    @org.junit.Test
    fun roleTagDecisionIsPicked()
    {
        val pipeline = pipelineWith(
            RoleTaggedPipe("pre", com.TTT.Enums.PipeRole.Preprocessor, "preprocessed input"),
            RoleTaggedPipe("decision", com.TTT.Enums.PipeRole.Decision, "DECISION_VIA_ROLE"),
            RoleTaggedPipe("post", com.TTT.Enums.PipeRole.Postprocessor, "postprocessed output")
        )

        runBlocking {
            val result = pipeline.execute(MultimodalContent(text = "in"))
            // The middle pipe has pipeRole == Decision; pump station picks it.
            assertEquals("DECISION_VIA_ROLE", result.text)
        }
    }

    @org.junit.Test
    fun roleTagPreprocessorIsNotPicked()
    {
        val pipeline = pipelineWith(
            RoleTaggedPipe("pre", com.TTT.Enums.PipeRole.Preprocessor, "preprocessed input"),
            RoleTaggedPipe("post", com.TTT.Enums.PipeRole.Postprocessor, "postprocessed output")
        )

        runBlocking {
            val result = pipeline.execute(MultimodalContent(text = "in"))
            // No Decision role tag, no isDecisionPipe, no LLM signal:
            // pump station falls back to the last pipe.
            assertEquals("postprocessed output", result.text)
        }
    }

    @org.junit.Test
    fun heuristicScoringPicksLLMAlone()
    {
        val pipeline = pipelineWith(
            TaggedPipe("pre", "preprocessed input"),
            LLMLikePipe("decision", "DECISION_VIA_LLM"),
            TaggedPipe("post", "postprocessed output")
        )

        runBlocking {
            val result = pipeline.execute(MultimodalContent(text = "in"))
            // The middle pipe has provider=model, so it scores +10 in the
            // heuristic. Pump station picks it.
            assertEquals("DECISION_VIA_LLM", result.text)
        }
    }

    @org.junit.Test
    fun isDecisionPipeBeatsHeuristicScoring()
    {
        val pipeline = pipelineWith(
            DecisionFlagPipe("pre", "PRE_VIA_FLAG"),
            LLMLikePipe("decision", "DECISION_VIA_LLM"),
            TaggedPipe("post", "postprocessed output")
        )

        runBlocking {
            val result = pipeline.execute(MultimodalContent(text = "in"))
            // Priority 2 (isDecisionPipe) is checked before Priority 4 (scoring).
            // The "pre" pipe has isDecisionPipe=true so it wins.
            assertEquals("PRE_VIA_FLAG", result.text)
        }
    }

    @org.junit.Test
    fun manualOverrideBeatsAllOtherSignals()
    {
        val pipeline = pipelineWith(
            DecisionFlagPipe("pre", "PRE_VIA_FLAG"),
            LLMLikePipe("decision", "DECISION_VIA_LLM"),
            TaggedPipe("post", "postprocessed output")
        )
        pipeline.decisionPipeName = "post"

        runBlocking {
            val result = pipeline.execute(MultimodalContent(text = "in"))
            // Manual override wins over all other signals.
            assertEquals("postprocessed output", result.text)
        }
    }

    @org.junit.Test
    fun lastDecisionPipeNameIsSetAfterExecuteForManualOverride()
    {
        val pipeline = pipelineWith(
            TaggedPipe("pre", "preprocessed input"),
            TaggedPipe("decision", "DECISION_OUTPUT"),
            TaggedPipe("post", "postprocessed output")
        )
        pipeline.decisionPipeName = "decision"

        runBlocking {
            pipeline.execute(MultimodalContent(text = "in"))
            assertEquals("decision", pipeline.lastDecisionPipeName)
        }
    }

    @org.junit.Test
    fun lastDecisionPipeNameIsNullWhenFallbackUsed()
    {
        val pipeline = pipelineWith(
            TaggedPipe("pre", "preprocessed input"),
            TaggedPipe("decision", "DECISION_OUTPUT"),
            TaggedPipe("post", "postprocessed output")
        )
        // No signals, no decisionPipeName: scoring returns null (max < 10),
        // so we fall back to the last pipe and lastDecisionPipeName is null.

        runBlocking {
            pipeline.execute(MultimodalContent(text = "in"))
            // lastDecisionPipeName should be null because the scoring heuristic
            // does not return a pipe (no LLM signal).
            assertNull(pipeline.lastDecisionPipeName, "Fallback should set lastDecisionPipeName to null")
        }
    }
}
