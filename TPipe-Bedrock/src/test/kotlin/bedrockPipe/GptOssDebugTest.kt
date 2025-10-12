package bedrockPipe

import TestCredentialUtils
import com.TTT.Enums.ProviderName
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class GptOssDebugTest {

    private lateinit var pipe: BedrockPipe

    @BeforeEach
    fun setup() {
        TestCredentialUtils.requireAwsCredentials()
        
        pipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("openai.gpt-oss-20b-1:0")
            .setSystemPrompt("You are a helpful assistant")
            .setTemperature(0.7)
            .setMaxTokens(100) as BedrockPipe
        
        pipe.setRegion("us-west-2")
    }

    @Test
    fun `debug GPT-OSS response`() = runBlocking {
        try {
            pipe.init()
            println("Model initialized successfully")
            
            val result = pipe.execute("What is 2+2?")
            println("Response length: ${result.length}")
            println("Response content: '$result'")
            
            if (result.isEmpty()) {
                println("Empty response - checking error handling")
            }
        } catch (e: Exception) {
            println("Exception occurred: ${e.message}")
            e.printStackTrace()
        }
    }
}