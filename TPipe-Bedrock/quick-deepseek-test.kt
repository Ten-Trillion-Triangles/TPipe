import bedrockPipe.BedrockPipe
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val accessKey = System.getenv("AWS_ACCESS_KEY_ID")
    val secretKey = System.getenv("AWS_SECRET_ACCESS_KEY")
    val bearerToken = System.getenv("AWS_BEARER_TOKEN_BEDROCK")
    
    if ((accessKey.isNullOrEmpty() || secretKey.isNullOrEmpty()) && bearerToken.isNullOrEmpty()) {
        println("Skipping test - AWS credentials not found (AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY or AWS_BEARER_TOKEN_BEDROCK)")
        return@runBlocking
    }
    
    println("Testing DeepSeek R1 with bearer token...")
    
    try {
        val pipe = BedrockPipe()
            .setModel("deepseek.r1-v1:0")
            .setMaxTokens(20)
            .setTemperature(0.1)
            .setReadTimeout(60)
        
        pipe.init()
        val result = pipe.generateText("Hi")
        
        println("DeepSeek result: '$result'")
        println("Response length: ${result.length}")
        println("Test ${if (result.isNotEmpty()) "PASSED" else "FAILED"}")
        
    } catch (e: Exception) {
        println("Test FAILED with exception: ${e.message}")
        e.printStackTrace()
    }
}