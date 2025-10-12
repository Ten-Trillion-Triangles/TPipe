import bedrockPipe.BedrockPipe
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers

fun main() {
    println("=== SIMPLE TIMEOUT TEST ===")
    
    // Check for AWS credentials
    val accessKey = System.getenv("AWS_ACCESS_KEY_ID")
    val secretKey = System.getenv("AWS_SECRET_ACCESS_KEY")
    val bearerToken = System.getenv("AWS_BEARER_TOKEN_BEDROCK")
    
    if ((accessKey.isNullOrEmpty() || secretKey.isNullOrEmpty()) && bearerToken.isNullOrEmpty()) {
        println("Skipping test - AWS credentials not found (AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY or AWS_BEARER_TOKEN_BEDROCK)")
        return
    }
    
    println("AWS credentials found. Running tests...")
    
    runBlocking(Dispatchers.IO) {
        try {
            bedrockEnv.loadInferenceConfig()
            
            // Test 1: DeepSeek Invoke API
            println("\n--- Testing DeepSeek Invoke API ---")
            val deepSeekPipe = BedrockPipe()
            deepSeekPipe.setModel("deepseek.r1-v1:0")
            deepSeekPipe.setRegion("us-east-2")
            deepSeekPipe.setReadTimeout(300) // 5 minutes
            deepSeekPipe.setSystemPrompt("You are a helpful assistant.")
            deepSeekPipe.setMaxTokens(100)
            deepSeekPipe.setTemperature(0.3)
            
            deepSeekPipe.init()
            
            val deepSeekResponse = deepSeekPipe.execute("What is 2+2?")
            println("DeepSeek Invoke API Response: '$deepSeekResponse'")
            println("Response length: ${deepSeekResponse.length}")
            
            // Test 2: DeepSeek Converse API
            println("\n--- Testing DeepSeek Converse API ---")
            val deepSeekConversePipe = BedrockPipe()
            deepSeekConversePipe.setModel("deepseek.r1-v1:0")
            deepSeekConversePipe.setRegion("us-east-2")
            deepSeekConversePipe.useConverseApi()
            deepSeekConversePipe.setReadTimeout(300) // 5 minutes
            deepSeekConversePipe.setSystemPrompt("You are a helpful assistant.")
            deepSeekConversePipe.setMaxTokens(100)
            deepSeekConversePipe.setTemperature(0.3)
            
            deepSeekConversePipe.init()
            
            val deepSeekConverseResponse = deepSeekConversePipe.execute("What is the capital of France?")
            println("DeepSeek Converse API Response: '$deepSeekConverseResponse'")
            println("Response length: ${deepSeekConverseResponse.length}")
            
            // Test 3: Claude Invoke API
            println("\n--- Testing Claude Invoke API ---")
            val claudePipe = BedrockPipe()
            claudePipe.setModel("anthropic.claude-3-sonnet-20240229-v1:0")
            claudePipe.setRegion("us-east-1")
            claudePipe.setReadTimeout(300) // 5 minutes
            claudePipe.setSystemPrompt("You are a helpful assistant.")
            claudePipe.setMaxTokens(100)
            claudePipe.setTemperature(0.3)
            
            claudePipe.init()
            
            val claudeResponse = claudePipe.execute("What is machine learning?")
            println("Claude Invoke API Response: '$claudeResponse'")
            println("Response length: ${claudeResponse.length}")
            
            // Test 4: Claude Converse API
            println("\n--- Testing Claude Converse API ---")
            val claudeConversePipe = BedrockPipe()
            claudeConversePipe.setModel("anthropic.claude-3-sonnet-20240229-v1:0")
            claudeConversePipe.setRegion("us-east-1")
            claudeConversePipe.useConverseApi()
            claudeConversePipe.setReadTimeout(300) // 5 minutes
            claudeConversePipe.setSystemPrompt("You are a helpful assistant.")
            claudeConversePipe.setMaxTokens(100)
            claudeConversePipe.setTemperature(0.3)
            
            claudeConversePipe.init()
            
            val claudeConverseResponse = claudeConversePipe.execute("Explain recursion briefly.")
            println("Claude Converse API Response: '$claudeConverseResponse'")
            println("Response length: ${claudeConverseResponse.length}")
            
            println("\n=== TEST SUMMARY ===")
            println("DeepSeek Invoke API: ${if (deepSeekResponse.isNotEmpty()) "SUCCESS" else "FAILED"}")
            println("DeepSeek Converse API: ${if (deepSeekConverseResponse.isNotEmpty()) "SUCCESS" else "FAILED"}")
            println("Claude Invoke API: ${if (claudeResponse.isNotEmpty()) "SUCCESS" else "FAILED"}")
            println("Claude Converse API: ${if (claudeConverseResponse.isNotEmpty()) "SUCCESS" else "FAILED"}")
            
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }
}