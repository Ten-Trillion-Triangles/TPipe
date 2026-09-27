package ollamaPipe

import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.ReasoningStream
import env.ChatRequest
import env.GeneratedRequest
import env.OllamaMessage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stream-loop tests for Ollama reasoning-stream delivery: fake NDJSON chunks
 * are fed through the /api/chat and /api/generate streaming loops via the
 * pipe's test seams, and the collected callback bytes are asserted against
 * the [ReasoningStream] OPEN_TAG / CLOSE_TAG framing contract.
 */
class OllamaReasoningStreamTest
{
    //==== Test data builders ==================================================//

    private fun chatLines(thinking: List<String>, content: List<String>): List<String>
    {
        val lines = mutableListOf<String>()
        thinking.forEach { delta ->
            lines.add("""{"model":"test","created_at":"2026-09-27T00:00:00Z","message":{"role":"assistant","content":"","thinking":"$delta"},"done":false}""")
        }
        content.forEach { delta ->
            lines.add("""{"model":"test","created_at":"2026-09-27T00:00:00Z","message":{"role":"assistant","content":"$delta","thinking":""},"done":false}""")
        }
        lines.add("""{"model":"test","created_at":"2026-09-27T00:00:00Z","message":{"role":"assistant","content":"","thinking":""},"done":true}""")
        return lines
    }

    private fun generateLines(thinking: List<String>, content: List<String>): List<String>
    {
        val lines = mutableListOf<String>()
        thinking.forEach { delta ->
            lines.add("""{"model":"test","created_at":"2026-09-27T00:00:00Z","response":"","thinking":"$delta","done":false}""")
        }
        content.forEach { delta ->
            lines.add("""{"model":"test","created_at":"2026-09-27T00:00:00Z","response":"$delta","thinking":"","done":false}""")
        }
        lines.add("""{"model":"test","created_at":"2026-09-27T00:00:00Z","response":"","thinking":"","done":true}""")
        return lines
    }

    private fun chatRequest(): ChatRequest
    {
        return ChatRequest(
            model = "test",
            messages = listOf(OllamaMessage(role = "user", content = "hello")),
            stream = true
        )
    }

    private fun generateRequest(): GeneratedRequest
    {
        return GeneratedRequest(
            model = "test",
            prompt = "hello",
            stream = true
        )
    }

    //==== /api/chat loop ======================================================//

    @Test
    fun chatStreamDeliversThinkingAsReasoningSegment() = runBlocking {

        val collected = mutableListOf<String>()
        val pipe = OllamaPipe()
        pipe.setModel("test")
        pipe.setStreamingCallback { chunk ->
            collected.add(chunk)
        }
        pipe.chatStreamLinesForTest = chatLines(listOf("Considering", "the options"), listOf("4"))

        val result: MultimodalContent = pipe.executeChatStreamForTest(chatRequest())

        assertEquals("4", result.text)
        assertEquals(
            listOf(
                ReasoningStream.OPEN_TAG,
                "Considering",
                "the options",
                ReasoningStream.CLOSE_TAG,
                "4"
            ),
            collected
        )
    }

    @Test
    fun chatStreamWithReasoningDeliveryOffEmitsNoMarkers() = runBlocking {

        val collected = mutableListOf<String>()
        val pipe = OllamaPipe()
        pipe.setModel("test")
        pipe.setStreamModelReasoning(false)
        pipe.setStreamingCallback { chunk ->
            collected.add(chunk)
        }
        pipe.chatStreamLinesForTest = chatLines(listOf("Considering"), listOf("4"))

        val result: MultimodalContent = pipe.executeChatStreamForTest(chatRequest())

        assertEquals("4", result.text)
        assertEquals(listOf("4"), collected)
        assertTrue(collected.none { it == ReasoningStream.OPEN_TAG || it == ReasoningStream.CLOSE_TAG })
    }

    @Test
    fun chatStreamWithoutThinkingEmitsContentOnly() = runBlocking {

        val collected = mutableListOf<String>()
        val pipe = OllamaPipe()
        pipe.setModel("test")
        pipe.setStreamingCallback { chunk ->
            collected.add(chunk)
        }
        pipe.chatStreamLinesForTest = chatLines(emptyList(), listOf("Hello", " world"))

        val result: MultimodalContent = pipe.executeChatStreamForTest(chatRequest())

        assertEquals("Hello world", result.text)
        assertEquals(listOf("Hello", " world"), collected)
    }

    //==== /api/generate loop ==================================================//

    @Test
    fun generateStreamDeliversThinkingAsReasoningSegment() = runBlocking {

        val collected = mutableListOf<String>()
        val pipe = OllamaPipe()
        pipe.setModel("test")
        pipe.useLegacyApi()
        pipe.setStreamingCallback { chunk ->
            collected.add(chunk)
        }
        pipe.generateStreamLinesForTest = generateLines(listOf("Tracing", " the steps"), listOf("42"))

        val result: MultimodalContent = pipe.executeGenerateStreamForTest(generateRequest())

        assertEquals("42", result.text)
        assertEquals(
            listOf(
                ReasoningStream.OPEN_TAG,
                "Tracing",
                " the steps",
                ReasoningStream.CLOSE_TAG,
                "42"
            ),
            collected
        )
    }

    @Test
    fun generateStreamWithReasoningDeliveryOffEmitsNoMarkers() = runBlocking {

        val collected = mutableListOf<String>()
        val pipe = OllamaPipe()
        pipe.setModel("test")
        pipe.useLegacyApi()
        pipe.setStreamModelReasoning(false)
        pipe.setStreamingCallback { chunk ->
            collected.add(chunk)
        }
        pipe.generateStreamLinesForTest = generateLines(listOf("Tracing"), listOf("42"))

        val result: MultimodalContent = pipe.executeGenerateStreamForTest(generateRequest())

        assertEquals("42", result.text)
        assertEquals(listOf("42"), collected)
        assertTrue(collected.none { it == ReasoningStream.OPEN_TAG || it == ReasoningStream.CLOSE_TAG })
    }
}
