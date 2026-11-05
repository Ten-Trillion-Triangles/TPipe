package ollamaPipe

import com.TTT.Util.serialize
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pipe-level tests for OLL-A: Ollama's native `think` field (bool OR level) is now driven by the
 * base-Pipe reasoning flag (`useModelReasoning`, set via enableThink()/setReasoning()) plus the
 * Ollama-specific `thinkLevel`. Previously `think` was a dead flag — set by enableThink() but
 * never serialized into the request, so thinking could not be requested.
 */
class OllamaThinkPipeTest
{
    @Test
    fun thinkOffByDefault()
    {
        val pipe = OllamaPipe()
        assertNull(pipe.buildThinkWireValue(), "no think value when reasoning is off")
    }

    @Test
    fun enableThinkSendsBoolTrue()
    {
        val pipe = OllamaPipe()
        pipe.enableThink()
        assertEquals(JsonPrimitive(true), pipe.buildThinkWireValue())
    }

    @Test
    fun thinkLevelOverridesBool()
    {
        val pipe = OllamaPipe()
        pipe.setThinkLevel("high")
        assertEquals(JsonPrimitive("high"), pipe.buildThinkWireValue())
    }

    @Test
    fun disableThinkClearsLevelAndFlag()
    {
        val pipe = OllamaPipe()
        pipe.setThinkLevel("high")
        pipe.disableThink()
        assertNull(pipe.buildThinkWireValue())
    }
}
