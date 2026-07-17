package com.TTT.Pipeline

import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TDD tests for the DistributionGrid summary agent feature.
 *
 * RED: tests fail against the pre-change codebase (DistributionGridSummarizerContext and
 * summaryAgent field do not exist yet).
 * GREEN: tests pass after Task 2 implements the data class and field.
 */
class DistributionGridSummaryAgentTest
{
    /**
     * A P2PInterface that records invocations and returns configurable output.
     * Used to verify that summaryAgent is called with the correct input.
     */
    internal class StubSummaryAgent(
        private val responseText: String = "STUB SUMMARY OUTPUT",
        private val throwOnInvoke: Throwable? = null
    ) : P2PInterface
    {
        var invokeCount: Int = 0
            private set
        var lastInputContext: DistributionGridSummarizerContext? = null
            private set

        private var containerObject: Any? = null

        override var killSwitch: KillSwitch? = null

        override fun setContainerObject(container: Any)
        {
            containerObject = container
        }

        override fun getContainerObject(): Any?
        {
            return containerObject
        }

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            invokeCount += 1
            lastInputContext = content.metadata["distributionGridSummarizerContext"]
                as? DistributionGridSummarizerContext
            if(throwOnInvoke != null)
            {
                throw throwOnInvoke
            }
            return MultimodalContent(text = responseText)
        }
    }

    @Test
    fun `DistributionGridSummarizerContext carries all five fields`()
    {
        val context = DistributionGridSummarizerContext(
            taskId = "TASK-42",
            currentNodeId = "node-A",
            targetNodeId = "node-B",
            summaryBudget = 1024,
            summarySeed = "older history text"
        )
        assertEquals("TASK-42", context.taskId)
        assertEquals("node-A", context.currentNodeId)
        assertEquals("node-B", context.targetNodeId)
        assertEquals(1024, context.summaryBudget)
        assertEquals("older history text", context.summarySeed)
    }

    @Test
    fun `DistributionGridMemoryPolicy default summaryAgent field is null`()
    {
        val policy = DistributionGridMemoryPolicy()
        assertNull(policy.summaryAgent, "summaryAgent default must be null")
        assertNull(policy.summarizer, "summarizer default must remain null")
        assertEquals(false, policy.enableSummarization, "enableSummarization default unchanged")
    }

    @Test
    fun `setSummaryAgent mutates existing memory policy in place preserving prior fields`()
    {
        val grid = DistributionGrid()
            .setRouter(StubSummaryAgent())
            .setWorker(StubSummaryAgent())

        grid.setMemoryPolicy(
            DistributionGridMemoryPolicy(
                enableSummarization = true,
                summaryBudget = 1024,
                minimumCriticalBudget = 256,
                minimumRecentBudget = 128
            )
        )

        val agent = StubSummaryAgent()
        grid.setSummaryAgent(agent)

        val policy = grid.getMemoryPolicy()
        assertSame(agent, policy.summaryAgent, "summaryAgent must be set")
        assertEquals(true, policy.enableSummarization, "enableSummarization must be preserved")
        assertEquals(1024, policy.summaryBudget, "summaryBudget must be preserved")
        assertEquals(256, policy.minimumCriticalBudget, "minimumCriticalBudget must be preserved")
        assertEquals(128, policy.minimumRecentBudget, "minimumRecentBudget must be preserved")
    }

    @Test
    fun `getSummaryAgent returns the most recently assigned agent`()
    {
        val grid = DistributionGrid()
            .setRouter(StubSummaryAgent())
            .setWorker(StubSummaryAgent())

        val first = StubSummaryAgent()
        val second = StubSummaryAgent()

        grid.setSummaryAgent(first)
        assertSame(first, grid.getSummaryAgent())

        grid.setSummaryAgent(second)
        assertSame(second, grid.getSummaryAgent())
    }

    @Test
    fun `setSummaryAgent null clears the agent without touching other fields`()
    {
        val grid = DistributionGrid()
            .setRouter(StubSummaryAgent())
            .setWorker(StubSummaryAgent())

        grid.setMemoryPolicy(
            DistributionGridMemoryPolicy(
                enableSummarization = true,
                summaryBudget = 512
            )
        )
        grid.setSummaryAgent(StubSummaryAgent())
        grid.setSummaryAgent(null)

        val policy = grid.getMemoryPolicy()
        assertNull(policy.summaryAgent, "summaryAgent must be cleared")
        assertEquals(true, policy.enableSummarization, "enableSummarization must be preserved")
        assertEquals(512, policy.summaryBudget, "summaryBudget must be preserved")
    }

    // ===== Task 5 RED: buildSummaryText agent-first priority tests =====
    // buildSummaryText is private — invoke via reflection to pin the contract.

    private fun invokeBuildSummaryText(
        grid: DistributionGrid,
        summarySeed: String,
        summaryBudget: Int,
        taskId: String,
        currentNodeId: String,
        targetNodeId: String
    ): String = kotlinx.coroutines.runBlocking {
        grid.buildSummaryText(
            summarySeed = summarySeed,
            summaryBudget = summaryBudget,
            settings = com.TTT.Pipe.TruncationSettings(),
            taskId = taskId,
            currentNodeId = currentNodeId,
            targetNodeId = targetNodeId
        )
    }

    @Test
    fun `buildSummaryText invokes summaryAgent and uses its text when set and enabled`()
    {
        kotlinx.coroutines.test.runTest {
            val grid = DistributionGrid()
                .setRouter(StubSummaryAgent())
                .setWorker(StubSummaryAgent())
            val agent = StubSummaryAgent(responseText = "AGENT-SUMMARIZED-OUTPUT")
            grid.setMemoryPolicy(
                DistributionGridMemoryPolicy(
                    enableSummarization = true,
                    summaryBudget = 1024,
                    summaryAgent = agent
                )
            )

            val result = invokeBuildSummaryText(
                grid,
                "older history seed",
                512,
                "TASK-1",
                "node-source",
                "node-target"
            )

            assertTrue(result.isNotBlank(), "summary block must be non-blank")
            assertEquals(1, agent.invokeCount, "agent must be invoked exactly once")
            assertNotNull(agent.lastInputContext, "agent must receive the summarizer context")
            assertEquals("TASK-1", agent.lastInputContext!!.taskId)
            assertEquals("node-source", agent.lastInputContext!!.currentNodeId)
            assertEquals("node-target", agent.lastInputContext!!.targetNodeId)
            assertEquals(512, agent.lastInputContext!!.summaryBudget)
            assertEquals("older history seed".take(4096), agent.lastInputContext!!.summarySeed)
        }
    }

    @Test
    fun `buildSummaryText passes MultimodalContent with distributionGridSummarizerContext metadata key`()
    {
        kotlinx.coroutines.test.runTest {
            val grid = DistributionGrid()
                .setRouter(StubSummaryAgent())
                .setWorker(StubSummaryAgent())
            val agent = StubSummaryAgent()
            grid.setMemoryPolicy(
                DistributionGridMemoryPolicy(
                    enableSummarization = true,
                    summaryBudget = 1024,
                    summaryAgent = agent
                )
            )

            invokeBuildSummaryText(
                grid,
                "older history seed",
                512,
                "TASK-X",
                "src",
                "dst"
            )

            assertNotNull(agent.lastInputContext)
            assertEquals("TASK-X", agent.lastInputContext!!.taskId)
        }
    }

    // ===== Task 7 RED: buildSummaryText fallback paths =====

    @Test
    fun `buildSummaryText falls back to lambda when agent returns blank text`()
    {
        kotlinx.coroutines.test.runTest {
            val grid = DistributionGrid()
                .setRouter(StubSummaryAgent())
                .setWorker(StubSummaryAgent())
            val blankAgent = StubSummaryAgent(responseText = "   ")
            var lambdaInvoked = false
            grid.setMemoryPolicy(
                DistributionGridMemoryPolicy(
                    enableSummarization = true,
                    summaryBudget = 1024,
                    summaryAgent = blankAgent,
                    summarizer = {
                        lambdaInvoked = true
                        "LAMBDA-FALLBACK-OUTPUT"
                    }
                )
            )

            val result = invokeBuildSummaryText(
                grid, "older history seed", 512, "TASK-1", "src", "dst"
            )

            assertTrue(result.isNotBlank())
            assertEquals(1, blankAgent.invokeCount, "agent must be tried first")
            assertTrue(lambdaInvoked, "lambda must be invoked as fallback when agent returns blank")
        }
    }

    @Test
    fun `buildSummaryText falls back to lambda when agent throws`()
    {
        kotlinx.coroutines.test.runTest {
            val grid = DistributionGrid()
                .setRouter(StubSummaryAgent())
                .setWorker(StubSummaryAgent())
            val crashingAgent = StubSummaryAgent(throwOnInvoke = RuntimeException("agent crashed"))
            var lambdaInvoked = false
            grid.setMemoryPolicy(
                DistributionGridMemoryPolicy(
                    enableSummarization = true,
                    summaryBudget = 1024,
                    summaryAgent = crashingAgent,
                    summarizer = {
                        lambdaInvoked = true
                        "LAMBDA-AFTER-CRASH"
                    }
                )
            )

            val result = invokeBuildSummaryText(
                grid, "older history seed", 512, "TASK-1", "src", "dst"
            )

            assertTrue(result.isNotBlank())
            assertEquals(1, crashingAgent.invokeCount, "crashing agent must be tried once")
            assertTrue(lambdaInvoked, "lambda must be invoked when agent throws")
        }
    }

    @Test
    fun `buildSummaryText falls back to verbatim when both agent and lambda fail`()
    {
        kotlinx.coroutines.test.runTest {
            val grid = DistributionGrid()
                .setRouter(StubSummaryAgent())
                .setWorker(StubSummaryAgent())
            val crashingAgent = StubSummaryAgent(throwOnInvoke = RuntimeException("agent crashed"))
            grid.setMemoryPolicy(
                DistributionGridMemoryPolicy(
                    enableSummarization = true,
                    summaryBudget = 1024,
                    summaryAgent = crashingAgent,
                    summarizer = { "" }
                )
            )

            val seed = "verbatim older history"
            val result = invokeBuildSummaryText(
                grid, seed, 512, "TASK-1", "src", "dst"
            )

            assertTrue(result.isNotBlank(), "verbatim fallback must still produce non-blank output")
            assertTrue(
                result.contains("older history", ignoreCase = true),
                "verbatim fallback must include the original seed text"
            )
        }
    }

    @Test
    fun `buildSummaryText skips agent branch when summaryAgent is null even if enableSummarization is true`()
    {
        kotlinx.coroutines.test.runTest {
            val grid = DistributionGrid()
                .setRouter(StubSummaryAgent())
                .setWorker(StubSummaryAgent())
            var lambdaInvoked = false
            grid.setMemoryPolicy(
                DistributionGridMemoryPolicy(
                    enableSummarization = true,
                    summaryBudget = 1024,
                    summaryAgent = null,
                    summarizer = {
                        lambdaInvoked = true
                        "LAMBDA-ONLY"
                    }
                )
            )

            invokeBuildSummaryText(grid, "older history", 256, "TASK-2", "src", "dst")
            assertTrue(lambdaInvoked, "lambda must run when agent is null")
        }
    }

    // ===== Task 11 RED: DSL summaryAgent methods =====

    @Test
    fun `DSL distributionGrid builder exposes top-level summaryAgent(agent)`()
    {
        val agent = StubSummaryAgent()
        val grid = distributionGrid {
            router(StubSummaryAgent())
            worker(StubSummaryAgent())
            summaryAgent(agent)
        }
        assertSame(agent, grid.getSummaryAgent())
    }

    @Test
    fun `DSL distributionGrid builder supports summaryAgent block form`()
    {
        val agent = StubSummaryAgent()
        val grid = distributionGrid {
            router(StubSummaryAgent())
            worker(StubSummaryAgent())
            summaryAgent {
                this.summaryAgent = agent
            }
        }
        assertSame(agent, grid.getSummaryAgent())
    }

    @Test
    fun `DSL memoryPolicy block exposes summaryAgent setter`()
    {
        val agent = StubSummaryAgent()
        val grid = distributionGrid {
            router(StubSummaryAgent())
            worker(StubSummaryAgent())
            memory {
                enableSummarization(true)
                summaryBudget(1024)
                summaryAgent(agent)
            }
        }
        assertSame(agent, grid.getMemoryPolicy().summaryAgent)
        assertEquals(true, grid.getMemoryPolicy().enableSummarization)
        assertEquals(1024, grid.getMemoryPolicy().summaryBudget)
    }

    @Test
    fun `DSL memoryPolicy then top-level summaryAgent preserves enableSummarization - silent-overwrite regression`()
    {
        val agent = StubSummaryAgent()
        val grid = distributionGrid {
            router(StubSummaryAgent())
            worker(StubSummaryAgent())
            memory {
                enableSummarization(true)
                summaryBudget(2048)
            }
            summaryAgent(agent)
        }
        // CRITICAL: this is the silent-overwrite pitfall canary.
        // If summaryAgent(agent) routed through memoryPolicy replacement,
        // enableSummarization would be false.
        assertSame(agent, grid.getSummaryAgent())
        assertEquals(true, grid.getMemoryPolicy().enableSummarization)
        assertEquals(2048, grid.getMemoryPolicy().summaryBudget)
    }
}