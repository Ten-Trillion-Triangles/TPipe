package com.TTT.Pipeline

import Defaults.OpenRouterConfiguration
import Defaults.PumpStationDefaults
import com.TTT.P2P.KillSwitch
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Structs.PipeSettings
import kotlinx.coroutines.runBlocking
import openrouterPipe.OpenRouterPipe
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live integration test for the post-success intervention surface that fires inside
 * `runExitFlow` after the goal agent passes (or on the no-goal-agent exit path).
 *
 * Follows the [PumpStationLiveLLMTest] pattern: env-gated, silently skips when
 * `TPIPE_LIVE_LLM_TEST != "true"` or when `OPENROUTER_API_KEY` is unset.
 *
 * # What this exercises
 * 1. `goalAgent` makes a real OpenRouter LLM call (one live call).
 * 2. `postGoalFunction` synchronously transforms the goal's output (deterministic).
 * 3. `postGoalAgent` (a stub [P2PInterface]) runs against the transformed output and
 *    appends a marker so the test can assert both that it ran and that the function's
 *    transformation was applied.
 * 4. The `PostGoalCompleted` event is observed on the `eventObserver`.
 * 5. The harness exits via [PumpStationExitReason.JudgeComplete] regardless of the
 *    agent's success/failure (broad coverage, no re-loop).
 *
 * To run:
 * ```
 * export TPIPE_LIVE_LLM_TEST=true
 * export OPENROUTER_API_KEY=sk-or-...
 * ./gradlew :test --tests "com.TTT.Pipeline.PumpStationPostGoalLiveTest" --rerun-tasks
 * ```
 *
 * Stochastic risk: a slow/inconsistent OpenRouter call may stall the goal agent
 * for a long time. If the goal call produces malformed JSON or fails outright,
 * the test fails for a non-deterministic reason. Re-run with `--rerun-tasks` if so.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PumpStationPostGoalLiveTest
{
    /**
     * One deterministic stub agent used as the post-goal hook. Captures the input
     * from `executeLocal`, prefixes it with a marker, and emits `terminatePipeline=false`.
     * Lets the test assert the post-goal hook fired AND that the goal content (transformed
     * by `postGoalFunction`) is what the agent saw.
     */
    private class CapturingPostGoalAgent(
        val marker: String = "[POSTGOAL-RAN]"
    ) : P2PInterface
    {
        var lastInputText: String? = null
            private set

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            lastInputText = content.text
            return MultimodalContent(text = "$marker ${content.text}")
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null
        override fun setParentInterface(parent: P2PInterface) {}
        override fun getParentP2PInterface(): P2PInterface? = null
        override fun getPaths(): String = ""
        override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
        override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
        override fun setPipeSettingsRecursively(settings: PipeSettings) {}
        override suspend fun P2PInit() {}

        override var killSwitch: KillSwitch? = null
    }

    /**
     * Builds a one-pipe OpenRouter pipeline configured as the goal agent.
     */
    private fun buildGoalPipeline(config: OpenRouterConfiguration): Pipeline
    {
        val pipe: OpenRouterPipe = Defaults.providers.OpenRouterDefaults.createOpenRouterPipe(config)
        pipe.setSystemPrompt(
            "You are a goal-verification agent. Inspect the conversation and respond " +
                "with one short sentence confirming the work is done. " +
                "NEVER signal failure. End your response with the literal token " +
                "'GOALCONFIRMED'."
        )
        return Pipeline().apply { add(pipe) }
    }

    @Test
    fun postGoalAgentAndFunctionFireAfterLiveGoalValidation() = runBlocking<Unit>
    {
        if (System.getenv("TPIPE_LIVE_LLM_TEST") != "true") return@runBlocking
        val apiKey = System.getenv("OPENROUTER_API_KEY")
        if (apiKey.isNullOrBlank()) return@runBlocking

        val config = OpenRouterConfiguration(
            model = "openai/gpt-4o-mini",
            apiKey = apiKey,
            pipeCount = 1
        )

        val observedEvents = mutableListOf<PumpStationEvent>()
        val postGoalAgentImpl = CapturingPostGoalAgent()
        val goalPipeline = buildGoalPipeline(config)

        val station = PumpStationDefaults.withOpenRouter(config) {
            goalAgent = goalPipeline
            postGoalAgent = postGoalAgentImpl
            // The transform stamps "TRANSFORMED:" on the goal output before the
            // post-goal agent sees it. The transformedContent event flag should fire.
            postGoalFunction = { content, _ ->
                MultimodalContent(text = "TRANSFORMED: ${content.text}")
            }
            eventObserver = { ev -> observedEvents.add(ev) }

            // Path: takes the topic input, returns a deterministic brief, signals
            // passPipeline so the harness exits via JudgeComplete (route through
            // runExitFlow, triggering the post-success hook).
            path("answer") {
                description = "Single-path brief that signals pass-pipeline."
                setExecutionFunction { content, _, _, _ ->
                    val brief = "Brief on: ${content.text}\n\n## Done."
                    MultimodalContent(text = brief).apply { passPipeline = true }
                }
            }
        }

        val result = station.executeLocal(
            MultimodalContent(text = "Confirm the harness ran a brief on Kotlin coroutines.")
        )

        // Exit was via JudgeComplete (post-success halt).
        assertEquals(
            PumpStationExitReason.JudgeComplete,
            station.getTaskState().exitReason,
            "expected JudgeComplete exit; got ${station.getTaskState().exitReason}"
        )

        // The post-goal agent fired and saw the transformed content.
        assertNotNull(
            postGoalAgentImpl.lastInputText,
            "post-goal agent's executeLocal was never invoked"
        )
        assertTrue(
            postGoalAgentImpl.lastInputText!!.startsWith("TRANSFORMED:"),
            "post-goal agent must receive the function-transformed content; " +
                "got: ${postGoalAgentImpl.lastInputText?.take(80)}"
        )

        // The event observer captured PostGoalCompleted with transformedContent=true.
        val postGoalEvents = observedEvents.filterIsInstance<PostGoalCompleted>()
        assertTrue(
            postGoalEvents.isNotEmpty(),
            "expected at least one PostGoalCompleted event; got: ${observedEvents.map { it::class.simpleName }}"
        )
        assertTrue(
            postGoalEvents.first().transformedContent,
            "transformedContent flag must be true when postGoalFunction rewrites its input"
        )
        assertTrue(
            postGoalEvents.first().passed,
            "post-goal agent returned with terminatePipeline=false — passed must be true"
        )

        // The final deliverable is the post-goal agent's output (not the goal agent's).
        assertNotNull(result.text)
        assertTrue(
            result.text!!.contains("[POSTGOAL-RAN]"),
            "final deliverable must carry the post-goal agent's marker; got: ${result.text?.take(120)}"
        )
    }
}
