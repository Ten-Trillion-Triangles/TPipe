import bedrockPipe.BedrockPipe
import bedrockPipe.BedrockMultimodalPipe
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class BedrockTimeoutTest
{
    
    @Test
    fun testDeepSeekInvokeAPIWithTimeout() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING DEEPSEEK R1 INVOKE API WITH TIMEOUT ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.setReadTimeout(300) // 5 minutes timeout
            pipe.setSystemPrompt("You are a helpful AI assistant.")
            pipe.setMaxTokens(200)
            pipe.setTemperature(0.7)
            
            pipe.init()
            
            // Test with timeout wrapper
            val response = withTimeout(60000) { // 60 second test timeout
                pipe.execute("What is 2+2? Give a brief answer.")
            }
            
            println("\\n=== DEEPSEEK INVOKE API RESPONSE ===")
            println("Response length: ${response.length}")
            println("Response: $response")
            println("=== END RESPONSE ===")
            
            assertNotNull(response, "Response should not be null")
            assertTrue(response.isNotEmpty(), "Response should not be empty")
        }
    }
    
    @Test
    fun testDeepSeekConverseAPIWithTimeout() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING DEEPSEEK R1 CONVERSE API WITH TIMEOUT ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.useConverseApi()
            pipe.setReadTimeout(300) // 5 minutes timeout
            pipe.setSystemPrompt("You are a helpful AI assistant.")
            pipe.setMaxTokens(200)
            pipe.setTemperature(0.7)
            
            pipe.init()
            
            // Test with timeout wrapper
            val response = withTimeout(10000) { // 10 second test timeout
                pipe.execute("What is the capital of France? Give a brief answer.")
            }
            
            println("\\n=== DEEPSEEK CONVERSE API RESPONSE ===")
            println("Response length: ${response.length}")
            println("Response: $response")
            println("=== END RESPONSE ===")
            
            assertNotNull(response, "Response should not be null")
            assertTrue(response.isNotEmpty(), "Response should not be empty")
        }
    }
    
    @Test
    fun testClaudeInvokeAPIWithTimeout() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING CLAUDE 3 SONNET INVOKE API WITH TIMEOUT ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("anthropic.claude-3-sonnet-20240229-v1:0")
            pipe.setRegion("us-east-1")
            pipe.setReadTimeout(300) // 5 minutes timeout
            pipe.setSystemPrompt("You are a helpful AI assistant.")
            pipe.setMaxTokens(200)
            pipe.setTemperature(0.7)
            
            pipe.init()
            
            // Test with timeout wrapper
            val response = withTimeout(10000) { // 10 second test timeout
                pipe.execute("Explain recursion in one sentence.")
            }
            
            println("\\n=== CLAUDE INVOKE API RESPONSE ===")
            println("Response length: ${response.length}")
            println("Response: $response")
            println("=== END RESPONSE ===")
            
            assertNotNull(response, "Response should not be null")
            assertTrue(response.isNotEmpty(), "Response should not be empty")
        }
    }
    
    @Test
    fun testClaudeConverseAPIWithTimeout() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING CLAUDE 3 SONNET CONVERSE API WITH TIMEOUT ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("anthropic.claude-3-sonnet-20240229-v1:0")
            pipe.setRegion("us-east-1")
            pipe.useConverseApi()
            pipe.setReadTimeout(300) // 5 minutes timeout
            pipe.setSystemPrompt("You are a helpful AI assistant.")
            pipe.setMaxTokens(200)
            pipe.setTemperature(0.7)
            
            pipe.init()
            
            // Test with timeout wrapper
            val response = withTimeout(10000) { // 10 second test timeout
                pipe.execute("What is machine learning in simple terms?")
            }
            
            println("\\n=== CLAUDE CONVERSE API RESPONSE ===")
            println("Response length: ${response.length}")
            println("Response: $response")
            println("=== END RESPONSE ===")
            
            assertNotNull(response, "Response should not be null")
            assertTrue(response.isNotEmpty(), "Response should not be empty")
        }
    }
    
    @Test
    fun testMultimodalPipeWithTimeout() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING MULTIMODAL PIPE WITH TIMEOUT ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockMultimodalPipe()
            pipe.setModel("anthropic.claude-3-sonnet-20240229-v1:0")
            pipe.setRegion("us-east-1")
            pipe.setReadTimeout(300) // 5 minutes timeout
            pipe.setSystemPrompt("You are a helpful AI assistant.")
            pipe.setMaxTokens(150)
            pipe.setTemperature(0.5)
            
            pipe.init()
            
            // Test with timeout wrapper
            val response = withTimeout(10000) { // 10 second test timeout
                pipe.execute("Describe the benefits of cloud computing.")
            }
            
            println("\\n=== MULTIMODAL PIPE RESPONSE ===")
            println("Response length: ${response.length}")
            println("Response: $response")
            println("=== END RESPONSE ===")
            
            assertNotNull(response, "Response should not be null")
            assertTrue(response.isNotEmpty(), "Response should not be empty")
        }
    }
    
    @Test
    fun testTimeoutConfiguration() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING TIMEOUT CONFIGURATION ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            // Test with very short timeout
            val shortTimeoutPipe = BedrockPipe()
            shortTimeoutPipe.setModel("deepseek.r1-v1:0")
            shortTimeoutPipe.setRegion("us-east-2")
            shortTimeoutPipe.setReadTimeout(1) // 1 second timeout - very short
            shortTimeoutPipe.setSystemPrompt("You are a helpful assistant.")
            shortTimeoutPipe.setMaxTokens(50)
            shortTimeoutPipe.setTemperature(0.1)
            
            shortTimeoutPipe.init()
            
            // This should complete quickly with a simple question
            val quickResponse = shortTimeoutPipe.execute("Hi")
            
            println("\\n=== SHORT TIMEOUT TEST ===")
            println("Quick response: '$quickResponse'")
            
            // Test with longer timeout
            val longTimeoutPipe = BedrockPipe()
            longTimeoutPipe.setModel("deepseek.r1-v1:0")
            longTimeoutPipe.setRegion("us-east-2")
            longTimeoutPipe.setReadTimeout(600) // 10 minutes timeout
            longTimeoutPipe.setSystemPrompt("You are a helpful assistant.")
            longTimeoutPipe.setMaxTokens(100)
            longTimeoutPipe.setTemperature(0.3)
            
            longTimeoutPipe.init()
            
            val longerResponse = longTimeoutPipe.execute("Explain quantum computing briefly.")
            
            println("\\n=== LONG TIMEOUT TEST ===")
            println("Longer response length: ${longerResponse.length}")
            println("Longer response: $longerResponse")
            
            // Both should work
            assertNotNull(quickResponse, "Quick response should not be null")
            assertNotNull(longerResponse, "Longer response should not be null")
        }
    }
    
    @Test
    fun testFallbackMechanism() 
    {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING FALLBACK MECHANISM ===")
        
        runBlocking(Dispatchers.IO) 
        {
            bedrockEnv.loadInferenceConfig()
            
            // Test DeepSeek with Converse API that should fallback to Invoke API
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.useConverseApi() // This should fallback to Invoke API if needed
            pipe.setReadTimeout(300)
            pipe.setSystemPrompt("You are a helpful assistant.")
            pipe.setMaxTokens(150)
            pipe.setTemperature(0.5)
            
            pipe.init()
            
            val response = withTimeout(15000) { // 15 second timeout for fallback
                pipe.execute("What is artificial intelligence?")
            }
            
            println("\\n=== FALLBACK MECHANISM TEST ===")
            println("Response length: ${response.length}")
            println("Response: $response")
            
            assertNotNull(response, "Response should not be null")
            assertTrue(response.isNotEmpty(), "Response should not be empty")
            
            // The response should not contain fallback indicators
            assertFalse(response.contains("SdkUnknown"), "Response should not contain SdkUnknown")
        }
    }
}