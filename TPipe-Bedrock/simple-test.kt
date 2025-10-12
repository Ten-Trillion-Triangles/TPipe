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
    
    println("=== COMPREHENSIVE PIPELINE TEST ===")
    
    runBlocking(Dispatchers.IO) 
    {
        bedrockEnv.loadInferenceConfig()
        
        val pipe = BedrockPipe()
        pipe.setModel("deepseek.r1-v1:0")
        pipe.setRegion("us-east-2")
        pipe.setSystemPrompt("You are an expert computer science tutor. Provide detailed explanations with examples.")
        pipe.setMaxTokens(300)
        pipe.setTemperature(0.5)
        
        pipe.setValidatorFunction { response: String ->
            val hasExample = response.lowercase().contains("example")
            val hasExplanation = response.length > 50
            val hasStructure = response.lowercase().contains("tree")
            
            println("🔍 [VALIDATOR] Has example: $hasExample")
            println("🔍 [VALIDATOR] Sufficient length: $hasExplanation") 
            println("🔍 [VALIDATOR] Mentions tree: $hasStructure")
            
            val isValid = hasExample && hasExplanation && hasStructure
            println("✅ [VALIDATOR] Overall valid: $isValid")
            isValid
        }
        
        pipe.setTransformationFunction { response: String ->
            val timestamp = System.currentTimeMillis()
            val transformed = """
📚 [TUTORING SESSION - $timestamp]
🎯 CONTEXT: Advanced data structures discussion

👨‍🏫 TUTOR RESPONSE:
$response

🔄 [SESSION CONTINUES]
"""
            println("🔄 [TRANSFORMER] Added contextual wrapper")
            transformed
        }
        
        var failureCount = 0
        pipe.setOnFailure { original: String, newText: String ->
            failureCount++
            println("❌ [FAILURE HANDLER] Attempt $failureCount failed")
            println("📏 [FAILURE HANDLER] Original length: ${original.length}")
            
            when (failureCount) {
                1 -> {
                    println("🔄 [FAILURE HANDLER] Retrying with more specific prompt")
                    true
                }
                else -> {
                    println("⏹️ [FAILURE HANDLER] Max retries reached")
                    false
                }
            }
        }
        
        pipe.init()
        
        println("\n🚀 [PIPELINE] Starting execution...")
        val result = pipe.execute("Explain binary search trees with a simple example.")
        
        println("\n📊 === PIPELINE RESULTS ===")
        println("🔢 Failure attempts: $failureCount")
        println("📏 Result length: ${result.length}")
        println("\n📄 === FULL RESULT ===")
        println(result)
        println("\n✅ === TEST COMPLETED ===")
    }
}