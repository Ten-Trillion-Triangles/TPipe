package com.TTT.Context

import com.TTT.Enums.ContextWindowSettings
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TruncationSettings
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Prior-state vs current retention sweep for the half-budget reconciliation
 * (`615ea3d0`). Copies into BOTH trees (untracked, never committed): the
 * `82b0f357` worktree (exact counting, half-budget bug) and HEAD
 * (estimator + slot-division fix).
 *
 * The harness builds identical windows at a FIXED 8000-token budget, runs
 * `selectAndTruncateContext`, and records what SURVIVES. A diff of the two
 * output files (one per tree, path via RETENTION_OUT env) is the verdict:
 *
 *   S1/S2  empty-lorebook single-region windows — the fix's intent:
 *          region now gets the FULL budget where it used to get half.
 *          Expectation: current retention >= prior retention.
 *   S3     empty-lorebook two-region window — three-way split became
 *          two-way when no lorebook is present.
 *          Expectation: current retention >= prior retention (both regions).
 *   S4/S5  NON-empty-lorebook windows — the legacy half/thirds logic is
 *          UNCHANGED when a lorebook is present.
 *          Expectation: IDENTICAL retention counts, prior vs current.
 *          Any delta here is a collateral regression from the half-budget fix.
 *
 * No assertions live in the harness — it is a pure reporter. The strict gate
 * is applied to the diff of the two output files.
 */
class RetentionShapeHarness
{
    private val settings = TruncationSettings()
    private val budget = 8000

    // ~2 tokens/word probe strings (calibrated in ContextWindowSlotDivisionTest):
    // full = 6000 tokens (fits full 8000 budget, overflows half 4000 / third 2666)
    // half = 3000 tokens (fits half 4000, overflows third 2666)
    private val fullProbe = (1..3000).joinToString(" ") { "word${it % 997}" }
    private val halfProbe = (1..1500).joinToString(" ") { "word${it % 997}" }

    private fun outLine(scenario: String, window: ContextWindow): String
    {
        return "${scenario},retainedHistory=${window.converseHistory.history.size}," +
            "retainedElements=${window.contextElements.size}," +
            "retainedLore=${window.loreBookKeys.size}"
    }

    private fun report(scenario: String, window: ContextWindow)
    {
        window.selectAndTruncateContext("", budget, ContextWindowSettings.TruncateBottom, settings)
        val line = outLine(scenario, window)
        val target = File(System.getenv("RETENTION_OUT") ?: "/tmp/token-sweep/retention-default.txt")
        target.parentFile.mkdirs()
        target.appendText(line + "\n")
        println(line)
    }

    @Test
    fun `S1 empty-lorebook history-only gets the full budget`()
    {
        val w = ContextWindow()
        w.converseHistory.add(ConverseRole.user, MultimodalContent(fullProbe))
        report("S1", w)
    }

    @Test
    fun `S2 empty-lorebook elements-only get the full budget`()
    {
        val w = ContextWindow()
        // 2 elements x 3000 tokens each = 6000 tokens total: each survives a full 8000 budget
        // on its own; under the old halved 4000 slot the pair (or single element budgeting)
        // is measured against half.
        w.contextElements.add(fullProbe)
        report("S2", w)
    }

    @Test
    fun `S3 empty-lorebook two-region window splits two ways`()
    {
        val w = ContextWindow()
        w.converseHistory.add(ConverseRole.user, MultimodalContent(fullProbe))
        w.contextElements.add(halfProbe)
        report("S3", w)
    }

    @Test
    fun `S4 non-empty-lorebook history+lore window keeps legacy split`()
    {
        val w = ContextWindow()
        w.converseHistory.add(ConverseRole.user, MultimodalContent(fullProbe))
        w.loreBookKeys["anchor"] = LoreBook().apply { key = "anchor"; value = "anchor lore value" }
        report("S4", w)
    }

    @Test
    fun `S5 non-empty-lorebook elements+lore window keeps legacy split`()
    {
        val w = ContextWindow()
        w.contextElements.add(fullProbe)
        w.loreBookKeys["anchor"] = LoreBook().apply { key = "anchor"; value = "anchor lore value" }
        report("S5", w)
    }

    @Test
    fun `S6 non-empty-lorebook three-region window keeps legacy thirds`()
    {
        val w = ContextWindow()
        w.converseHistory.add(ConverseRole.user, MultimodalContent(fullProbe))
        w.contextElements.add(halfProbe)
        w.loreBookKeys["anchor"] = LoreBook().apply { key = "anchor"; value = "anchor lore value" }
        report("S6", w)
    }
}
