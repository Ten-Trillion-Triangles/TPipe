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
    fun testBedrockMultimodalPipeReasoning() {
        println("Testing BedrockMultimodalPipe Reasoning Extraction (GLM)")
        val pipe = BedrockMultimodalPipe()
            .setProvider(ProviderName.Aws)
            .setModel("zai.glm-4.7-flash")
        (pipe as BedrockMultimodalPipe).setRegion("us-west-2")
        pipe.useConverseApi()
        pipe.setReasoning("high")
        
        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        
        val result = runBlocking {
            pipeline.init(initPipes = true)
            pipe.setMaxTokens(2000)
            pipeline.execute(MultimodalContent(text = "Solve this logic puzzle: A man is looking at a photograph. His friend asks, 'Who is it?' The man replies, 'Brothers and sisters I have none, but that man's father is my father's son.' Who is in the photograph?"))
        }
        
        println("Result Text: ${result.text}")
        println("Result Reasoning: ${result.modelReasoning}")
        
        val traceReport = pipeline.getTraceReport(com.TTT.Debug.TraceFormat.HTML)
        writeStringToFile("${TPipeConfig.getTraceDir()}/Library/multimodal-glm-trace.html", traceReport)
        
        assertNotNull(result.text, "Text result should not be null")
        assertTrue(result.modelReasoning.isNotEmpty(), "Reasoning should not be empty for GLM High Reasoning")
    }

    @Test
    fun testReasoningEmissionToCallback() {
        println("Testing Reasoning Emission to Callback (DeepSeek)")
        val modelId = "deepseek.v3-v1:0"
        val pipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel(modelId)
        (pipe as BedrockPipe).setRegion("us-west-2")
        pipe.useConverseApi()
        pipe.setReasoning("high")
        
        val capturedChunks = mutableListOf<String>()
        pipe.enableStreaming({ chunk -> 
            capturedChunks.add(chunk)
        })
        
        val pipeline = Pipeline()
        pipeline.add(pipe)
        
        val result = runBlocking {
            pipeline.init(initPipes = true)
            pipe.setMaxTokens(2000)
            pipeline.execute(MultimodalContent(text = "What is 2+2? Reason briefly."))
        }
        
        val totalCapturedLength = capturedChunks.sumOf { it.length }
        val expectedLength = result.text.length + result.modelReasoning.length
        
        println("Captured Chunks: ${capturedChunks.size}")
        println("Total Captured Length: $totalCapturedLength")
        println("Result Text Length: ${result.text.length}")
        println("Result Reasoning Length: ${result.modelReasoning.length}")
        println("Expected Total Length: $expectedLength")
        
        assertTrue(result.modelReasoning.isNotEmpty(), "Model should have produced reasoning")
        
        // The captured chunks should roughly contain both text and reasoning.
        // Some overhead/formatting might differ slightly but it should be much larger than just text.
        assertTrue(totalCapturedLength >= expectedLength, "Captured chunks should contain both text and reasoning")
        
        // Verify we can disable it
        capturedChunks.clear()
        pipe.enableStreaming({ chunk -> capturedChunks.add(chunk) }, streamReasoning = false)
        
        val resultNoReasoningStream = runBlocking {
            pipeline.execute(MultimodalContent(text = "What is 2+2?"))
        }
        
        val totalCapturedLengthNoReasoning = capturedChunks.sumOf { it.length }
        println("No-Reasoning-Stream Captured Length: $totalCapturedLengthNoReasoning")
        println("No-Reasoning-Stream Result Text Length: ${resultNoReasoningStream.text.length}")
        
        // When reasoning streaming is off, captured length should be close to text length
        assertTrue(totalCapturedLengthNoReasoning < totalCapturedLength, "Should have captured less data when reasoning stream is disabled")
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
