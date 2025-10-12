import bedrockPipe.BedrockPipe
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

class ClaudeResponseTest {
    
    @Test
    fun showClaudeResponse() {
        TestCredentialUtils.requireAwsCredentials()
        
        try {
            println("Starting Claude response test...")
            
            runBlocking(Dispatchers.IO) {
                withTimeout(60000) { // 60 second timeout
                    println("Loading inference config...")
                    bedrockEnv.loadInferenceConfig()
                    println("Inference config loaded")
                    
                    println("Creating BedrockPipe...")
                    val pipe = BedrockPipe()
                    pipe.setModel("us.anthropic.claude-sonnet-4-20250514-v1:0")
                    pipe.setRegion("us-east-1")
                    pipe.setSystemPrompt("You are a helpful assistant.")
                    pipe.setMaxTokens(100)
                    pipe.setTemperature(0.3)
                    pipe.setReadTimeout(30) // 30 second timeout
                    
                    println("Initializing pipe...")
                    pipe.init()
                    println("Pipe initialized")
                    
                    println("Executing request...")
                    val result = pipe.execute("What is 2+2?")
                    
                    println("CLAUDE RESPONSE:")
                    println("Length: ${result.length}")
                    println("Content: '$result'")
                    
                    if (result.isEmpty()) {
                        println("ERROR: Empty response received")
                        
                        // Try to call generateText directly to see if there are any exceptions
                        println("Trying generateText directly...")
                        try {
                            val directResult = pipe.generateText("What is 2+2?")
                            println("Direct result: '$directResult'")
                        } catch (e: Exception) {
                            println("Direct call failed: ${e.javaClass.simpleName}: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }
}