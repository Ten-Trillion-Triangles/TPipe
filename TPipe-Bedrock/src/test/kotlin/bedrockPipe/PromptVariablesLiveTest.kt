package bedrockPipe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PromptVariablesLiveTest
{
    @BeforeAll
    fun checkCredentials()
    {
        val allowTest = System.getenv("AllowTest")
        assumeTrue(allowTest == "true", "AllowTest flag not enabled — skipping live test")
    }

    @Test
    fun setPromptVariablesPersistsThroughCall()
    {
        val pipe = BedrockPipe()
        pipe.setRegion("us-east-1")
        pipe.setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
        pipe.useConverseApi()
        pipe.setPromptVariables(mapOf(
            "name" to "world",
            "topic" to "kotlin"
        ))
        kotlinx.coroutines.runBlocking { pipe.init() }

        val result = kotlinx.coroutines.runBlocking {
            pipe.generateText("Say 'ok'")
        }
        assertNotNull(result, "Call with promptVariables should succeed")
        val vars = pipe.getPromptVariables()
        assertEquals(2, vars?.size, "Both promptVariables entries should persist")
        assertTrue(vars?.containsKey("name") == true)
    }
}
