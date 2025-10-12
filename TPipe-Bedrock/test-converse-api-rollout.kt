import bedrockPipe.BedrockPipe
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers

fun main() {
    val accessKey = System.getenv("AWS_ACCESS_KEY_ID")
    val secretKey = System.getenv("AWS_SECRET_ACCESS_KEY")
    val bearerToken = System.getenv("AWS_BEARER_TOKEN_BEDROCK")
    
    if ((accessKey.isNullOrEmpty() || secretKey.isNullOrEmpty()) && bearerToken.isNullOrEmpty()) {
        println("Skipping test - AWS credentials not found")
        return
    }
    
    println("=== CONVERSE API ROLLOUT TEST ===")
    
    runBlocking(Dispatchers.IO) {
        bedrockEnv.loadInferenceConfig()
        
        val models = listOf(
            "anthropic.claude-3-haiku-20240307-v1:0" to "Claude",
            "amazon.nova-pro-v1:0" to "Nova",
            "amazon.titan-text-premier-v1:0" to "Titan"
        )
        
        models.forEach { (modelId, modelName) ->
            println("\n--- Testing $modelName ($modelId) ---")
            
            try {
                // Test Invoke API (existing)
                val invokePipe = BedrockPipe()
                    .setModel(modelId)
                    .setRegion("us-east-1")
                    .setMaxTokens(50)
                
                val invokeResult = invokePipe.generateText("What is 2+2?")
                println("✅ Invoke API: '${invokeResult.take(100)}...'")
                
                // Test Converse API (new)
                val conversePipe = BedrockPipe()
                    .setModel(modelId)
                    .setRegion("us-east-1")
                    .setMaxTokens(50)
                    .useConverseApi()
                
                val converseResult = conversePipe.generateText("What is 2+2?")
                println("✅ Converse API: '${converseResult.take(100)}...'")
                
                // Verify both work
                if (invokeResult.isNotEmpty() && converseResult.isNotEmpty()) {
                    println("✅ $modelName: Both APIs working!")
                } else {
                    println("❌ $modelName: API failure - Invoke: ${invokeResult.isNotEmpty()}, Converse: ${converseResult.isNotEmpty()}")
                }
                
            } catch (e: Exception) {
                println("❌ $modelName: Exception - ${e.message}")
            }
        }
        
        println("\n=== ROLLOUT TEST COMPLETE ===")
    }
}
