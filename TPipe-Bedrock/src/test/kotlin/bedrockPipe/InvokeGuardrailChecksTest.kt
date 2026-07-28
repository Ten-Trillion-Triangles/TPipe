package bedrockPipe

import com.TTT.Pipe.MultimodalContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class InvokeGuardrailChecksTest
{
    @Test
    fun precheckWithoutGuardrailConfiguredThrows()
    {
        val pipe = BedrockPipe()
        // No guardrail set — precheck must throw IllegalStateException.
        // Silent no-op would let unsafe prompts through without a guardrail.
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                pipe.applyGuardrailPrecheck(MultimodalContent(text = "hello"))
            }
        }
    }

    @Test
    fun precheckWithGuardrailButNoClientReturnsNull()
    {
        // With guardrail set but no init() — no bedrockClient.
        // The call should fail gracefully (return null + trace failure),
        // not throw. Caller can distinguish "no call attempted" from
        // "call attempted and got assessments."
        val pipe = BedrockPipe()
        pipe.setGuardrail("test-guardrail-id", "DRAFT")
        val result = kotlinx.coroutines.runBlocking {
            pipe.applyGuardrailPrecheck(MultimodalContent(text = "hello"))
        }
        assertNull(result, "Without a live client, precheck should return null and log failure")
        assertEquals("test-guardrail-id", pipe.getGuardrailIdentifier())
    }

    @Test
    fun precheckExtractsTextFromMultimodalContent()
    {
        // When MultimodalContent has only text, the guardrail request
        // should contain a single GuardrailContentBlock.Text with that text.
        // Pin via the post-call assertions: the request construction path
        // is tested via live test in Step 4. Here we confirm that the
        // method can be called with multimodal content and reaches the
        // guardrail-not-configured branch.
        val pipe = BedrockPipe()
        val mm = MultimodalContent(
            text = "test prompt",
            binaryContent = mutableListOf()
        )
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { pipe.applyGuardrailPrecheck(mm) }
        }
    }
}
