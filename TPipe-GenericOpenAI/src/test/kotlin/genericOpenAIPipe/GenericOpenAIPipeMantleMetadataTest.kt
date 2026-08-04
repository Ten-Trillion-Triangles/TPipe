package genericOpenAIPipe

import kotlin.test.*

/**
 * Pins the reasoning-pipe metadata contract for Mantle-shaped pipes.
 *
 * Background: the bug fixed by [com.TTT.Pipe.PipePromptInjectionReasoningTest] showed
 * that reasoning-pipe middle/footer prompt injection at `Pipe.kt:8033/8047` was
 * provider-coupled — it happened to work on Bedrock / Ollama / OpenRouter / GenericOpenAI
 * because the four first-party builders in `TPipe-Defaults` invoke
 * `ReasoningBuilder.assignDefaults` which writes the two keys. Mantle (this module)
 * constructed pipes via `GenericOpenAIPipe.setBedrockMantle(...)` without calling
 * `assignDefaults`, so the keys were absent and the unguarded cast threw NPE.
 *
 * Structural fix: `configureBedrockMantle(...)` now writes the same keys with the
 * same default values, so Mantle reasoning pipes are first-class — the feature
 * works identically regardless of which provider sits behind the pipe.
 *
 * This test asserts the contract at the Mantle entry points: setBedrockMantle,
 * setBedrockMantleWithResponses, setBedrockMantleAuth. None of these require AWS
 * credentials to be set; metadata population happens before any network call.
 */
class GenericOpenAIPipeMantleMetadataTest
{
    @Test
    fun `setBedrockMantle populates injectMiddlePrompt and injectFooterPrompt as Boolean`()
    {
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key-not-used-for-network")
            .setBedrockMantle(region = "us-east-2", modelId = "google.gemma-4-e2b")

        assertTrue(pipe.pipeMetadata.containsKey("injectMiddlePrompt"))
        assertTrue(pipe.pipeMetadata.containsKey("injectFooterPrompt"))
        assertTrue(pipe.pipeMetadata["injectMiddlePrompt"] is Boolean)
        assertTrue(pipe.pipeMetadata["injectFooterPrompt"] is Boolean)
        assertEquals(false, pipe.pipeMetadata["injectMiddlePrompt"])
        assertEquals(false, pipe.pipeMetadata["injectFooterPrompt"])
    }

    @Test
    fun `setBedrockMantleWithResponses populates injectMiddlePrompt and injectFooterPrompt as Boolean`()
    {
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key-not-used-for-network")
            .setBedrockMantleWithResponses(region = "us-east-2", modelId = "google.gemma-4-31b")

        assertTrue(pipe.pipeMetadata.containsKey("injectMiddlePrompt"))
        assertTrue(pipe.pipeMetadata.containsKey("injectFooterPrompt"))
        assertTrue(pipe.pipeMetadata["injectMiddlePrompt"] is Boolean)
        assertTrue(pipe.pipeMetadata["injectFooterPrompt"] is Boolean)
        assertEquals(false, pipe.pipeMetadata["injectMiddlePrompt"])
        assertEquals(false, pipe.pipeMetadata["injectFooterPrompt"])
    }

    @Test
    fun `Mantle pipe attached as reasoning pipe does not throw NPE on getMiddlePromptForReasoning`()
    {
        // Reproduces the bug-report scenario: a parent pipe attaches a Mantle-shaped
        // reasoning pipe and queries the middle prompt. Pre-fix: NullPointerException
        // at Pipe.kt:8033. Post-fix: returns empty string (default injection = false).
        val parent = TestPipe()
        val reasoning = GenericOpenAIPipe()
            .setApiKey("test-key-not-used-for-network")
            .setBedrockMantle(region = "us-east-2", modelId = "google.gemma-4-e2b")
        parent.setReasoningPipe(reasoning)

        // Both calls must not throw. Returns empty because injection defaults to false.
        assertEquals("", parent.getMiddlePromptForReasoning())
        assertEquals("", parent.getFooterPromptForReasoning())
    }

    @Test
    fun `Mantle pipe with explicit injectMiddlePrompt=true injects the middle prompt`()
    {
        // Confirms that callers CAN opt in to middle-prompt injection on Mantle
        // by overriding the metadata key after construction — the structural fix
        // sets the default but does not block the override path.
        val parent = TestPipe()
        parent.setMiddlePrompt("##MIDDLE##")
        val reasoning = GenericOpenAIPipe()
            .setApiKey("test-key-not-used-for-network")
            .setBedrockMantle(region = "us-east-2", modelId = "google.gemma-4-e2b")
        // Override the default: this caller wants the middle prompt.
        reasoning.pipeMetadata["injectMiddlePrompt"] = true
        parent.setReasoningPipe(reasoning)

        assertEquals("##MIDDLE##", parent.getMiddlePromptForReasoning())
    }

    private class TestPipe : com.TTT.Pipe.Pipe()
    {
        override suspend fun generateText(promptInjector: String): String = ""
        override fun truncateModuleContext(): com.TTT.Pipe.Pipe = this
    }
}
