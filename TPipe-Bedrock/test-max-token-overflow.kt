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
    
    println("=== MAX TOKEN OVERFLOW TEST ===")
    
    runBlocking(Dispatchers.IO) {
        bedrockEnv.loadInferenceConfig()
        
        // Test 1: Overflow disabled (default behavior)
        println("\n--- Test 1: Overflow Disabled ---")
        val pipe1 = BedrockPipe()
        pipe1.setModel("anthropic.claude-3-haiku-20240307-v1:0")
        pipe1.setRegion("us-east-1")
        pipe1.setMaxTokens(10) // Force overflow
        
        val result1 = pipe1.generateText("Write a very long detailed story about space exploration with multiple characters and plot twists.")
        println("Result with overflow disabled: '${result1}'")
        println("Length: ${result1.length}")
        
        // Test 2: Overflow enabled
        println("\n--- Test 2: Overflow Enabled ---")
        val pipe2 = BedrockPipe()
        pipe2.setModel("anthropic.claude-3-haiku-20240307-v1:0")
        pipe2.setRegion("us-east-1")
        pipe2.setMaxTokens(10) // Force overflow
        pipe2.enableMaxTokenOverflow()
        
        val result2 = pipe2.generateText("Write a very long detailed story about space exploration with multiple characters and plot twists.")
        println("Result with overflow enabled: '${result2}'")
        println("Length: ${result2.length}")
        
        // Test 3: DeepSeek with reasoning (should only count content, not reasoning)
        println("\n--- Test 3: DeepSeek with Reasoning ---")
        val pipe3 = BedrockPipe()
        pipe3.setModel("deepseek.r1-v1:0")
        pipe3.setRegion("us-east-2")
        pipe3.setMaxTokens(50) // Force overflow during reasoning
        pipe3.enableMaxTokenOverflow()
        
        val result3 = pipe3.generateText("Solve this complex math problem: What is the derivative of x^3 + 2x^2 - 5x + 7?")
        println("DeepSeek result with overflow enabled: '${result3}'")
        println("Length: ${result3.length}")
    }
}
