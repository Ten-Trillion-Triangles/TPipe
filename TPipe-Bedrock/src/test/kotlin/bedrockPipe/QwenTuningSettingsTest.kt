package bedrockPipe

import com.TTT.Pipe.TruncationSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class QwenTuningSettingsTest {

    private val expectedQwenSettings = TruncationSettings(
        countSubWordsInFirstWord = true,
        favorWholeWords = false,
        countOnlyFirstWordFound = false,
        splitForNonWordChar = true,
        alwaysSplitIfWholeWordExists = false,
        countSubWordsIfSplit = false,
        nonWordSplitCount = 2,
        tokenCountingBias = -0.036641221374045754
    )

    @Test
    fun `truncateModuleContext applies tuned qwen settings`() {
        val pipe = BedrockPipe().setModel("qwen.qwen3-32b-v1:0")

        pipe.truncateModuleContext()

        assertEquals(expectedQwenSettings, pipe.getTruncationSettings())
    }

    @Test
    fun `truncateModuleContextSuspend applies tuned qwen settings`() = runBlocking {
        val pipe = BedrockPipe().setModel("qwen.qwen3-32b-v1:0")

        pipe.truncateModuleContextSuspend()

        assertEquals(expectedQwenSettings, pipe.getTruncationSettings())
    }
}
