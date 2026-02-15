package bedrockPipe

import bedrockPipe.BedrockPipe
import com.TTT.Config.TPipeConfig
import com.TTT.Enums.ProviderName
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.writeStringToFile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Pipe.Pipe

class StreamingReasoningTest {
    
    @BeforeEach
    fun setup() {
        TestCredentialUtils.requireAwsCredentials()
    }
    
    @Test
    fun testDeepSeekStreamingReasoning() {
        println("Testing DeepSeek Streaming Reasoning Extraction")
        runStreamingReasoningTest("deepseek.v3-v1:0", "deepseek-streaming-reasoning-trace.html")
    }

    @Test
    fun testQwenStreamingReasoning() {
        println("Testing Qwen Streaming Reasoning Extraction")
        runStreamingReasoningTest("qwen.qwen3-vl-235b-a22b", "qwen-streaming-reasoning-trace.html")
    }

    private fun runStreamingReasoningTest(modelId: String, traceFileName: String, region: String = "us-west-2") {
        val pipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel(modelId)
        (pipe as BedrockPipe).setRegion(region)
        pipe.useConverseApi()
        
        // Enable reasoning
        pipe.setReasoning("high")
        
        val capturedChunks = mutableListOf<String>()
        pipe.enableStreaming({ chunk -> 
            capturedChunks.add(chunk)
            print(chunk) // Live output for visibility during test
        })
        
        pipe.enableMaxTokenOverflow()
        pipe.setReadTimeout(300)
        
        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        
        val result = runBlocking {
            pipeline.init(initPipes = true)
            pipe.setMaxTokens(4000)
            pipeline.execute(MultimodalContent(text = "What is 2+2? Reason step-by-step."))
        }
        
        println("\nModel: $modelId")
        println("Result Text: ${result.text}")
        println("Result Reasoning: ${result.modelReasoning}")
        println("Captured Chunks: ${capturedChunks.size}")
        
        val traceReport = pipeline.getTraceReport(com.TTT.Debug.TraceFormat.HTML)
        writeStringToFile("${TPipeConfig.getTraceDir()}/Library/$traceFileName", traceReport)
        println("Trace saved to $traceFileName")
        
        assertNotNull(result.text, "Text result should not be null")
        assertTrue(result.text.isNotEmpty(), "Text result should not be empty")
        assertTrue(capturedChunks.isNotEmpty(), "Should have captured streaming chunks")
        
        if (result.modelReasoning.isNotEmpty()) {
            println("Successfully extracted reasoning content from stream")
        } else {
            println("Warning: No reasoning content returned from stream")
        }
        
        // Verify reasoning is in trace metadata
        assertTrue(traceReport.contains("reasoningContent"), "Trace report should contain reasoningContent metadata")
    }
}
