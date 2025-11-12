package com.TTT

import com.TTT.Pipe.TruncationSettings
import com.TTT.Context.Dictionary
import com.TTT.P2P.AgentRequest
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PRequest
import com.TTT.Pipe.MultiPageBudgetStrategy
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.toTokenBudgetSettings
import com.TTT.Pipe.toTruncationSettings
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiPageIntegrationGapTest {

    @Test
    fun testTruncationSettingsMultiPageIntegration() {
        val settings = TruncationSettings().apply {
            multiPageBudgetStrategy = MultiPageBudgetStrategy.PRIORITY_FILL
            pageWeights = mapOf("primary" to 2.0)
        }

        val budget = settings.toTokenBudgetSettings(contextWindowSize = 1200, userPromptSize = 50, maxTokens = 200)
        assertEquals(MultiPageBudgetStrategy.PRIORITY_FILL, budget.multiPageBudgetStrategy)
        assertEquals(settings.pageWeights, budget.pageWeights)

        val roundTrip = budget.toTruncationSettings()
        assertEquals(MultiPageBudgetStrategy.PRIORITY_FILL, roundTrip.multiPageBudgetStrategy)
        assertEquals(settings.pageWeights, roundTrip.pageWeights)
    }

    @Test
    fun testP2PMultiPageRequirements() {
        val settings = TruncationSettings().apply {
            multiPageBudgetStrategy = MultiPageBudgetStrategy.WEIGHTED_SPLIT
            pageWeights = mapOf("primary" to 3.0)
        }

        val requirements = P2PRequirements(
            multiPageBudgetSettings = settings.toTokenBudgetSettings(maxTokens = 10),
            allowMultiPageContext = true
        )

        assertTrue(requirements.allowMultiPageContext)
        val countingSettings = requirements.multiPageBudgetSettings!!.toTruncationSettings()
        assertEquals(MultiPageBudgetStrategy.WEIGHTED_SPLIT, countingSettings.multiPageBudgetStrategy)
        assertEquals(settings.pageWeights, countingSettings.pageWeights)

        val tokenCount = Dictionary.countTokens("multi page context", countingSettings)
        assertTrue(tokenCount > 0)
    }

    @Test
    fun testManifoldMultiPageIntegration() {
        val manifold = Manifold()
        val pipeline = Pipeline()
        val pipe = DummyPipe()

        pipe.setJsonOutput(AgentRequest())
        pipe.enableLoreBookFillMode()
        pipe.setMultiPageBudgetStrategy(MultiPageBudgetStrategy.WEIGHTED_SPLIT)
        pipe.setPageWeights(mapOf("pageA" to 2.0, "pageB" to 1.0))

        pipeline.pipelineName = "TestManager"
        pipeline.add(pipe)

        manifold.setManagerPipeline(pipeline)
        manifold.autoTruncateContext()

        val settings = pipe.getTruncationSettings()
        assertTrue(settings.fillMode)
        assertEquals(MultiPageBudgetStrategy.WEIGHTED_SPLIT, settings.multiPageBudgetStrategy)
        assertEquals(mapOf("pageA" to 2.0, "pageB" to 1.0), settings.pageWeights)
    }

    private class DummyPipe : Pipe() {
        override fun truncateModuleContext(): Pipe {
            return this
        }

        override suspend fun generateText(promptInjector: String): String {
            return promptInjector
        }
    }
}
