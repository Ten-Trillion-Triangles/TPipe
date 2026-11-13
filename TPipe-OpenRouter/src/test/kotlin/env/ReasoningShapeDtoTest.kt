package env

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** OR-E: reasoning shape is now {effort, summary} + a flat request-level `reasoning_effort`. */
class ReasoningShapeDtoTest
{
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun reasoningConfigSerializesCurrentShape()
    {
        val s = json.encodeToString(ReasoningConfig.serializer(), ReasoningConfig(effort = "high", summary = "detailed"))
        val p = json.decodeFromString(ReasoningConfig.serializer(), s)
        assertEquals("high", p.effort)
        assertEquals("detailed", p.summary)
    }

    @Test
    fun requestCarriesFlatReasoningEffort()
    {
        val s = json.encodeToString(
            OpenRouterChatRequest.serializer(),
            OpenRouterChatRequest(model = "m", messages = listOf(ChatMessage(role = "user", content = "hi")), reasoningEffort = "medium")
        )
        val obj = json.parseToJsonElement(s).let { (it as kotlinx.serialization.json.JsonObject) }
        assertNotNull(obj["reasoning_effort"], "flat reasoning_effort must serialize on the request")
    }

    @Test
    fun legacyReasoningFieldsStillParse()
    {
        val p = json.parseToJsonElement("""{"effort":"low","max_tokens":100,"exclude":true,"enabled":true}""")
        val cfg = json.decodeFromJsonElement(ReasoningConfig.serializer(), p)
        assertEquals("low", cfg.effort)
        @Suppress("DEPRECATION")
        assertEquals(true, cfg.enabled)
    }
}
