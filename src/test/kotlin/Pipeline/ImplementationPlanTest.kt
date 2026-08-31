package com.TTT.Pipeline

import com.TTT.P2P.AgentRequest
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.KillSwitchException
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Regression coverage for static implementation-plan prompt injection. */
class ImplementationPlanTest
{
    @Test
    fun pipeOverlayIsNormalizedAndIdempotent()
    {
        val pipe = ScriptedTestPipe().setSystemPrompt("Base prompt")

        pipe.setImplementationPlanOverlay("  finish the task  ").applySystemPrompt()
        val first = pipe.getSystemPromptForTest()
        pipe.applySystemPrompt()
        val second = pipe.getSystemPromptForTest()

        assertEquals("Base prompt\n\nImplementation plan:\nfinish the task", first)
        assertEquals(first, second)
        assertEquals(1, second.split("Implementation plan:").size - 1)
    }

    @Test
    fun blankOverlayLeavesBasePromptUntouched()
    {
        val pipe = ScriptedTestPipe().setSystemPrompt("Base prompt")

        pipe.setImplementationPlanOverlay(" \n\t ").applySystemPrompt()

        assertEquals("Base prompt", pipe.getSystemPromptForTest())
    }

    @Test
    fun pumpStationTargetsOnlyConfiguredControlPipelines()
    {
        val judge = pipelineWithPipes("judge-a", "judge-b")
        val dispatch = pipelineWithPipes("dispatch")
        val goal = pipelineWithPipes("goal")
        val unrelated = pipelineWithPipes("unrelated")
        val station = PumpStation()
            .setJudgeAgent(judge)
            .setDispatchAgent(dispatch)
            .setGoalAgent(goal)

        station.setImplementationPlan("  verify output  ")

        assertEquals("verify output", station.getImplementationPlan())
        (judge.getPipes() + dispatch.getPipes() + goal.getPipes()).forEach { pipe ->
            assertTrue(pipe.getSystemPromptForTest().contains("Implementation plan:\nverify output"))
        }
        assertFalse(unrelated.getPipes().first().getSystemPromptForTest().contains("Implementation plan:"))
    }

    @Test
    fun manifoldTargetsPrimaryAndAgentRequestManagerPipesOnly()
    {
        val primary = ScriptedTestPipe("primary").setSystemPrompt("primary")
        val dispatch = ScriptedTestPipe("dispatch")
            .setSystemPrompt("dispatch")
            .setJsonOutput(AgentRequest())
        val unrelated = ScriptedTestPipe("unrelated").setSystemPrompt("unrelated")
        val manager = Pipeline().add(primary).add(dispatch).add(unrelated)
        val worker = ScriptedTestPipe("worker").setSystemPrompt("worker")
        val manifold = Manifold().setManagerPipeline(manager)

        manifold.setImplementationPlan("delegate safely")

        assertTrue(primary.getSystemPromptForTest().contains("Implementation plan:\ndelegate safely"))
        assertTrue(dispatch.getSystemPromptForTest().contains("Implementation plan:\ndelegate safely"))
        assertFalse(unrelated.getSystemPromptForTest().contains("Implementation plan:"))
        assertFalse(worker.getSystemPromptForTest().contains("Implementation plan:"))
    }

    @Test
    fun pumpStationDslCarriesPlanThroughPathPromotion()
    {
        val station = pumpStation("implementation-plan-dsl") {
            judgeAgent = scriptedPipeline("{\"isComplete\":false,\"shouldTerminate\":false}")
            dispatchAgent = scriptedPipeline("{\"pathName\":\"work\",\"pathSchema\":\"{}\"}")
            path("work") {
                description = "work"
                schema = "{}"
                setExecutionFunction { content, _, _, _ ->
                    MultimodalContent(text = "done", context = content.context)
                }
            }
            implementationPlan("  use the declared workflow  ")
        }

        assertEquals("use the declared workflow", station.getImplementationPlan())
        assertTrue(
            station.getDispatchAgent()!!.getPipes().first().getSystemPromptForTest()
                .contains("Implementation plan:\nuse the declared workflow")
        )
    }

    @Test
    fun manifoldDslCarriesPlanIntoManager()
    {
        val managerPipe = ScriptedTestPipe("dsl-dispatch")
            .setJsonOutput(AgentRequest())
            .setTokenBudget(TokenBudgetSettings(contextWindowSize = 4096, userPromptSize = 1024, maxTokens = 256))
        val workerPipe = ScriptedTestPipe("dsl-worker")
            .setContextWindowSize(2048)
            .autoTruncateContext()

        val manifold = manifold {
            manager {
                pipeline {
                    add(managerPipe)
                }
                agentDispatchPipe("dsl-dispatch")
            }
            worker("worker") {
                pipeline { add(workerPipe) }
            }
            implementationPlan("follow the manager contract")
        }

        assertEquals("follow the manager contract", manifold.getImplementationPlan())
        assertTrue(managerPipe.getSystemPromptForTest().contains("Implementation plan:\nfollow the manager contract"))
    }

    @Test
    fun lifecycleGuardRejectsMutationOnlyWhileActive()
    {
        val guard = ImplementationPlanLifecycleGuard()

        guard.beginExecution()
        assertFailsWith<IllegalStateException> {
            guard.mutateBetweenExecutions { "not allowed" }
        }
        guard.endExecution()

        assertEquals("allowed", guard.mutateBetweenExecutions { "allowed" })
    }

    @Test
    fun pumpStationKillSwitchStillFinalizesAndReleasesPlanGuard() = runBlocking<Unit>
    {
        val station = PumpStation()
            .setJudgeAgent(scriptedPipeline("{\"isComplete\":false,\"shouldTerminate\":false}"))
            .setDispatchAgent(scriptedPipeline("{\"pathName\":\"work\",\"pathSchema\":\"{}\"}"))
        station.addPath(testPath("work"))
        station.killSwitch = KillSwitch(inputTokenLimit = 0)

        assertFailsWith<KillSwitchException> {
            station.executeLocal(MultimodalContent(text = "trip"))
        }

        // If finally did not release the guard, this setter would throw. The existing
        // PumpStation loop remains responsible for state transition and rethrowing.
        station.setImplementationPlan("after kill switch")
        assertEquals("after kill switch", station.getImplementationPlan())
    }

    @Test
    fun manifoldKillSwitchPropagatesAndReleasesPlanGuard() = runBlocking<Unit>
    {
        val managerPipe = ScriptedTestPipe("manager")
            .setJsonOutput(AgentRequest())
            .setTokenBudget(TokenBudgetSettings(contextWindowSize = 4096, userPromptSize = 1024, maxTokens = 256))
        val workerPipe = ScriptedTestPipe("worker")
            .setContextWindowSize(2048)
            .autoTruncateContext()
        val manifold = Manifold()
            .setManagerPipeline(Pipeline().add(managerPipe))
            .addWorkerPipeline(
                pipeline = Pipeline().add(workerPipe),
                agentName = "worker"
            )
            .setManagerTokenBudget(
                TokenBudgetSettings(contextWindowSize = 4096, userPromptSize = 1024, maxTokens = 256)
            )
        manifold.init()
        // A negative limit trips at Manifold's pre-manager check, before the
        // scripted manager needs to produce a valid AgentRequest response.
        manifold.killSwitch = KillSwitch(inputTokenLimit = -1)

        assertFailsWith<KillSwitchException> {
            manifold.execute(MultimodalContent(text = "trip"))
        }

        manifold.setImplementationPlan("after kill switch")
        assertEquals("after kill switch", manifold.getImplementationPlan())
    }

    /** Build a pipeline containing named scripted pipes for target-selection assertions. */
    private fun pipelineWithPipes(vararg names: String): Pipeline
    {
        return Pipeline().apply {
            names.forEach { name -> add(ScriptedTestPipe(name).setSystemPrompt(name)) }
        }
    }

    /** Build a pipeline whose sole pipe returns a fixed response. */
    private fun scriptedPipeline(response: String): Pipeline
    {
        return Pipeline().add(object : Pipe()
        {
            init { pipeName = "scripted" }
            override suspend fun generateText(promptInjector: String): String = response
            override fun truncateModuleContext(): Pipe = this
        })
    }
}
