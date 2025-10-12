import bedrockPipe.BedrockPipe
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("Testing AWS_BEARER_TOKEN_BEDROCK functionality...")
    
    val accessKey = System.getenv("AWS_ACCESS_KEY_ID")
    val secretKey = System.getenv("AWS_SECRET_ACCESS_KEY")
    val bearerToken = System.getenv("AWS_BEARER_TOKEN_BEDROCK")
    
    if ((accessKey.isNullOrEmpty() || secretKey.isNullOrEmpty()) && bearerToken.isNullOrEmpty()) {
        println("Skipping test - AWS credentials not found (AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY or AWS_BEARER_TOKEN_BEDROCK)")
        return@runBlocking
    }
    
    println("Bearer token present: ${!bearerToken.isNullOrEmpty()}")
    
    if (!bearerToken.isNullOrEmpty()) {
        try {
            val decoded = String(java.util.Base64.getDecoder().decode(bearerToken))
            val parts = decoded.split(":")
            println("Decoded parts count: ${parts.size}")
            if (parts.size >= 2) {
                println("Access Key ID: ${parts[0].take(10)}...")
                println("Secret Key: ${parts[1].take(10)}...")
            }
        } catch (e: Exception) {
            println("Failed to decode bearer token: ${e.message}")
        }
    }
    
    try {
        val pipe = BedrockPipe()
            .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
            .setMaxTokens(50)
            .setTemperature(0.1)
            .setReadTimeout(30)
        
        pipe.init()
        val result = pipe.generateText("Hi")
        
        println("Response received: '${result}'")
        println("Response length: ${result.length}")
        println("Test ${if (result.isNotEmpty()) "PASSED" else "FAILED"}")
        
    } catch (e: Exception) {
        println("Test FAILED with exception: ${e.message}")
        e.printStackTrace()
    }
}