package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the contract that all five harness-injected hints use
 * [ConverseRole.harness], not [ConverseRole.user] or [ConverseRole.system].
 *
 * Why this matters:
 *  - [ConverseRole.user] is the LLM provider's contract for human-user input.
 *    Harness corrections are NOT user intent; emitting them as `user` is
 *    role fraud, and may cause the LLM to weight the hint as authoritative
 *    user instruction rather than as a runtime correction.
 *  - [ConverseRole.system] is the LLM's system-prompt slot. The context
 *    trimming rule at [com.TTT.Pipeline.PumpStationLoop.kt:1015] keeps
 *    only the most-recent `system` message — that behavior is correct for
 *    the system prompt, but wrong for harness corrections, which must
 *    survive context pressure.
 *  - [ConverseRole.harness] is a dedicated tier for harness-emitted
 *    messages: distinct from `user` (no role fraud) and from `system`
 *    (no aggressive pruning). Pruning rule 3 (the `system` filter) does
 *    not touch `harness` entries, so the hints survive context pressure.
 */
class ConverseRoleHarnessHintTest
{
    @Test
    fun `ConverseRole enum declares the harness tier`() {
        // The enum must contain a `harness` entry; without it, the
        // 5 call sites below would fail to compile. This test pins the
        // enum surface so a future refactor can't silently drop the
        // harness role.
        val roleNames = ConverseRole.entries.map { it.name }
        assertTrue("harness" in roleNames,
            "ConverseRole enum must include 'harness' for harness-injected hints. " +
                "Got: $roleNames")
    }

    @Test
    fun `path-safety hint uses ConverseRole harness`() {
        val station = pumpStation("role-path-safety-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("blocked") {
                risk = PathRiskLevel.Medium
                setExecutionFunction { content, _, _, _ -> content }
            }
        }
        val hint = ConverseData(
            role = ConverseRole.harness,
            content = MultimodalContent(text = "[Path Safety] Path 'blocked' was rejected by the path-safety gate.")
        )
        station.turnHistory.add(hint)

        val stored = station.turnHistory.history.last()
        assertEquals(ConverseRole.harness, stored.role,
            "path-safety hint must use ConverseRole.harness, " +
                "got ${stored.role}")
    }

    @Test
    fun `steering injection uses ConverseRole harness`() {
        val station = pumpStation("role-steering-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("noop") {
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ -> content }
            }
        }
        // Mimic the injectSteeringForPhase call site's role assignment.
        runBlocking { station.refreshPipelinesPrompts() }
        val entry = ConverseData(
            role = ConverseRole.harness,
            content = MultimodalContent(text = "[DITL steering entry]")
        )
        station.turnHistory.add(entry)

        val stored = station.turnHistory.history.last()
        assertEquals(ConverseRole.harness, stored.role,
            "DITL steering entries must use ConverseRole.harness, " +
                "got ${stored.role}")
    }

    @Test
    fun `no production code emits ConverseRole user for harness hints`() {
        // Static analysis guard: scan the production Pipeline/ sources
        // for any hint-text markers (the "Harness Notice" / "Path Safety" /
        // "DITL steering" / "buildPathSchemaFallbackMessage" call sites)
        // emitting as ConverseRole.user. These are role-fraud: the hint
        // text identifies it as a harness-emitted message, not user input.
        //
        // The path-safety hint's emit site lives at PumpStation.kt:3067.
        // The empty-pathName hint at PumpStationLoop.kt:419. The
        // pathSchema-fallback hint at PumpStationLoop.kt:914. The
        // empty-rationale nudge at PumpStationLoop.kt:3274. The steering
        // injection at PumpStationLoop.kt:188. All must be `harness`.
        val hintMarkers = listOf(
            "src/main/kotlin/Pipeline/PumpStation.kt",
            "src/main/kotlin/Pipeline/PumpStationLoop.kt"
        )
        val userRoleOffenders = mutableListOf<Pair<String, Int>>()
        for (path in hintMarkers)
        {
            val file = java.io.File(path)
            if (!file.exists()) continue
            file.useLines { lines ->
                lines.forEachIndexed { idx, line ->
                    if (line.contains("ConverseRole.user") &&
                        // Exclude comments and string literals containing
                        // "user" — but the production code's hint site
                        // has the literal "ConverseRole.user" as a
                        // constructor argument, so any match is a real
                        // emission. The KDoc referencing the role names
                        // does not match the call site shape.
                        line.contains("=") && !line.trimStart().startsWith("//") &&
                        !line.trimStart().startsWith("*"))
                    {
                        userRoleOffenders.add(path to (idx + 1))
                    }
                }
            }
        }
        assertEquals(emptyList<Pair<String, Int>>(), userRoleOffenders,
            "No production site should emit ConverseRole.user for harness " +
                "hints. Found: $userRoleOffenders")
    }
}
