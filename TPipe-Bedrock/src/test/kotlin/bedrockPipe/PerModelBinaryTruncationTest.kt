package bedrockPipe

import bedrockPipe.BedrockPipe
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipe.TruncationSettings
import com.TTT.Context.Dictionary.BinaryEstimationMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Pins the per-model binary TruncationSettings configured by BedrockPipe.truncateModuleContext().
 *
 * The framework's pipe class does NOT have its own `binaryTokenEstimation` / `binaryMimeOverride`
 * fields -- the binary* fields live only on the TruncationSettings data class, and the dispatcher
 * writes them to `tokenBudgetSettings.truncationSettings`. So tests must read those fields off
 * the nested TruncationSettings instance.
 *
 * Pattern: instantiate a BedrockPipe, set its `model` field via reflection, set a default
 * TokenBudgetSettings (so the dispatcher has something to write to), call truncateModuleContext(),
 * then assert that `pipe.tokenBudgetSettings.truncationSettings.binaryTokenEstimation` etc. match
 * the expected values for that modelId at 1024x1024 resolution baseline.
 */
class PerModelBinaryTruncationTest
{
    private fun pipeFor(modelId: String): BedrockPipe
    {
        val pipe = BedrockPipe()
        // Set the modelId via reflection -- BedrockPipe inherits the `model` field from Pipe.
        val modelField = BedrockPipe::class.java.superclass.getDeclaredField("model")
        modelField.isAccessible = true
        modelField.set(pipe, modelId)
        // Set a tokenBudgetSettings so the dispatcher has a TruncationSettings to write to.
        // Without this, the dispatcher is a no-op for binary settings.
        val tbsField = BedrockPipe::class.java.superclass.getDeclaredField("tokenBudgetSettings")
        tbsField.isAccessible = true
        tbsField.set(pipe, TokenBudgetSettings())
        return pipe
    }

    private fun settingsFor(pipe: Pipe): TruncationSettings?
    {
        val tbsField = BedrockPipe::class.java.superclass.getDeclaredField("tokenBudgetSettings")
        tbsField.isAccessible = true
        val tbs = tbsField.get(pipe) as TokenBudgetSettings?
        return tbs?.truncationSettings
    }

    @Test
    fun `Anthropic Claude 3 Haiku populates patch-formula tier-1 override`()
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
    fun `Google Gemma 3 4B populates flat 256 token override`()
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
        assertEquals(256, overrides["image/webp"])
        assertEquals(256, overrides["image/gif"])
        assertEquals(1.0, settings.binaryFudgeFactor)
    }

    @Test
    fun `Mistral vision populates Pixtral formula tier-1 override`()
    {
        val pipe = pipeFor("mistral.magistral-small-2509")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        val overrides = settings.binaryMimeOverride
        assertNotNull(overrides)
        assertEquals(4159, overrides!!["image/jpeg"])
        assertEquals(4159, overrides["image/png"])
        assertEquals(4159, overrides["image/webp"])
        assertEquals(1.0, settings.binaryFudgeFactor)
    }

    @Test
    fun `Voxtral audio populates HYBRID with empty override (duration-dependent)`()
    {
        val pipe = pipeFor("mistral.voxtral-mini-3b-2507")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        assertEquals(1.0, settings.binaryFudgeFactor)
    }

    @Test
    fun `Qwen3-VL populates 32x32 effective block tier-1 override`()
    {
        val pipe = pipeFor("qwen.qwen3-vl-235b-a22b")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        val overrides = settings.binaryMimeOverride
        assertNotNull(overrides)
        assertEquals(1024, overrides!!["image/jpeg"])
        assertEquals(1024, overrides["image/png"])
        assertEquals(1024, overrides["image/webp"])
        assertEquals(1.0, settings.binaryFudgeFactor)
    }

    @Test
    fun `Kimi K2_5 populates MoonViT-3D tier-1 override with 1_05 fudge`()
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
    fun `Nemotron Nano VL populates 1280 token override`()
    {
        val pipe = pipeFor("nvidia.nemotron-nano-12b-v2")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        val overrides = settings.binaryMimeOverride
        assertNotNull(overrides)
        assertEquals(1280, overrides!!["image/jpeg"])
        assertEquals(1280, overrides["image/png"])
        assertEquals(1.0, settings.binaryFudgeFactor)
    }

    @Test
    fun `Writer Palmyra Vision populates 1728 token override with 1_10 fudge`()
    {
        val pipe = pipeFor("writer.palmyra-vision-7b")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        val overrides = settings.binaryMimeOverride
        assertNotNull(overrides)
        assertEquals(1728, overrides!!["image/jpeg"])
        assertEquals(1728, overrides["image/png"])
        assertEquals(1.10, settings.binaryFudgeFactor)
    }

    @Test
    fun `Amazon Titan Embed Image populates 1 token per image`()
    {
        val pipe = pipeFor("amazon.titan-embed-image-v1")
        pipe.truncateModuleContext()

        val settings = settingsFor(pipe)
        assertNotNull(settings)
        assertEquals(BinaryEstimationMode.HYBRID, settings!!.binaryTokenEstimation)
        val overrides = settings.binaryMimeOverride
        assertNotNull(overrides)
        assertEquals(1, overrides!!["image/jpeg"])
        assertEquals(1, overrides["image/png"])
        assertEquals(1.0, settings.binaryFudgeFactor)
    }

    @Test
    fun `Amazon Nova 2 Multimedia Embeddings populates 1 token per image`()
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
    fun `Text-only model Llama 3 leaves binary fields at defaults`()
    {
        val pipe = pipeFor("meta.llama3-70b-instruct-v1:0")
        pipe.truncateModuleContext()

        // Text-only model: the dispatcher branch does not match any of the multimodal predicates.
        // The else -> null arm of the per-model when produces null binarySettings,
        // so the existing tokenBudgetSettings.truncationSettings (if any) is untouched.
        // No assertion on the per-model binary override -- just verify no crash.
    }
}
