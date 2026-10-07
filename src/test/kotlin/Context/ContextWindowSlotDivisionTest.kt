package com.TTT.Context

import com.TTT.Enums.ContextWindowSettings
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TruncationSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the slot-division behavior of `selectAndTruncateContext` / `selectAndTruncateContextSuspend`
 * after the fix that reserves budget slots only for regions actually present in the window.
 *
 * Semantics under test:
 *  - No lorebook entries + one region present (elements OR history) -> that region gets the FULL budget.
 *  - No lorebook entries + both regions present -> each gets HALF the budget.
 *  - Lorebook entries present -> legacy split is unchanged (half, half, or thirds respectively).
 *
 * Token calibration (measured, Dictionary.countTokens with default TruncationSettings):
 * probe strings tokenize at ~2 tokens per word, so
 *  - 3000 words = 6000 tokens  -> over a 4000-token half, under an 8000-token full budget
 *  - 1500 words = 3000 tokens  -> over a 2666-token third, under a 4000-token half
 */
class ContextWindowSlotDivisionTest {

    private val truncationSettings = TruncationSettings()

    private val budget = 8000

    private val halfFittingProbe = (1..1500).joinToString(" ") { "word${it % 997}" }
    private val halfExceedingProbe = (1..3000).joinToString(" ") { "word${it % 997}" }

    //=== No lorebook: single region gets the full budget =======================================

    @Test
    fun `history only in empty lorebook window gets the full budget`() {
        val window = ContextWindow()
        window.converseHistory.add(ConverseRole.user, MultimodalContent(halfExceedingProbe))

        window.selectAndTruncateContext("", budget, ContextWindowSettings.TruncateBottom, truncationSettings)

        // 6000-token history entry: discarded under the old halved 4000 slot, survives the full 8000.
        assertEquals(1, window.converseHistory.history.size,
            "history-only window with empty lorebook must get the full budget")
    }

    @Test
    fun `history only in empty lorebook window gets the full budget (suspend)`() = runBlocking {
        val window = ContextWindow()
        window.converseHistory.add(ConverseRole.user, MultimodalContent(halfExceedingProbe))

        window.selectAndTruncateContextSuspend("", budget, ContextWindowSettings.TruncateBottom, truncationSettings)

        assertEquals(1, window.converseHistory.history.size,
            "history-only window with empty lorebook must get the full budget")
    }

    //=== No lorebook: two regions split the budget in half =====================================

    @Test
    fun `elements and history in empty lorebook window split the budget in half`() {
        val window = ContextWindow()
        window.contextElements.add(halfFittingProbe)
        window.converseHistory.add(ConverseRole.user, MultimodalContent(halfFittingProbe))

        window.selectAndTruncateContext("", budget, ContextWindowSettings.TruncateBottom, truncationSettings)

        // 3000-token entries each: discarded under the old 2666-token third, survive the 4000-token half.
        assertEquals(1, window.contextElements.size, "each present region must get half the budget")
        assertEquals(1, window.converseHistory.history.size, "each present region must get half the budget")
    }

    @Test
    fun `elements and history in empty lorebook window split the budget in half (suspend)`() = runBlocking {
        val window = ContextWindow()
        window.contextElements.add(halfFittingProbe)
        window.converseHistory.add(ConverseRole.user, MultimodalContent(halfFittingProbe))

        window.selectAndTruncateContextSuspend("", budget, ContextWindowSettings.TruncateBottom, truncationSettings)

        assertEquals(1, window.contextElements.size, "each present region must get half the budget")
        assertEquals(1, window.converseHistory.history.size, "each present region must get half the budget")
    }

    //=== Lorebook present: legacy splits are preserved ==========================================

    @Test
    fun `elements only with lorebook entries still halves the budget`() {
        val window = ContextWindow()
        window.addLoreBookEntry("probe", "Probe lorebook value text.", 10)
        window.contextElements.add(halfExceedingProbe)

        window.selectAndTruncateContext("", budget, ContextWindowSettings.TruncateBottom, truncationSettings)

        // A configured lorebook reserves its slot: the 6000-token element exceeds the 4000-token
        // half and is discarded, exactly as before the fix.
        assertEquals(0, window.contextElements.size,
            "configured lorebook must still reserve its budget slot")
    }

    @Test
    fun `history only with lorebook entries still halves the budget`() {
        val window = ContextWindow()
        window.addLoreBookEntry("probe", "Probe lorebook value text.", 10)
        window.converseHistory.add(ConverseRole.user, MultimodalContent(halfExceedingProbe))

        window.selectAndTruncateContext("", budget, ContextWindowSettings.TruncateBottom, truncationSettings)

        assertEquals(0, window.converseHistory.history.size,
            "configured lorebook must still reserve its budget slot")
    }

    @Test
    fun `elements and history with lorebook entries still use thirds`() {
        val window = ContextWindow()
        window.addLoreBookEntry("probe", "Probe lorebook value text.", 10)
        window.contextElements.add(halfFittingProbe)
        window.converseHistory.add(ConverseRole.user, MultimodalContent(halfFittingProbe))

        window.selectAndTruncateContext("", budget, ContextWindowSettings.TruncateBottom, truncationSettings)

        // 3000-token entries exceed the 2666-token third: both discarded under the three-way split.
        assertEquals(0, window.contextElements.size,
            "three-way split with configured lorebook must be unchanged")
        assertEquals(0, window.converseHistory.history.size,
            "three-way split with configured lorebook must be unchanged")
    }
}
