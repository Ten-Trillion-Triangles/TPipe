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
    
    val pipe = BedrockPipe()
        .setProvider(ProviderName.Aws)
        .setModel("us.deepseek.r1-v1:0")
        .setRegion("us-east-1")
        .setSystemPrompt("You are a helpful assistant.")
        .setTemperature(0.7)
        .setMaxTokens(500)
    
    pipe.init()
    
    val result = pipe.execute("What is 15% of 240? Show your calculation.")
    println("DeepSeek Response: $result")
}