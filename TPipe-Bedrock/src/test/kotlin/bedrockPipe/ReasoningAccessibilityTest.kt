package bedrockPipe

import bedrockPipe.BedrockPipe
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests to verify that model reasoning is accessible through MultimodalContent.modelReasoning
 * in the base BedrockPipe class (not just BedrockMultimodalPipe).
 */
class ReasoningAccessibilityTest {

    @Test
    fun testQwen3ReasoningAccessibility() = runBlocking {
        val pipe = BedrockPipe()
        pipe.setModel("qwen.qwen3-32b-v1:0")
        pipe.setReasoning()
        
        val input = MultimodalContent("Think step by step: What is 2+2?")
        val result = pipe.generateContent(input)
        
        // Verify text response exists
        assertNotNull(result.text)
        assertNotEquals("", result.text.trim())
        
        // Verify reasoning field is accessible (may be empty if model doesn't provide reasoning)
        assertNotNull(result.modelReasoning)
        
        println("Text: ${result.text}")
        println("Reasoning: ${result.modelReasoning}")
        println("Has reasoning: ${result.modelReasoning.isNotEmpty()}")
    }

    @Test
    fun testDeepSeekReasoningAccessibility() = runBlocking {
        val pipe = BedrockPipe()
        pipe.setModel("deepseek.deepseek-r1-distill-llama-70b")
        pipe.setReasoning()
        
        val input = MultimodalContent("Analyze this problem: How many days are in February during a leap year?")
        val result = pipe.generateContent(input)
        
        // Verify text response exists
        assertNotNull(result.text)
        assertNotEquals("", result.text.trim())
        
        // Verify reasoning field is accessible
        assertNotNull(result.modelReasoning)
        
        println("Text: ${result.text}")
        println("Reasoning: ${result.modelReasoning}")
        println("Has reasoning: ${result.modelReasoning.isNotEmpty()}")
    }

    @Test
    fun testClaudeNoReasoningButFieldAccessible() = runBlocking {
        val pipe = BedrockPipe()
        pipe.setModel("anthropic.claude-3-sonnet-20240229-v1:0")
        
        val input = MultimodalContent("What is the capital of France?")
        val result = pipe.generateContent(input)
        
        // Verify text response exists
        assertNotNull(result.text)
        assertNotEquals("", result.text.trim())
        
        // Verify reasoning field is accessible (should be empty for Claude)
        assertNotNull(result.modelReasoning)
        assertEquals("", result.modelReasoning)
        
        println("Text: ${result.text}")
        println("Reasoning: ${result.modelReasoning}")
        println("Has reasoning: ${result.modelReasoning.isNotEmpty()}")
    }

    @Test
    fun testReasoningFieldNotNullEvenOnError() = runBlocking {
        val pipe = BedrockPipe()
        pipe.setModel("invalid-model-id")
        
        val input = MultimodalContent("Test prompt")
        val result = pipe.generateContent(input)
        
        // Even on error, fields should not be null
        assertNotNull(result.text)
        assertNotNull(result.modelReasoning)
        
        println("Error case - Text: '${result.text}'")
        println("Error case - Reasoning: '${result.modelReasoning}'")
    }
}
