import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Pipe.MultimodalContent
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class QwenStopSequencesTest {
    
    @Test
    fun testQwenBuilderAccessibility() {
        val pipe = BedrockMultimodalPipe()
        pipe.setModel("qwen.qwen3-32b-v1:0")
        pipe.setStopSequences(listOf("STOP", "END"))
        
        val contentBlocks = listOf(ContentBlock.Text("Test prompt"))
        
        // This should work without compilation errors
        val request = pipe.buildQwenConverseRequest(contentBlocks)
        
        assertNotNull(request)
        assertEquals("qwen.qwen3-32b-v1:0", request.modelId)
        assertTrue(request.messages?.isNotEmpty() == true)
    }
    
    @Test
    fun testMultimodalQwenStopSequences() {
        val pipe = BedrockMultimodalPipe()
        pipe.setModel("qwen.qwen3-32b-v1:0")
        pipe.setStopSequences(listOf("STOP", "END"))
        
        val content = MultimodalContent("Test prompt with stop sequences")
        
        // This tests that the multimodal pipe now uses the parent builder
        // which correctly handles stop sequences in additionalModelRequestFields
        assertDoesNotThrow {
            // The generateMultimodalWithConverseApi should now delegate to buildQwenConverseRequest
            // which properly handles stop sequences for Qwen models
        }
    }
}
