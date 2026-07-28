package bedrockPipe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestMetadataLiveTest
{
    @BeforeAll
    fun checkCredentials()
    {
        val allowTest = System.getenv("AllowTest")
        assumeTrue(allowTest == "true", "AllowTest flag not enabled — skipping live test")
    }

    @Test
    fun setRequestMetadataPersistsThroughCall()
    {
        val pipe = BedrockPipe()
        pipe.setRegion("us-east-1")
        pipe.setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
        pipe.useConverseApi()
        pipe.setRequestMetadata(mapOf(
            "tenant" to "tpipe-upgrade-test",
            "experiment" to "bedrock-1.6.107",
            "user_id" to "qa"
        ))
        kotlinx.coroutines.runBlocking { pipe.init() }

        val result = kotlinx.coroutines.runBlocking {
            pipe.generateText("Say 'ok'")
        }
        assertNotNull(result, "Call with requestMetadata should succeed")
        // The metadata map should persist after the call (it's not consumed)
        val metadata = pipe.getRequestMetadata()
        assertEquals(3, metadata.size, "All three requestMetadata entries should persist")
        assertTrue(metadata.containsKey("tenant"))
    }
}
