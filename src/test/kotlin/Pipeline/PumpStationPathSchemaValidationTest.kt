package com.TTT.Pipeline

import com.TTT.Context.ConverseRole
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Regression test for Defect 10 (HIGH 🔴): pathSchema emitted by the dispatch LLM
 * is concatenated into the path's prompt text without validation, so a malformed /
 * non-JSON schema string becomes the literal prompt the path LLM obediently
 * researches (instead of the user's research topic).
 *
 * Evidence (live trace 2026-07-10): gather-pipe received input "PathRequest" (the
 * dispatch LLM emitted `"pathSchema": "PathRequest"` as a string), and the path
 * obediently researched the string "PathRequest" rather than the actual topic.
 *
 * Fix (warn-and-continue, user-approved):
 *   PumpStationLoop.kt:611 `buildPathInput` validates the dispatch-emitted schema
 *   string. On parse failure, the harness:
 *     - appends a `[Harness Notice]` hint to `turnHistory` so the next dispatch
 *       LLM sees the constraint, AND
 *     - falls back to `path.pathSchema` (the canonical source-of-truth) as the
 *       authoritative input.
 *
 *   PumpStationHelpers.kt:873+ adds a `buildPathSchemaFallbackMessage` helper
 *   mirroring the existing `buildInvalidPathRequestMessage` `[Harness Notice]`
 *   wrapper style.
 *
 * Test design (per OOB cross-cutting rule from cage):
 *   This test verifies the WARN-AND-CONTINUE behavior of the helper-function
 *   level (defect unit-of-behavior). We CANNOT drive `executeLocal` end-to-end in
 *   this sandbox: the kotlinx-serialization compiler plugin is not wired into
 *   the direct kotlinc compile path, so the existing `applySystemPrompt` /
 *   `refreshPipelinesPrompts` call inside `P2PInit` throws a known
 *   `SerializationException` (acknowledged sandbox limitation per the approved
 *   plan).
 *
 *   Instead we drive `buildPathInput` directly as a UNIT — verify that
 *   for a dispatched PathRequest with a non-JSON `pathSchema`:
 *     1. The returned MultimodalContent.text does NOT contain the garbage schema
 *        string (instead, it falls back to the path's canonical `path.pathSchema`).
 *     2. `turnHistory.history` contains a new [Harness Notice] entry referencing
 *        the schema fallback.
 *
 *   The existing `executeLocal`-driven tests in `PumpStationDispatchDefaultsTest`
 *   cover the broader harness wiring; this test covers the precise Defect 10
 *   unit-of-behavior at the helper boundary.
 *
 *   Caveat: because `buildPathInput` is `internal` to PumpStationLoop.kt,
 *   this test relies on Kotlin's `-Xfriend-paths` compile pass to gain internal
 *   visibility. The launcher at /tmp/pumpstation_run_test.sh handles this.
 */
class PumpStationPathSchemaValidationTest
{
    /** Canonical fallback we set on the test path's `pathSchema`. */
    private val canonicalFallback = """{"type":"object","properties":{"q":{"type":"string"}}}"""

    /** Build a minimal PumpStation with one path that has the canonical schema. */
    private fun stationForBuildPathInput(): Pair<PumpStation, PathObject>
    {
        val station = PumpStation()
        val path = PathObject().apply {
            pathName = "p1"
            pathDescription = "test path"
            // The path's own canonical schema — what the harness should fall
            // back to when the dispatch LLM emits a malformed pathSchema.
            pathSchema = canonicalFallback
            // executeFunction is REQUIRED at PumpStation.kt:485 — setExecutionFunction
            // populates it. The function body is never invoked by these tests
            // (we never call executeLocal), but we set it to satisfy the harness
            // invariant.
            setExecutionFunction { content, _, _, _ ->
                MultimodalContent(text = "p1 result")
            }
        }
        station.addPath(path)
        return station to path
    }

    @Test
    fun buildPathInput_filters_non_json_dispatch_schema_and_falls_back()
    {
        // Direct unit-level call into buildPathInput. This bypasses the
        // applySystemPrompt / refreshPipelinesPrompts chain so we don't hit
        // the sandbox serialization-plugin limitation.
        runBlocking {
            val (station, path) = stationForBuildPathInput()
            val badRequest = PathRequest(
                pathName = "p1",
                pathSchema = "Hello I am not valid JSON",
                pathSelectionRationale = null
            )
            station.taskState.originalInput = MultimodalContent(text = "research Mars geology")

            val initialHistorySize = station.turnHistory.history.size

            val inbound = station.buildPathInput(path, badRequest)

            // Assertion 1: returned MultimodalContent.text must NOT contain the
            // dispatch-emitted garbage schema string.
            assertTrue(
                !inbound.text.contains("Hello I am not valid JSON"),
                "Defect 10: buildPathInput must filter non-JSON dispatch schemas. " +
                    "Pre-fix behavior concatenates the garbage schema into the " +
                    "path LLM's prompt verbatim. Inbound text excerpt: ${inbound.text.take(400)}"
            )

            // Assertion 2: the path LLM DID get the canonical schema (via the
            // fallback path), and the user's original input is also embedded.
            assertTrue(
                inbound.text.contains("research Mars geology"),
                "Defect 10: path LLM must receive the user's original input even " +
                    "when the dispatch emits a malformed schema. Inbound text: ${inbound.text.take(400)}"
            )
            // The fallback schema's distinguishing substring.
            assertTrue(
                inbound.text.contains("\"q\"") || inbound.text.contains("\"properties\""),
                "Defect 10: path LLM must receive the path's canonical schema " +
                    "(fallback) when the dispatch emits a malformed schema. " +
                    "Inbound text: ${inbound.text.take(400)}"
            )

            // Assertion 3: turnHistory grew because the harness appended a
            // [Harness Notice] hint so the next dispatch LLM sees the constraint.
            assertTrue(
                station.turnHistory.history.size > initialHistorySize,
                "Defect 10: turnHistory must grow when buildPathInput filters a " +
                    "malformed dispatch schema — the [Harness Notice] hint is " +
                    "appended to history."
            )

            // Assertion 4: at least one of the appended entries carries the
            // [Harness Notice] wrapper AND references pathSchema, so the next
            // dispatch LLM learns to leave the field blank.
            val hintEntries = station.turnHistory.history.filter { cd ->
                cd.role == ConverseRole.user &&
                    cd.content.text.contains("[Harness Notice]", ignoreCase = true) &&
                    cd.content.text.contains("pathSchema", ignoreCase = true)
            }
            assertTrue(
                hintEntries.isNotEmpty(),
                "Defect 10: turnHistory must contain at least one [Harness Notice] " +
                    "entry referencing pathSchema. Got history tail: " +
                    station.turnHistory.history.takeLast(3).map { it.content.text.take(120) }
            )
        }
    }

    @Test
    fun buildPathInput_passes_through_valid_json_dispatch_schema()
    {
        // Regression guard: a dispatch-emitted schema that IS valid JSON should
        // pass through unchanged — the validity check must not reject legitimate
        // runtime-customized schemas.
        runBlocking {
            val (station, path) = stationForBuildPathInput()
            val validSchema = """{"type":"object","properties":{"q":{"type":"string"}}}"""
            val goodRequest = PathRequest(
                pathName = "p1",
                pathSchema = validSchema,
                pathSelectionRationale = null
            )
            station.taskState.originalInput = MultimodalContent(text = "research Mars geology")

            val initialHistorySize = station.turnHistory.history.size

            val inbound = station.buildPathInput(path, goodRequest)

            // Valid dispatch schemas pass through; no [Harness Notice] appended.
            assertTrue(
                !station.turnHistory.history.drop(initialHistorySize).any { cd ->
                    cd.content.text.contains("[Harness Notice]", ignoreCase = true) &&
                        cd.content.text.contains("pathSchema", ignoreCase = true)
                },
                "Regression: a valid dispatch-emitted schema must NOT trigger " +
                    "the fallback hint. History tail: " +
                    station.turnHistory.history.takeLast(3).map { it.content.text.take(120) }
            )

            // And the dispatch's valid schema string is in the inbound prompt.
            assertTrue(
                inbound.text.contains(validSchema),
                "Regression: valid dispatch schema must be merged into the " +
                    "path LLM's prompt. Inbound text: ${inbound.text.take(400)}"
            )
        }
    }

    @Test
    fun buildPathInput_uses_path_canonical_schema_when_dispatch_blank()
    {
        // When the dispatch emits an EMPTY pathSchema, the harness falls back to
        // path.pathSchema (canonical source). This documents the existing
        // behavior (also pre-fix-correct) — the test pins it as a baseline.
        runBlocking {
            val (station, path) = stationForBuildPathInput()
            val request = PathRequest(
                pathName = "p1",
                pathSchema = "",  // empty → fallback
                pathSelectionRationale = null
            )
            station.taskState.originalInput = MultimodalContent(text = "research Mars geology")

            val inbound = station.buildPathInput(path, request)

            // The path's canonical schema is the only schema present (no dispatch
            // garbage, no [Harness Notice] appended for an empty (legitimate) schema).
            assertTrue(
                inbound.text.contains(canonicalFallback),
                "Baseline: empty dispatch pathSchema must fall back to path's " +
                    "canonical schema. Inbound text: ${inbound.text.take(400)}"
            )
            assertTrue(
                !inbound.text.contains("[Harness Notice]"),
                "Baseline: empty dispatch pathSchema must NOT trigger a " +
                    "[Harness Notice] — empty is the legitimate default. " +
                    "Inbound text: ${inbound.text.take(400)}"
            )
        }
    }
}
