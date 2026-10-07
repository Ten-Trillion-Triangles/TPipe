package com.TTT.Context

import com.TTT.Enums.ContextWindowSettings
import com.TTT.Pipe.TruncationSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression suite for the 2026-10-06 "List is empty" crash reported from Autogenesis.
 *
 * Ground truth (trace + stack): `Pipe.executeReasoningPipe` packs a single `modelReasoning`
 * element into a FRESH `ContextWindow`, calls
 * `selectAndTruncateContext(reasoningBudget, TruncateBottom, truncationSettings)` and then
 * copies the result back. Element-level truncation removes whole elements that do not fit the
 * budget, so the copy-back in Pipe.kt is null-safe and degrades to an empty reasoning stream
 * instead of throwing `NoSuchElementException`.
 *
 * Budget semantics pinned here: a window whose lorebook has no entries does not reserve half
 * the budget for an empty lorebook slot. A lone context element truncates against the FULL
 * total budget.
 *
 * Token calibration (measured, Dictionary.countTokens with default TruncationSettings):
 * probe strings tokenize at ~2 tokens per word, so
 *  - 3000 words = 6000 tokens  -> over the halved 4000 budget, under the full 8000 budget
 *  - 6000 words = 12000 tokens -> over the full 8000 budget: discarded by element truncation
 *  - 500 words  = 1000 tokens  -> well under any budget: negative control
 */
class ReasoningTruncationEmptyListTest {

    private val truncationSettings = TruncationSettings()

    /** Same construction as Pipe.kt for the Generative provider preset: reasoningBudget = 8000. */
    private val reasoningBudget = 8000

    /** 6000 tokens: over the halved 4000 budget, under the full 8000 budget. */
    private val oversizedReasoning = (1..3000).joinToString(" ") { "word${it % 997}" }

    /** 12000 tokens: over even the full 8000 budget. */
    private val untruncatableReasoning = (1..6000).joinToString(" ") { "word${it % 997}" }

    /** 1000 tokens: comfortably under any budget. */
    private val smallReasoning = (1..500).joinToString(" ") { "word${it % 997}" }

    @Test
    fun `single oversized element in bare window survives against the full budget`() {
        val window = ContextWindow()
        window.contextElements.add(oversizedReasoning)

        // Mirrors the Pipe.kt reasoning path: selectAndTruncateContext(budget, TruncateBottom, truncationSettings).
        window.selectAndTruncateContext("", reasoningBudget, ContextWindowSettings.TruncateBottom, truncationSettings)

        // No lorebook on this window means no reserved lorebook slot: the lone element is
        // truncated against the full 8000-token budget and survives intact.
        assertEquals(1, window.contextElements.size,
            "single 6000-token element must survive the full 8000-token budget")
        assertEquals(oversizedReasoning, window.contextElements.first())
    }

    @Test
    fun `dot first after truncation returns the surviving element instead of throwing`() {
        val window = ContextWindow()
        window.contextElements.add(oversizedReasoning)

        window.selectAndTruncateContext("", reasoningBudget, ContextWindowSettings.TruncateBottom, truncationSettings)

        // The original crash: the Pipe copy-back called contextElements.first() on a list that
        // the halved budget had emptied. With the full budget the element survives, so the
        // copy-back path is safe for any element that fits the budget.
        assertEquals(oversizedReasoning, window.contextElements.first())
    }

    @Test
    fun `empty lorebook selects no keys so the element takes the whole budget`() {
        val window = ContextWindow()
        window.contextElements.add(oversizedReasoning)

        // An empty lorebook selects no keys at any budget: there is nothing for the reserved
        // lorebook slot to consume, which is why the element receives the full budget instead.
        val selected = window.selectLoreBookContext("", reasoningBudget / 2)
        assertEquals(emptyList<String>(), selected,
            "empty lorebook selects no keys; the budget goes to the element instead")
    }

    @Test
    fun `element that fits the half budget survives (negative control)`() {
        val window = ContextWindow()
        window.contextElements.add(smallReasoning)

        window.selectAndTruncateContext("", reasoningBudget, ContextWindowSettings.TruncateBottom, truncationSettings)

        // 1000 tokens < 4000-token half-budget: the element survives under any split, proving the
        // mechanism is specifically about elements that exceed the allocated slot.
        assertEquals(1, window.contextElements.size, "1000-token element must survive the 4000-token half")
        assertEquals(smallReasoning, window.contextElements.first())
    }

    @Test
    fun `direct full-budget list truncation keeps the oversized element (control)`() {
        val window = ContextWindow()
        window.contextElements.add(oversizedReasoning)

        // Bypass the slot partition: ask the list truncator directly for the FULL 8000 budget.
        // 6000 tokens <= 8000 -> element survives intact. Proves the element is not
        // intrinsically untruncatable.
        window.truncateContextElements(reasoningBudget, 1, ContextWindowSettings.TruncateBottom)

        assertEquals(1, window.contextElements.size, "6000-token element survives the full 8000 budget")
        assertEquals(oversizedReasoning, window.contextElements.first())
    }

    @Test
    fun `single element in empty lorebook window keeps the full budget`() {
        val window = ContextWindow()
        window.contextElements.add(oversizedReasoning)

        window.selectAndTruncateContext("", reasoningBudget, ContextWindowSettings.TruncateBottom, truncationSettings)

        // Regression for the slot-division fix: an empty lorebook no longer claims a half, so
        // the lone element truncates against the full 8000 budget and survives.
        assertEquals(1, window.contextElements.size,
            "empty-lorebook window must give the lone context element the FULL budget, not half")
    }

    @Test
    fun `element exceeding even the full budget is discarded (element-level semantics)`() {
        val window = ContextWindow()
        window.contextElements.add(untruncatableReasoning)

        window.selectAndTruncateContext("", reasoningBudget, ContextWindowSettings.TruncateBottom, truncationSettings)

        // Element-level truncation removes whole elements rather than shrinking text: a single
        // element that does not fit the full 8000-token budget is dropped entirely. The Pipe
        // copy-back is null-safe and degrades to an empty reasoning stream in this case.
        assertEquals(0, window.contextElements.size,
            "12000-token element must be discarded against the full 8000-token budget")
    }
}
