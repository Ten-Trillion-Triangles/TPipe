import bedrockPipe.BedrockPipe
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

class SimpleTimeoutTest {
    
    @Test
    fun testBasicFunctionality() {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== SIMPLE TIMEOUT TEST ===")
        
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
                deepSeekPipe.setMaxTokens(50)
                deepSeekPipe.setTemperature(0.1)
                
                deepSeekPipe.init()
                
                val deepSeekResponse = deepSeekPipe.execute("What is 2+2?")
                println("DeepSeek Invoke API Response: '$deepSeekResponse'")
                println("Response length: ${deepSeekResponse.length}")
                
                // Test 2: Claude Invoke API
                println("\n--- Testing Claude Invoke API ---")
                val claudePipe = BedrockPipe()
                claudePipe.setModel("anthropic.claude-3-sonnet-20240229-v1:0")
                claudePipe.setRegion("us-east-1")
                claudePipe.setReadTimeout(300) // 5 minutes
                claudePipe.setSystemPrompt("You are a helpful assistant.")
                claudePipe.setMaxTokens(50)
                claudePipe.setTemperature(0.1)
                
                claudePipe.init()
                
                val claudeResponse = claudePipe.execute("What is 3+3?")
                println("Claude Invoke API Response: '$claudeResponse'")
                println("Response length: ${claudeResponse.length}")
                
                println("\n=== TEST SUMMARY ===")
                println("DeepSeek Invoke API: ${if (deepSeekResponse.isNotEmpty()) "SUCCESS" else "FAILED"}")
                println("Claude Invoke API: ${if (claudeResponse.isNotEmpty()) "SUCCESS" else "FAILED"}")
                
            } catch (e: Exception) {
                println("ERROR: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}