import bedrockPipe.BedrockPipe
import com.TTT.Enums.ProviderName
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val accessKey = System.getenv("AWS_ACCESS_KEY_ID")
    val secretKey = System.getenv("AWS_SECRET_ACCESS_KEY")
    val bearerToken = System.getenv("AWS_BEARER_TOKEN_BEDROCK")
    
    if ((accessKey.isNullOrEmpty() || secretKey.isNullOrEmpty()) && bearerToken.isNullOrEmpty()) {
        println("Skipping test - AWS credentials not found (AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY or AWS_BEARER_TOKEN_BEDROCK)")
        return@runBlocking
    }
    
    println("Testing DeepSeek R1 with inference profile...")
    
    try {
        val pipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("deepseek.r1-v1:0")
        
        pipe.setRegion("us-east-2")
        
        pipe.setSystemPrompt("You are an expert reasoning assistant.")
            .setTemperature(0.7)
            .setMaxTokens(150)
        
        pipe.init()
        
        // amazonq-ignore-next-line
        val result = pipe.execute("What is 15% of 240? Show your reasoning step by step.")
        
        if (result.isEmpty()) {
            println("ERROR: Empty response from DeepSeek")
        } else {
            println("SUCCESS: DeepSeek responded:")
            println(result)
        }
        
    } catch (e: Exception) {
        println("ERROR: ${e.javaClass.simpleName}: ${e.message}")
        e.printStackTrace()
    }
}