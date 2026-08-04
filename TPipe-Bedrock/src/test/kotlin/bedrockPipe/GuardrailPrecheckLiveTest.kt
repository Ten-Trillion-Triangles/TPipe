package bedrockPipe

import com.TTT.Pipe.MultimodalContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuardrailPrecheckLiveTest
{
    @BeforeAll
    fun checkCredentials()
    {
        // Live tests skip silently without AWS creds. Per memory:
        // TPipe-Bedrock live test env gate is `AllowTest=true` + ~/.aws/credentials.
        val allowTest = System.getenv("AllowTest")
        assumeTrue(allowTest == "true", "AllowTest flag not enabled — skipping live test")
    }

    @Test
    fun liveGuardrailPrecheckReturnsAssessments()
    {
        val pipe = BedrockPipe()
        pipe.setRegion("us-east-1")
        pipe.setGuardrail("qa-test-guardrail", "DRAFT")  // Replace with a real guardrail ARN in CI
        kotlinx.coroutines.runBlocking { pipe.init() }

        val result = kotlinx.coroutines.runBlocking {
            pipe.applyGuardrailPrecheck(MultimodalContent(text = "Tell me a joke"))
        }

        // Assertions on the live result. With a real guardrail configured,
        // we expect non-null response with at least the inline check results.
        assertNotNull(result, "Live guardrail precheck should return a response")
        result?.let {
            // The 1.6.107 InvokeGuardrailChecksResponse carries per-policy results
            // (contentFilter, promptAttack, sensitiveInformation) — not the older
            // 'assessments' list returned by ApplyGuardrailResponse. Pin the shape.
            assertNotNull(it.results, "Response should have results (per-policy check outcomes)")
            assertNotNull(it.usage, "Response should have usage (token consumption)")
        }
    }
}
