package bedrockPipe

import bedrockPipe.BedrockPipe
import com.TTT.Config.TPipeConfig
import com.TTT.Enums.ProviderName
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Pipe.Pipe
import com.TTT.Util.writeStringToFile

class QwenNextReasoningTest {
    
    private lateinit var pipeline: Pipeline
    private lateinit var qwenPipe: BedrockPipe
    
    @BeforeEach
    fun setup() {
        TestCredentialUtils.requireAwsCredentials()
        
        val pipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("qwen.qwen3-next-80b-a3b")
        (pipe as BedrockPipe).setRegion("us-west-2")
        
        // Use official TPipe reasoning setter (High effort)
        pipe.setReasoning("high")
        pipe.enableStreaming({ _ -> })
        pipe.setReadTimeout(300) // 5 minutes timeout
        pipe.enableMaxTokenOverflow()
        
        qwenPipe = pipe 
        
        pipeline = Pipeline()
        pipeline.add(qwenPipe)
        pipeline.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        
        runBlocking {
            pipeline.init(initPipes = true)
        }
    }
    
    @Test
    fun testQwenNextReasoningExtractionInvoke() {
        println("Testing Qwen Next Reasoning Extraction (Invoke API)")
        setUseConverseApi(qwenPipe, false)
        qwenPipe.enableStreaming(null)
        
        val result = runBlocking {
            qwenPipe.setMaxTokens(4000)
            pipeline.execute(MultimodalContent(text = "Solve this logic puzzle: A man is looking at a photograph. His friend asks, 'Who is it?' The man replies, 'Brothers and sisters I have none, but that man's father is my father's son.' Who is in the photograph? Reason step-by-step."))
        }
        
        println("Invoke Result Text: ${result.text}")
        println("Invoke Result Reasoning: ${result.modelReasoning}")
        
        if (result.modelReasoning.isNotEmpty()) {
            println("Successfully extracted reasoning logic from Invoke API")
        } else {
            println("Warning: No reasoning content returned from Invoke API")
        }
        
        println("Pipeline Trace Report:")
        val traceReport = pipeline.getTraceReport(com.TTT.Debug.TraceFormat.HTML)
        writeStringToFile("${TPipeConfig.getTraceDir()}/Library/qwen-next-invoke-reasoning-trace.html", traceReport)
        
        assertNotNull(result.text, "Text result should not be null")
        assertTrue(result.text.isNotEmpty(), "Text result should not be empty")
    }

    @Test
    fun testQwenNextReasoningExtractionConverse() {
        println("Testing Qwen Next Reasoning Extraction (Converse API)")
        setUseConverseApi(qwenPipe, true)
        
        val result = runBlocking {
            qwenPipe.setMaxTokens(4000)
            pipeline.execute(MultimodalContent(text = "Solve this logic puzzle: A man is looking at a photograph. His friend asks, 'Who is it?' The man replies, 'Brothers and sisters I have none, but that man's father is my father's son.' Who is in the photograph? Reason step-by-step."))
        }

        println("Converse Result Text: ${result.text}")
        println("Converse Result Reasoning: ${result.modelReasoning}")
        
        println("Pipeline Trace Report:")
        val traceReport = pipeline.getTraceReport(com.TTT.Debug.TraceFormat.HTML)
        writeStringToFile("${TPipeConfig.getTraceDir()}/Library/qwen-next-converse-reasoning-trace.html", traceReport)
        
        assertNotNull(result.text, "Text result should not be null")
        assertTrue(result.text.isNotEmpty(), "Text result should not be empty")
    }

    private fun setUseConverseApi(pipe: BedrockPipe, enabled: Boolean) {
        val field = BedrockPipe::class.java.getDeclaredField("useConverseApi")
        field.isAccessible = true
        field.set(pipe, enabled)
    }
}
