package com.TTT.P2P

import com.TTT.P2P.AgentRequest
import com.TTT.Pipeline.Connector
import com.TTT.Pipeline.DistributionGrid
import com.TTT.Pipeline.Junction
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.MultiConnector
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.Splitter
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that the new abort and pipe-timeout recursive propagation methods
 * on P2PInterface (added 2026-08-17) follow the same convention as the
 * pre-existing `setStreamingCallbackRecursive` / `enableStallDetectorRecursive` /
 * `setConverseRoleRecursive` / `setTokenBudgetRecursive` / `setPipeSettingsRecursively`
 * pattern.
 *
 * The contract for every `*Recursive` method:
 *   1. Defined on `P2PInterface` with a no-op default body
 *   2. Overridden on every container (Pipeline, Manifold, Junction, Splitter,
 *      Connector, MultiConnector, DistributionGrid, PumpStation) to drill
 *      through children and call the leaf-pipe handler on each
 *   3. Overridden on `Pipe` itself with delegation: if `containerPtr` is null
 *      apply locally, otherwise call `containerPtr.setXRecursive(...)` to drill
 *      upward
 *
 * These tests cover layers 1 + 2: when `abortRecursive()` or
 * `enablePipeTimeoutRecursive(...)` is called on a container, every leaf pipe
 * underneath receives the propagation. Layer 3 is covered by the Pipe-tree
 * tests in PipeAbortChildCascadeTest and PipeTimeoutPropagateTest.
 *
 * Test strategy: register a unique sentinel flag on each leaf pipe (activeJob
 * for abort, the public pipeTimeout / maxRetryAttempts fields for timeout) and
 * assert the flag is set after the recursive call. Use the existing Pipe-tree
 * walker — these tests do NOT re-implement the walker logic, they verify the
 * contract that the container overrides correctly delegate to each leaf pipe's
 * handler.
 */
class P2PInterfaceRecursiveAbortAndTimeoutTest
{
    /**
     * Test double: plain Pipe subclass with no Bedrock dependencies.
     * Tracks abort invocation via a counter (the timeout check reads the
     * public pipeTimeout / maxRetryAttempts / enablePipeTimeout fields).
     */
    private class RecursiveTrackingPipe : Pipe()
    {
        var abortCount: Int = 0

        init
        {
            // Manifold.setManagerPipeline requires at least one pipe whose
            // jsonOutput matches the AgentRequest example schema. Setting
            // it here lets tests compose Manifold without that constraint
            // surfacing as a test failure.
            @Suppress("UNCHECKED_CAST")
            setJsonOutput(AgentRequest())
        }

        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String = promptInjector

        override suspend fun abort()
        {
            abortCount++
            super.abort()
        }
    }

    @Test
    fun `Pipeline abortRecursive propagates abort to every leaf pipe`()
    {
        runBlocking {
            val pipe1 = RecursiveTrackingPipe()
            val pipe2 = RecursiveTrackingPipe()
            val pipeline = Pipeline().add(pipe1).add(pipe2)

            pipeline.abortRecursive()

            assertEquals(1, pipe1.abortCount, "pipe1 should receive one abort")
            assertEquals(1, pipe2.abortCount, "pipe2 should receive one abort")
        }
    }

    @Test
    fun `Pipeline enablePipeTimeoutRecursive propagates timeout config to every leaf pipe`()
    {
        runBlocking {
            val pipe1 = RecursiveTrackingPipe()
            val pipe2 = RecursiveTrackingPipe()
            val pipeline = Pipeline().add(pipe1).add(pipe2)

            pipeline.enablePipeTimeoutRecursive(
                applyRecursively = true,
                duration = 123456L,
                autoRetry = true,
                retryLimit = 7
            )

            assertTrue(pipe1.enablePipeTimeout, "pipe1 enablePipeTimeout should be true")
            assertEquals(123456L, pipe1.pipeTimeout, "pipe1 pipeTimeout matches")
            assertEquals(7, pipe1.maxRetryAttempts, "pipe1 retry limit matches")
            assertTrue(pipe2.enablePipeTimeout, "pipe2 enablePipeTimeout should be true")
            assertEquals(123456L, pipe2.pipeTimeout)
        }
    }

    @Test
    fun `Manifold abortRecursive propagates abort to manager and worker pipelines`()
    {
        runBlocking {
            val managerPipe = RecursiveTrackingPipe()
            val workerPipe = RecursiveTrackingPipe()
            val managerPipeline = Pipeline().add(managerPipe)
            val workerPipeline = Pipeline().add(workerPipe)

            val manifold = Manifold()
                .setManagerPipeline(managerPipeline)
                .addWorkerPipeline(workerPipeline)

            manifold.abortRecursive()

            assertEquals(1, managerPipe.abortCount, "manager pipe receives abort")
            assertEquals(1, workerPipe.abortCount, "worker pipe receives abort")
        }
    }

    @Test
    fun `Manifold enablePipeTimeoutRecursive propagates timeout to manager and worker`()
    {
        runBlocking {
            val managerPipe = RecursiveTrackingPipe()
            val workerPipe = RecursiveTrackingPipe()
            val managerPipeline = Pipeline().add(managerPipe)
            val workerPipeline = Pipeline().add(workerPipe)

            val manifold = Manifold()
                .setManagerPipeline(managerPipeline)
                .addWorkerPipeline(workerPipeline)

            manifold.enablePipeTimeoutRecursive(
                applyRecursively = true,
                duration = 90000L,
                retryLimit = 3
            )

            assertEquals(90000L, managerPipe.pipeTimeout)
            assertEquals(3, managerPipe.maxRetryAttempts)
            assertEquals(90000L, workerPipe.pipeTimeout)
        }
    }

    @Test
    fun `Splitter abortRecursive propagates abort to every branch pipeline's pipes`()
    {
        runBlocking {
            val branch1Pipe = RecursiveTrackingPipe()
            val branch2Pipe = RecursiveTrackingPipe()
            val branch1 = Pipeline().add(branch1Pipe)
            val branch2 = Pipeline().add(branch2Pipe)

            val splitter = Splitter()
                .addPipeline("key1", branch1)
                .addPipeline("key2", branch2)

            splitter.abortRecursive()

            assertEquals(1, branch1Pipe.abortCount)
            assertEquals(1, branch2Pipe.abortCount)
        }
    }

    @Test
    fun `Splitter enablePipeTimeoutRecursive propagates timeout to every branch`()
    {
        runBlocking {
            val branch1Pipe = RecursiveTrackingPipe()
            val branch2Pipe = RecursiveTrackingPipe()
            val branch1 = Pipeline().add(branch1Pipe)
            val branch2 = Pipeline().add(branch2Pipe)

            val splitter = Splitter()
                .addPipeline("key1", branch1)
                .addPipeline("key2", branch2)

            splitter.enablePipeTimeoutRecursive(duration = 60000L, retryLimit = 4)

            assertEquals(60000L, branch1Pipe.pipeTimeout)
            assertEquals(60000L, branch2Pipe.pipeTimeout)
        }
    }

    @Test
    fun `Connector abortRecursive propagates abort to every branch pipeline`()
    {
        runBlocking {
            val branch1Pipe = RecursiveTrackingPipe()
            val branch2Pipe = RecursiveTrackingPipe()
            val branch1 = Pipeline().add(branch1Pipe)
            val branch2 = Pipeline().add(branch2Pipe)

            val connector = Connector()
                .add("path1", branch1)
                .add("path2", branch2)

            connector.abortRecursive()

            assertEquals(1, branch1Pipe.abortCount)
            assertEquals(1, branch2Pipe.abortCount)
        }
    }

    @Test
    fun `Connector enablePipeTimeoutRecursive propagates timeout to every branch`()
    {
        runBlocking {
            val branch1Pipe = RecursiveTrackingPipe()
            val branch2Pipe = RecursiveTrackingPipe()
            val branch1 = Pipeline().add(branch1Pipe)
            val branch2 = Pipeline().add(branch2Pipe)

            val connector = Connector()
                .add("path1", branch1)
                .add("path2", branch2)

            connector.enablePipeTimeoutRecursive(duration = 45000L)

            assertEquals(45000L, branch1Pipe.pipeTimeout)
            assertEquals(45000L, branch2Pipe.pipeTimeout)
        }
    }

    @Test
    fun `MultiConnector abortRecursive propagates abort to every inner connector`()
    {
        runBlocking {
            val leafPipe = RecursiveTrackingPipe()
            val innerConnector = Connector()
                .add("leaf", Pipeline().add(leafPipe))
            val multiConnector = MultiConnector().add(innerConnector)

            multiConnector.abortRecursive()

            assertEquals(1, leafPipe.abortCount, "leaf pipe under MultiConnector receives abort")
        }
    }

    @Test
    fun `MultiConnector enablePipeTimeoutRecursive propagates timeout to every inner connector`()
    {
        runBlocking {
            val leafPipe = RecursiveTrackingPipe()
            val innerConnector = Connector()
                .add("leaf", Pipeline().add(leafPipe))
            val multiConnector = MultiConnector().add(innerConnector)

            multiConnector.enablePipeTimeoutRecursive(duration = 30000L, retryLimit = 2)

            assertEquals(30000L, leafPipe.pipeTimeout)
            assertEquals(2, leafPipe.maxRetryAttempts)
        }
    }

    @Test
    fun `Junction abortRecursive propagates abort to moderator and every participant`()
    {
        runBlocking {
            val moderatorPipe = RecursiveTrackingPipe()
            val participant1Pipe = RecursiveTrackingPipe()
            val participant2Pipe = RecursiveTrackingPipe()

            val junction = Junction()
                .setModerator(Pipeline().add(moderatorPipe))
                .addParticipant("analyst", Pipeline().add(participant1Pipe))
                .addParticipant("critic", Pipeline().add(participant2Pipe))

            junction.abortRecursive()

            assertEquals(1, moderatorPipe.abortCount, "moderator pipe receives abort")
            assertEquals(1, participant1Pipe.abortCount, "participant1 pipe receives abort")
            assertEquals(1, participant2Pipe.abortCount, "participant2 pipe receives abort")
        }
    }

    @Test
    fun `Junction enablePipeTimeoutRecursive propagates timeout to moderator and participants`()
    {
        runBlocking {
            val moderatorPipe = RecursiveTrackingPipe()
            val participantPipe = RecursiveTrackingPipe()

            val junction = Junction()
                .setModerator(Pipeline().add(moderatorPipe))
                .addParticipant("analyst", Pipeline().add(participantPipe))

            junction.enablePipeTimeoutRecursive(duration = 120000L, retryLimit = 6)

            assertEquals(120000L, moderatorPipe.pipeTimeout)
            assertEquals(6, moderatorPipe.maxRetryAttempts)
            assertEquals(120000L, participantPipe.pipeTimeout)
        }
    }

    @Test
    fun `DistributionGrid abortRecursive propagates abort to router and worker pipelines`()
    {
        runBlocking {
            val routerPipe = RecursiveTrackingPipe()
            val workerPipe = RecursiveTrackingPipe()

            val grid = DistributionGrid()
                .setRouter(Pipeline().add(routerPipe))
                .setWorker(Pipeline().add(workerPipe))

            grid.abortRecursive()

            assertEquals(1, routerPipe.abortCount, "router pipe receives abort")
            assertEquals(1, workerPipe.abortCount, "worker pipe receives abort")
        }
    }

    @Test
    fun `DistributionGrid enablePipeTimeoutRecursive propagates timeout to router and worker`()
    {
        runBlocking {
            val routerPipe = RecursiveTrackingPipe()
            val workerPipe = RecursiveTrackingPipe()

            val grid = DistributionGrid()
                .setRouter(Pipeline().add(routerPipe))
                .setWorker(Pipeline().add(workerPipe))

            grid.enablePipeTimeoutRecursive(duration = 75000L, retryLimit = 8)

            assertEquals(75000L, routerPipe.pipeTimeout)
            assertEquals(8, routerPipe.maxRetryAttempts)
            assertEquals(75000L, workerPipe.pipeTimeout)
        }
    }

    /**
     * Single-call abortRecursive on a Pipeline with multiple pipes confirms
     * the propagation walks through every pipe in the tree.
     */
    @Test
    fun `Pipeline with three leaf pipes abortRecursive reaches every pipe`()
    {
        runBlocking {
            val pipe1 = RecursiveTrackingPipe()
            val pipe2 = RecursiveTrackingPipe()
            val pipe3 = RecursiveTrackingPipe()
            val pipeline = Pipeline().add(pipe1).add(pipe2).add(pipe3)

            pipeline.abortRecursive()

            assertEquals(1, pipe1.abortCount)
            assertEquals(1, pipe2.abortCount)
            assertEquals(1, pipe3.abortCount)
        }
    }

    /**
     * Same multi-pipe scenario for the timeout propagation.
     */
    @Test
    fun `Pipeline with three leaf pipes enablePipeTimeoutRecursive reaches every pipe`()
    {
        runBlocking {
            val pipe1 = RecursiveTrackingPipe()
            val pipe2 = RecursiveTrackingPipe()
            val pipe3 = RecursiveTrackingPipe()
            val pipeline = Pipeline().add(pipe1).add(pipe2).add(pipe3)

            pipeline.enablePipeTimeoutRecursive(duration = 50000L, retryLimit = 9)

            assertEquals(50000L, pipe1.pipeTimeout)
            assertEquals(50000L, pipe2.pipeTimeout)
            assertEquals(50000L, pipe3.pipeTimeout)
            assertEquals(9, pipe1.maxRetryAttempts)
        }
    }
}