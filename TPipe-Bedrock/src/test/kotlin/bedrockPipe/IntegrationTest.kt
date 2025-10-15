package bedrockPipe

import bedrockPipe.BedrockPipe
import com.TTT.Enums.ProviderName
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Integration test for BedrockPipe with real AWS Bedrock API calls
 */
class IntegrationTest {
    
    @Test
    fun testRealBedrockCalls() = runBlocking {
        TestCredentialUtils.requireAwsCredentials()
        
        println("Testing BedrockPipe with real AWS Bedrock calls...")
        
        // Test DeepSeek R1
        val deepseekPipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("deepseek.r1-v1:0")
        (deepseekPipe as BedrockPipe).setRegion("us-east-2")
        deepseekPipe
            .setSystemPrompt("You are an expert reasoning assistant.")
            .setTemperature(0.7)
            .setMaxTokens(150)
        
        deepseekPipe.init()
        val deepseekResult = deepseekPipe.execute("Solve: What is 15% of 240?")
        
        // Print result for manual verification
        if (deepseekResult.isEmpty()) {
            System.out.println("DeepSeek R1: Empty response")
        } else {
            System.out.println("DeepSeek R1 Response: $deepseekResult")
            assert(deepseekResult.isNotEmpty()) { "Expected non-empty response from DeepSeek R1" }
        }
    }
}