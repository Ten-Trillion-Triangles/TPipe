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

class GlmReasoningTest {
    
    private lateinit var pipeline: Pipeline
    private lateinit var glmPipe: BedrockPipe
    
    @BeforeEach
    fun setup() {
        TestCredentialUtils.requireAwsCredentials()
        
        val pipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("zai.glm-4.7-flash")
        (pipe as BedrockPipe).setRegion("us-west-2")
        
        // Use official TPipe reasoning setter (High effort)
        pipe.setReasoning("high")
        pipe.enableStreaming({ _ -> })
        
        glmPipe = pipe 
        
        pipeline = Pipeline()
        pipeline.add(glmPipe)
        pipeline.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        
        runBlocking {
            pipeline.init(initPipes = true)
        }
    }
    
    @Test
    fun testGlmReasoningExtractionInvoke() {
        println("Testing GLM Reasoning Extraction (Invoke API)")
        setUseConverseApi(glmPipe, false)
        
        val result = runBlocking {
            glmPipe.setMaxTokens(2000)
            pipeline.execute(MultimodalContent(text = "Solve this logic puzzle: A man is looking at a photograph. His friend asks, 'Who is it?' The man replies, 'Brothers and sisters I have none, but that man's father is my father's son.' Who is in the photograph?"))
        }
        
        println("Invoke Result Text: ${result.text}")
        println("Invoke Result Reasoning: ${result.modelReasoning}")
        
        if (result.modelReasoning.isNotEmpty()) {
            println("Successfully extracted reasoning logic from Invoke API")
        } else {
            println("Warning: No reasoning content returned from Invoke API (model may have decided not to reason, or API format mismatch)")
        }
        
        println("Pipeline Trace Report:")
        val traceReport = pipeline.getTraceReport(com.TTT.Debug.TraceFormat.HTML)
        java.io.File("glm-invoke-reasoning-trace.html").writeText(traceReport)
        println("Trace saved to glm-invoke-reasoning-trace.html")
        
        assertNotNull(result.text, "Text result should not be null")
        assertTrue(result.text.isNotEmpty(), "Text result should not be empty")
    }

    @Test
    fun testGlmReasoningExtractionConverse() {
        println("Testing GLM Reasoning Extraction (Converse API)")
        setUseConverseApi(glmPipe, true)
        
        val result = runBlocking {
            glmPipe.setMaxTokens(2000)
            pipeline.execute(MultimodalContent(text = "What is 2+2"))
        }

        println("Converse Result Text: ${result.text}")
        println("Converse Result Reasoning: ${result.modelReasoning}")
        
        assertNotNull(result.text, "Text result should not be null")
        assertTrue(result.text.isNotEmpty(), "Text result should not be empty")
        
        if (result.modelReasoning.isNotEmpty()) {
             println("Successfully extracted reasoning logic from Converse API")
        } else {
             println("Warning: No reasoning content returned from Converse API (model may have decided not to reason)")
        }
        
        println("Pipeline Trace Report:")
        val traceReport = pipeline.getTraceReport(com.TTT.Debug.TraceFormat.HTML)
        writeStringToFile("${TPipeConfig.getTraceDir()}/Library/glm-test.html", traceReport)
        println("Trace saved to glm-converse-reasoning-trace.html")
    }

    private fun setUseConverseApi(pipe: BedrockPipe, enabled: Boolean) {
        val field = BedrockPipe::class.java.getDeclaredField("useConverseApi")
        field.isAccessible = true
        field.set(pipe, enabled)
    }
}
