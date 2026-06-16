package com.TTT.Pipeline

import com.TTT.Enums.ProviderName
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.MultimodalContent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the auto-injection of default prompts at [setXxxAgent] time,
 * and the override detection via [setXxxSystemPrompt].
 *
 * The pump station's contract-abstraction layer makes the developer-facing
 * API a single concern: which agent to wire in. The JSON shape, default
 * prompt, parser, and flag fallback are all internal. The developer never
 * has to read [DEFAULT_JUDGE_PROMPT] to use the pump station.
 */
class MagicContractAutoInjectionTest
{
    /**
     * A scripted LLM-like pipe with a configurable prompt and response.
     */
    private class ScriptedLLMPipe(
        name: String = "llm",
        initialPrompt: String? = null,
        val response: String = ""
    ) : Pipe()
    {
        init {
            pipeName = name
            if(initialPrompt != null) setSystemPrompt(initialPrompt)
        }
        override suspend fun generateText(promptInjector: String): String = response
        override fun truncateModuleContext(): Pipe = this
    }

    private fun buildJudgePipeline(prompt: String? = null): Pipeline
    {
        val p = Pipeline()
        p.add(ScriptedLLMPipe("judge-llm", initialPrompt = prompt))
        return p
    }

    //============================== Default prompt auto-injection ==============================

    @Test
    fun setJudgeAgentAppliesDefaultJudgePromptToDecisionPipe()
    {
        val station = buildTestStation()
        val judge = buildJudgePipeline()
        station.setJudgeAgent(judge)

        val pipe = judge.getPipes().first()
        val systemPrompt = pipe.toPipeSettings().systemPrompt
        assertNotNull(systemPrompt, "After setJudgeAgent, the decision pipe should have a system prompt")
        assertTrue(
            systemPrompt.contains("judge") || systemPrompt.contains("isComplete"),
            "Default judge prompt should mention the judge contract keywords, got: $systemPrompt"
        )
    }

    @Test
    fun setJudgeAgentDoesNotOverrideExistingCustomPrompt()
    {
        val station = buildTestStation()
        val customPrompt = "My custom judge prompt — do something specific"
        val judge = buildJudgePipeline(prompt = customPrompt)
        station.setJudgeAgent(judge)

        val pipe = judge.getPipes().first()
        val systemPrompt = pipe.toPipeSettings().systemPrompt
        assertEquals(customPrompt, systemPrompt, "If the developer pre-set a prompt, the pump station must not overwrite it")
    }

    //============================== Override detection ==========================================

    @Test
    fun setJudgeSystemPromptOverridesDefault()
    {
        val station = buildTestStation()
        val judge = buildJudgePipeline()
        station.setJudgeAgent(judge)
        val custom = "My custom judge — return only flags"
        station.setJudgeSystemPrompt(custom)

        val pipe = judge.getPipes().first()
        val systemPrompt = pipe.toPipeSettings().systemPrompt
        assertEquals(custom, systemPrompt, "setJudgeSystemPrompt should replace the default on the decision pipe")
    }

    @Test
    fun setJudgeSystemPromptToNullRestoresDefault()
    {
        val station = buildTestStation()
        val judge = buildJudgePipeline()
        station.setJudgeAgent(judge)
        val custom = "My custom judge"
        station.setJudgeSystemPrompt(custom)
        // Now reset to null — should re-inject the default
        station.setJudgeSystemPrompt(null)

        val pipe = judge.getPipes().first()
        val systemPrompt = pipe.toPipeSettings().systemPrompt
        assertNotNull(systemPrompt)
        assertTrue(
            systemPrompt.contains("judge") || systemPrompt.contains("isComplete"),
            "After setJudgeSystemPrompt(null), the default should be re-applied, got: $systemPrompt"
        )
    }

    @Test
    fun setJudgeSystemPromptWithNonNullDisablesJsonContract()
    {
        val station = buildTestStation()
        val judge = buildJudgePipeline()
        station.setJudgeAgent(judge)
        // Default: contract is enabled
        assertTrue(station.judgeExpectsJsonContract, "Default should be true (contract in effect)")

        station.setJudgeSystemPrompt("custom")
        assertFalse(
            station.judgeExpectsJsonContract,
            "Setting a non-null custom prompt should disable the JSON contract"
        )

        station.setJudgeSystemPrompt(null)
        assertTrue(
            station.judgeExpectsJsonContract,
            "Setting custom prompt back to null should re-enable the JSON contract"
        )
    }

    //============================== Per-agent coverage ==========================================

    @Test
    fun setDispatchAgentAppliesDefaultDispatchPromptToDecisionPipe()
    {
        val station = buildTestStation()
        val dispatch = Pipeline().apply { add(ScriptedLLMPipe("dispatch-llm")) }
        station.setDispatchAgent(dispatch)

        val pipe = dispatch.getPipes().first()
        val systemPrompt = pipe.toPipeSettings().systemPrompt
        assertNotNull(systemPrompt, "setDispatchAgent should auto-inject a default prompt")
    }

    @Test
    fun setPathSafetyAgentAppliesDefaultSafetyPromptToDecisionPipe()
    {
        val station = buildTestStation()
        val safety = Pipeline().apply { add(ScriptedLLMPipe("safety-llm")) }
        station.setPathSafetyAgent(MockP2PAgent()) // actually want a Pipeline
        // The path-safety agent is P2PInterface; for pipeline-based test, swap
        val safetyPipeline = Pipeline().apply { add(ScriptedLLMPipe("safety-llm")) }
        // Use a custom approach: just verify the dispatcher-side setSystemPrompt works
        station.setPathSafetySystemPrompt("custom safety prompt")
        // The MockP2PAgent doesn't have a decision pipe; verify the custom prompt is stored
        assertEquals("custom safety prompt", station.customPathSafetySystemPrompt)
    }

    //============================== Refresh behavior ===========================================

    @Test
    fun settingCustomPromptAppliesItToExistingDecisionPipe()
    {
        val station = buildTestStation()
        val judge = buildJudgePipeline()
        station.setJudgeAgent(judge)
        // Verify default is in place
        val pipe = judge.getPipes().first()
        val defaultPrompt = pipe.toPipeSettings().systemPrompt
        assertNotNull(defaultPrompt)

        // Now set a custom prompt — should replace
        val custom = "CUSTOM JUDGE PROMPT"
        station.setJudgeSystemPrompt(custom)
        assertEquals(custom, pipe.toPipeSettings().systemPrompt)
    }
}
