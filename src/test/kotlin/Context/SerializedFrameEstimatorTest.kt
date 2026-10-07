package com.TTT.Context

import com.TTT.Pipe.BinaryContent
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TruncationSettings
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.Util.serialize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [SerializedFrameEstimator]:
 *  - the fast reconstruction is BYTE-identical to `serialize()` for the common text-centric schema
 *    (the golden invariant that keeps the reconstruction honest as the schema evolves);
 *  - `estimateTokens` therefore equals the exact serialized token count;
 *  - windows carrying the rare deep fields (PCP `tools`, `binaryContent`, nested `miniBankContext`)
 *    route to the exact serializer and still match;
 *  - the frame/content split is internally consistent.
 */
class SerializedFrameEstimatorTest
{
    private val settings = TruncationSettings()

    //==== Golden: reconstruction is byte-identical to serialize ====================//

    @Test
    fun `reconstruction matches serialize byte for byte across common shapes`()
    {
        val windows = listOf(
            ContextWindow(),
            elemsWindow(0),
            elemsWindow(3),
            loreWindow(0),
            loreWindow(2),
            histWindow(0),
            histWindow(4),
            versionedWindow(7),
            mixedWindow()
        )

        for(window in windows)
        {
            assertEquals(
                serialize(window),
                SerializedFrameEstimator.renderWindowString(window),
                "reconstruction must be byte-identical to serialize() for this window")
        }
    }

    @Test
    fun `mini bank reconstruction matches serialize byte for byte`()
    {
        val bank = MiniBank()
        bank.contextMap["story"] = elemsWindow(4)
        bank.contextMap["game"] = loreWindow(1)

        assertEquals(serialize(bank), SerializedFrameEstimator.renderMiniBankString(bank))
    }

    @Test
    fun `estimate tokens equals the exact serialized token count`()
    {
        val windows = listOf(ContextWindow(), elemsWindow(5), histWindow(3), mixedWindow())

        for(window in windows)
        {
            val exact = Dictionary.countTokens(serialize(window), settings)
            assertEquals(exact, SerializedFrameEstimator.estimateTokens(window, settings))
        }
    }

    //==== Rare deep fields route to the exact serializer ===========================//

    @Test
    fun `window with pcp tools is estimated exactly via the fallback`()
    {
        val window = ContextWindow()
        val content = MultimodalContent(text = "hello").apply { tools = PcPRequest().apply { argumentsOrFunctionParams = listOf("arg") } }
        window.converseHistory.history.add(ConverseData(ConverseRole.user, content))

        val exact = Dictionary.countTokens(serialize(window), settings)
        assertEquals(exact, SerializedFrameEstimator.estimateTokens(window, settings))
    }

    @Test
    fun `window with binary content is estimated exactly via the fallback`()
    {
        val window = ContextWindow()
        val content = MultimodalContent(binaryContent = mutableListOf(BinaryContent.TextDocument(content = "body")))
        window.converseHistory.history.add(ConverseData(ConverseRole.user, content))

        val exact = Dictionary.countTokens(serialize(window), settings)
        assertEquals(exact, SerializedFrameEstimator.estimateTokens(window, settings))
    }

    //==== Frame + content split is consistent =====================================//

    @Test
    fun `frame cost plus content tokens equals the estimate`()
    {
        for(window in listOf(ContextWindow(), elemsWindow(4), histWindow(3), mixedWindow()))
        {
            val frame = SerializedFrameEstimator.frameCost(window, settings)
            val content = SerializedFrameEstimator.contentTokens(window, settings)
            val estimate = SerializedFrameEstimator.estimateTokens(window, settings)

            assertTrue(frame >= 0, "frame cost must be non-negative")
            assertEquals(estimate, frame + content, "estimate must equal frame + content")
        }
    }

    @Test
    fun `reserving the pre-truncation frame keeps content plus frame within the budget`()
    {
        //A packed element window. Truncate the raw content to (budget - frameReserve); the exact
        //serialized total of the result must not exceed the budget.
        val window = elemsWindow(40)
        val budget = 400

        val frameReserve = SerializedFrameEstimator.frameCost(window, settings)
        val contentBudget = (budget - frameReserve).coerceAtLeast(0)

        //Truncate raw content to fit contentBudget (element-level, like selectAndTruncateContext).
        window.truncateContextElements(contentBudget, 1, com.TTT.Enums.ContextWindowSettings.TruncateBottom)

        val actualTotal = Dictionary.countTokens(serialize(window), settings)
        assertTrue(
            actualTotal <= budget,
            "content + reserved frame must fit the budget; actualTotal=$actualTotal budget=$budget")
    }

    //==== window builders ==========================================================//

    private fun elemsWindow(n: Int): ContextWindow
    {
        val window = ContextWindow()
        repeat(n) { window.contextElements.add("context element $it with some content") }
        return window
    }

    private fun loreWindow(n: Int): ContextWindow
    {
        val window = ContextWindow()
        repeat(n)
        {
            val lore = LoreBook()
            lore.key = "key$it"
            lore.value = "value $it"
            if(it % 2 == 0)
            {
                lore.weight = 5
                lore.aliasKeys.add("alias$it")
            }
            window.loreBookKeys["key$it"] = lore
        }
        return window
    }

    private fun histWindow(n: Int): ContextWindow
    {
        val window = ContextWindow()
        repeat(n)
        {
            window.converseHistory.history.add(
                ConverseData(if(it % 2 == 0) ConverseRole.user else ConverseRole.assistant,
                    MultimodalContent(text = "history turn $it")))
        }
        return window
    }

    private fun versionedWindow(v: Long): ContextWindow
    {
        val window = ContextWindow()
        window.version = v
        return window
    }

    private fun mixedWindow(): ContextWindow
    {
        val window = elemsWindow(3)
        window.loreBookKeys["lore"] = LoreBook().apply { key = "lore"; value = "v" }
        window.converseHistory.history.add(ConverseData(ConverseRole.user, MultimodalContent(text = "a turn")))
        return window
    }
}
