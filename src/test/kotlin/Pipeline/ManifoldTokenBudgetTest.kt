package com.TTT.Pipeline

import com.TTT.P2P.AgentRequest
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression tests for Manifold manager-budget resolution and worker overflow validation.
 */
class ManifoldTokenBudgetTest
{
    /**
     * Verifies that manager shared-history budgeting follows the manager pipe token budget instead of the smallest
     * worker context window.
     */
    @Test
    fun managerBudgetFollowsManagerPipeBudgetInsteadOfSmallestWorkerWindow()
    {
        val managerPipe = DummyPipe()
            .setPipeName("manager")
            .setJsonOutput(AgentRequest())
            .setMaxTokens(512)
            .setSystemPrompt("manager system prompt")
            .setTokenBudget(
                TokenBudgetSettings(
                    contextWindowSize = 4096,
                    userPromptSize = 1536,
                    maxTokens = 512
                )
            )
        val managerPipeline = Pipeline().apply {
            pipelineName = "manager-pipeline"
            add(managerPipe)
        }

        val workerPipe = DummyPipe()
            .setPipeName("worker")
            .setContextWindowSize(128)
            .setMaxTokens(32)
            .autoTruncateContext()
        val workerPipeline = Pipeline().apply {
            pipelineName = "worker-pipeline"
            add(workerPipe)
        }

        val manifold = Manifold()
            .setManagerPipeline(managerPipeline)
            .addWorkerPipeline(workerPipeline)
            .autoTruncateContext()

        assertEquals(1536, manifold.getEffectiveManagerHistoryTokenBudget())
        assertEquals(4096, manifold.getEffectiveManagerTokenBudget()!!.contextWindowSize)
    }

    /**
     * Verifies that the legacy Manifold context-window override feeds the manager budget path when no explicit
     * token budget is configured.
     */
    @Test
    fun legacyContextWindowOverrideFeedsManagerBudgetPath()
    {
        val managerPipe = DummyPipe()
            .setPipeName("manager")
            .setJsonOutput(AgentRequest())
            .setMaxTokens(0)
        val managerPipeline = Pipeline().apply {
            pipelineName = "manager-pipeline"
            add(managerPipe)
        }

        val manifold = Manifold()
            .setManagerPipeline(managerPipeline)
            .setContextWindowSize(1024)
            .autoTruncateContext()

        assertEquals(1024, manifold.getEffectiveManagerTokenBudget()!!.contextWindowSize)
        assertEquals(1024, manifold.getEffectiveManagerHistoryTokenBudget())
    }

    /**
     * Verifies that worker pipelines must declare either token budgeting or legacy auto truncation before Manifold
     * startup proceeds.
     */
    @Test
    fun initFailsWhenWorkerPipelineHasNoOverflowProtection() = runBlocking {
        val managerPipe = DummyPipe()
            .setPipeName("manager")
            .setJsonOutput(AgentRequest())
            .setTokenBudget(
                TokenBudgetSettings(
                    contextWindowSize = 4096,
                    userPromptSize = 1024,
                    maxTokens = 256
                )
            )
        val managerPipeline = Pipeline().apply {
            pipelineName = "manager-pipeline"
            add(managerPipe)
        }

        val unsafeWorkerPipe = DummyPipe().setPipeName("unsafe-worker")
        val workerPipeline = Pipeline().apply {
            pipelineName = "unsafe-worker-pipeline"
            add(unsafeWorkerPipe)
        }

        val manifold = Manifold()
            .setManagerPipeline(managerPipeline)
            .addWorkerPipeline(workerPipeline)
            .autoTruncateContext()

        val exception = assertFailsWith<Exception> {
            manifold.init()
        }
        assertTrue(exception.message!!.contains("unsafe-worker-pipeline"))
    }

    private class DummyPipe : Pipe()
    {
        override fun truncateModuleContext(): Pipe
        {
            return this
        }

        override suspend fun generateText(promptInjector: String): String
        {
            return promptInjector
        }
    }
}
