package genericOpenAIPipe.mantle

import genericOpenAIPipe.api.ApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the Mantle GPT-5.6 explicit-caching support guard.
 *
 * Pins:
 *   - [supportsMantleGpt56ExplicitCaching] returns true ONLY when both the
 *     model prefix matches (`openai.gpt-5.6-`) AND the API mode is
 *     Responses. Any other combination returns false.
 *   - [requireMantleGpt56ExplicitCachingSupport] throws
 *     [IllegalStateException] with a message naming the model when support
 *     is missing. The throw-on-misuse shape is critical — silently ignoring
 *     a requested cost-control mechanism would make usage and billing
 *     impossible to reason about.
 */
class MantleGpt56GuardTest
{
    @Test
    fun testSupportsReturnsTrueForGpt56SolOnResponsesApi()
    {
        assertTrue(
            supportsMantleGpt56ExplicitCaching("openai.gpt-5.6-sol", ApiMode.OpenAIResponses)
        )
    }

    @Test
    fun testSupportsReturnsTrueForGpt56LunaOnResponsesApi()
    {
        assertTrue(
            supportsMantleGpt56ExplicitCaching("openai.gpt-5.6-luna", ApiMode.OpenAIResponses)
        )
    }

    @Test
    fun testSupportsReturnsFalseForGpt56OnChatCompletionsApi()
    {
        assertFalse(
            supportsMantleGpt56ExplicitCaching("openai.gpt-5.6-luna", ApiMode.OpenAI)
        )
    }

    @Test
    fun testSupportsReturnsFalseForNonGpt56Model()
    {
        assertFalse(
            supportsMantleGpt56ExplicitCaching("google.gemma-4-e2b", ApiMode.OpenAIResponses)
        )
    }

    @Test
    fun testRequireThrowsForGpt56OnChatCompletionsApi()
    {
        val ex = assertFailsWith<IllegalStateException> {
            requireMantleGpt56ExplicitCachingSupport(
                model = "openai.gpt-5.6-luna",
                apiMode = ApiMode.OpenAI,
            )
        }
        // Message must name the model so the offending call site is identifiable.
        assertTrue(
            ex.message!!.contains("openai.gpt-5.6-luna"),
            "Expected the throw message to name the model; got: ${ex.message}",
        )
    }

    @Test
    fun testRequireThrowsForGemmaModelOnResponsesApi()
    {
        assertFailsWith<IllegalStateException> {
            requireMantleGpt56ExplicitCachingSupport(
                model = "google.gemma-4-e2b",
                apiMode = ApiMode.OpenAIResponses,
            )
        }
    }

    @Test
    fun testRequireDoesNotThrowForValidCombination()
    {
        // Sanity: when both axes line up, the guard is a no-op.
        requireMantleGpt56ExplicitCachingSupport(
            model = "openai.gpt-5.6-terra",
            apiMode = ApiMode.OpenAIResponses,
        )
    }
}
