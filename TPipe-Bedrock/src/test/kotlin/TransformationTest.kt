import bedrockPipe.BedrockPipe
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TransformationTest
{
    
    @Test
    fun testBasicTransformation() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING BASIC TRANSFORMATION ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.setSystemPrompt("You are a helpful assistant.")
            pipe.setMaxTokens(150)
            pipe.setTemperature(0.3)
            
            pipe.setTransformationFunction { content ->
                content.text = "[TRANSFORMED] ${content.text} [END]"
                content
            }
            
            pipe.init()
            
            val result = pipe.execute("What is 2+2?")
            
            println("Result: $result")
            
            assertNotNull(result)
            assertTrue(result.contains("[TRANSFORMED]"))
            assertTrue(result.contains("[END]"))
        }
    }
    
    @Test
    fun testMultiStepTransformation() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING MULTI-STEP TRANSFORMATION ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.setSystemPrompt("You are a code assistant.")
            pipe.setMaxTokens(200)
            pipe.setTemperature(0.2)
            
            pipe.setTransformationFunction { content ->
                val step1 = content.text.uppercase()
                val step2 = "### CODE RESPONSE ###\n$step1\n### END ###"
                val step3 = step2.replace("FUNCTION", "🔧 FUNCTION")
                content.text = step3
                content
            }
            
            pipe.init()
            
            val result = pipe.execute("Write a simple function to add two numbers.")
            
            println("Multi-step result: $result")
            
            assertNotNull(result)
            assertTrue(result.contains("### CODE RESPONSE ###"))
            assertTrue(result.contains("### END ###"))
        }
    }
    
    @Test
    fun testConditionalTransformation() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING CONDITIONAL TRANSFORMATION ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.setSystemPrompt("You are a helpful assistant.")
            pipe.setMaxTokens(100)
            pipe.setTemperature(0.4)
            
            pipe.setTransformationFunction { content ->
                val newText = when {
                    content.text.lowercase().contains("error") -> "❌ ERROR: ${content.text}"
                    content.text.lowercase().contains("success") -> "✅ SUCCESS: ${content.text}"
                    content.text.length > 50 -> "📝 LONG: ${content.text}"
                    else -> "📄 SHORT: ${content.text}"
                }
                content.text = newText
                content
            }
            
            pipe.init()
            
            val result = pipe.execute("Explain machine learning briefly.")
            
            println("Conditional result: $result")
            
            assertNotNull(result)
            assertTrue(result.startsWith("📝 LONG:") || result.startsWith("📄 SHORT:"))
        }
    }
    
    @Test
    fun testChainedTransformations() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING CHAINED TRANSFORMATIONS ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            // First pipe: Generate content
            val pipe1 = BedrockPipe()
            pipe1.setModel("deepseek.r1-v1:0")
            pipe1.setRegion("us-east-2")
            pipe1.setSystemPrompt("Generate a simple explanation.")
            pipe1.setMaxTokens(100)
            pipe1.setTemperature(0.3)
            
            pipe1.setTransformationFunction { content ->
                content.text = "STAGE1: ${content.text}"
                content
            }
            
            pipe1.init()
            
            val stage1Result = pipe1.execute("What is recursion?")
            
            // Second pipe: Process the result
            val pipe2 = BedrockPipe()
            pipe2.setModel("deepseek.r1-v1:0")
            pipe2.setRegion("us-east-2")
            pipe2.setSystemPrompt("Summarize the following text in one sentence.")
            pipe2.setMaxTokens(50)
            pipe2.setTemperature(0.1)
            
            pipe2.setTransformationFunction { content ->
                content.text = "STAGE2: ${content.text}"
                content
            }
            
            pipe2.init()
            
            val stage2Result = pipe2.execute(stage1Result)
            
            println("Stage 1: $stage1Result")
            println("Stage 2: $stage2Result")
            
            assertNotNull(stage1Result)
            assertNotNull(stage2Result)
            assertTrue(stage1Result.contains("STAGE1:"))
            assertTrue(stage2Result.contains("STAGE2:"))
        }
    }
    
    @Test
    fun testDataExtractionTransformation() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING DATA EXTRACTION TRANSFORMATION ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.setSystemPrompt("Provide a technical explanation with examples.")
            pipe.setMaxTokens(200)
            pipe.setTemperature(0.2)
            
            pipe.setTransformationFunction { content ->
                val wordCount = content.text.split("\\s+".toRegex()).size
                val hasExample = content.text.lowercase().contains("example")
                val hasTechnical = content.text.lowercase().contains("algorithm") || content.text.lowercase().contains("complexity")
                
                val newText = """
                📊 ANALYSIS REPORT
                ==================
                Word Count: $wordCount
                Contains Example: $hasExample
                Technical Content: $hasTechnical
                
                📝 CONTENT:
                ${content.text}
                
                ✅ ANALYSIS COMPLETE
                """.trimIndent()
                
                content.text = newText
                content
            }
            
            pipe.init()
            
            val result = pipe.execute("Explain sorting algorithms.")
            
            println("Extraction result: $result")
            
            assertNotNull(result)
            assertTrue(result.contains("📊 ANALYSIS REPORT"))
            assertTrue(result.contains("Word Count:"))
            assertTrue(result.contains("✅ ANALYSIS COMPLETE"))
        }
    }
}