package bedrockPipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import com.TTT.Pipe.MultimodalContent
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that legacy Bedrock InvokeModel request bodies retain TPipe system
 * instructions when a provider exposes only a single prompt field.
 */
class InvokeSystemPromptPropagationTest {

    private val systemMarker = "SYSTEM_MARKER"
    private val userMarker = "USER_MARKER"

    @Test
    fun promptOnlyBuildersIncludeSystemAndUserContent() {
        val builders = listOf(
            "buildTitanRequest" to "inputText",
            "buildJurassicRequest" to "prompt",
            "buildCohereRequest" to "prompt",
            "buildGenericRequest" to "prompt"
        )

        builders.forEach { (builderName, promptField) ->
            val pipe = BedrockPipe().setSystemPrompt(systemMarker) as BedrockPipe
            val request = invokeBuilder(pipe, builderName)
            val prompt = request[promptField]?.jsonPrimitive?.content

            assertTrue(prompt?.contains(systemMarker) == true, "$builderName lost system content")
            assertTrue(prompt?.contains(userMarker) == true, "$builderName lost user content")
            assertTrue(prompt!!.indexOf(systemMarker) < prompt.indexOf(userMarker))
        }
    }

    @Test
    fun llamaBuilderUsesSystemAndUserHeaderTemplate() {
        val pipe = BedrockPipe().setSystemPrompt(systemMarker) as BedrockPipe
        val prompt = invokeBuilder(pipe, "buildLlamaRequest")["prompt"]!!.jsonPrimitive.content

        assertTrue(prompt.startsWith("<|begin_of_text|>"))
        assertTrue(prompt.contains("<|start_header_id|>system<|end_header_id|>"))
        assertTrue(prompt.contains("<|start_header_id|>user<|end_header_id|>"))
        assertTrue(prompt.contains("<|start_header_id|>assistant<|end_header_id|>"))
        assertTrue(prompt.indexOf(systemMarker) < prompt.indexOf(userMarker))
    }

    @Test
    fun mistralBuilderUsesInstructionTemplate() {
        val pipe = BedrockPipe().setSystemPrompt(systemMarker) as BedrockPipe
        val prompt = invokeBuilder(pipe, "buildMistralRequest")["prompt"]!!.jsonPrimitive.content

        assertTrue(prompt.startsWith("<s>[INST]"))
        assertTrue(prompt.endsWith("[/INST]"))
        assertTrue(prompt.contains(systemMarker))
        assertTrue(prompt.contains(userMarker))
        assertTrue(prompt.indexOf(systemMarker) < prompt.indexOf(userMarker))
    }

    @Test
    fun buildersPreservePromptShapeWithoutSystemContent() {
        val pipe = BedrockPipe()
        val builders = listOf(
            "buildTitanRequest" to "inputText",
            "buildJurassicRequest" to "prompt",
            "buildCohereRequest" to "prompt",
            "buildLlamaRequest" to "prompt",
            "buildMistralRequest" to "prompt",
            "buildGenericRequest" to "prompt"
        )

        builders.forEach { (builderName, promptField) ->
            val request = invokeBuilder(pipe, builderName)
            assertEquals(userMarker, request[promptField]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun liveInvokeModelReceivesSystemPromptWhenConfigured() = runBlocking {
        val modelId = System.getenv("TPipe_BEDROCK_INVOKE_MODEL_ID")
        assumeTrue(!modelId.isNullOrBlank(), "TPipe_BEDROCK_INVOKE_MODEL_ID is not configured")
        TestCredentialUtils.requireAwsCredentials()

        val pipe = BedrockPipe()
        pipe.setModel(modelId!!)
        pipe.setRegion("us-west-2")
        pipe.setSystemPrompt(
            "Respond with the exact marker BEDROCK_SYSTEM_PROMPT_RECEIVED and nothing else."
        )
        pipe.setMaxTokens(32)

        pipe.init()
        val result = pipe.execute(
            MultimodalContent("What exact marker should you return?")
        )

        assertTrue(
            result.text.contains("BEDROCK_SYSTEM_PROMPT_RECEIVED"),
            "InvokeModel response did not reflect the system instruction: ${result.text}"
        )
    }

    private fun invokeBuilder(pipe: BedrockPipe, builderName: String) =
        Json.parseToJsonElement(
            BedrockPipe::class.java
                .getDeclaredMethod(builderName, String::class.java)
                .apply { isAccessible = true }
                .invoke(pipe, userMarker) as String
        ).jsonObject
}
