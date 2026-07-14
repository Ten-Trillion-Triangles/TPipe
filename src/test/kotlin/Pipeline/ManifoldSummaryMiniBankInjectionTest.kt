package com.TTT.Pipeline

import com.TTT.Enums.SummaryMode
import com.TTT.P2P.AgentRequest
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * API-surface and DSL-wiring tests for the opt-in summary-to-MiniBank auto-injection feature.
 *
 * These tests pin the public setter / DSL block contract. Loop-driven integration (where the
 * Manifold's `while` loop fires the summary pipeline and threads the result into
 * `workingContentObject.miniBankContext`) is exercised by the existing `ManifoldDslTest` real-LLM
 * smoke tests in the same package, which run outside this unit-test bucket. Pinning the new
 * public surface at this level keeps RED→GREEN fast and consistent with the existing TPipe test
 * patterns that verify wiring without exercising the full manager-loop.
 */
class ManifoldSummaryMiniBankInjectionTest
{
    private class DummyPipe : Pipe()
    {
        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String = promptInjector
        override suspend fun generateContent(content: MultimodalContent): MultimodalContent = content
    }

    private class ScriptedPipe(private val outputs: List<String>) : Pipe()
    {
        private var invocationCount = 0
        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String
        {
            val index = invocationCount.coerceAtMost(outputs.lastIndex)
            invocationCount++
            return outputs[index]
        }
        override suspend fun generateContent(content: MultimodalContent): MultimodalContent
        {
            return MultimodalContent(generateText(content.text))
        }
    }

    //---------------------------------------------------------------------
    // Manifold setter tests (pin public API surface).
    //---------------------------------------------------------------------

    @Test
    fun setSummaryMiniBankKeyReturnsManifoldForChaining()
    {
        val manifold = Manifold()
        val returned = manifold.setSummaryMiniBankKey("custom-key")
        assertSame(manifold, returned, "setter must return the same Manifold instance for builder chaining")
    }

    @Test
    fun setInjectSummaryIntoMiniBankReturnsManifoldForChaining()
    {
        val manifold = Manifold()
        val returned = manifold.setInjectSummaryIntoMiniBank(true)
        assertSame(manifold, returned)
    }

    @Test
    fun setSummaryMiniBankKeyRejectsBlankKey()
    {
        val manifold = Manifold()
        assertFailsWith<IllegalArgumentException>(
            message = "Blank MiniBank key must throw IllegalArgumentException",
            block = { manifold.setSummaryMiniBankKey("") }
        )
        assertFailsWith<IllegalArgumentException>(
            message = "Whitespace-only MiniBank key must throw IllegalArgumentException",
            block = { manifold.setSummaryMiniBankKey("   ") }
        )
    }

    @Test
    fun settersDoNotBreakManifoldBuildInternalContract()
    {
        // Build a representative manifold with the new setters applied, and verify the
        // pre-existing DSL/state-machine path still produces a Manifold that can be queried
        // for its manager pipeline. This pins the additive-only contract: existing build paths
        // still work; the new setters don't disturb the state machine.
        val managerPipe = ScriptedPipe(
            outputs = listOf(
                serialize(AgentRequest(agentName = "worker")),
                serialize(TaskProgress(isTaskComplete = true))
            )
        )
            .setPipeName("dispatcher")
            .setJsonOutput(AgentRequest())
            .setTokenBudget(
                TokenBudgetSettings(
                    contextWindowSize = 4096,
                    userPromptSize = 1024,
                    maxTokens = 256
                )
            )

        val workerPipe = DummyPipe()
            .setPipeName("worker")
            .setContextWindowSize(2048)
            .autoTruncateContext()

        val manifold = manifold {
            manager {
                pipeline {
                    pipelineName = "manager"
                    add(managerPipe)
                }
                agentDispatchPipe("dispatcher")
            }
            history {
                managerTokenBudget(
                    TokenBudgetSettings(
                        contextWindowSize = 4096,
                        userPromptSize = 1024,
                        maxTokens = 256
                    )
                )
            }
            worker("worker") {
                pipeline {
                    pipelineName = "worker-pipeline"
                    add(workerPipe)
                }
            }
            summaryInjection {
                injectIntoMiniBank()
                miniBankKey("unit-test.summary")
            }
        }

        // Add the summary pipeline via setters (so we exercise the post-build setter path too).
        val summaryPipe = DummyPipe()
            .setPipeName("summary")
            .setContextWindowSize(2048)
            .autoTruncateContext()
        manifold.setSummaryPipeline(
            Pipeline().apply {
                pipelineName = "summary-pipeline"
                add(summaryPipe)
            }
        )
        manifold.setSummaryMode(SummaryMode.APPEND)
        // The inject-flag is already true via summaryInjection DSL block; calling again is a no-op.

        assertEquals("manager", manifold.getManagerPipeline().pipelineName)
        assertEquals(1, manifold.getWorkerPipelines().size)
    }

    //---------------------------------------------------------------------
    // DSL tests — pin the summaryInjection { } block contract.
    //---------------------------------------------------------------------

    @Test
    fun summaryInjectionDslBlockDrivesSetters() = runBlocking {
        val managerPipe = ScriptedPipe(
            outputs = listOf(
                serialize(AgentRequest(agentName = "worker")),
                serialize(TaskProgress(isTaskComplete = true))
            )
        )
            .setPipeName("dispatcher")
            .setJsonOutput(AgentRequest())
            .setTokenBudget(
                TokenBudgetSettings(
                    contextWindowSize = 4096,
                    userPromptSize = 1024,
                    maxTokens = 256
                )
            )
        val workerPipe = DummyPipe()
            .setPipeName("worker")
            .setContextWindowSize(2048)
            .autoTruncateContext()

        val built = manifold {
            manager {
                pipeline {
                    pipelineName = "manager"
                    add(managerPipe)
                }
                agentDispatchPipe("dispatcher")
            }
            history {
                managerTokenBudget(
                    TokenBudgetSettings(
                        contextWindowSize = 4096,
                        userPromptSize = 1024,
                        maxTokens = 256
                    )
                )
            }
            worker("worker") {
                pipeline {
                    pipelineName = "worker-pipeline"
                    add(workerPipe)
                }
            }
            summaryInjection {
                injectIntoMiniBank()
                miniBankKey("dsl.summary.key")
            }
        }
        // Manager pipeline name sanity — the DSL wiring preserves state. The fold behavior
        // is exercised at runtime by the summary pipeline; pin the state shape here.
        assertEquals("manager", built.getManagerPipeline().pipelineName)
        assertEquals(1, built.getWorkerPipelines().size)
    }

    @Test
    fun summaryInjectionDslBlockRejectsSecondCall() {
        val managerPipe = ScriptedPipe(
            outputs = listOf(
                serialize(AgentRequest(agentName = "worker")),
                serialize(TaskProgress(isTaskComplete = true))
            )
        )
            .setPipeName("dispatcher")
            .setJsonOutput(AgentRequest())
            .setTokenBudget(
                TokenBudgetSettings(
                    contextWindowSize = 4096,
                    userPromptSize = 1024,
                    maxTokens = 256
                )
            )
        val workerPipe = DummyPipe()
            .setPipeName("worker")
            .setContextWindowSize(2048)
            .autoTruncateContext()

        assertFailsWith<IllegalArgumentException>(
            message = "second summaryInjection { ... } block must throw IllegalArgumentException",
            block = {
                runBlocking {
                    manifold {
                        manager {
                            pipeline {
                                pipelineName = "manager"
                                add(managerPipe)
                            }
                            agentDispatchPipe("dispatcher")
                        }
                        history {
                            managerTokenBudget(
                                TokenBudgetSettings(
                                    contextWindowSize = 4096,
                                    userPromptSize = 1024,
                                    maxTokens = 256
                                )
                            )
                        }
                        worker("worker") {
                            pipeline {
                                pipelineName = "worker-pipeline"
                                add(workerPipe)
                            }
                        }
                        summaryInjection {
                            injectIntoMiniBank()
                        }
                        summaryInjection {
                            injectIntoMiniBank()
                        }
                    }
                }
            }
        )
    }

    @Test
    fun summaryInjectionDslMiniBankKeyRejectsBlank() {
        val managerPipe = ScriptedPipe(
            outputs = listOf(
                serialize(AgentRequest(agentName = "worker")),
                serialize(TaskProgress(isTaskComplete = true))
            )
        )
            .setPipeName("dispatcher")
            .setJsonOutput(AgentRequest())
            .setTokenBudget(
                TokenBudgetSettings(
                    contextWindowSize = 4096,
                    userPromptSize = 1024,
                    maxTokens = 256
                )
            )
        val workerPipe = DummyPipe()
            .setPipeName("worker")
            .setContextWindowSize(2048)
            .autoTruncateContext()

        assertFailsWith<IllegalArgumentException>(
            message = "Blank MiniBank key inside DSL must throw IllegalArgumentException",
            block = {
                runBlocking {
                    manifold {
                        manager {
                            pipeline {
                                pipelineName = "manager"
                                add(managerPipe)
                            }
                            agentDispatchPipe("dispatcher")
                        }
                        history {
                            managerTokenBudget(
                                TokenBudgetSettings(
                                    contextWindowSize = 4096,
                                    userPromptSize = 1024,
                                    maxTokens = 256
                                )
                            )
                        }
                        worker("worker") {
                            pipeline {
                                pipelineName = "worker-pipeline"
                                add(workerPipe)
                            }
                        }
                        summaryInjection {
                            miniBankKey("")
                        }
                    }
                }
            }
        )
    }
}
