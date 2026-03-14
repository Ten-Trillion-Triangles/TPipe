package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Pipe.MultiPageBudgetStrategy
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue

private class StressTestPipe(name: String) : Pipe() {
    init {
        pipeName = name
    }
    override fun truncateModuleContext(): Pipe = this
    override suspend fun generateText(promptInjector: String): String = "stress test response"

    fun setTestMiniBank(miniBank: MiniBank) {
        this.miniContextBank = miniBank
    }

    fun getTestAllocations(budget: TokenBudgetSettings, pageKeys: List<String>): Map<String, Int> {
        this.setTokenBudget(budget)
        val truncationSettings = this.getTruncationSettings()
        return calculateSizeBasedPriorityFill(budget.contextWindowSize ?: 100000, pageKeys, truncationSettings)
    }
}

class TokenBudgetStressTest {

    @Test
    fun testConcurrencyLargeTokenCounts() = runBlocking {
        val pipe = StressTestPipe("Stress-Pipe")
        val miniBank = MiniBank()
        val pageKeys = mutableListOf<String>()

        // Create 1000 contexts with large text content
        for (i in 1..1000) {
            val key = "key_$i"
            pageKeys.add(key)
            val cw = ContextWindow()
            repeat(100) { j ->
                cw.contextElements.add("This is a large string for stress testing the token calculation algorithm, iteration $j in context $key.")
            }
            miniBank.contextMap[key] = cw
        }

        pipe.setTestMiniBank(miniBank)

        val budget = TokenBudgetSettings(
            contextWindowSize = 500000, // Very large budget
            multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL
        )

        val start = System.currentTimeMillis()

        withContext(Dispatchers.Default) {
            // Spin up 10 concurrent requests to token allocator
            val deferredList = (1..10).map {
                async {
                    pipe.getTestAllocations(budget, pageKeys)
                }
            }
            val results = deferredList.awaitAll()

            val end = System.currentTimeMillis()

            results.forEach { allocations ->
                assertTrue(allocations.isNotEmpty(), "Allocations should not be empty")
                assertTrue(allocations.values.sum() <= 500000, "Allocations should respect the total budget")
            }
            println("Stress test completed in ${end - start} ms with ${results.size} concurrent calls.")
        }
    }

    @Test
    fun testDeepNestedStructureTokenBudget() = runBlocking {
        val pipe = StressTestPipe("Nested-Stress")
        val cw = ContextWindow()

        // Deep string element construction
        val sb = StringBuilder()
        for (i in 1..5000) {
            sb.append("This is an extremely nested representation of token budgeting. It creates an artificially large and complex string block that needs to be truncated correctly without failing or exceeding boundaries. Nested count: $i ")
        }
        cw.contextElements.add(sb.toString())

        val miniBank = MiniBank()
        miniBank.contextMap["deep_key"] = cw
        pipe.setTestMiniBank(miniBank)

        val strictBudget = TokenBudgetSettings(
            maxTokens = 10,
            contextWindowSize = 100, // Very small budget relative to content
            multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL,
            truncateContextWindowAsString = true
        )

        val allocations = pipe.getTestAllocations(strictBudget, listOf("deep_key"))
        assertTrue(allocations.isNotEmpty())
        assertTrue(allocations.values.sum() <= 100)
    }
}
