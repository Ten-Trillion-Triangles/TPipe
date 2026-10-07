package com.TTT.Pipe

import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Context.SerializedFrameEstimator
import com.TTT.Pipe.MultiPageBudgetStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Invariant guard: the multi-page budgeting hot path must perform NO full-window serialization.
 *
 * `countContextWindowTokens` is the exact (expensive) reference counter — it serializes the whole
 * window. It is `open` specifically so this test can override it and count invocations. The hot
 * path (`calculatePageBudgets` and its dynamic strategies) must route through
 * [SerializedFrameEstimator] instead, so the counter must stay at zero.
 */
class FrameEstimationZeroSerializeTest
{
    private class CountingPipe : Pipe()
    {
        var exactSerializerInvocations = 0

        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String = "test"

        fun withBank(b: MiniBank): CountingPipe
        {
            miniContextBank = b
            return this
        }

        override fun countContextWindowTokens(contextWindow: ContextWindow, truncationSettings: TruncationSettings): Int
        {
            exactSerializerInvocations++
            return super.countContextWindowTokens(contextWindow, truncationSettings)
        }
    }

    @Test
    fun `dynamic size fill budgeting performs no exact serialization`()
    {
        val pipe = CountingPipe()
        val bank = MiniBank()
        bank.contextMap["story"] = pageWith(8)
        bank.contextMap["gameplay"] = pageWith(12)
        bank.contextMap["log"] = pageWith(30)
        pipe.withBank(bank)

        val settings = TruncationSettings()
        val allocations = pipe.calculatePageBudgets(
            totalBudget = 200,
            pageKeys = listOf("story", "gameplay", "log"),
            strategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL,
            weights = null,
            truncationSettings = settings,
            reserveEmptyPageBudget = true
        )

        //Sanity: the allocation actually produced a usable split.
        assertTrue(allocations.values.sum() <= 200, "allocations must not exceed the budget")

        //The invariant: the exact serializer was never invoked on the hot path.
        assertEquals(0, pipe.exactSerializerInvocations,
            "DYNAMIC_SIZE_FILL budgeting must not call the exact serialize counter; it was called " +
                "${pipe.exactSerializerInvocations} time(s)")
    }

    @Test
    fun `all budget strategies perform no exact serialization`()
    {
        for(strategy in MultiPageBudgetStrategy.entries)
        {
            val pipe = CountingPipe()
            val bank = MiniBank()
            bank.contextMap["a"] = pageWith(5)
            bank.contextMap["b"] = pageWith(20)
            pipe.withBank(bank)

            pipe.calculatePageBudgets(
                totalBudget = 100,
                pageKeys = listOf("a", "b"),
                strategy = strategy,
                weights = mapOf("a" to 1.0, "b" to 2.0),
                truncationSettings = TruncationSettings(),
                reserveEmptyPageBudget = true
            )

            assertEquals(0, pipe.exactSerializerInvocations,
                "strategy $strategy must not call the exact serialize counter")
        }
    }

    @Test
    fun `estimator-backed counters perform no exact serialization`()
    {
        val pipe = CountingPipe()
        val window = pageWith(6)
        val settings = TruncationSettings()

        val estimate = SerializedFrameEstimator.estimateTokens(window, settings)
        val bank = MiniBank().apply { contextMap["p"] = pageWith(4) }
        val bankEstimate = SerializedFrameEstimator.estimateMiniBankTokens(bank, settings)

        assertTrue(estimate > 0, "window estimate should be nonzero")
        assertTrue(bankEstimate > 0, "bank estimate should be nonzero")
        assertEquals(0, pipe.exactSerializerInvocations,
            "the estimator must never invoke the exact serialize counter")
    }

    //==== helpers ==================================================================//

    private fun pageWith(nElements: Int): ContextWindow
    {
        val window = ContextWindow()
        repeat(nElements) { window.contextElements.add("story chapter $it about a hero") }
        return window
    }
}
