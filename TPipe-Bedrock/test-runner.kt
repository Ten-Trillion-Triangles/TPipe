import bedrockPipe.BedrockPipe
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers

fun main() 
{
    val accessKey = System.getenv("AWS_ACCESS_KEY_ID")
    val secretKey = System.getenv("AWS_SECRET_ACCESS_KEY")
    val bearerToken = System.getenv("AWS_BEARER_TOKEN_BEDROCK")
    
    if ((accessKey.isNullOrEmpty() || secretKey.isNullOrEmpty()) && bearerToken.isNullOrEmpty()) {
        println("Skipping test - AWS credentials not found (AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY or AWS_BEARER_TOKEN_BEDROCK)")
        return
    }
    
    println("=== COMPREHENSIVE PIPELINE TEST WITH ALL FEATURES ===")
    
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
        pipe.setValidatorFunction { response ->
            val hasExample = response.lowercase().contains("example")
            val hasExplanation = response.length > 100
            val hasStructure = response.lowercase().contains("tree") || response.lowercase().contains("graph") || response.lowercase().contains("hash")
            
            println("[VALIDATOR] Has example: $hasExample")
            println("[VALIDATOR] Sufficient length: $hasExplanation")
            println("[VALIDATOR] Mentions data structure: $hasStructure")
            
            val isValid = hasExample && hasExplanation && hasStructure
            println("[VALIDATOR] Overall valid: $isValid")
            isValid
        }
        
        // Context-aware transformation
        pipe.setTransformationFunction { response ->
            val timestamp = System.currentTimeMillis()
            val contextualResponse = """[TUTORING SESSION - $timestamp]
CONTEXT: Advanced data structures discussion

TUTOR RESPONSE:
$response

[SESSION CONTINUES]"""
            
            println("[TRANSFORMER] Added contextual wrapper and timestamp")
            contextualResponse
        }
        
        // Intelligent failure recovery
        var failureCount = 0
        pipe.setOnFailure { original, newText ->
            failureCount++
            println("[FAILURE HANDLER] Attempt $failureCount failed")
            println("[FAILURE HANDLER] Original length: ${original.length}")
            
            when (failureCount) {
                1 -> {
                    println("[FAILURE HANDLER] First failure - requesting more detailed response")
                    pipe.setUserPrompt("Please provide a detailed explanation with specific examples of:")
                    true // Retry
                }
                2 -> {
                    println("[FAILURE HANDLER] Second failure - adding explicit requirements")
                    pipe.setUserPrompt("Give a comprehensive answer that includes: 1) Definition 2) Example 3) Use case for:")
                    true // Retry
                }
                else -> {
                    println("[FAILURE HANDLER] Max retries reached - accepting response")
                    false // Accept
                }
            }
        }
        
        pipe.init()
        
        // Execute with context
        val contextualPrompt = contextBuilder.toString() + "Now explain binary search trees and their advantages."
        println("\n=== EXECUTING PIPELINE ===")
        println("Context: ${contextBuilder.toString()}")
        println("Prompt: Now explain binary search trees and their advantages.")
        
        val result = pipe.execute(contextualPrompt)
        
        println("\n=== COMPREHENSIVE PIPELINE RESULT ===")
        println("Failure attempts: $failureCount")
        println("Result length: ${result.length}")
        println("Result preview: ${result.take(200)}...")
        println("\n=== FULL RESULT ===")
        println(result)
        
        println("\n=== TEST COMPLETED SUCCESSFULLY ===")
    }
}