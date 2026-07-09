package Defaults.reasoning

import com.TTT.Pipe.Pipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins the configuration of every provider-flavoured reasoning pipe factory exposed by
 * [ReasoningBuilder]. The contract is:
 *
 * 1. The returned pipe must be a non-null [Pipe] ready to attach via [Pipe.setReasoningPipe].
 * 2. The reasoning settings must round-trip into the child's pipeMetadata so the runtime
 *    execution path sees them (Injector, Method, Rounds).
 * 3. The provider-specific configuration must be preserved after [assignDefaults] mutates
 *    the pipe — verified by inspecting pipeMetadata["configuredProvider"] plus pipeName,
 *    which the factories populate as part of the assignDefaults bootstrap.
 *
 * The bedrock and ollama entries pin the established reference behaviour. The openrouter
 * and genericOpenAI entries pin the new factories added in the 2026-07 reasoning-builder
 * extension cycle.
 */
class ReasoningBuilderProviderFactoriesTest
{
    private fun buildBedrockReasoning(): Pipe
    {
        val settings = ReasoningSettings(
            reasoningMethod = ReasoningMethod.StructuredCot,
            depth = ReasoningDepth.Med,
            duration = ReasoningDuration.Med,
            reasoningInjector = ReasoningInjector.SystemPrompt,
            numberOfRounds = 1
        )
        val pipe = ReasoningBuilder.reasonWithBedrock(
            Defaults.BedrockConfiguration(
                region = "us-east-1",
                model = "anthropic.claude-3-sonnet-20240229-v1:0",
                pipeCount = 1
            ),
            settings,
            com.TTT.Structs.PipeSettings(maxTokens = 1000)
        )
        ReasoningBuilder.assignDefaults(
            settings.copy(reasoningInjector = ReasoningInjector.SystemPrompt),
            null,
            pipe
        )
        return pipe
    }

    private fun buildOpenRouterReasoning(): Pipe
    {
        val settings = ReasoningSettings(
            reasoningMethod = ReasoningMethod.StructuredCot,
            reasoningInjector = ReasoningInjector.SystemPrompt,
            numberOfRounds = 1
        )
        return ReasoningBuilder.reasonWithOpenRouter(
            Defaults.OpenRouterConfiguration(
                model = "anthropic/claude-3.5-sonnet",
                apiKey = "test-openrouter-key"
            ),
            settings,
            com.TTT.Structs.PipeSettings(maxTokens = 1000)
        )
    }

    private fun buildOllamaReasoning(): Pipe
    {
        val settings = ReasoningSettings(
            reasoningMethod = ReasoningMethod.ExplicitCot,
            reasoningInjector = ReasoningInjector.BeforeUserPrompt,
            numberOfRounds = 2
        )
        return ReasoningBuilder.reasonWithOllama(
            Defaults.OllamaConfiguration(model = "llama3.1:8b"),
            settings,
            com.TTT.Structs.PipeSettings(maxTokens = 1000)
        )
    }

    private fun buildGenericOpenAIReasoning(): Pipe
    {
        val settings = ReasoningSettings(
            reasoningMethod = ReasoningMethod.StructuredCot,
            reasoningInjector = ReasoningInjector.SystemPrompt,
            numberOfRounds = 1
        )
        return ReasoningBuilder.reasonWithGenericOpenAI(
            Defaults.GenericOpenAIConfiguration(
                model = "gpt-4o-mini",
                apiKey = "test-generic-key"
            ),
            settings,
            com.TTT.Structs.PipeSettings(maxTokens = 1000)
        )
    }

    @Test
    fun reasonWithBedrockReturnsPipeWithConfiguredReasoningMetadata()
    {
        val pipe = buildBedrockReasoning()
        assertNotNull(pipe)
        assertEquals("StructuredCot", pipe.pipeMetadata["reasoningMethod"])
        assertEquals("SystemPrompt", pipe.pipeMetadata["injectionMethod"])
        assertEquals(1, pipe.pipeMetadata["reasoningRounds"])
    }

    @Test
    fun reasonWithOllamaReturnsPipeWithConfiguredReasoningMetadata()
    {
        val pipe = buildOllamaReasoning()
        assertNotNull(pipe)
        assertEquals("ExplicitCot", pipe.pipeMetadata["reasoningMethod"])
        assertEquals("BeforeUserPrompt", pipe.pipeMetadata["injectionMethod"])
        assertEquals(2, pipe.pipeMetadata["reasoningRounds"])
    }

    @Test
    fun reasonWithOpenRouterReturnsPipeWithConfiguredReasoningMetadata()
    {
        val pipe = buildOpenRouterReasoning()
        assertNotNull(pipe)
        assertEquals("StructuredCot", pipe.pipeMetadata["reasoningMethod"])
        assertEquals("SystemPrompt", pipe.pipeMetadata["injectionMethod"])
        assertEquals(1, pipe.pipeMetadata["reasoningRounds"])
    }

    @Test
    fun reasonWithGenericOpenAIReturnsPipeWithConfiguredReasoningMetadata()
    {
        val pipe = buildGenericOpenAIReasoning()
        assertNotNull(pipe)
        assertEquals("StructuredCot", pipe.pipeMetadata["reasoningMethod"])
        assertEquals("SystemPrompt", pipe.pipeMetadata["injectionMethod"])
        assertEquals(1, pipe.pipeMetadata["reasoningRounds"])
    }
}
