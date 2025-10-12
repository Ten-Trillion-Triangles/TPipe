import bedrockPipe.BedrockPipe
import env.bedrockEnv
import kotlinx.coroutines.runBlocking

fun main() {
    val accessKey = System.getenv("AWS_ACCESS_KEY_ID")
    val secretKey = System.getenv("AWS_SECRET_ACCESS_KEY")
    val bearerToken = System.getenv("AWS_BEARER_TOKEN_BEDROCK")
    
    if ((accessKey.isNullOrEmpty() || secretKey.isNullOrEmpty()) && bearerToken.isNullOrEmpty()) {
        println("Skipping test - AWS credentials not found (AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY or AWS_BEARER_TOKEN_BEDROCK)")
        return
    }
    
    runBlocking {
        bedrockEnv.loadInferenceConfig()
        
        val pipe = BedrockPipe()
        pipe.setModel("deepseek.r1-v1:0")
        pipe.setRegion("us-east-2")
        pipe.setSystemPrompt("You are a helpful assistant.")
        pipe.setMaxTokens(100)
        pipe.setTemperature(0.3)
        
        pipe.init()
        
        val result = pipe.execute("What is 2+2?")
        
        println("=== DEEPSEEK RESPONSE ===")
        println("Length: ${result.length}")
        println("Response: '$result'")
        println("=== END ===")
    }
}