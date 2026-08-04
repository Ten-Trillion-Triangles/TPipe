package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the "magic contract opt-out" behavior. The pump station's default
 * prompts ask the agent to emit a specific JSON shape (JudgeVerdict JSON,
 * path-safety {"safe": bool} JSON). That's the auto-injected contract, same
 * pattern as dispatch.
 *
 * A developer who writes a custom agent that doesn't follow the JSON contract
 * can disable the JSON parser (set [PumpStation.judgeExpectsJsonContract] /
 * [PumpStation.pathSafetyExpectsJsonContract] to false) and the harness will
 * only read MultimodalContent flags. This is the canonical loop-control pattern
 * documented on [checkMultimodalFlags] and [FlagCheckResult]: "agents signal
 * via flags, not via magic contracts."
 */
class MagicContractOptOutTest
{
    //=====================================Judge contract gating=====================================================

    @Test
    fun judgeJsonParserRunsByDefault()
    {
        val station = buildTestStation()
        // Disable the first-turn skip guard so the judge actually runs and
        // we can observe the parser's verdict. Without this, runJudgePhase()
        // returns JudgeVerdict.empty() on turn 0 (the default skip behaviour)
        // and the test's isComplete assertion fails.
        station.setSkipJudgeOnFirstTurn(false)
        val judgePipe = ScriptedTestPipe(response = """{"isComplete": true, "shouldTerminate": false}""")
        val judge = Pipeline().apply { add(judgePipe) }
        station.setJudgeAgent(judge)
        // Default: judgeExpectsJsonContract is true, so the JSON parser runs.
        assertTrue(station.judgeExpectsJsonContract, "Default should be true to preserve existing behavior")

        runBlocking {
            val verdict = station.runJudgePhase()
            assertTrue(verdict.isComplete, "Default mode: JSON is parsed, isComplete=true is honored")
        }
    }

    @Test
    fun judgeJsonParserSkippedWhenContractDisabled()
    {
        val station = buildTestStation()
        // Agent returns text saying isComplete=true, but no flags.
        val judgePipe = ScriptedTestPipe(response = """{"isComplete": true, "shouldTerminate": false}""")
        val judge = Pipeline().apply { add(judgePipe) }
        station.setJudgeAgent(judge)
        // Opt out of the JSON contract.
        station.setJudgeJsonContractEnabled(false)

        runBlocking {
            val verdict = station.runJudgePhase()
            // JSON parser was skipped, so isComplete=true was ignored.
            // No flags were set either, so the verdict is empty.
            assertFalse(verdict.isComplete, "Contract disabled: JSON is ignored, only flags drive the verdict")
            assertFalse(verdict.shouldHalt, "Contract disabled: no flags set, no halt")
        }
    }

    @Test
    fun judgeFlagsDriveVerdictWhenContractDisabled()
    {
        val station = buildTestStation()
        // Disable the first-turn skip guard so the judge actually runs and
        // the post-judge hook fires. Without this, runJudgePhase() returns
        // JudgeVerdict.empty() on turn 0 and the test's shouldHalt assertion
        // fails.
        station.setSkipJudgeOnFirstTurn(false)
        // Agent returns text saying isComplete=false, but a post-judge hook sets terminatePipeline.
        val judgePipe = ScriptedTestPipe(response = """{"isComplete": false, "shouldTerminate": false}""")
        val judge = Pipeline().apply { add(judgePipe) }
        station.setJudgeAgent(judge)
        // Opt out of the JSON contract; flags will drive the verdict.
        station.setJudgeJsonContractEnabled(false)
        // Simulate an agent that uses flags instead of JSON: set terminatePipeline via the post-judge hook.
        station.setPostJudgeFunction { content, _ ->
            content.copy(terminatePipeline = true)
        }

        runBlocking {
            val verdict = station.runJudgePhase()
            // JSON parser was skipped (the contract is disabled). withFlagCheck reads the
            // terminatePipeline flag set by the post-judge hook and forces shouldHalt.
            assertTrue(verdict.shouldHalt, "Contract disabled: terminatePipeline flag should halt the harness")
        }
    }

    //=====================================Path-safety contract gating==============================================

    @Test
    fun pathSafetyJsonParserRunsByDefault()
    {
        val station = buildTestStation()
        val path = testPath("test", returnText = "ran")
        path.riskLevel = PathRiskLevel.Medium
        // pathSafetyAgent returns {"safe": false} with no flags.
        val safetyAgent = MockP2PAgent(script = listOf(
            MultimodalContent(text = """{"safe": false, "reason": "rejected"}""")
        ))
        station.setPathSafetyAgent(safetyAgent)
        // Default: pathSafetyExpectsJsonContract is true.
        assertTrue(station.pathSafetyExpectsJsonContract, "Default should be true to preserve existing behavior")

        runBlocking {
            val approved = station.checkPathSafety(path, MultimodalContent(text = "input"))
            assertFalse(approved, "Default mode: JSON is parsed, safe=false means rejected")
        }
    }

    @Test
    fun pathSafetyJsonParserSkippedWhenContractDisabled()
    {
        val station = buildTestStation()
        val path = testPath("test", returnText = "ran")
        path.riskLevel = PathRiskLevel.Medium
        // Safety agent returns {"safe": false} but no flags.
        val safetyAgent = MockP2PAgent(script = listOf(
            MultimodalContent(text = """{"safe": false, "reason": "rejected"}""")
        ))
        station.setPathSafetyAgent(safetyAgent)
        // Opt out of the JSON contract.
        station.setPathSafetyJsonContractEnabled(false)

        runBlocking {
            val approved = station.checkPathSafety(path, MultimodalContent(text = "input"))
            // JSON parser was skipped, so safe=false was ignored.
            // The flag fallback `!(terminatePipeline || passPipeline)` returns true (approve).
            assertTrue(approved, "Contract disabled: JSON is ignored, flag fallback approves the path")
        }
    }

    @Test
    fun pathSafetyFlagsDriveVerdictWhenContractDisabled()
    {
        val station = buildTestStation()
        val path = testPath("test", returnText = "ran")
        path.riskLevel = PathRiskLevel.Medium
        // Safety agent returns garbage text but sets terminatePipeline = true.
        val safetyAgent = MockP2PAgent(script = listOf(
            MultimodalContent(text = "the model is unsure", terminatePipeline = true)
        ))
        station.setPathSafetyAgent(safetyAgent)
        // Opt out of the JSON contract.
        station.setPathSafetyJsonContractEnabled(false)

        runBlocking {
            val approved = station.checkPathSafety(path, MultimodalContent(text = "input"))
            // JSON parser was skipped, so we go straight to the flag fallback.
            // terminatePipeline=true -> !(true || false) -> false -> rejected.
            assertFalse(approved, "Contract disabled: terminatePipeline flag should reject the path")
        }
    }
}
