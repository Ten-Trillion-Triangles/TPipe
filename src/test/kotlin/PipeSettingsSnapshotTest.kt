package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Enums.ContextWindowSettings
import com.TTT.Enums.PromptMode
import com.TTT.Enums.ProviderName
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.Structs.PipeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class SnapshotPipe : Pipe()
{
    override fun truncateModuleContext(): Pipe = this

    override suspend fun generateText(promptInjector: String): String = "snapshot test"
}

class PipeSettingsSnapshotTest
{
    @Test
    fun toPipeSettingsReturnsDetachedCopiesOfMutableState()
    {
        val pipe = SnapshotPipe().apply {
            setPipeName("source")
            setProvider(ProviderName.Aws)
            setModel("model-a")
            setPromptMode(PromptMode.singlePrompt)
            setSystemPrompt("system")
            setUserPrompt("user")
            setMultimodalInput(MultimodalContent(text = "multimodal"))
            setJsonInput("{\"input\":true}")
            setJsonOutput("{\"output\":true}")
            setPcPContext(PcpContext().apply {
                allowedDirectoryPaths.add("/allowed")
            })
            setTemperature(0.25)
            setTopP(0.15)
            setTopK(3)
            setMaxTokens(256)
            setContextWindowSize(4096)
            setContextWindowSettings(ContextWindowSettings.TruncateBottom)
            setPageKey("alpha, beta")
            setStopSequences(listOf("stop-one", "stop-two"))
            setMultiplyWindowSizeBy(2)
            setCountSubWordsInFirstWord(false)
            setFavorWholeWords(false)
            setCountOnlyFirstWordFound(true)
            setSplitForNonWordChar(false)
            setAlwaysSplitIfWholeWordExists(true)
            setCountSubWordsIfSplit(true)
            setNonWordSplitCount(7)
            setTokenBudget(
                TokenBudgetSettings(
                    contextWindowSize = 4096,
                    maxTokens = 256,
                    pageWeights = mutableMapOf("alpha" to 1.0)
                )
            )
        }

        pipe.getContextWindowObject().contextElements.add("pipe-context")
        pipe.getMiniContextBankObject().contextMap["page"] = ContextWindow().apply {
            contextElements.add("pipe-mini-bank")
        }

        val snapshot = pipe.toPipeSettings()

        snapshot.contextWindow!!.contextElements.add("snapshot-context")
        snapshot.miniContextBank!!.contextMap["page"] = ContextWindow().apply {
            contextElements.add("snapshot-mini-bank")
        }
        snapshot.multimodalInput!!.text = "snapshot multimodal"
        snapshot.pcpContext!!.allowedDirectoryPaths.add("/snapshot")
        snapshot.pageKeyList!!.add("gamma")
        snapshot.stopSequences = snapshot.stopSequences!! + "stop-three"
        snapshot.tokenBudgetSettings!!.pageWeights = snapshot.tokenBudgetSettings!!.pageWeights.orEmpty() + ("beta" to 2.0)

        val fresh = pipe.toPipeSettings()

        assertFalse(fresh.contextWindow!!.contextElements.contains("snapshot-context"))
        assertFalse(fresh.miniContextBank!!.contextMap.containsKey("snapshot-mini-bank"))
        assertEquals("multimodal", fresh.multimodalInput!!.text)
        assertFalse(fresh.pcpContext!!.allowedDirectoryPaths.contains("/snapshot"))
        assertEquals<List<String>?>(listOf("alpha", "beta"), fresh.pageKeyList)
        assertEquals(listOf("stop-one", "stop-two"), fresh.stopSequences)
        assertEquals(mapOf("alpha" to 1.0), fresh.tokenBudgetSettings!!.pageWeights)

        assertTrue(snapshot.contextWindow !== pipe.getContextWindowObject())
        assertTrue(snapshot.miniContextBank !== pipe.getMiniContextBankObject())
        assertTrue(snapshot.multimodalInput !== fresh.multimodalInput)
        assertTrue(snapshot.pcpContext !== fresh.pcpContext)
    }

    @Test
    fun applyPipeSettingsDeepCopiesTheIncomingSnapshot()
    {
        val source = PipeSettings(
            pipeName = "source",
            provider = ProviderName.Aws,
            model = "model-b",
            promptMode = PromptMode.singlePrompt,
            systemPrompt = "system",
            userPrompt = "user",
            multimodalInput = MultimodalContent(text = "source multimodal"),
            jsonInput = "{\"input\":true}",
            jsonOutput = "{\"output\":true}",
            pcpContext = PcpContext().apply {
                allowedDirectoryPaths.add("/source")
            },
            supportsNativeJson = false,
            temperature = 0.4,
            topP = 0.2,
            topK = 5,
            maxTokens = 512,
            contextWindowSize = 8192,
            contextWindow = ContextWindow().apply {
                contextElements.add("source-context")
            },
            miniContextBank = MiniBank(mutableMapOf("page" to ContextWindow().apply {
                contextElements.add("source-mini-bank")
            })),
            readFromGlobalContext = true,
            readFromPipelineContext = true,
            updatePipelineContextOnExit = true,
            autoInjectContext = true,
            autoTruncateContext = true,
            emplaceLorebook = false,
            appendLoreBook = true,
            loreBookFillMode = true,
            loreBookFillAndSplitMode = true,
            useModelReasoning = true,
            modelReasoningSettingsV2 = 6000,
            modelReasoningSettingsV3 = "reasoning",
            pageKey = "",
            pageKeyList = mutableListOf("alpha", "beta"),
            contextWindowTruncation = ContextWindowSettings.TruncateBottom,
            truncateContextAsString = true,
            repetitionPenalty = 0.5,
            stopSequences = listOf("stop-one", "stop-two"),
            multiplyWindowSizeBy = 2,
            countSubWordsInFirstWord = false,
            favorWholeWords = false,
            countOnlyFirstWordFound = true,
            splitForNonWordChar = false,
            alwaysSplitIfWholeWordExists = true,
            countSubWordsIfSplit = true,
            nonWordSplitCount = 6,
            tracingEnabled = true,
            pipeId = "pipe-1",
            currentPipelineId = "pipeline-1",
            tokenBudgetSettings = TokenBudgetSettings(
                contextWindowSize = 8192,
                maxTokens = 512,
                pageWeights = mutableMapOf("alpha" to 1.0)
            )
        )

        val target = SnapshotPipe()
        target.applyPipeSettings(source)

        source.contextWindow!!.contextElements.add("source-mutated")
        source.miniContextBank!!.contextMap["page"]!!.contextElements.add("source-mutated")
        source.multimodalInput!!.text = "source mutated"
        source.pcpContext!!.allowedDirectoryPaths.add("/mutated")
        source.pageKeyList!!.add("gamma")
        source.stopSequences = source.stopSequences!! + "stop-three"
        source.tokenBudgetSettings!!.pageWeights = source.tokenBudgetSettings!!.pageWeights.orEmpty() + ("beta" to 2.0)

        val applied = target.toPipeSettings()

        assertEquals("source", applied.pipeName)
        assertEquals(ProviderName.Aws, applied.provider)
        assertEquals("model-b", applied.model)
        assertEquals(PromptMode.singlePrompt, applied.promptMode)
        assertTrue(applied.systemPrompt!!.contains("system"))
        assertEquals("user", applied.userPrompt)
        assertEquals("source multimodal", applied.multimodalInput!!.text)
        assertEquals("{\"input\":true}", applied.jsonInput)
        assertEquals("{\"output\":true}", applied.jsonOutput)
        assertFalse(applied.pcpContext!!.allowedDirectoryPaths.contains("/mutated"))
        assertEquals(0.4, applied.temperature)
        assertEquals(0.2, applied.topP)
        assertEquals(5, applied.topK)
        assertEquals(512, applied.maxTokens)
        assertEquals(8192, applied.tokenBudgetSettings!!.contextWindowSize)
        assertEquals(listOf("source-context"), applied.contextWindow!!.contextElements)
        assertEquals(listOf("source-mini-bank"), applied.miniContextBank!!.contextMap["page"]!!.contextElements)
        assertTrue(applied.readFromGlobalContext!!)
        assertTrue(applied.readFromPipelineContext!!)
        assertTrue(applied.updatePipelineContextOnExit!!)
        assertTrue(applied.autoInjectContext!!)
        assertTrue(applied.autoTruncateContext!!)
        assertFalse(applied.emplaceLorebook!!)
        assertTrue(applied.appendLoreBook!!)
        assertTrue(applied.loreBookFillMode!!)
        assertTrue(applied.loreBookFillAndSplitMode!!)
        assertTrue(applied.useModelReasoning!!)
        assertEquals(6000, applied.modelReasoningSettingsV2)
        assertEquals("reasoning", applied.modelReasoningSettingsV3)
        assertEquals<List<String>?>(listOf("alpha", "beta"), applied.pageKeyList)
        assertEquals(ContextWindowSettings.TruncateBottom, applied.contextWindowTruncation)
        assertTrue(applied.truncateContextAsString!!)
        assertEquals(0.5, applied.repetitionPenalty)
        assertEquals(listOf("stop-one", "stop-two"), applied.stopSequences)
        assertEquals(2, applied.multiplyWindowSizeBy)
        assertFalse(applied.countSubWordsInFirstWord!!)
        assertFalse(applied.favorWholeWords!!)
        assertTrue(applied.countOnlyFirstWordFound!!)
        assertFalse(applied.splitForNonWordChar!!)
        assertTrue(applied.alwaysSplitIfWholeWordExists!!)
        assertTrue(applied.countSubWordsIfSplit!!)
        assertEquals(6, applied.nonWordSplitCount)
        assertTrue(applied.tracingEnabled!!)
        assertEquals("pipe-1", applied.pipeId)
        assertEquals("pipeline-1", applied.currentPipelineId)
        assertEquals(mapOf("alpha" to 1.0), applied.tokenBudgetSettings!!.pageWeights)

        assertTrue(applied.contextWindow !== source.contextWindow)
        assertTrue(applied.miniContextBank !== source.miniContextBank)
        assertTrue(applied.multimodalInput !== source.multimodalInput)
        assertTrue(applied.pcpContext !== source.pcpContext)
        assertTrue(applied.tokenBudgetSettings !== source.tokenBudgetSettings)
    }

    @Test
    fun applyPartialPipeSettingsDoesNotOverwriteExistingValues()
    {
        val pipe = SnapshotPipe().apply {
            setPipeName("original-name")
            setTemperature(0.7)
            setMaxTokens(1000)
            enableTracing()
            setTokenBudget(TokenBudgetSettings(contextWindowSize = 4000))
        }

        // 1. Create a partial settings object with only temperature set
        val partialSettings = PipeSettings(
            temperature = 0.3
        )

        pipe.applyPipeSettings(partialSettings)

        // Assert updated value
        assertEquals(0.3, pipe.toPipeSettings().temperature)

        // Assert retained values
        val currentSettings = pipe.toPipeSettings()
        assertEquals("original-name", currentSettings.pipeName)
        assertEquals(1000, currentSettings.maxTokens)
        assertTrue(currentSettings.tracingEnabled!!)
        assertEquals(4000, currentSettings.tokenBudgetSettings!!.contextWindowSize)

        // 2. Verify special cases: tracingEnabled is null in settings
        val settingsWithNullTracing = PipeSettings(maxTokens = 2000) // tracingEnabled is null
        pipe.applyPipeSettings(settingsWithNullTracing)
        assertEquals(2000, pipe.toPipeSettings().maxTokens)
        assertTrue(pipe.toPipeSettings().tracingEnabled!!, "Tracing should remain enabled when settings.tracingEnabled is null")

        // 3. Verify special cases: tokenBudgetSettings is null in settings
        val settingsWithNullBudget = PipeSettings(pipeName = "new-name") // tokenBudgetSettings is null
        pipe.applyPipeSettings(settingsWithNullBudget)
        assertEquals("new-name", pipe.toPipeSettings().pipeName)
        assertEquals(4000, pipe.toPipeSettings().tokenBudgetSettings!!.contextWindowSize, "Token budget should be preserved when settings.tokenBudgetSettings is null")
    }
}
