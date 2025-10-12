import bedrockPipe.BedrockPipe
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

class ShowResponseTest {
    
    @Test
    fun showDeepSeekResponse() {
        TestCredentialUtils.requireAwsCredentials()
        
        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.setSystemPrompt("You are a helpful assistant.")
            pipe.setMaxTokens(500)
            pipe.setTemperature(0.3)
            
            pipe.init()
            
            val result = pipe.execute("What is 2+2?")
            
            println("DEEPSEEK RESPONSE:")
            println("Length: ${result.length}")
            println("Content: '$result'")
        }
    }
}