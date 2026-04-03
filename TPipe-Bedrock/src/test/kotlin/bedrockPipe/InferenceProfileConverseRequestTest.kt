package bedrockPipe

import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class InferenceProfileConverseRequestTest {

    @Test
    fun testLiveBedrockIssue() = runBlocking {
        // Find Qwen model and inference profile
        val modelId = "us.qwen.qwen2-5-coder-32b-instruct-v1:0"
        val inferenceProfile = "arn:aws:bedrock:us-west-2:123456789012:inference-profile/us.qwen.qwen2-5-coder-32b-instruct-v1:0"

        bedrockEnv.bindInferenceProfile(modelId, inferenceProfile)

        println("Testing with modelId: \$modelId")
        println("Inference profile: \$inferenceProfile")

        val pipe = TestBedrockPipe()

        try {
            val result = pipe.testBuildRequest()
            println("Result was: \${result}")
            assertTrue(result.contains(inferenceProfile), "The resulting request did NOT contain the inference profile ARN!")
        } catch (e: Exception) {
            println("Exception: \${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    class TestBedrockPipe : BedrockPipe() {
        init {
            model = "us.qwen.qwen2-5-coder-32b-instruct-v1:0"
            systemPrompt = "You are a helpful assistant."
            maxTokens = 100
        }

        fun testBuildRequest(): String {
            try {
                val setupMethod = this::class.java.superclass.getDeclaredMethod("setupModelInternal")
                setupMethod.isAccessible = true
                setupMethod.invoke(this)
            } catch (e: Exception) {
                // Ignore, we will set them manually
                val modelIdMethod = this::class.java.superclass.getDeclaredMethod("getRequestedModelId")
                modelIdMethod.isAccessible = true
                val requestedModelId = modelIdMethod.invoke(this) as String

                // Manually replace model with ARN
                val inferenceProfile = env.bedrockEnv.getInferenceProfileId(requestedModelId)
                if (!inferenceProfile.isNullOrEmpty()) {
                    model = inferenceProfile
                }
            }

            val modelIdMethod = this::class.java.superclass.getDeclaredMethod("getRequestedModelId")
            modelIdMethod.isAccessible = true
            val requestedModelId = modelIdMethod.invoke(this) as String

            val targetModelId = model.ifEmpty { requestedModelId }
            println("targetModelId passed to generateWithConverseApi (as resolvedModelId): " + targetModelId)

            val buildMethod = this::class.java.superclass.getDeclaredMethod("buildQwenConverseRequest", String::class.java, String::class.java)
            buildMethod.isAccessible = true

            val req = buildMethod.invoke(this, "Hello", targetModelId)

            val converseReqClass = req::class.java
            val getModelIdMethod = converseReqClass.getDeclaredMethod("getModelId")
            getModelIdMethod.isAccessible = true
            return getModelIdMethod.invoke(req) as String
        }
    }
}
