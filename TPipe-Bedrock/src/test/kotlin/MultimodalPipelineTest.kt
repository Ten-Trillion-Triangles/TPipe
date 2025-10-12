import bedrockPipe.BedrockPipe
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.Pipeline
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MultimodalPipelineTest {
    
    @Test
    fun testMultimodalPipelineExecution() {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING MULTIMODAL PIPELINE EXECUTION ===")
        
        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            
            // Create first pipe - content generator
            val generatorPipe = BedrockPipe()
            generatorPipe.setModel("deepseek.r1-v1:0")
            generatorPipe.setRegion("us-east-2")
            generatorPipe.setSystemPrompt("You are a content generator.")
            generatorPipe.setMaxTokens(100)
            generatorPipe.setTemperature(0.3)
            
            generatorPipe.setTransformationFunction { content ->
                content.text = "[GENERATED] ${content.text}"
                content
            }
            
            // Create second pipe - content processor
            val processorPipe = BedrockPipe()
            processorPipe.setModel("deepseek.r1-v1:0")
            processorPipe.setRegion("us-east-2")
            processorPipe.setSystemPrompt("Summarize the following content in one sentence.")
            processorPipe.setMaxTokens(50)
            processorPipe.setTemperature(0.1)
            
            processorPipe.setTransformationFunction { content ->
                content.text = "[PROCESSED] ${content.text}"
                content
            }
            
            // Initialize pipes
            generatorPipe.init()
            processorPipe.init()
            
            // Create pipeline
            val pipeline = Pipeline()
            pipeline.add(generatorPipe)
            pipeline.add(processorPipe)
            pipeline.init()
            
            // Test string-based execution (legacy)
            val stringResult = pipeline.execute("What is artificial intelligence?")
            
            println("String result: $stringResult")
            
            // Test multimodal execution
            val multimodalInput = MultimodalContent(text = "What is machine learning?", binaryContent = mutableListOf())
            val multimodalResult = pipeline.execute(multimodalInput)
            
            println("Multimodal result: ${multimodalResult.text}")
            
            // Assertions
            assertNotNull(stringResult)
            assertTrue(stringResult.isNotEmpty())
            assertTrue(stringResult.contains("[PROCESSED]"))
            
            assertNotNull(multimodalResult)
            assertTrue(multimodalResult.text.isNotEmpty())
            assertTrue(multimodalResult.text.contains("[PROCESSED]"))
            assertNotNull(multimodalResult.binaryContent)
        }
    }
}