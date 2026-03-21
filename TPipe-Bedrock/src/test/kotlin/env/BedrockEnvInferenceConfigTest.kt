package env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class BedrockEnvInferenceConfigTest
{
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
