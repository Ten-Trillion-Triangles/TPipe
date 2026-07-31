package genericOpenAIPipe

import com.TTT.Pipe.Pipe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live integration smoke for the injectMiddlePrompt / injectFooterPrompt fix.
 *
 * Verifies that:
 *   1. A Mantle-shaped reasoning pipe attached via [Pipe.setReasoningPipe] does NOT
 *      throw NullPointerException when the parent queries the middle / footer prompt.
 *      (Catches the historical bug at Pipe.kt:8033 / 8047.)
 *   2. After the structural fix, the Mantle pipe carries the reasoning-pipe metadata
 *      contract (injectMiddlePrompt and injectFooterPrompt keys are Boolean, default false).
 *      This is the binary assertion that proves Mantle is a first-class provider — same
 *      contract shape as Bedrock / Ollama / OpenRouter / GenericOpenAI reasoning pipes.
 *   3. A caller who WANTS middle / footer injection on Mantle can opt in by setting
 *      pipeMetadata["injectMiddlePrompt"] = true on the Mantle pipe after construction —
 *      same override path that works for every other provider.
 *
 * The wire-traffic shape of Mantle is verified separately by [BedrockMantleLiveTest];
 * this smoke focuses on the metadata-injection contract that the bug report names. It
 * does NOT require AWS credentials to run — the metadata is populated inside
 * configureBedrockMantle before any network call.
 *
 * Gated by [INJECT_MIDDLE_PROMPT_LIVE_TEST]=true. Default `./gradlew test` is unaffected.
 *
 * Run with:
 *   INJECT_MIDDLE_PROMPT_LIVE_TEST=true \
 *   ./gradlew :TPipe-GenericOpenAI:test --tests "*InjectMiddlePromptLiveMantleTest"
 */
class InjectMiddlePromptLiveMantleTest
{
    private class TestPipe : Pipe()
    {
        override suspend fun generateText(promptInjector: String): String = ""
        override fun truncateModuleContext(): Pipe = this
    }

    private fun liveTestEnabled(): Boolean
    {
        return (System.getenv("INJECT_MIDDLE_PROMPT_LIVE_TEST") ?: "false") == "true"
    }

    @Test
    fun `Mantle reasoning pipe — metadata contract satisfied, getters do not throw`()
    {
        assumeTrue(liveTestEnabled(), "INJECT_MIDDLE_PROMPT_LIVE_TEST not set — skipping live smoke")

        val parent = TestPipe()
        parent.setPipeName("parent-inject-middle-prompt-smoke")
        parent.setSystemPrompt("You are a strict validator. Output JSON only.")
        parent.setMiddlePrompt("##INJECTED MIDDLE — emit JSON##")
        parent.setFooterPrompt("##INJECTED FOOTER — end of prompt##")

        // Build a Mantle-shaped reasoning pipe. Pre-fix this would bypass
        // ReasoningBuilder.assignDefaults; post-fix the structural layer in
        // configureBedrockMantle writes the metadata defaults.
        val reasoningPipe = GenericOpenAIPipe()
            .setBedrockMantle(region = "us-east-2", modelId = "google.gemma-4-31b")
        reasoningPipe.setPipeName("mantle-reasoning-inject-smoke")
        reasoningPipe.setApiKey("not-used-for-network")
        reasoningPipe.setMaxTokens(64)

        // (1) Both getters must NOT throw. Pre-fix: NPE. Post-fix: returns "" because
        // the default is false (matches ReasoningSettings.injectMiddlePrompt = false).
        parent.setReasoningPipe(reasoningPipe)
        assertEquals("", parent.getMiddlePromptForReasoning())
        assertEquals("", parent.getFooterPromptForReasoning())

        // (2) Mantle pipe carries the metadata contract — same as Bedrock / Ollama /
        // OpenRouter reasoning pipes. This is the structural-fix proof: Mantle is first-class.
        assertTrue(reasoningPipe.pipeMetadata.containsKey("injectMiddlePrompt"),
            "Mantle pipe missing injectMiddlePrompt metadata key — structural fix not applied")
        assertTrue(reasoningPipe.pipeMetadata.containsKey("injectFooterPrompt"),
            "Mantle pipe missing injectFooterPrompt metadata key — structural fix not applied")
        val middleKey = reasoningPipe.pipeMetadata["injectMiddlePrompt"]
        val footerKey = reasoningPipe.pipeMetadata["injectFooterPrompt"]
        assertNotNull(middleKey)
        assertNotNull(footerKey)
        assertTrue(middleKey is Boolean, "Mantle injectMiddlePrompt is not Boolean — type contract violated")
        assertTrue(footerKey is Boolean, "Mantle injectFooterPrompt is not Boolean — type contract violated")
        assertEquals(false, middleKey, "Mantle injectMiddlePrompt default should be false")
        assertEquals(false, footerKey, "Mantle injectFooterPrompt default should be false")
    }

    @Test
    fun `Mantle reasoning pipe — caller can opt in to middle and footer injection by overriding metadata`()
    {
        assumeTrue(liveTestEnabled(), "INJECT_MIDDLE_PROMPT_LIVE_TEST not set — skipping live smoke")

        val parent = TestPipe()
        parent.setPipeName("parent-opt-in-smoke")
        parent.setSystemPrompt("strict validator")
        parent.setMiddlePrompt("##INJECTED MIDDLE — emit JSON##")
        parent.setFooterPrompt("##INJECTED FOOTER — end of prompt##")

        // Caller WANTS injection. Override the Mantle defaults.
        val reasoningPipe = GenericOpenAIPipe()
            .setBedrockMantle(region = "us-east-2", modelId = "google.gemma-4-31b")
        reasoningPipe.setPipeName("mantle-opt-in-smoke")
        reasoningPipe.setApiKey("not-used-for-network")
        reasoningPipe.setMaxTokens(64)
        reasoningPipe.pipeMetadata["injectMiddlePrompt"] = true
        reasoningPipe.pipeMetadata["injectFooterPrompt"] = true

        parent.setReasoningPipe(reasoningPipe)

        // Now the parent pipe injects the configured text — same as Bedrock / Ollama /
        // OpenRouter / GenericOpenAI reasoning pipes would behave.
        assertEquals("##INJECTED MIDDLE — emit JSON##", parent.getMiddlePromptForReasoning())
        assertEquals("##INJECTED FOOTER — end of prompt##", parent.getFooterPromptForReasoning())
    }
}
