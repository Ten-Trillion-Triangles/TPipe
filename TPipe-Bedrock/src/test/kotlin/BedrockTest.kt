import bedrockPipe.BedrockPipe
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BedrockTest
{
    
    @Test
    fun testDeepSeekR1InvokeAPI() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING DEEPSEEK R1 INVOKE API ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.setSystemPrompt("You are a helpful AI assistant.")
            pipe.setUserPrompt("Please respond to:")
            pipe.setMaxTokens(500)
            pipe.setTemperature(0.7)
            
            pipe.init()
            
            val response = pipe.execute("Explain the concept of recursion in programming with a simple example.")
            
            println("\n=== INVOKE API RESPONSE ===")
            println("Response length: ${response.length}")
            println("Response: $response")
            println("=== END RESPONSE ===")
            
            assertNotNull(response, "Response should not be null")
            assertTrue(response.isNotEmpty(), "Response should not be empty")
            assertTrue(response.length > 100, "Response should be substantial with 500 token limit")
        }
    }
    
    @Test
    fun testDeepSeekR1ConverseAPI() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING DEEPSEEK R1 CONVERSE API ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.useConverseApi()
            pipe.setSystemPrompt("You are a helpful AI assistant.")
            pipe.setUserPrompt("Please respond to:")
            pipe.setMaxTokens(500)
            pipe.setTemperature(0.7)
            
            pipe.init()
            
            val response = pipe.execute("What are the key differences between functional and object-oriented programming?")
            
            println("\n=== CONVERSE API RESPONSE ===")
            println("Response length: ${response.length}")
            println("Response: $response")
            println("=== END RESPONSE ===")
            
            assertNotNull(response, "Response should not be null")
            assertTrue(response.isNotEmpty(), "Response should not be empty")
            assertTrue(response.length > 100, "Response should be substantial with 500 token limit")
        }
    }
    
    @Test
    fun testValidatedPipeline() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING BASIC EXECUTE PIPELINE ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.setSystemPrompt("You are a helpful assistant.")
            pipe.setMaxTokens(200)
            pipe.setTemperature(0.3)
            
            pipe.init()
            
            val result = pipe.execute("What is 2+2?")
            
            println("\n=== BASIC EXECUTE RESULT ===")
            println("Result: $result")
            
            assertNotNull(result, "Result should not be null")
            assertTrue(result.isNotEmpty(), "Result should not be empty")
        }
    }
    
    @Test
    fun testPipelineWithFailureRecovery() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING PIPELINE WITH FAILURE RECOVERY ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.setSystemPrompt("You are a math solver.")
            pipe.setMaxTokens(150)
            pipe.setTemperature(0.1)
            
            // Strict validator requiring specific format
            pipe.setValidatorFunction { content ->
                val hasAnswer = content.text.lowercase().contains("answer:")
                val hasNumber = content.text.contains(Regex("\\d+"))
                val isValid = hasAnswer && hasNumber
                println("[VALIDATOR] Answer format: $hasAnswer, Has number: $hasNumber, Valid: $isValid")
                isValid
            }
            
            // Recovery transformation
            pipe.setTransformationFunction { content ->
                if (!content.text.contains("Answer:")) {
                    content.text = "Answer: ${content.text}"
                }
                content
            }
            
            pipe.init()
            
            val mathResult = pipe.execute("What is 25 + 17?")
            
            println("\n=== MATH RESULT ===")
            println("Result: $mathResult")
            
            assertNotNull(mathResult, "Math result should not be null")
            assertTrue(mathResult.isNotEmpty(), "Math result should not be empty")
        }
    }
    
    @Test
    fun testMultiStepValidatedPipeline() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING MULTI-STEP VALIDATED PIPELINE ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            // Step 1: Generate code with validation
            val codePipe = BedrockPipe()
            codePipe.setModel("deepseek.r1-v1:0")
            codePipe.setRegion("us-east-2")
            codePipe.setSystemPrompt("Generate Python code with proper function definition.")
            codePipe.setMaxTokens(200)
            codePipe.setTemperature(0.2)
            
            codePipe.setValidatorFunction { content ->
                val hasFunction = content.text.contains("def ")
                println("[CODE VALIDATOR] Has function definition: $hasFunction")
                hasFunction
            }
            
            codePipe.setTransformationFunction { content ->
                content.text = "```python\n${content.text}\n```"
                content
            }
            
            codePipe.init()
            
            val code = codePipe.execute("Write a function to calculate fibonacci numbers.")
            
            println("\n=== STEP 1: CODE GENERATION ===")
            println("Generated code: $code")
            
            // Step 2: Analyze code with validation
            val analysisPipe = BedrockPipe()
            analysisPipe.setModel("deepseek.r1-v1:0")
            analysisPipe.setRegion("us-east-2")
            analysisPipe.useConverseApi()
            analysisPipe.setSystemPrompt("Analyze code complexity and provide time complexity.")
            analysisPipe.setMaxTokens(150)
            analysisPipe.setTemperature(0.3)
            
            analysisPipe.setValidatorFunction { content ->
                val hasComplexity = content.text.lowercase().contains("complexity") || content.text.contains("O(")
                println("[ANALYSIS VALIDATOR] Has complexity analysis: $hasComplexity")
                hasComplexity
            }
            
            analysisPipe.init()
            
            val analysis = analysisPipe.execute("Analyze this code: $code")
            
            println("\n=== STEP 2: CODE ANALYSIS ===")
            println("Analysis: $analysis")
            
            // Assertions
            assertNotNull(code, "Code should not be null")
            assertNotNull(analysis, "Analysis should not be null")
            assertTrue(code.isNotEmpty(), "Code should not be empty")
            assertTrue(analysis.isNotEmpty(), "Analysis should not be empty")
            assertTrue(code.contains("```python"), "Code should be formatted")
        }
    }
    
    @Test
    fun testComprehensivePipelineWithAllFeatures() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING COMPREHENSIVE PIPELINE WITH ALL FEATURES ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            // Context management - build context from multiple sources
            val contextBuilder = StringBuilder()
            contextBuilder.append("Previous conversation context:\n")
            contextBuilder.append("User asked about data structures.\n")
            contextBuilder.append("Assistant explained arrays and lists.\n")
            contextBuilder.append("Current topic: Advanced data structures\n\n")
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.setSystemPrompt("You are an expert computer science tutor. Provide detailed explanations with examples.")
            pipe.setMaxTokens(400)
            pipe.setTemperature(0.5)
            
            // Multi-layer validation
            pipe.setValidatorFunction { content ->
                val hasExample = content.text.lowercase().contains("example")
                val hasExplanation = content.text.length > 100
                val hasStructure = content.text.lowercase().contains("tree") || content.text.lowercase().contains("graph") || content.text.lowercase().contains("hash")
                
                println("[VALIDATOR] Has example: $hasExample")
                println("[VALIDATOR] Sufficient length: $hasExplanation")
                println("[VALIDATOR] Mentions data structure: $hasStructure")
                
                val isValid = hasExample && hasExplanation && hasStructure
                println("[VALIDATOR] Overall valid: $isValid")
                isValid
            }
            
            // Context-aware transformation
            pipe.setTransformationFunction { content ->
                val timestamp = System.currentTimeMillis()
                val contextualResponse = """[TUTORING SESSION - $timestamp]
CONTEXT: Advanced data structures discussion

TUTOR RESPONSE:
${content.text}

[SESSION CONTINUES]"""
                
                println("[TRANSFORMER] Added contextual wrapper and timestamp")
                content.text = contextualResponse
                content
            }
            
            // Intelligent failure recovery
            var failureCount = 0
            pipe.setOnFailure { original, processed ->
                failureCount++
                println("[FAILURE HANDLER] Attempt $failureCount failed")
                println("[FAILURE HANDLER] Original length: ${original.text.length}")
                
                when (failureCount) {
                    1 -> {
                        println("[FAILURE HANDLER] First failure - requesting more detailed response")
                        pipe.setUserPrompt("Please provide a detailed explanation with specific examples of:")
                        processed // Continue with processed content
                    }
                    2 -> {
                        println("[FAILURE HANDLER] Second failure - adding explicit requirements")
                        pipe.setUserPrompt("Give a comprehensive answer that includes: 1) Definition 2) Example 3) Use case for:")
                        processed // Continue with processed content
                    }
                    else -> {
                        println("[FAILURE HANDLER] Max retries reached - accepting response")
                        processed // Accept processed content
                    }
                }
            }
            
            pipe.init()
            
            // Execute with context
            val contextualPrompt = contextBuilder.toString() + "Now explain binary search trees and their advantages."
            val result = pipe.execute(contextualPrompt)
            
            println("\n=== COMPREHENSIVE PIPELINE RESULT ===")
            println("Failure attempts: $failureCount")
            println("Result length: ${result.length}")
            println("Result preview: ${result.take(200)}...")
            println("\n=== FULL RESULT ===")
            println(result)
            
            // Validate final result
            assertNotNull(result, "Result should not be null")
            assertTrue(result.isNotEmpty(), "Result should not be empty")
            assertTrue(result.contains("[TUTORING SESSION"), "Result should have contextual wrapper")
            assertTrue(result.contains("TUTOR RESPONSE:"), "Result should have structured format")
            assertTrue(result.length > 200, "Result should be comprehensive")
            
            println("\n=== TEST COMPLETED SUCCESSFULLY ===")
        }
    }
}