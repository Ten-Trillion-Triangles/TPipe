package env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class BedrockEnvInferenceConfigTest
{
    @Test
    fun crossRegionGeographicInferenceProfileArnFallsBackToPortableProfileId()
    {
        val configuredArn =
            "arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.amazon.nova-lite-v1:0"

        assertEquals(
            "us.amazon.nova-lite-v1:0",
            bedrockEnv.resolveInferenceProfileForRegion(configuredArn, "us-east-1"),
            "A geographic inference profile ARN from another region must be portable to the configured region"
        )
    }

    @Test
    fun sameRegionAndCustomInferenceProfileArnsRemainUnchanged()
    {
        val sameRegionArn =
            "arn:aws:bedrock:us-east-1:521369004927:inference-profile/us.amazon.nova-lite-v1:0"
        val customArn =
            "arn:aws:bedrock:us-east-2:521369004927:application-inference-profile/custom-profile"

        assertEquals(sameRegionArn, bedrockEnv.resolveInferenceProfileForRegion(sameRegionArn, "us-east-1"))
        assertEquals(customArn, bedrockEnv.resolveInferenceProfileForRegion(customArn, "us-east-1"))
    }

    @Test
    fun loadInferenceConfigUsesOverrideFile()
    {
        val tempConfig = File.createTempFile("tpipe-inference", ".txt")
        tempConfig.writeText(
            """
                qwen.qwen3-coder-30b-a3b-v1:0=
                custom.model:1=profile-123
            """.trimIndent()
        )

        try
        {
            bedrockEnv.resetInferenceConfig()
            bedrockEnv.setInferenceConfigFile(tempConfig)
            bedrockEnv.loadInferenceConfig()

            assertEquals(
                "",
                bedrockEnv.getInferenceProfileId("qwen.qwen3-coder-30b-a3b-v1:0"),
                "Direct-call fallback should remain blank when the temp config says so"
            )
            assertEquals(
                "profile-123",
                bedrockEnv.getInferenceProfileId("custom.model:1"),
                "The override file should be read instead of the user's home config"
            )
            assertTrue(
                bedrockEnv.getAllModels().contains("qwen.qwen3-coder-30b-a3b-v1:0"),
                "The override file should fully populate the model map"
            )
        }
        finally
        {
            bedrockEnv.resetInferenceConfig()
            tempConfig.delete()
        }
    }
}
