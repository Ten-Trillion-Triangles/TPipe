import bedrockPipe.BedrockPipe
import com.TTT.Enums.ProviderName
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val pipe = BedrockPipe()
        .setProvider(ProviderName.Aws)
        .setModel("openai.gpt-oss-20b-1:0")
        .setSystemPrompt("You are a helpful assistant")
        .setTemperature(0.7)
        .setMaxTokens(1000)
        .setReasoning()
    
    (pipe as BedrockPipe).setRegion("us-west-2")

    try {
        pipe.init()
        val result = pipe.execute("What is the capital of France?")
        println("GPT-OSS Response: $result")
    } catch (e: Exception) {
        println("Error: ${e.message}")
    }
}