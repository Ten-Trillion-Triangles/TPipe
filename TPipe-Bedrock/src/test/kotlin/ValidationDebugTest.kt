import bedrockPipe.BedrockPipe
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ValidationDebugTest {
    
    @Test
    fun testValidatorFunction() {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING VALIDATOR FUNCTION ===")
        
        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.setSystemPrompt("You are a helpful assistant.")
            pipe.setMaxTokens(100)
            pipe.setTemperature(0.1)
            
            var validatorCalled = false
            pipe.setValidatorFunction { content ->
                validatorCalled = true
                println("[VALIDATOR] Called with response length: ${content.text.length}")
                println("[VALIDATOR] Response preview: ${content.text.take(50)}...")
                true // Always pass
            }
            
            pipe.init()
            val result = pipe.execute("What is 1+1?")
            
            println("Validator called: $validatorCalled")
            println("Result length: ${result.length}")
            println("Result: $result")
            
            assertTrue(validatorCalled, "Validator should have been called")
            assertTrue(result.isNotEmpty(), "Result should not be empty")
        }
    }
    
    @Test
    fun testTransformFunction() {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING TRANSFORM FUNCTION ===")
        
        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.setSystemPrompt("You are a helpful assistant.")
            pipe.setMaxTokens(100)
            pipe.setTemperature(0.1)
            
            var transformCalled = false
            pipe.setTransformationFunction { content ->
                transformCalled = true
                println("[TRANSFORM] Called with response length: ${content.text.length}")
                content.text = "[TRANSFORMED] ${content.text}"
                content
            }
            
            pipe.init()
            val result = pipe.execute("What is 1+1?")
            
            println("Transform called: $transformCalled")
            println("Result: $result")
            
            assertTrue(transformCalled, "Transform should have been called")
            assertTrue(result.contains("[TRANSFORMED]"), "Result should be transformed")
        }
    }
    
    @Test
    fun testFailureFunction() {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING FAILURE FUNCTION ===")
        
        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.setSystemPrompt("You are a helpful assistant.")
            pipe.setMaxTokens(100)
            pipe.setTemperature(0.1)
            
            var failureCalled = false
            pipe.setValidatorFunction { content ->
                println("[VALIDATOR] Failing validation for response: ${content.text.take(50)}...")
                false // Always fail to trigger failure function
            }
            
            pipe.setOnFailure { original, processed ->
                failureCalled = true
                println("[FAILURE] Called with original: ${original.text.take(50)}...")
                println("[FAILURE] processed: ${processed.text}")
                processed // Return processed content to continue pipeline
            }
            
            pipe.init()
            val result = pipe.execute("What is 1+1?")
            
            println("Failure called: $failureCalled")
            println("Result: $result")
            
            assertTrue(failureCalled, "Failure function should have been called")
        }
    }
}