package Defaults.reasoning

import com.TTT.Pipe.Pipe
import Defaults.BedrockConfiguration
import Defaults.GenericOpenAIConfiguration
import Defaults.OllamaConfiguration
import Defaults.OpenRouterConfiguration
import genericOpenAIPipe.GenericOpenAIPipe
import kotlin.test.*

/**
 * Provider-agnostic feature parity for reasoning-pipe middle/footer prompt injection.
 *
 * Contract: every first-party reasoning-pipe builder (reasonWithBedrock, reasonWithOllama,
 * reasonWithOpenRouter, reasonWithGenericOpenAI) MUST populate pipeMetadata["injectMiddlePrompt"]
 * and pipeMetadata["injectFooterPrompt"] with Boolean values before returning the pipe.
 *
 * Background: the bug fixed by [com.TTT.Pipe.PipePromptInjectionReasoningTest] showed that
 * reasoning-pipe metadata reads at Pipe.kt:8033 and Pipe.kt:8047 used unguarded `as Boolean`
 * casts. The first-party builders below survive because they all call [ReasoningBuilder.assignDefaults]
 * which writes those keys (ReasoningBuilder.kt:317-318). This test pins that contract so any
 * future first-party builder that bypasses assignDefaults fails loudly here rather than crashing
 * at runtime with NullPointerException under retry absorption.
 *
 * The Mantle reasoning builders (buildMantleAuthorPipe, buildMantleReasoningPipe) live in the
 * autogenesis consumer repo and are not in scope for this TPipe-Defaults test. Their structural
 * fix is documented in `.hermes/plans/2026-07-30_222920-fix-inject-middle-prompt-npe.md` Task 6.
 */
class ReasoningBuilderParityTest
{
    private fun assertReasoningPipeMetadataContract(pipe: Pipe, builderName: String)
    {
        val middle = pipe.pipeMetadata["injectMiddlePrompt"]
        val footer = pipe.pipeMetadata["injectFooterPrompt"]

        assertNotNull(
            middle,
            "$builderName did not populate pipeMetadata[\"injectMiddlePrompt\"] — " +
                "reasoning pipe middle-prompt injection is broken on this provider."
        )
        assertNotNull(
            footer,
            "$builderName did not populate pipeMetadata[\"injectFooterPrompt\"] — " +
                "reasoning pipe footer-prompt injection is broken on this provider."
        )
        assertTrue(
            middle is Boolean,
            "$builderName populated pipeMetadata[\"injectMiddlePrompt\"] with " +
                "${middle::class.simpleName}, expected Boolean."
        )
        assertTrue(
            footer is Boolean,
            "$builderName populated pipeMetadata[\"injectFooterPrompt\"] with " +
                "${footer::class.simpleName}, expected Boolean."
        )
    }

    @Test
    fun `reasonWithBedrock populates injectMiddlePrompt and injectFooterPrompt as Boolean`()
    {
        val pipe = ReasoningBuilder.reasonWithBedrock(
            bedrockConfig = BedrockConfiguration(
                region = "us-east-2",
                model = "amazon.nova-lite-v1:0"
            ),
            reasoningSettings = ReasoningSettings(),
            pipeSettings = null
        )
        assertReasoningPipeMetadataContract(pipe, "reasonWithBedrock")
    }

    @Test
    fun `reasonWithOllama populates injectMiddlePrompt and injectFooterPrompt as Boolean`()
    {
        val pipe = ReasoningBuilder.reasonWithOllama(
            ollamaConfig = OllamaConfiguration(model = "llama3.1:8b"),
            reasoningSettings = ReasoningSettings(),
            pipeSettings = null
        )
        assertReasoningPipeMetadataContract(pipe, "reasonWithOllama")
    }

    @Test
    fun `reasonWithOpenRouter populates injectMiddlePrompt and injectFooterPrompt as Boolean`()
    {
        val pipe = ReasoningBuilder.reasonWithOpenRouter(
            openRouterConfig = OpenRouterConfiguration(
                model = "anthropic/claude-3.5-sonnet",
                apiKey = "test-key-not-used-for-network"
            ),
            reasoningSettings = ReasoningSettings(),
            pipeSettings = null
        )
        assertReasoningPipeMetadataContract(pipe, "reasonWithOpenRouter")
    }

    @Test
    fun `reasonWithGenericOpenAI populates injectMiddlePrompt and injectFooterPrompt as Boolean`()
    {
        val pipe = ReasoningBuilder.reasonWithGenericOpenAI(
            genericConfig = GenericOpenAIConfiguration(model = "gpt-4o-mini"),
            reasoningSettings = ReasoningSettings(),
            pipeSettings = null
        )
        assertReasoningPipeMetadataContract(pipe, "reasonWithGenericOpenAI")
    }

    @Test
    fun `assignDefaults direct call populates injectMiddlePrompt and injectFooterPrompt as Boolean`()
    {
        // Pin the contract at the lowest layer: any caller of assignDefaults that targets
        // a Pipe is responsible for the metadata keys. The four builders above exercise
        // this; this test catches a regression where assignDefaults itself stops writing
        // those keys (e.g. a refactor that drops the assignment block at lines 317-318).
        val pipe = GenericOpenAIPipe().apply {
            setPipeName("direct-assignDefaults-test")
            setApiKey("test-key-not-used-for-network")
        }
        ReasoningBuilder.assignDefaults(ReasoningSettings(), null, pipe)
        assertReasoningPipeMetadataContract(pipe, "ReasoningBuilder.assignDefaults (direct)")
    }
}
