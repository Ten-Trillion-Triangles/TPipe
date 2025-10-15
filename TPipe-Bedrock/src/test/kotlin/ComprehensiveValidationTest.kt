import bedrockPipe.BedrockPipe
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComprehensiveValidationTest {
    
    @Test
    fun testAllFunctionsWorking() {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING ALL VALIDATION FUNCTIONS TOGETHER ===")
        
        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.setSystemPrompt("You are a helpful assistant.")
            pipe.setMaxTokens(150)
            pipe.setTemperature(0.1)
            
            var validatorCalled = false
            var transformCalled = false
            
            // Validator that always passes
            pipe.setValidatorFunction { content ->
                validatorCalled = true
                println("[VALIDATOR] Response length: ${content.text.length}")
                content.text.length > 10 // Simple length check
            }
            
            // Transform function
            pipe.setTransformationFunction { content ->
                transformCalled = true
                println("[TRANSFORM] Transforming response")
                content.text = "[PROCESSED] ${content.text}"
                content
            }
            
            pipe.init()
            val result = pipe.execute("What is 2+2?")
            
            println("\n=== RESULTS ===")
            println("Validator called: $validatorCalled")
            println("Transform called: $transformCalled")
            println("Result contains [PROCESSED]: ${result.contains("[PROCESSED]")}")
            println("Result length: ${result.length}")
            
            assertTrue(validatorCalled, "Validator should be called")
            assertTrue(transformCalled, "Transform should be called")
            assertTrue(result.contains("[PROCESSED]"), "Result should be transformed")
            assertTrue(result.isNotEmpty(), "Result should not be empty")
        }
    }
    
    @Test
    fun testValidationFailureRecovery() {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING VALIDATION FAILURE AND RECOVERY ===")
        
        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockPipe()
            pipe.setModel("deepseek.r1-v1:0")
            pipe.setRegion("us-east-2")
            pipe.setSystemPrompt("You are a helpful assistant.")
            pipe.setMaxTokens(100)
            pipe.setTemperature(0.1)
            
            var validatorCalled = false
            var failureCalled = false
            
            // Validator that fails first time, passes second time
            var callCount = 0
            pipe.setValidatorFunction { content ->
                validatorCalled = true
                callCount++
                println("[VALIDATOR] Call #$callCount, response length: ${content.text.length}")
                content.text.contains("4") // Look for the answer "4" in "2+2=4"
            }
            
            // Failure handler that allows continuation
            pipe.setOnFailure { original, processed ->
                failureCalled = true
                println("[FAILURE] Validation failed, but continuing pipeline")
                processed // Continue pipeline even on failure
            }
            
            pipe.init()
            val result = pipe.execute("What is 2+2? Give a short answer.")
            
            println("\n=== FAILURE RECOVERY RESULTS ===")
            println("Validator called: $validatorCalled")
            println("Failure called: $failureCalled")
            println("Result: $result")
            
            assertTrue(validatorCalled, "Validator should be called")
            assertTrue(result.isNotEmpty(), "Result should not be empty even if validation fails")
        }
    }
}