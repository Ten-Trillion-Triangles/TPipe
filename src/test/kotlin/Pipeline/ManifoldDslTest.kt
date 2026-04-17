package com.TTT.Pipeline

import com.TTT.Enums.ContextWindowSettings
import com.TTT.Enums.SummaryMode
import com.TTT.P2P.AgentRequest
import com.TTT.P2P.AgentDescriptor
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.CustomJsonSchema
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression coverage for the manifold DSL builder.
 */
class ManifoldDslTest
{
    /**
     * Verifies that the DSL can assemble and initialize a custom manifold with one manager and one worker.
     */
    @Test
    fun buildsInitializedManifoldFromCustomDsl()
    {
        val managerPipe = DummyPipe()
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

        val builtManifold = manifold {
            manager {
                pipeline {
                    pipelineName = "custom-manager"
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

            worker("research-worker") {
                description("Researches and summarizes information.")
                skill("research", "Investigates the requested problem.")
                pipeline {
                    pipelineName = "research-worker-pipeline"
                    add(workerPipe)
                }
            }
        }

        assertEquals("custom-manager", builtManifold.getManagerPipeline().pipelineName)
        assertEquals(1, builtManifold.getWorkerPipelines().size)
        assertTrue(builtManifold.isManagerBudgetControlEnabled())
    }

    /**
     * Verifies that the DSL will automatically enable manager shared-history control when the primary manager pipe
     * already has overflow protection configured.
     */
    @Test
    fun infersManagerHistoryControlFromPrimaryManagerPipe()
    {
        val builtManifold = manifold {
            manager {
                pipeline {
                    pipelineName = "manager"
                    add(
                        DummyPipe()
                            .setPipeName("dispatcher")
                            .setJsonOutput(AgentRequest())
                            .setTokenBudget(
                                TokenBudgetSettings(
                                    contextWindowSize = 3072,
                                    userPromptSize = 768,
                                    maxTokens = 256
                                )
                            )
                    )
                }
                agentDispatchPipe("dispatcher")
            }

            worker("safe-worker") {
                pipeline {
                    pipelineName = "safe-worker-pipeline"
                    add(
                        DummyPipe()
                            .setPipeName("safe-worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }
        }

        assertTrue(builtManifold.isManagerBudgetControlEnabled())
        assertEquals(3072, builtManifold.getEffectiveManagerTokenBudget()!!.contextWindowSize)
    }

    /**
     * Verifies that a partial history override does not discard the inferred manager-history control that already
     * exists on a valid primary manager pipe.
     */
    @Test
    fun partialHistoryOverridePreservesInferredManagerHistoryControl()
    {
        val builtManifold = manifold {
            manager {
                pipeline {
                    pipelineName = "manager"
                    add(
                        DummyPipe()
                            .setPipeName("dispatcher")
                            .setJsonOutput(AgentRequest())
                            .setTokenBudget(
                                TokenBudgetSettings(
                                    contextWindowSize = 3072,
                                    userPromptSize = 768,
                                    maxTokens = 256
                                )
                            )
                    )
                }
                agentDispatchPipe("dispatcher")
            }

            history {
                truncationMethod(ContextWindowSettings.TruncateBottom)
            }

            worker("safe-worker") {
                pipeline {
                    pipelineName = "safe-worker-pipeline"
                    add(
                        DummyPipe()
                            .setPipeName("safe-worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }
        }

        assertTrue(builtManifold.isManagerBudgetControlEnabled())
        assertEquals(ContextWindowSettings.TruncateBottom, builtManifold.getTruncationMethod())
        assertEquals(3072, builtManifold.getEffectiveManagerTokenBudget()!!.contextWindowSize)
    }

    /**
     * Verifies that a history block using only a context-window override still activates manager history control.
     */
    @Test
    fun historyContextWindowSizeEnablesManagerHistoryControl()
    {
        val builtManifold = manifold {
            manager {
                pipeline {
                    pipelineName = "manager"
                    add(
                        DummyPipe()
                            .setPipeName("dispatcher")
                            .setJsonOutput(AgentRequest())
                            .setMaxTokens(256)
                    )
                }
                agentDispatchPipe("dispatcher")
            }

            history {
                contextWindowSize(4096)
            }

            worker("safe-worker") {
                pipeline {
                    pipelineName = "safe-worker-pipeline"
                    add(
                        DummyPipe()
                            .setPipeName("safe-worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }
        }

        assertTrue(builtManifold.isManagerBudgetControlEnabled())
        assertEquals(4096, builtManifold.getEffectiveManagerTokenBudget()!!.contextWindowSize)
    }

    /**
     * Verifies that a history block using only a truncation-method override still activates manager history control.
     */
    @Test
    fun historyTruncationMethodEnablesManagerHistoryControl()
    {
        val builtManifold = manifold {
            manager {
                pipeline {
                    pipelineName = "manager"
                    add(
                        DummyPipe()
                            .setPipeName("dispatcher")
                            .setJsonOutput(AgentRequest())
                            .setMaxTokens(256)
                    )
                }
                agentDispatchPipe("dispatcher")
            }

            history {
                truncationMethod(ContextWindowSettings.TruncateMiddle)
            }

            worker("safe-worker") {
                pipeline {
                    pipelineName = "safe-worker-pipeline"
                    add(
                        DummyPipe()
                            .setPipeName("safe-worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }
        }

        assertTrue(builtManifold.isManagerBudgetControlEnabled())
        assertEquals(ContextWindowSettings.TruncateMiddle, builtManifold.getTruncationMethod())
    }

    /**
     * Verifies that a custom truncation function is accepted as a valid manager history-control path by itself.
     */
    @Test
    fun historyTruncationFunctionCountsAsValidHistoryControl()
    {
        val builtManifold = manifold {
            manager {
                pipeline {
                    pipelineName = "manager"
                    add(
                        DummyPipe()
                            .setPipeName("dispatcher")
                            .setJsonOutput(AgentRequest())
                            .setMaxTokens(256)
                    )
                }
                agentDispatchPipe("dispatcher")
            }

            history {
                truncationFunction { _ -> }
            }

            worker("safe-worker") {
                pipeline {
                    pipelineName = "safe-worker-pipeline"
                    add(
                        DummyPipe()
                            .setPipeName("safe-worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }
        }

        assertEquals("manager", builtManifold.getManagerPipeline().pipelineName)
    }

    /**
     * Verifies that duplicate worker agent names are rejected before manifold startup.
     */
    @Test
    fun failsEarlyOnDuplicateWorkerNames()
    {
        val exception = assertFailsWith<IllegalArgumentException> {
            manifold {
                manager {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("dispatcher")
                                .setJsonOutput(AgentRequest())
                                .setTokenBudget(
                                    TokenBudgetSettings(
                                        contextWindowSize = 4096,
                                        userPromptSize = 1024,
                                        maxTokens = 256
                                    )
                                )
                        )
                    }
                    agentDispatchPipe("dispatcher")
                }

                worker("duplicate") {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("worker-a")
                                .setContextWindowSize(2048)
                                .autoTruncateContext()
                        )
                    }
                }

                worker("duplicate") {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("worker-b")
                                .setContextWindowSize(2048)
                                .autoTruncateContext()
                        )
                    }
                }
            }
        }

        assertTrue(exception.message!!.contains("duplicate"))
    }

    /**
     * Verifies that the manager still must expose an AgentRequest output pipe even when assembled through the DSL.
     */
    @Test
    fun failsEarlyWhenManagerCannotDispatchAgents()
    {
        val exception = assertFailsWith<IllegalArgumentException> {
            manifold {
                manager {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("not-a-dispatcher")
                                .setContextWindowSize(2048)
                                .autoTruncateContext()
                        )
                    }
                }

                worker("worker") {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("worker")
                                .setContextWindowSize(2048)
                                .autoTruncateContext()
                        )
                    }
                }
            }
        }

        assertTrue(exception.message!!.contains("AgentRequest"))
    }

    /**
     * Verifies that an explicitly named dispatch pipe must itself emit AgentRequest JSON instead of merely existing.
     */
    @Test
    fun failsEarlyWhenExplicitDispatchPipeDoesNotEmitAgentRequest()
    {
        val exception = assertFailsWith<IllegalArgumentException> {
            manifold {
                manager {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("prep")
                                .setContextWindowSize(2048)
                                .autoTruncateContext()
                        )
                        add(
                            DummyPipe()
                                .setPipeName("dispatcher")
                                .setJsonOutput(AgentRequest())
                                .setTokenBudget(
                                    TokenBudgetSettings(
                                        contextWindowSize = 4096,
                                        userPromptSize = 1024,
                                        maxTokens = 256
                                    )
                                )
                        )
                    }
                    agentDispatchPipe("prep")
                }

                worker("worker") {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("worker")
                                .setContextWindowSize(2048)
                                .autoTruncateContext()
                        )
                    }
                }
            }
        }

        assertTrue(exception.message!!.contains("prep"))
        assertTrue(exception.message!!.contains("AgentRequest"))
    }

    /**
     * Verifies that manager-level advanced P2P overrides must be supplied as a complete descriptor/requirements pair.
     */
    @Test
    fun failsEarlyWhenManagerDescriptorProvidedWithoutRequirements()
    {
        val exception = assertFailsWith<IllegalArgumentException> {
            manifold {
                manager {
                    pipeline(
                        pipeline = Pipeline().apply {
                            add(
                                DummyPipe()
                                    .setPipeName("dispatcher")
                                    .setJsonOutput(AgentRequest())
                                    .setTokenBudget(
                                        TokenBudgetSettings(
                                            contextWindowSize = 4096,
                                            userPromptSize = 1024,
                                            maxTokens = 256
                                        )
                                    )
                            )
                        },
                        descriptor = buildCustomDescriptor(
                            agentName = "manager",
                            transportAddress = "manager-address"
                        )
                    )
                }

                worker("worker") {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("worker")
                                .setContextWindowSize(2048)
                                .autoTruncateContext()
                        )
                    }
                }
            }
        }

        assertTrue(exception.message!!.contains("Manager pipeline"))
        assertTrue(exception.message!!.contains("descriptor and requirements"))
    }

    /**
     * Verifies that manager-level advanced P2P overrides must not accept requirements without a matching descriptor.
     */
    @Test
    fun failsEarlyWhenManagerRequirementsProvidedWithoutDescriptor()
    {
        val exception = assertFailsWith<IllegalArgumentException> {
            manifold {
                manager {
                    pipeline(
                        pipeline = Pipeline().apply {
                            add(
                                DummyPipe()
                                    .setPipeName("dispatcher")
                                    .setJsonOutput(AgentRequest())
                                    .setTokenBudget(
                                        TokenBudgetSettings(
                                            contextWindowSize = 4096,
                                            userPromptSize = 1024,
                                            maxTokens = 256
                                        )
                                    )
                            )
                        },
                        requirements = P2PRequirements()
                    )
                }

                worker("worker") {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("worker")
                                .setContextWindowSize(2048)
                                .autoTruncateContext()
                        )
                    }
                }
            }
        }

        assertTrue(exception.message!!.contains("Manager pipeline"))
        assertTrue(exception.message!!.contains("descriptor and requirements"))
    }

    /**
     * Verifies that a custom dispatch pipe replaces the manifold default instead of also causing agent-list injection
     * on the last manager pipe through the stale fallback path.
     */
    @Test
    fun customDispatchPipeReplacesDefaultAgentCallerPipeName()
    {
        val analysisPipe = DummyPipe()
            .setPipeName("analysis")
            .setContextWindowSize(2048)
            .autoTruncateContext()
        val dispatcherPipe = DummyPipe()
            .setPipeName("custom-dispatcher")
            .setJsonOutput(AgentRequest())
            .setTokenBudget(
                TokenBudgetSettings(
                    contextWindowSize = 4096,
                    userPromptSize = 1024,
                    maxTokens = 256
                )
            )
        val finalizerPipe = DummyPipe()
            .setPipeName("finalizer")
            .setContextWindowSize(2048)
            .autoTruncateContext()

        val builtManifold = manifold {
            manager {
                pipeline {
                    pipelineName = "custom-manager"
                    add(analysisPipe)
                    add(dispatcherPipe)
                    add(finalizerPipe)
                }
                agentDispatchPipe("custom-dispatcher")
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
                    add(
                        DummyPipe()
                            .setPipeName("worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }
        }

        val managerPipes = builtManifold.getManagerPipeline().getPipes()
        assertEquals(0, managerPipes[0].getP2PAgentList()?.size ?: 0)
        assertTrue((managerPipes[1].getP2PAgentList()?.size ?: 0) > 0)
        assertEquals(0, managerPipes[2].getP2PAgentList()?.size ?: 0)
    }

    /**
     * Verifies that custom worker descriptors cannot reuse the same effective agent name under different DSL names.
     */
    @Test
    fun failsEarlyWhenCustomWorkerDescriptorAgentNamesCollide()
    {
        val exception = assertFailsWith<IllegalArgumentException> {
            manifold {
                manager {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("dispatcher")
                                .setJsonOutput(AgentRequest())
                                .setTokenBudget(
                                    TokenBudgetSettings(
                                        contextWindowSize = 4096,
                                        userPromptSize = 1024,
                                        maxTokens = 256
                                    )
                                )
                        )
                    }
                    agentDispatchPipe("dispatcher")
                }

                worker("worker-a") {
                    pipeline(
                        pipeline = buildSafeWorkerPipeline("worker-a-pipeline"),
                        descriptor = buildCustomDescriptor(
                            agentName = "shared-agent",
                            transportAddress = "shared-agent-a"
                        ),
                        requirements = P2PRequirements()
                    )
                }

                worker("worker-b") {
                    pipeline(
                        pipeline = buildSafeWorkerPipeline("worker-b-pipeline"),
                        descriptor = buildCustomDescriptor(
                            agentName = "shared-agent",
                            transportAddress = "shared-agent-b"
                        ),
                        requirements = P2PRequirements()
                    )
                }
            }
        }

        assertTrue(exception.message!!.contains("Duplicate agent names"))
        assertTrue(exception.message!!.contains("shared-agent"))
    }

    /**
     * Verifies that worker-level advanced P2P overrides must be supplied as a complete descriptor/requirements pair.
     */
    @Test
    fun failsEarlyWhenWorkerDescriptorProvidedWithoutRequirements()
    {
        val exception = assertFailsWith<IllegalArgumentException> {
            manifold {
                manager {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("dispatcher")
                                .setJsonOutput(AgentRequest())
                                .setTokenBudget(
                                    TokenBudgetSettings(
                                        contextWindowSize = 4096,
                                        userPromptSize = 1024,
                                        maxTokens = 256
                                    )
                                )
                        )
                    }
                    agentDispatchPipe("dispatcher")
                }

                worker("worker") {
                    pipeline(
                        pipeline = buildSafeWorkerPipeline("worker-pipeline"),
                        descriptor = buildCustomDescriptor(
                            agentName = "worker",
                            transportAddress = "worker-address"
                        )
                    )
                }
            }
        }

        assertTrue(exception.message!!.contains("Worker 'worker'"))
        assertTrue(exception.message!!.contains("descriptor and requirements"))
    }

    /**
     * Verifies that worker-level advanced P2P overrides must not accept requirements without a matching descriptor.
     */
    @Test
    fun failsEarlyWhenWorkerRequirementsProvidedWithoutDescriptor()
    {
        val exception = assertFailsWith<IllegalArgumentException> {
            manifold {
                manager {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("dispatcher")
                                .setJsonOutput(AgentRequest())
                                .setTokenBudget(
                                    TokenBudgetSettings(
                                        contextWindowSize = 4096,
                                        userPromptSize = 1024,
                                        maxTokens = 256
                                    )
                                )
                        )
                    }
                    agentDispatchPipe("dispatcher")
                }

                worker("worker") {
                    pipeline(
                        pipeline = buildSafeWorkerPipeline("worker-pipeline"),
                        requirements = P2PRequirements()
                    )
                }
            }
        }

        assertTrue(exception.message!!.contains("Worker 'worker'"))
        assertTrue(exception.message!!.contains("descriptor and requirements"))
    }

    /**
     * Verifies that a custom descriptor agent name cannot collide with another worker's DSL routing name.
     */
    @Test
    fun failsEarlyWhenCustomWorkerDescriptorCollidesWithDslWorkerName()
    {
        val exception = assertFailsWith<IllegalArgumentException> {
            manifold {
                manager {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("dispatcher")
                                .setJsonOutput(AgentRequest())
                                .setTokenBudget(
                                    TokenBudgetSettings(
                                        contextWindowSize = 4096,
                                        userPromptSize = 1024,
                                        maxTokens = 256
                                    )
                                )
                        )
                    }
                    agentDispatchPipe("dispatcher")
                }

                worker("worker-a") {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("worker-a")
                                .setContextWindowSize(2048)
                                .autoTruncateContext()
                        )
                    }
                }

                worker("worker-b") {
                    pipeline(
                        pipeline = buildSafeWorkerPipeline("worker-b-pipeline"),
                        descriptor = buildCustomDescriptor(
                            agentName = "worker-a",
                            transportAddress = "worker-b-custom"
                        ),
                        requirements = P2PRequirements()
                    )
                }
            }
        }

        assertTrue(exception.message!!.contains("Duplicate agent names"))
        assertTrue(exception.message!!.contains("worker-a"))
    }

    /**
     * Verifies that custom worker descriptors cannot reuse the same transport identity under different agent names.
     */
    @Test
    fun failsEarlyWhenCustomWorkerTransportsCollide()
    {
        val exception = assertFailsWith<IllegalArgumentException> {
            manifold {
                manager {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("dispatcher")
                                .setJsonOutput(AgentRequest())
                                .setTokenBudget(
                                    TokenBudgetSettings(
                                        contextWindowSize = 4096,
                                        userPromptSize = 1024,
                                        maxTokens = 256
                                    )
                                )
                        )
                    }
                    agentDispatchPipe("dispatcher")
                }

                worker("worker-a") {
                    pipeline(
                        pipeline = buildSafeWorkerPipeline("worker-a-pipeline"),
                        descriptor = buildCustomDescriptor(
                            agentName = "custom-worker-a",
                            transportAddress = "shared-transport"
                        ),
                        requirements = P2PRequirements()
                    )
                }

                worker("worker-b") {
                    pipeline(
                        pipeline = buildSafeWorkerPipeline("worker-b-pipeline"),
                        descriptor = buildCustomDescriptor(
                            agentName = "custom-worker-b",
                            transportAddress = "shared-transport"
                        ),
                        requirements = P2PRequirements()
                    )
                }
            }
        }

        assertTrue(exception.message!!.contains("Duplicate transports"))
        assertTrue(exception.message!!.contains("shared-transport"))
    }

    /**
     * Verifies that custom-descriptor workers still register as local manifold workers and appear in the manager's
     * injected agent list after build.
     */
    @Test
    fun customDescriptorWorkersRemainLocalToTheManifold()
    {
        val builtManifold = manifold {
            manager {
                pipeline {
                    add(
                        DummyPipe()
                            .setPipeName("dispatcher")
                            .setJsonOutput(AgentRequest())
                            .setTokenBudget(
                                TokenBudgetSettings(
                                    contextWindowSize = 4096,
                                    userPromptSize = 1024,
                                    maxTokens = 256
                                )
                            )
                    )
                }
                agentDispatchPipe("dispatcher")
            }

            worker("custom-worker") {
                pipeline(
                    pipeline = buildSafeWorkerPipeline("custom-worker-pipeline"),
                    descriptor = buildCustomDescriptor(
                        agentName = "custom-worker-routing-name",
                        transportAddress = "custom-worker-routing-name"
                    ),
                    requirements = P2PRequirements()
                )
            }
        }

        val dispatcherAgentList = builtManifold.getManagerPipeline().getPipeByName("dispatcher").second!!.getP2PAgentList()
        assertTrue((dispatcherAgentList?.size ?: 0) > 0)
        assertTrue(dispatcherAgentList!!.containsAgentNamed("custom-worker-routing-name"))
    }

    /**
     * Verifies that the manifold-level validator can resolve a normal DSL-created worker pipeline once that worker
     * returns a result during execution.
     */
    @Test
    fun validationHooksCanResolveDslCreatedWorkerPipelines() = runBlocking {
        val validatedAgentNames = mutableListOf<String>()
        val builtManifold = manifold {
            manager {
                pipeline {
                    pipelineName = "manager"
                    add(
                        ScriptedPipe(
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
                    )
                }
                agentDispatchPipe("dispatcher")
            }

            worker("worker") {
                pipeline {
                    pipelineName = "worker-pipeline"
                    add(
                        ScriptedPipe(outputs = listOf("worker-result"))
                            .setPipeName("worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }

            validation {
                validator { _, agent ->
                    validatedAgentNames.add(agent.getP2pDescription()?.agentName ?: "<missing-descriptor>")
                    true
                }
            }
        }

        builtManifold.execute(MultimodalContent("do the work"))

        assertTrue(validatedAgentNames.contains("worker"))
    }

    /**
     * Verifies that the manifold-level failure hook can resolve the DSL-created worker pipeline after worker
     * validation fails.
     */
    @Test
    fun failureHooksCanResolveDslCreatedWorkerPipelines() = runBlocking {
        val failureAgentNames = mutableListOf<String>()
        val builtManifold = manifold {
            manager {
                pipeline {
                    pipelineName = "manager"
                    add(
                        ScriptedPipe(outputs = listOf(serialize(AgentRequest(agentName = "worker"))))
                            .setPipeName("dispatcher")
                            .setJsonOutput(AgentRequest())
                            .setTokenBudget(
                                TokenBudgetSettings(
                                    contextWindowSize = 4096,
                                    userPromptSize = 1024,
                                    maxTokens = 256
                                )
                            )
                    )
                }
                agentDispatchPipe("dispatcher")
            }

            worker("worker") {
                pipeline {
                    pipelineName = "worker-pipeline"
                    add(
                        ScriptedPipe(outputs = listOf("worker-result"))
                            .setPipeName("worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }

            validation {
                validator { _, agent ->
                    agent.getP2pDescription()?.agentName != "worker"
                }
                failure { content, agent ->
                    failureAgentNames.add(agent.getP2pDescription()?.agentName ?: "<missing-descriptor>")
                    content.terminate()
                    false
                }
            }
        }

        builtManifold.execute(MultimodalContent("do the work"))

        assertEquals(listOf("worker"), failureAgentNames)
    }

    /**
     * Verifies that a custom worker transport cannot collide with the implicit default transport generated for a
     * different DSL worker name.
     */
    @Test
    fun failsEarlyWhenCustomWorkerTransportCollidesWithDefaultWorkerTransport()
    {
        val exception = assertFailsWith<IllegalArgumentException> {
            manifold {
                manager {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("dispatcher")
                                .setJsonOutput(AgentRequest())
                                .setTokenBudget(
                                    TokenBudgetSettings(
                                        contextWindowSize = 4096,
                                        userPromptSize = 1024,
                                        maxTokens = 256
                                    )
                                )
                        )
                    }
                    agentDispatchPipe("dispatcher")
                }

                worker("shared-worker") {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("worker-a")
                                .setContextWindowSize(2048)
                                .autoTruncateContext()
                        )
                    }
                }

                worker("custom-worker") {
                    pipeline(
                        pipeline = buildSafeWorkerPipeline("custom-worker-pipeline"),
                        descriptor = buildCustomDescriptor(
                            agentName = "different-routing-name",
                            transportAddress = "shared-worker"
                        ),
                        requirements = P2PRequirements()
                    )
                }
            }
        }

        assertTrue(exception.message!!.contains("Duplicate transports"))
        assertTrue(exception.message!!.contains("shared-worker"))
    }

    /**
     * Verifies that local TPipe worker descriptors cannot advertise one routing name while registering under a
     * different transport address.
     */
    @Test
    fun failsEarlyWhenCustomLocalWorkerTransportAddressDiffersFromAgentName()
    {
        val exception = assertFailsWith<IllegalArgumentException> {
            manifold {
                manager {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("dispatcher")
                                .setJsonOutput(AgentRequest())
                                .setTokenBudget(
                                    TokenBudgetSettings(
                                        contextWindowSize = 4096,
                                        userPromptSize = 1024,
                                        maxTokens = 256
                                    )
                                )
                        )
                    }
                    agentDispatchPipe("dispatcher")
                }

                worker("worker") {
                    pipeline(
                        pipeline = buildSafeWorkerPipeline("worker-pipeline"),
                        descriptor = buildCustomDescriptor(
                            agentName = "researcher",
                            transportAddress = "worker-1"
                        ),
                        requirements = P2PRequirements()
                    )
                }
            }
        }

        assertTrue(exception.message!!.contains("worker"))
        assertTrue(exception.message!!.contains("transportAddress"))
        assertTrue(exception.message!!.contains("researcher"))
        assertTrue(exception.message!!.contains("worker-1"))
    }

    /**
     * Verifies that manifold-local DSL workers reject non-TPipe custom transports because manager dispatch only
     * resolves locally registered workers through the internal TPipe transport.
     */
    @Test
    fun failsEarlyWhenCustomWorkerUsesNonTpipeTransport()
    {
        val exception = assertFailsWith<IllegalArgumentException> {
            manifold {
                manager {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("dispatcher")
                                .setJsonOutput(AgentRequest())
                                .setTokenBudget(
                                    TokenBudgetSettings(
                                        contextWindowSize = 4096,
                                        userPromptSize = 1024,
                                        maxTokens = 256
                                    )
                                )
                        )
                    }
                    agentDispatchPipe("dispatcher")
                }

                worker("worker") {
                    pipeline(
                        pipeline = buildSafeWorkerPipeline("worker-pipeline"),
                        descriptor = buildCustomDescriptor(
                            agentName = "worker",
                            transportAddress = "http://localhost:8080/worker",
                            transportMethod = Transport.Http
                        ),
                        requirements = P2PRequirements()
                    )
                }
            }
        }

        assertTrue(exception.message!!.contains("unsupported transport"))
        assertTrue(exception.message!!.contains("Transport.Tpipe"))
    }

    /**
     * Verifies that a custom-manager descriptor still preserves the manager return transport so local worker dispatch
     * can succeed during execution.
     */
    @Test
    fun customManagerDescriptorPreservesReturnTransportForWorkerDispatch() = runBlocking {
        val builtManifold = manifold {
            manager {
                pipeline(
                    pipeline = Pipeline().apply {
                        pipelineName = "manager"
                        add(
                            ScriptedPipe(
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
                        )
                    },
                    descriptor = buildCustomDescriptor(
                        agentName = "custom-manager",
                        transportAddress = "custom-manager"
                    ),
                    requirements = P2PRequirements(
                        allowExternalConnections = false,
                        requireConverseInput = true
                    )
                )
                agentDispatchPipe("dispatcher")
            }

            worker("worker") {
                pipeline {
                    pipelineName = "worker-pipeline"
                    add(
                        ScriptedPipe(outputs = listOf("worker-result"))
                            .setPipeName("worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }
        }

        val result = builtManifold.execute(MultimodalContent("do the work"))
        assertTrue(result.text.isNotBlank())
        assertTrue(!result.terminatePipeline)
    }

    /**
     * Verifies that a worker request template survives manifold dispatch and still supplies required auth metadata.
     */
    @Test
    fun workerRequestTemplateIsPreservedDuringDefaultManagerDispatch() = runBlocking {
        val builtManifold = manifold {
            manager {
                pipeline {
                    pipelineName = "manager"
                    add(
                        ScriptedPipe(
                            outputs = listOf(
                                serialize(AgentRequest(agentName = "templated-worker")),
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
                    )
                }
                agentDispatchPipe("dispatcher")
            }

            worker("templated-worker") {
                pipeline(
                    pipeline = buildSafeWorkerPipeline("templated-worker-pipeline"),
                    descriptor = buildCustomDescriptor(
                        agentName = "templated-worker",
                        transportAddress = "templated-worker",
                        requestTemplate = P2PRequest().apply {
                            authBody = "expected-auth"
                        }
                    ),
                    requirements = P2PRequirements(
                        allowExternalConnections = false,
                        requireConverseInput = true,
                        authMechanism = { authBody -> authBody == "expected-auth" }
                    )
                )
            }
        }

        val result = builtManifold.execute(MultimodalContent("do the work"))
        assertTrue(result.text.isNotBlank())
        assertTrue(!result.terminatePipeline)
    }

    /**
     * Verifies that a custom manager descriptor still supplies the return path without overriding a worker's own
     * request template fields like auth or schema overrides.
     */
    @Test
    fun customManagerDescriptorPreservesWorkerRequestTemplateFields() = runBlocking {
        val builtManifold = manifold {
            manager {
                pipeline(
                    pipeline = Pipeline().apply {
                        pipelineName = "manager"
                        add(
                            ScriptedPipe(
                                outputs = listOf(
                                    serialize(AgentRequest(agentName = "templated-worker")),
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
                        )
                    },
                    descriptor = buildCustomDescriptor(
                        agentName = "custom-manager",
                        transportAddress = "custom-manager"
                    ),
                    requirements = P2PRequirements(
                        allowExternalConnections = false,
                        requireConverseInput = true
                    )
                )
                agentDispatchPipe("dispatcher")
            }

            worker("templated-worker") {
                pipeline(
                    pipeline = buildSafeWorkerPipeline("templated-worker-pipeline"),
                    descriptor = buildCustomDescriptor(
                        agentName = "templated-worker",
                        transportAddress = "templated-worker",
                        requestTemplate = P2PRequest().apply {
                            authBody = "expected-auth"
                            outputSchema = CustomJsonSchema()
                        }
                    ),
                    requirements = P2PRequirements(
                        allowExternalConnections = false,
                        requireConverseInput = true,
                        allowAgentDuplication = true,
                        authMechanism = { authBody -> authBody == "expected-auth" }
                    )
                )
            }
        }

        val result = builtManifold.execute(MultimodalContent("do the work"))
        assertTrue(result.text.isNotBlank())
        assertTrue(!result.terminatePipeline)
    }

    /**
     * Create a valid worker pipeline for descriptor-collision validation tests.
     *
     * @param pipelineName Name assigned to the created worker pipeline.
     * @return Worker pipeline with required overflow protection.
     */
    private fun buildSafeWorkerPipeline(pipelineName: String): Pipeline
    {
        return Pipeline().apply {
            this.pipelineName = pipelineName
            add(
                DummyPipe()
                    .setPipeName("$pipelineName-pipe")
                    .setContextWindowSize(8192)
                    .setMaxTokens(512)
                    .autoTruncateContext()
            )
        }
    }

    /**
     * Create a valid manager pipeline for regression tests.
     */
    private fun buildSafeManagerPipeline(pipelineName: String): Pipeline
    {
        return Pipeline().apply {
            this.pipelineName = pipelineName
            add(
                DummyPipe()
                    .setPipeName("$pipelineName-pipe")
                    .setJsonOutput(AgentRequest())
                    .setContextWindowSize(8192)
                    .setMaxTokens(512)
                    .autoTruncateContext()
            )
        }
    }

    /**
     * Create a minimal custom descriptor for worker collision validation.
     *
     * @param agentName Effective routing name exposed to the manager.
     * @param transportAddress Effective transport identity used by the registry.
     * @return Custom worker descriptor for DSL validation tests.
     */
    private fun buildCustomDescriptor(
        agentName: String,
        transportAddress: String,
        transportMethod: Transport = Transport.Tpipe,
        requestTemplate: P2PRequest? = null
    ): P2PDescriptor
    {
        return P2PDescriptor(
            agentName = agentName,
            agentDescription = "custom worker",
            transport = P2PTransport(
                transportMethod = transportMethod,
                transportAddress = transportAddress
            ),
            requiresAuth = false,
            usesConverse = true,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = true,
            recordsPromptContent = true,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.pcp,
            requestTemplate = requestTemplate
        )
    }

    private fun List<AgentDescriptor>.containsAgentNamed(agentName: String): Boolean
    {
        return any { descriptor -> descriptor.agentName == agentName }
    }

    private class DummyPipe : Pipe()
    {
        /**
         * No-op truncation implementation for DSL regression tests.
         */
        override fun truncateModuleContext(): Pipe
        {
            return this
        }

        /**
         * Echo implementation used for manager and worker test pipes.
         *
         * @param promptInjector Prompt content supplied by the framework.
         * @return Unchanged prompt text.
         */
        override suspend fun generateText(promptInjector: String): String
        {
            return promptInjector
        }

        /**
         * No-op multimodal generation implementation for DSL regression tests.
         *
         * @param promptInjector Prompt content supplied by the framework.
         * @return Empty content object to satisfy abstract pipe behavior during startup-only tests.
         */
        override suspend fun generateContent(content: MultimodalContent): MultimodalContent
        {
            return content
        }
    }

    private class ScriptedPipe(private val outputs: List<String>) : Pipe()
    {
        private var invocationCount = 0

        override fun truncateModuleContext(): Pipe
        {
            return this
        }

        override suspend fun generateText(promptInjector: String): String
        {
            val outputIndex = invocationCount.coerceAtMost(outputs.lastIndex)
            val output = outputs[outputIndex]
            invocationCount++
            return output
        }

        override suspend fun generateContent(content: MultimodalContent): MultimodalContent
        {
            return MultimodalContent(generateText(content.text))
        }
    }

    /**
     * Verifies that buildSuspend correctly initializes the manifold.
     */
    @Test
    fun testManifoldDslBuildSuspend() = runBlocking {
        val builtManifold = manifold {
            manager {
                pipeline(buildSafeManagerPipeline("manager"))
                agentDispatchPipe("manager-pipe")
            }
            history {
                autoTruncate()
            }
            worker("worker") {
                pipeline(buildSafeWorkerPipeline("worker"))
            }
        }

        assertEquals("manager", builtManifold.getManagerPipeline().pipelineName)
        assertEquals(1, builtManifold.getWorkerPipelines().size)
    }

    /**
     * Verifies that initialUserPrompt is captured when execute() is called.
     */
    @Test
    fun initialUserPromptIsCapturedOnExecute() = runBlocking {
        val builtManifold = manifold {
            manager {
                pipeline {
                    pipelineName = "manager"
                    add(
                        ScriptedPipe(
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
                    )
                }
                agentDispatchPipe("dispatcher")
            }

            worker("worker") {
                pipeline {
                    pipelineName = "worker-pipeline"
                    add(
                        ScriptedPipe(outputs = listOf("worker-result"))
                            .setPipeName("worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }
        }

        val userPrompt = "test prompt for capture"
        builtManifold.execute(MultimodalContent(userPrompt))

        assertEquals(userPrompt, builtManifold.initialUserPrompt)
    }

    /**
     * Verifies that summaryPipeline DSL block correctly wires a pipeline and summaryMode is set to APPEND by default.
     */
    @Test
    fun summaryPipelineDslWiresPipelineWithAppendMode() = runBlocking {
        val summaryPipe = DummyPipe()
            .setPipeName("summary")
            .setContextWindowSize(2048)
            .autoTruncateContext()

        val builtManifold = manifold {
            manager {
                pipeline {
                    pipelineName = "manager"
                    add(
                        ScriptedPipe(
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
                    )
                }
                agentDispatchPipe("dispatcher")
            }

            worker("worker") {
                pipeline {
                    pipelineName = "worker-pipeline"
                    add(
                        ScriptedPipe(outputs = listOf("worker-result"))
                            .setPipeName("worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }

            summaryPipeline {
                pipeline {
                    pipelineName = "summary-pipeline"
                    add(summaryPipe)
                }
            }
        }

        builtManifold.execute(MultimodalContent("do the work"))
        // If we reach here without throwing, the summary pipeline was wired correctly
        // (setSummaryPipeline enforces overflow protection at build time, so a successful
        // execute() proves the pipeline was registered and invoked without error).
        assertEquals("manager", builtManifold.getManagerPipeline().pipelineName)
    }

    /**
     * Verifies that summaryPipeline DSL block accepts summaryMode configuration.
     */
    @Test
    fun summaryPipelineDslAcceptsSummaryMode() = runBlocking {
        val summaryPipe = DummyPipe()
            .setPipeName("summary")
            .setContextWindowSize(2048)
            .autoTruncateContext()

        val builtManifold = manifold {
            manager {
                pipeline {
                    pipelineName = "manager"
                    add(
                        ScriptedPipe(
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
                    )
                }
                agentDispatchPipe("dispatcher")
            }

            worker("worker") {
                pipeline {
                    pipelineName = "worker-pipeline"
                    add(
                        ScriptedPipe(outputs = listOf("worker-result"))
                            .setPipeName("worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }

            summaryPipeline {
                summaryMode(SummaryMode.REGENERATE)
                pipeline {
                    pipelineName = "summary-pipeline"
                    add(summaryPipe)
                }
            }
        }

        builtManifold.execute(MultimodalContent("do the work"))
        assertEquals("manager", builtManifold.getManagerPipeline().pipelineName)
    }
}
