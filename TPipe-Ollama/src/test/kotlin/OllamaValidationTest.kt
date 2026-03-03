package ollamaPipe

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class OllamaValidationTest {

    @Test
    fun testOllamaGeneration() = runBlocking {
        println("Starting OllamaPipe Validation Test...")

        val pipe = OllamaPipe()
            .setIP("127.0.0.1")
            .setPort(11434)
            .setModel("tinydolphin")
            .setSystemPrompt("You are a concise assistant.")
            .setTemperature(0.0)

        println("Initializing pipe...")
        pipe.init()

        println("\n--- Test 1: Basic Chat Generation ---")
        val result: MultimodalContent = pipe.execute(MultimodalContent("Say 'Ollama is working' and nothing else."))
        println("Result: ${result.text}")
        assertTrue(result.text.contains("Ollama", ignoreCase = true))
    }

    @Test
    fun testOllamaStreaming() = runBlocking {
        println("\n--- Test 2: Streaming Generation ---")
        val streamPipe = OllamaPipe()
        streamPipe.setModel("tinydolphin")
        streamPipe.setTemperature(0.0)

        streamPipe.enableStreaming({ chunk: String ->
            print(chunk)
        })

        print("Streamed: ")
        val streamResult = streamPipe.execute(MultimodalContent("Count from 1 to 3."))
        println("\nDone.")
        assertTrue(streamResult.text.isNotEmpty())
    }

    @Test
    fun testDeepSeekReasoning() = runBlocking {
        println("\n--- Test 3: DeepSeek-R1 Reasoning Extraction ---")

        val pipe = OllamaPipe()
            .setModel("deepseek-r1:1.5b")
            .setTemperature(0.0)

        pipe.init()

        val result = pipe.execute(MultimodalContent("Explain why 2+2 is 4. Be thoughtful."))

        println("REASONING: ${result.modelReasoning}")
        println("ANSWER: ${result.text}")

        assertTrue(result.text.isNotEmpty(), "Answer should not be empty")

        if (result.modelReasoning.isNotEmpty()) {
            println("Verified: Reasoning extracted.")
            assertFalse(result.text.contains("<think>"), "Answer should not contain think tags")
        } else {
            println("Note: No reasoning extracted (model may not have used tags for this response).")
        }
    }
}
