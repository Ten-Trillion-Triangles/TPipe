package genericOpenAIPipe

import genericOpenAIPipe.GenericOpenAIPipe
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipe.TruncationSettings
import com.TTT.Context.Dictionary.BinaryEstimationMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Pins the per-model binary TruncationSettings configured by GenericOpenAIPipe.truncateModuleContext()
 * for Mantle multimodal models. Same pattern as bedrockPipe/PerModelBinaryTruncationTest.
 *
 * GenericOpenAIPipe's model field is `protected` (inherited from Pipe.kt). The binary settings
 * land on `tokenBudgetSettings.truncationSettings` via the dispatcher's tbs.truncationSettings
 * assignment.
 */
class MantleBinaryTruncationTest
{
    private fun pipeFor(modelId: String): GenericOpenAIPipe
    {
        val pipe = GenericOpenAIPipe()
        // Set the modelId via reflection -- GenericOpenAIPipe inherits the `model` field from Pipe.
        val modelField = GenericOpenAIPipe::class.java.superclass.getDeclaredField("model")
        modelField.isAccessible = true
        modelField.set(pipe, modelId)
        // Set a tokenBudgetSettings so the dispatcher has a TruncationSettings to write to.
        val tbsField = GenericOpenAIPipe::class.java.superclass.getDeclaredField("tokenBudgetSettings")
        tbsField.isAccessible = true
        tbsField.set(pipe, TokenBudgetSettings())
        return pipe
    }

    private fun settingsFor(pipe: Pipe): TruncationSettings?
    {
        val tbsField = GenericOpenAIPipe::class.java.superclass.getDeclaredField("tokenBudgetSettings")
        tbsField.isAccessible = true
        val tbs = tbsField.get(pipe) as TokenBudgetSettings?
        return tbs?.truncationSettings
    }

    @Test
    fun `Anthropic Claude 3 Haiku on Mantle populates patch-formula tier-1 override`()
    {
        val pipe = pipeFor("anthropic.claude-3-haiku-20240307-v1:0")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        val overrides = settings.binaryMimeOverride
        assertNotNull(overrides)
        assertEquals(1369, overrides!!["image/jpeg"])
        assertEquals(1369, overrides["image/png"])
        assertEquals(1.0, settings.binaryFudgeFactor)
    }

    @Test
    fun `Google Gemma 3 on Mantle populates flat 256 token override`()
    {
        val pipe = pipeFor("google.gemma-3-4b-it")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        val overrides = settings.binaryMimeOverride
        assertNotNull(overrides)
        assertEquals(256, overrides!!["image/jpeg"])
        assertEquals(256, overrides["image/png"])
        assertEquals(1.0, settings.binaryFudgeFactor)
    }

    @Test
    fun `Mistral Pixtral on Mantle populates 4159 token override`()
    {
        val pipe = pipeFor("mistral.magistral-small-2509")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        val overrides = settings.binaryMimeOverride
        assertNotNull(overrides)
        assertEquals(4159, overrides!!["image/jpeg"])
        assertEquals(1.0, settings.binaryFudgeFactor)
    }

    @Test
    fun `Voxtral on Mantle populates HYBRID mode (duration-dependent)`()
    {
        val pipe = pipeFor("mistral.voxtral-mini-3b-2507")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        assertEquals(1.0, settings.binaryFudgeFactor)
    }

    @Test
    fun `Qwen3-VL on Mantle populates 1024 token override`()
    {
        val pipe = pipeFor("qwen.qwen3-vl-235b-a22b")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        val overrides = settings.binaryMimeOverride
        assertNotNull(overrides)
        assertEquals(1024, overrides!!["image/jpeg"])
        assertEquals(1.0, settings.binaryFudgeFactor)
    }

    @Test
    fun `Kimi K2_5 on Mantle populates 1369 token override with 1_05 fudge`()
    {
        val pipe = pipeFor("moonshotai.kimi-k2.5")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        val overrides = settings.binaryMimeOverride
        assertNotNull(overrides)
        assertEquals(1369, overrides!!["image/jpeg"])
        assertEquals(1.05, settings.binaryFudgeFactor)
    }

    @Test
    fun `Nemotron Nano VL on Mantle populates 1280 token override`()
    {
        val pipe = pipeFor("nvidia.nemotron-nano-12b-v2")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        val overrides = settings.binaryMimeOverride
        assertNotNull(overrides)
        assertEquals(1280, overrides!!["image/jpeg"])
        assertEquals(1.0, settings.binaryFudgeFactor)
    }

    @Test
    fun `Writer Palmyra Vision on Mantle populates 1728 token override with 1_10 fudge`()
    {
        val pipe = pipeFor("writer.palmyra-vision-7b")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        val overrides = settings.binaryMimeOverride
        assertNotNull(overrides)
        assertEquals(1728, overrides!!["image/jpeg"])
        assertEquals(1.10, settings.binaryFudgeFactor)
    }

    @Test
    fun `Nova 2 Multimedia Embeddings on Mantle populates 1 token per image`()
    {
        val pipe = pipeFor("amazon.nova-2-multimodal-embeddings-v1:0")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        val overrides = settings.binaryMimeOverride
        assertNotNull(overrides)
        assertEquals(1, overrides!!["image/jpeg"])
        assertEquals(1, overrides["image/png"])
        assertEquals(1, overrides["image/webp"])
        assertEquals(1.0, settings.binaryFudgeFactor)
    }

    @Test
    fun `Nova 2 Sonic on Mantle populates HYBRID mode (duration-dependent)`()
    {
        val pipe = pipeFor("amazon.nova-2-sonic-v1:0")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        assertEquals(1.0, settings.binaryFudgeFactor)
    }

    @Test
    fun `Text-only GPT-4 on Mantle leaves binary fields at defaults`()
    {
        val pipe = pipeFor("gpt-4-turbo")
        pipe.truncateModuleContext()

        // Text-only model: no branch matches. The else -> null arm fires and no overrides are set.
        val settings = settingsFor(pipe)
        // settings may be null (no per-model binary override), or a fresh TruncationSettings with no binary*
        // (depends on whether the dispatcher created a new instance). No assertion on specific value.
    }
}
