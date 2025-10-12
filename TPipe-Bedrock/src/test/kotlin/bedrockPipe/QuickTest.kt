package bedrockPipe

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class QuickTest {

    @Test
    fun testClaudeHaikuQuick() = runBlocking {
        TestCredentialUtils.requireAwsCredentials()
        
        try {
            withTimeout(60000) { // 60 second timeout
                val pipe = BedrockPipe()
                    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
                    .setMaxTokens(10)
                    .setTemperature(0.1) as BedrockPipe
                
                pipe.setReadTimeout(60)

                pipe.init()
                val result = pipe.generateText("Hi")
                
                println("Claude Haiku result: '$result'")
                assertNotNull(result)
                assertTrue(result.isNotEmpty(), "Expected non-empty response")
            }
        } catch (e: Exception) {
            println("Test failed: ${e.message}")
            fail("Test should not throw exception: ${e.message}")
        }
    }
}