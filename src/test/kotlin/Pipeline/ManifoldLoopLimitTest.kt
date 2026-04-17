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
 * Regression tests for the Manifold loop limit safety system.
 */
class ManifoldLoopLimitTest
{
    /**
     * DummyPipe for real container tests — echoes input.
     */
    class DummyPipe : Pipe()
    {
        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String = promptInjector
    }

    /**
     * Verifies that the default loop limit is 100.
     */
    @Test
    fun defaultLimitIs100()
    {
        val manifold = Manifold()
        assertEquals(100, manifold.getMaxLoopIterations())
    }

    /**
     * Verifies that hasLoopLimit returns true by default (default limit is 100).
     */
    @Test
    fun hasLoopLimitReturnsTrueByDefault()
    {
        val manifold = Manifold()
        // Default limit is 100, so hasLoopLimit should return true
        assertTrue(manifold.hasLoopLimit())
    }

    /**
     * Verifies that hasLoopLimit returns true after setting a limit.
     */
    @Test
    fun hasLoopLimitReturnsTrueWhenSet()
    {
        val manifold = Manifold()
        manifold.setMaxLoopIterations(50)
        assertTrue(manifold.hasLoopLimit())
    }

    /**
     * Verifies that setMaxLoopIterations(null) removes the limit.
     */
    @Test
    fun setMaxLoopIterationsNullRemovesLimit()
    {
        val manifold = Manifold()
        manifold.setMaxLoopIterations(50)
        assertTrue(manifold.hasLoopLimit())
        manifold.setMaxLoopIterations(null)
        assertTrue(!manifold.hasLoopLimit())
    }

    /**
     * Verifies that setMaxLoopIterations returns this Manifold for chaining.
     */
    @Test
    fun setMaxLoopIterationsReturnsThisForChaining()
    {
        val manifold = Manifold()
        val result = manifold.setMaxLoopIterations(10)
        assertEquals(manifold, result)
    }

    /**
     * Verifies that DSL maxIterations configuration is applied to the built manifold.
     */
    @Test
    fun dslMaxIterationsConfigurationIsApplied()
    {
        val managerPipe = DummyPipe()
            .setPipeName("manager")
            .setJsonOutput(AgentRequest())
            .setMaxTokens(256)
            .autoTruncateContext()

        val workerPipe = DummyPipe()
            .setPipeName("worker")
            .setContextWindowSize(2048)
            .autoTruncateContext()

        val builtManifold = manifold {
            maxIterations(25)
            manager {
                pipeline {
                    pipelineName = "test-manager"
                    add(managerPipe)
                }
                agentDispatchPipe("manager")
            }
            worker("test-worker") {
                pipeline {
                    pipelineName = "test-worker-pipeline"
                    add(workerPipe)
                }
            }
        }

        assertEquals(25, builtManifold.getMaxLoopIterations())
        assertTrue(builtManifold.hasLoopLimit())
    }

    /**
     * Verifies that setMaxLoopIterations accepts chaining and hasLoopLimit reflects it.
     */
    @Test
    fun setMaxLoopIterationsAffectsHasLoopLimit(): Unit = runBlocking {
        val managerPipe = DummyPipe()
            .setPipeName("manager")
            .setJsonOutput(AgentRequest())
            .setMaxTokens(256)
            .autoTruncateContext()

        val workerPipe = DummyPipe()
            .setPipeName("worker")
            .setContextWindowSize(2048)
            .autoTruncateContext()
            .setSystemPrompt("pass")

        val managerPipeline = Pipeline().apply {
            pipelineName = "manager-pipeline"
            add(managerPipe)
        }

        val workerPipeline = Pipeline().apply {
            pipelineName = "worker-pipeline"
            add(workerPipe)
        }

        val manifold = Manifold()
            .setManagerPipeline(managerPipeline)
            .addWorkerPipeline(workerPipeline)
            .setMaxLoopIterations(3)
            .autoTruncateContext()

        // Verify the limit is set correctly
        assertEquals(3, manifold.getMaxLoopIterations())
        assertTrue(manifold.hasLoopLimit())

        // Change limit
        manifold.setMaxLoopIterations(10)
        assertEquals(10, manifold.getMaxLoopIterations())

        // Remove limit
        manifold.setMaxLoopIterations(null)
        assertEquals(null, manifold.getMaxLoopIterations())
        assertTrue(!manifold.hasLoopLimit())
    }
}