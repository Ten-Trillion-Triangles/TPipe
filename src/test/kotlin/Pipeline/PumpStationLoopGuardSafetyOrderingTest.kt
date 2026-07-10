package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Regression test for Defect 11 (HIGH 🔴): loop-guard fires BEFORE the path-safety gate.
 *
 * In `invokePath`, the loop-guard checks at the time of cataloging
 *   1. `maxConsecutiveSamePath`
 *   2. `maxTotalPathCallsPerPath`
 * currently run BEFORE the path-safety gate. As a result, a path that the
 * path-safety gate would reject (and `return input` from) still increments the
 * `consecutivePathCount` counter. With `maxConsecutiveSamePath = 2`, dispatching
 * the same rejected path three times in a row trips `LoopGuardTripped` even
 * though the path never executed.
 *
 * Live evidence (audit 2026-07-10, stub-07 trace): loop-guard tripped on
 * safety-rejected paths. The Defect 19 catalog entry noted the same pattern in
 * production traces.
 *
 * Fix (user-approved): REORDER so the path-safety gate runs FIRST. After the
 * fix, when the gate rejects and returns early, the loop-guard counter never
 * increments and `LoopGuardTripped` is never emitted for safety-rejected paths.
 *
 * Test design (sandbox-tuned):
 *   Per skill Pitfall #N+6, directly-driven `executeLocal` tests hit a
 *   kotlinx-serialization compiler plugin wall under direct kotlinc. The
 *   `applySystemPrompt` chain at Pipe.kt:2327 calls `examplePromptFor(PathRequest::class)`
 *   and throws SerializationException without the plugin. Verified — even the
 *   existing `PathSafetyDispatchFeedbackTest.kt` fails the same way in this
 *   sandbox. The approved pivot for these defects is to drive the patched
 *   helper DIRECTLY in a unit-level call.
 *
 *   Here, the helper is `invokePathInternal` (PumpStation.kt:2413 — internal,
 *   accessible via `-Xfriend-paths`). We drive it 3 times in a row with a
 *   safety-rejecting `pathSafetyFunction` + `maxConsecutiveSamePath = 2`, then
 *   assert:
 *
 *     • `consecutivePathCountInternal` stays at the pre-call value (or 0)
 *       — proves the safety return-input happens BEFORE the guard counter runs.
 *     • `pathCallCounts[name]` stays at the pre-call value (or 0)
 *       — proves the safety return-input happens BEFORE the per-path call
 *       counter increments.
 *     • Zero `LoopGuardTripped` events fire across 3 invokePathInternal calls.
 *
 *   With the bug, `consecutivePathCount` would be 1, 2, 3 after each call and
 *   `LoopGuardTripped(guard="maxConsecutiveSamePath")` would fire on call #2.
 */
class PumpStationLoopGuardSafetyOrderingTest
{
    /**
     * Core regression: a path rejected by the safety gate at every turn must
     * not trip the `maxConsecutiveSamePath` loop guard. With the bug, the
     * guard counter increments BEFORE the safety check runs, so calling
     * `invokePathInternal` on the same rejected path twice in a row would
     * trip the guard (`consecutive >= 2`).
     *
     * After the fix, the safety gate returns early and the guard never runs.
     */
    @Test
    fun safetyRejectedPathNeverTripsLoopGuard()
    {
        runBlocking {
            // ---- Arrange ----
            val station = buildTestStation(maxHarnessTurns = 6)
                .setMaxConsecutiveSamePath(2)
                .setMaxTotalPathCallsPerPath(null)  // disable the second guard for this test
                // Path-safety gate that ALWAYS rejects. This is the canonical
                // rejection gate that doesn't require the kotlinx-serialization
                // plugin — operator's OOB correction.
                .setPathSafetyFunction { _, _, _ -> false }

            // p1 is Medium risk so the safety gate fires (gates on risk != Low).
            val path = PathObject().apply {
                pathName = "p1"
                pathDescription = "test path"
                riskLevel = PathRiskLevel.Medium
                setExecutionFunction { content, _, _, _ ->
                    MultimodalContent(text = "p1 result", context = content.context)
                }
            }
            station.addPath(path)

            val loopGuardTripped = mutableListOf<LoopGuardTripped>()
            val pathSafetyCompleted = mutableListOf<PathSafetyCompleted>()
            val seen = mutableSetOf<Pair<String, Int>>()

            station.setEventObserver { event ->
                when (event) {
                    is PathSafetyCompleted -> {
                        val key = "safety" to event.timestamp.toInt()
                        if (seen.add(key)) pathSafetyCompleted.add(event)
                    }
                    is LoopGuardTripped -> {
                        val key = "guard" to event.timestamp.toInt()
                        if (seen.add(key)) loopGuardTripped.add(event)
                    }
                    else -> {}
                }
            }

            // ---- Act ----
            // Snapshot pre-call state.
            val initialConsecutive = station.consecutivePathCountInternal
            val initialCallCounts = station.pathCallCounts.toMap()

            // Drive 3 calls. With maxConsecutiveSamePath = 2, the old code would
            // trip the loop guard on call #2. With the fix, all 3 should pass
            // through the guard normally because the safety gate has already
            // returned input before the guard runs.
            repeat(3) { iteration ->
                station.invokePathInternal(path, MultimodalContent(text = "call #$iteration"))
            }

            // ---- Assert ----

            // (a) At least one PathSafetyCompleted(approved=false) — proves the
            // safety gate RAN (would also be true on pre-fix code, so this is a
            // sanity anchor, not the key assertion).
            assertTrue(
                pathSafetyCompleted.isNotEmpty(),
                "expected at least one PathSafetyCompleted event; got ${pathSafetyCompleted.size}"
            )
            assertTrue(
                pathSafetyCompleted.any { !it.approved },
                "expected at least one PathSafetyCompleted with approved=false; " +
                    "events: ${pathSafetyCompleted.map { it.approved }}"
            )

            // (b) THE KEY ASSERTION: zero LoopGuardTripped events. With the bug,
            // calling invokePathInternal 3 times with consecutive tracking and
            // limit=2 produces at least one LoopGuardTripped on call #2
            // (consecutive climbs 1, 2 → 2 >= 2 triggers the guard).
            assertTrue(
                loopGuardTripped.isEmpty(),
                "Defect 11: loop-guard must NOT trip on safety-rejected paths. " +
                    "Got ${loopGuardTripped.size} LoopGuardTripped events: " +
                    loopGuardTripped.map { "guard=${it.guard}, detail=${it.detail}" }
            )

            // (c) THE COUNTER PIN: consecutivePathCount must remain at the
            // pre-call value (must NOT have grown). With the bug, the guard
            // counter increments BEFORE the safety check runs and would have
            // grown to 1, 2, ...; with the fix, the safety gate's
            // `return input` happens before the counter update so the counter
            // stays unchanged.
            assertTrue(
                station.consecutivePathCountInternal == initialConsecutive,
                "Defect 11: consecutivePathCount must not grow when every " +
                    "invokePathInternal call is safety-rejected. " +
                    "before=${initialConsecutive}, " +
                    "after=${station.consecutivePathCountInternal}"
            )

            // (d) THE PER-PATH PIN: pathCallCounts[p1] must remain at the
            // pre-call value. With the bug, the call counter increments BEFORE
            // the safety check runs and would have grown to 1, 2, 3; with the
            // fix, the safety gate's `return input` happens before the call
            // counter update so the counter stays unchanged.
            assertTrue(
                station.pathCallCounts.getOrDefault("p1", 0) == initialCallCounts.getOrDefault("p1", 0),
                "Defect 11: pathCallCounts[p1] must not grow when every " +
                    "invokePathInternal call is safety-rejected. " +
                    "before=${initialCallCounts["p1"] ?: 0}, " +
                    "after=${station.pathCallCounts["p1"] ?: 0}"
            )
        }
    }

    /**
     * Regression guard: when the safety gate APPROVES, the loop-guard still
     * functions as designed. With `maxConsecutiveSamePath = 2`, two
     * `invokePathInternal` calls on the same approved path trip the guard
     * exactly once.
     *
     * This pins the lower bound: the reordering fix does not break the loop
     * guard when safety is not blocking.
     */
    @Test
    fun loopGuardStillFiresWhenSafetyApproves()
    {
        runBlocking {
            val station = buildTestStation(maxHarnessTurns = 6)
                .setMaxConsecutiveSamePath(2)
                .setMaxTotalPathCallsPerPath(null)
                // Approve everything.
                .setPathSafetyFunction { _, _, _ -> true }

            val path = PathObject().apply {
                pathName = "p1"
                pathDescription = "test path"
                riskLevel = PathRiskLevel.Medium
                setExecutionFunction { content, _, _, _ ->
                    MultimodalContent(text = "p1 result", context = content.context)
                }
            }
            station.addPath(path)

            val loopGuardTripped = mutableListOf<LoopGuardTripped>()
            val seen = mutableSetOf<Pair<String, Int>>()
            station.setEventObserver { event ->
                if (event is LoopGuardTripped && event.guard == "maxConsecutiveSamePath") {
                    val key = "guard" to event.timestamp.toInt()
                    if (seen.add(key)) loopGuardTripped.add(event)
                }
            }

            // Two calls. On call #2, consecutivePathCount climbs to 2 and the
            // guard fires. With the fix, the safety gate approves and falls
            // through to path execution AND the loop guard — both run.
            station.invokePathInternal(path, MultimodalContent(text = "call #1"))
            station.invokePathInternal(path, MultimodalContent(text = "call #2"))

            // The guard should fire when safety approves (the path actually
            // executes — we increment consecutive to the limit).
            assertTrue(
                loopGuardTripped.isNotEmpty(),
                "Regression guard: when safety approves, the maxConsecutiveSamePath " +
                    "loop guard must still fire. Got 0 LoopGuardTripped events."
            )
        }
    }

    /**
     * Companion assertion: the `[Path Safety]` hint appended on rejection
     * must still reach turnHistory AFTER the reorder. This pins that we
     * moved the safety block verbatim, not logically changed it.
     */
    @Test
    fun safetyRejectionStillAppendsTurnHistoryHint()
    {
        runBlocking {
            val station = buildTestStation(maxHarnessTurns = 3)
                .setMaxConsecutiveSamePath(2)
                .setMaxTotalPathCallsPerPath(null)
                .setPathSafetyFunction { _, _, _ -> false }  // reject all

            val path = PathObject().apply {
                pathName = "p1"
                pathDescription = "test path"
                riskLevel = PathRiskLevel.Medium
                setExecutionFunction { content, _, _, _ ->
                    MultimodalContent(text = "p1 result", context = content.context)
                }
            }
            station.addPath(path)

            // One invokePathInternal call. The safety gate rejects, appends the
            // [Path Safety] hint, returns input.
            station.invokePathInternal(path, MultimodalContent(text = "first call"))

            val hintTexts = station.turnHistory.history.mapNotNull { it.content.text }
            val pathSafetyHint = hintTexts.firstOrNull { it.contains("[Path Safety]") }
            assertTrue(
                pathSafetyHint != null,
                "Defect 11: the [Path Safety] hint must still be appended to " +
                    "turnHistory after the reorder. turnHistory tail: " +
                    hintTexts.takeLast(3)
            )
            assertTrue(
                pathSafetyHint!!.contains("p1"),
                "Defect 11: the [Path Safety] hint must mention the rejected " +
                    "pathName 'p1'. Got: '$pathSafetyHint'"
            )

            // Sanity check — the reordering must NOT cause the loop guard to
            // trip when safety rejected (the KEY assertion).
            assertFalse(
                station.turnHistory.history.any {
                    it.content.text?.contains("[Harness Notice]") == true &&
                        it.content.text?.contains("consecutive") == true
                },
                "Defect 11: the reordering must not let the loop-guard fire on " +
                    "safety-rejected paths. turnHistory: " +
                    station.turnHistory.history.takeLast(3).map { it.content.text?.take(120) }
            )
        }
    }
}
