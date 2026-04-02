package bedrockPipe

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import env.bedrockEnv
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Validates that inference profile resolution from the CLI config file reaches the actual
 * ConverseRequest.modelId for model families that accept a modelId parameter in their builders.
 */
class InferenceProfileConverseRequestTest
{
    private val baseQwen = "qwen.qwen3-coder-30b-a3b-v1:0"
    private val profileQwen = "us.qwen.qwen3-coder-30b-a3b-v1:0"

    private val baseDeepSeek = "deepseek.r1-v1:0"
    private val profileDeepSeek = "us.deepseek.r1-v1:0"

    private val baseGptOss = "openai.gpt-oss-20b-1:0"
    private val profileGptOss = "us.openai.gpt-oss-20b-1:0"

    /**
     * Simulates the CLI path: pipe model is set to the base model ID, inference config file maps
     * it to an inference profile. After init resolves the mapping, the builder must use the
     * resolved profile — not the original base model — as the ConverseRequest.modelId.
     */
    private fun buildPipeWithInferenceConfig(baseModel: String, profileId: String): BedrockMultimodalPipe
    {
        val tempConfig = File.createTempFile("tpipe-inference-test", ".txt")
        tempConfig.writeText("$baseModel=$profileId")

        try
        {
            bedrockEnv.resetInferenceConfig()
            bedrockEnv.setInferenceConfigFile(tempConfig)
            bedrockEnv.loadInferenceConfig()
        }
        finally
        {
            tempConfig.delete()
        }

        val pipe = BedrockMultimodalPipe()
        pipe.setModel(baseModel)
        pipe.setRegion("us-east-1")

        // Simulate what init() does for inference resolution without starting the AWS client
        val inferenceId = bedrockEnv.getInferenceProfileId(baseModel)
        if(!inferenceId.isNullOrEmpty())
        {
            pipe.setModel(inferenceId)
        }

        return pipe
    }

    @Test
    fun qwenConverseRequestUsesResolvedInferenceProfile()
    {
        val pipe = buildPipeWithInferenceConfig(baseQwen, profileQwen)
        val request = pipe.buildQwenConverseRequest(
            listOf(ContentBlock.Text("test")),
            profileQwen
        )
        assertEquals(profileQwen, request.modelId,
            "Qwen ConverseRequest.modelId must be the resolved inference profile, not the base model")

        bedrockEnv.resetInferenceConfig()
    }

    @Test
    fun deepSeekConverseRequestUsesResolvedInferenceProfile()
    {
        val pipe = buildPipeWithInferenceConfig(baseDeepSeek, profileDeepSeek)
        val request = pipe.buildDeepSeekConverseRequestObject(
            profileDeepSeek,
            listOf(ContentBlock.Text("test"))
        )
        assertEquals(profileDeepSeek, request.modelId,
            "DeepSeek ConverseRequest.modelId must be the resolved inference profile, not the base model")

        bedrockEnv.resetInferenceConfig()
    }

    @Test
    fun gptOssConverseRequestUsesResolvedInferenceProfile()
    {
        val pipe = buildPipeWithInferenceConfig(baseGptOss, profileGptOss)
        val request = pipe.buildGptOssConverseRequest(
            profileGptOss,
            listOf(ContentBlock.Text("test"))
        )
        assertEquals(profileGptOss, request.modelId,
            "GPT-OSS ConverseRequest.modelId must be the resolved inference profile, not the base model")

        bedrockEnv.resetInferenceConfig()
    }

    @Test
    fun claudeConverseRequestUsesModelPropertyNotParameter()
    {
        val baseModel = "anthropic.claude-3-sonnet-20240229-v1:0"
        val profileId = "us.anthropic.claude-3-sonnet-20240229-v1:0"
        val pipe = buildPipeWithInferenceConfig(baseModel, profileId)
        val request = pipe.buildClaudeConverseRequest(listOf(ContentBlock.Text("test")))
        assertEquals(profileId, request.modelId,
            "Claude ConverseRequest.modelId must be the resolved inference profile from the model property")

        bedrockEnv.resetInferenceConfig()
    }
}
