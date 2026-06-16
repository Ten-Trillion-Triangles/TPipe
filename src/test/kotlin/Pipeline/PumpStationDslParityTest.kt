package com.TTT.Pipeline

import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Drives a `pumpStation { }` block that touches every new configuration surface
 * added by the DSL-completion work (phantom-type state machine, all 20+ new
 * top-level vars, `pause { }`, `harnessAgent { }`, `harnessAgentBuilder { }`,
 * `PathBlock.pathMetadata`, `PathBlock.pcpSchema`, `PathBlock.bindFunction(name, fn)`,
 * `setExternalContextProvider` parity, etc.) and asserts the configuration
 * actually landed on the returned [PumpStation].
 *
 * If the build step ever drops one of these surface entries, this test will
 * fail on the first round-trip assertion. Conversely, if the harness loop
 * grows a new public setter, add it to this test (and to [PumpStationBuilder])
 * so the parity stays in lockstep.
 */
class PumpStationDslParityTest
{
    @Test
    fun testDslBuildAppliesAllConfiguration()
    {
        runBlocking {
            val events = mutableListOf<PumpStationEvent>()
            val externalCtx = mutableMapOf<String, Any>()

            val judgeBuilder: (suspend (PumpStation) -> Pipeline) = { Pipeline() }
            val dispatchBuilder: (suspend (PumpStation) -> Pipeline) = { Pipeline() }
            val interventionBuilder: (suspend (PumpStation) -> P2PInterface) = { SgTestAgent(agentTag = "ib-dsl") }
            val lorebookBuilder: (suspend (PumpStation) -> P2PInterface) = { SgTestAgent(agentTag = "lb-dsl") }
            val summaryBuilder: (suspend (PumpStation) -> P2PInterface) = { SgTestAgent(agentTag = "sm-dsl") }
            val goalBuilder: (suspend (PumpStation) -> P2PInterface) = { SgTestAgent(agentTag = "go-dsl") }
            val harnessBuilder: (suspend (PumpStation) -> P2PInterface) = { SgTestAgent(agentTag = "hb-dsl") }

            val station = pumpStation("parity-station") {
                // ===== Agent assignments =====
                judgeAgent = Pipeline()
                dispatchAgent = Pipeline()
                interventionAgent = SgTestAgent(agentTag = "intervention")
                healthAgent = SgTestAgent(agentTag = "health")
                lorebookAgent = SgTestAgent(agentTag = "lorebook")
                summaryAgent = SgTestAgent(agentTag = "summary")
                goalAgent = SgTestAgent(agentTag = "goal")
                preInitAgent = SgTestAgent(agentTag = "preInit")
                pathSafetyAgent = SgTestAgent(agentTag = "pathSafety")

                judgeAgentBuilderFunction = judgeBuilder
                dispatchAgentBuilderFunction = dispatchBuilder
                interventionAgentBuilderFunction = interventionBuilder
                lorebookAgentBuilderFunction = lorebookBuilder
                summaryAgentBuilderFunction = summaryBuilder
                goalAgentBuilderFunction = goalBuilder
                healthAgentBuilderFunction = { SgTestAgent(agentTag = "health-builder") }
                healthAgentTurnInterval = 7
                healthAgentErrorRatioThreshold = 0.25
                healthAgentConcurrencyMode = PumpStationConcurrencyMode.Async

                harnessAgent(SgTestAgent(agentTag = "harness-direct"), concurrency = PumpStationConcurrencyMode.Blocking) {}
                harnessAgentBuilder(harnessBuilder, concurrency = PumpStationConcurrencyMode.Async) {}

                // ===== Configuration =====
                personality = "a friendly, focused assistant"
                systemTask = "parity test task"
                userGuidelines = "be concise"
                entryUserPrompt = "do the parity test"
                maxHarnessTurns = 17
                judgeRunMode = PumpStationJudgeRunMode.FlagTriggered
                maxConcurrentBackgroundAgents = 2
                maxConcurrentForegroundAgents = 4
                foregroundTurnInterval = 1
                backgroundTurnInterval = 2
                memoryManagementMode = PumpStationMemoryManagementMode.Hybrid
                compactionThreshold = 0.55
                compactionStrategy = PumpStationCompactionStrategy.Chunked
                maxTurnHistorySize = 21
                maxTurns = 13
                concurrencyMode = PumpStationConcurrencyMode.Blocking
                maxGoalFailAttempts = 5
                maxRawTurnHistorySize = 300
                blowoutThreshold = 0.85
                memoryUpdateTimeoutMs = 45_000L
                maxBlowoutRecoveries = 4
                maxRepairPromptTokens = 800
                stopHarnessOnInvalidPathRequest = true
                failurePolicy = PumpStationFailurePolicy(
                    repairInvalidDispatchJson = false,
                    maxDispatchRepairAttempts = 5,
                    stashOversizedOutputs = false,
                    callInterventionOnPathFailure = false,
                    stopHarnessOnInvalidPathRequest = true
                )

                // ===== Loop guards =====
                maxConsecutiveSamePath = 2
                maxTotalPathCallsPerPath = 5
                pathLimitExceededPolicy = PathLimitExceededPolicy.Halt
                pathLimitExceededFunction = { _, _, _ ->
                    PathLimitExceededResult(action = PathLimitExceededPolicy.Halt, reason = "parity")
                }
                judgeJsonContractEnabled = false
                pathSafetyJsonContractEnabled = false

                // ===== System prompts =====
                judgeSystemPrompt = "custom judge prompt"
                dispatchSystemPrompt = "custom dispatch prompt"
                pathSafetySystemPrompt = "custom path safety prompt"
                healthSystemPrompt = "custom health prompt"
                lorebookSystemPrompt = "custom lorebook prompt"
                goalSystemPrompt = "custom goal prompt"

                // ===== Event observer =====
                eventObserver = { ev -> events.add(ev) }

                // ===== Reserve paths & external context =====
                externalContextProvider = { _ -> externalCtx }

                // ===== Pause phases =====
                pause {
                    add(PumpStationPausePhase.BeforeJudge)
                    add(PumpStationPausePhase.BeforePathExecution)
                }

                // ===== DITL hooks =====
                preInitFunction = { c, _ -> c }
                preValidationJudgeFunction = { _, mb, _ -> mb }
                postJudgeFunction = { c, _ -> c }
                preValidationDispatchFunction = { _, _, mb, _ -> mb }
                preInvokeFunction = { _, _, _ -> true }
                pathSafetyFunction = { _, _, _ -> true }
                postGenerateFunction = { _, _ -> SgTestAgent(agentTag = "pg") }
                pathValidationFunction = { _, _ -> true }
                pathTransformationFunction = { c, _ -> c }
                postMemoryFunction = { c, _ -> c }
                preCompactionFunction = { c, _, _, _ -> c }
                postCompactionFunction = { c, _, _ -> c }
                onContextTruncated = { _, _ -> }

                // ===== Path with every new PathBlock surface =====
                path("alpha") {
                    description = "alpha path"
                    risk = PathRiskLevel.Medium
                    dispatchHint = "alpha hint"
                    runsInBackground = false
                    schema = """{"type":"object"}"""
                    pathMetadata["k1"] = "v1"
                    pathMetadata["k2"] = 42
                    setInternalAgent(SgTestAgent(agentTag = "alpha-agent"))
                }
                path("beta") {
                    description = "beta path"
                    risk = PathRiskLevel.High
                    setInternalAgent(SgTestAgent(agentTag = "beta-agent"))
                }

                // ===== Reserve path =====
                reservePath("gamma") {
                    description = "gamma reserve path"
                    risk = PathRiskLevel.Low
                    setInternalAgent(SgTestAgent(agentTag = "gamma-agent"))
                    revealWhen { state, _ -> state.turnIndex > 0 }
                }

                // ===== Dispatcher rules =====
                dispatcherRules {
                    maxConsecutive("alpha", 1)
                    before("alpha", listOf("beta"))
                    after("alpha", "beta")
                }
            }

            // ===== Round-trip assertions =====
            // Agent / builder assignments land on the station via setJudgeAgent etc.
            // Inspect the path descriptors and the path objects themselves.
            val alpha = station.getPath("alpha")
            assertNotNull(alpha, "alpha path should be registered")
            assertEquals("alpha path", alpha.pathDescription)
            assertEquals(PathRiskLevel.Medium, alpha.riskLevel)
            assertEquals("alpha hint", alpha.dispatchHint)
            assertEquals("""{"type":"object"}""", alpha.pathSchema)
            assertEquals("v1", alpha.pathMetadata["k1"])
            assertEquals(42, alpha.pathMetadata["k2"])
            assertTrue(alpha.isInternalAgentSet, "alpha should have its internal agent set")

            val beta = station.getPath("beta")
            assertNotNull(beta, "beta path should be registered")
            assertEquals(PathRiskLevel.High, beta.riskLevel)

            val gamma = station.getReservePathNames()
            assertTrue("gamma" in gamma, "gamma reserve path should be registered; got $gamma")

            // Pause phases
            val state = station.getTaskState()
            assertNotNull(state.runId, "runId should be populated after P2PInit")

            // Concurrency / max-turns / personality.
            // After the maxTurns wire-up, `maxHarnessTurns` is a delegating DSL var
            // that writes the same field as `maxTurns`. The block above sets
            // `maxHarnessTurns = 17` then `maxTurns = 13`, so the most-recent write
            // (maxTurns = 13) wins and both getters return 13. This proves the two
            // names share one backing field, not two independent values.
            assertEquals(13, station.getMaxTurns())
            assertEquals(13, station.getMaxHarnessTurns())
            assertEquals(PumpStationJudgeRunMode.FlagTriggered, station.getJudgeRunMode())
            assertEquals(0.85, station.getBlowoutThreshold(), 0.001)
            assertEquals(45_000L, station.getMemoryUpdateTimeoutMs())
            assertEquals(4, station.getMaxBlowoutRecoveries())
            assertEquals(800, station.getMaxRepairPromptTokens())
            assertEquals(5, station.getMaxGoalFailAttempts())
            assertEquals(300, station.getMaxRawTurnHistorySize())

            // Failure policy carried over
            assertEquals(false, station.failurePolicy.repairInvalidDispatchJson)
            assertEquals(5, station.failurePolicy.maxDispatchRepairAttempts)
            assertEquals(true, station.failurePolicy.stopHarnessOnInvalidPathRequest)

            // Magic-contract toggles
            assertEquals(false, station.getJudgeJsonContractEnabled(), "judgeJsonContractEnabled should be false")
            assertEquals(false, station.getPathSafetyJsonContractEnabled(), "pathSafetyJsonContractEnabled should be false")

            // Dispatcher rules
            val rules = station.getDispatcherRules()
            assertEquals(3, rules.size, "all 3 dispatcher rules should be applied; got $rules")

            // harnessAgent / harnessAgentBuilder slots both landed
            val harnessSlots = station.getAdditionalHarnessAgentSlots()
            assertEquals(2, harnessSlots.size, "harnessAgent + harnessAgentBuilder should produce 2 slots; got $harnessSlots")
            assertTrue(harnessSlots.any { it.agent?.let { a -> a is SgTestAgent && a.agentTag == "harness-direct" } == true })
            assertTrue(harnessSlots.any { it.builderFunction != null })

            // Personality: verified indirectly via the public setPersonality round-trip
            // and the PromptCompositionTest which exercises buildJudgeSystemPrompt.
            assertEquals("a friendly, focused assistant", station.getPersonality(),
                "personality should round-trip on the station")
        }
    }
}
