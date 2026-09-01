package codexPipe

import codexPipe.auth.CodexAuthManager
import codexPipe.model.CodexModelCatalogClient
import genericOpenAIPipe.env.ReasoningConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Opt-in end-to-end checks for a manually authenticated Codex session.
 *
 * Run with `TPIPE_CODEX_LIVE_TEST=true` after device login or file-backed CLI
 * import. The default test suite skips this class's network activity.
 */
class CodexLiveTest
{
    @Test
    fun liveCodexCatalogTextStreamingAndJsonSmokeTest() = runBlocking<Unit>
    {
        assumeTrue(System.getenv("TPIPE_CODEX_LIVE_TEST") == "true")

        val auth = CodexAuthManager.default()
        val catalog = CodexModelCatalogClient(auth)
        val models = catalog.listModels()
        assertFalse(models.isEmpty(), "Codex model discovery returned no models")

        val model = System.getenv("TPIPE_CODEX_MODEL")
            ?.takeIf { it.isNotBlank() }
            ?: models.first().slug
        val selected = models.firstOrNull { it.slug == model }
        val reasoning = selected?.supportedReasoningLevels?.firstOrNull()

        val streamedChunks = mutableListOf<String>()
        val streamingPipe = CodexPipes.create(model, auth)
        try
        {
            streamingPipe.setSystemPrompt("Answer concisely and identify the active model.")
            if(reasoning != null)
            {
                streamingPipe.setReasoningConfig(ReasoningConfig(effort = reasoning))
            }
            streamingPipe.setStreamingCallback(callback = { chunk -> streamedChunks.add(chunk) })
            streamingPipe.init()
            val streamed = streamingPipe.execute("Reply with one short sentence.")
            assertTrue(streamed.isNotBlank(), "Codex streaming response was empty")
            assertTrue(streamedChunks.isNotEmpty(), "Codex streaming callback received no chunks")
        }
        finally
        {
            streamingPipe.abort()
        }

        val nonStreamingPipe = CodexPipes.create(model, auth)
        try
        {
            nonStreamingPipe.setSystemPrompt("Return a JSON object with an answer string.")
            nonStreamingPipe.setJsonOutput("""{"answer":"string"}""")
            nonStreamingPipe.init()
            val response = nonStreamingPipe.execute("Return the requested JSON object.")
            assertTrue(response.isNotBlank(), "Codex internally streamed non-streaming response was empty")
        }
        finally
        {
            nonStreamingPipe.abort()
        }
    }
}
