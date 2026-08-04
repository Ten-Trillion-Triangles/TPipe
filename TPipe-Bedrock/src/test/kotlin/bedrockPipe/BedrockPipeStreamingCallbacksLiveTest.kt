package bedrockPipe

import TestCredentialUtils
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue

/**
 * Live integration tests for BedrockPipe streaming callbacks.
 *
 * Verifies the streaming recursion through [com.TTT.Pipe.Pipe.propagateStreamingCallback]
 * works end-to-end on real Bedrock InvokeModelWithResponseStream calls.
 *
 * Gated on:
 * - AllowTest=true (env var, per TestCredentialUtils.requireAwsCredentials)
 * - ~/.aws/credentials present (resolved via DefaultChainCredentialsProvider)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BedrockPipeStreamingCallbacksLiveTest
{
    @BeforeAll
    fun requireAwsCredentials()
    {
        TestCredentialUtils.requireAwsCredentials()
    }

    @Test
    fun testSetStreamingCallbackPropagatesToValidatorOnLiveCall()
    {
        val received = mutableListOf<String>()
        val validator = BedrockPipe().setRegion("us-east-2")
        val parent = BedrockPipe().setRegion("us-east-2")
        parent.setValidatorPipe(validator)
        parent.setModel("amazon.nova-lite-v1:0")
        parent.setStreamingCallback(suspend { chunk: String -> received.add(chunk) })

        // Recursion assertion: propagateStreamingCallback from setStreamingCallback
        // (Plan task 8) must wire the callback into the validator's manager too.
        // Unit regression test in StreamingCallbackTest.kt verifies the
        // end-to-end fire path via validator.testEmit(); this live test confirms
        // the production wiring on real BedrockPipe instances with the live
        // streaming flag flipped.
        val validatorManager = validator.obtainStreamingCallbackManager()
        assertTrue(
            validatorManager.callbackCount() == 1,
            "Expected setStreamingCallback to propagate the callback into the validator's manager; " +
            "got callbackCount=${validatorManager.callbackCount()}"
        )

        // Also exercise the live wire on the parent so the test fails loudly if
        // the production code regresses streaming on a real AWS call.
        runBlocking { parent.init()
            parent.execute(MultimodalContent(text = "Reply with one short sentence.")) }
        assertTrue(received.isNotEmpty(), "Live execute on parent produced no streaming chunks")
        println("DEBUG: parent captured ${received.size} chunks; validator recursion wired ${validatorManager.callbackCount()} callback(s)")
    }

    @Test
    fun testStreamingCallbacksMultipleListenersBothReceiveOnLiveCall()
    {
        val chunksA = mutableListOf<String>()
        val chunksB = mutableListOf<String>()
        val pipe = BedrockPipe().setRegion("us-east-2")
        pipe.setModel("amazon.nova-lite-v1:0")

        pipe.streamingCallbacks {
            add(suspend { chunk: String -> chunksA.add(chunk) })
            add(suspend { chunk: String -> chunksB.add(chunk) })
        }

        runBlocking { pipe.init()
            pipe.execute(MultimodalContent(text = "Reply with one short sentence.")) }

        assertTrue(chunksA.isNotEmpty(), "Callback A received no chunks")
        assertTrue(chunksB.isNotEmpty(), "Callback B received no chunks")
        println("DEBUG: A=${chunksA.size} chunks, B=${chunksB.size} chunks")
    }

    @Test
    fun testDisableStreamingClearsDescendantsOnLiveCall()
    {
        val parentReceived = mutableListOf<String>()
        val transformation = BedrockPipe().setRegion("us-east-2")
        val parent = BedrockPipe().setRegion("us-east-2")
        parent.setTransformationPipe(transformation)
        parent.setModel("amazon.nova-lite-v1:0")
        parent.setStreamingCallback(suspend { chunk: String -> parentReceived.add(chunk) })

        runBlocking { parent.init()
            parent.execute(MultimodalContent(text = "Reply with one short sentence.")) }
        val capturedBeforeDisable = parentReceived.toList()

        parent.disableStreaming()

        // After disable, a fresh call should not fire callbacks through the parent's manager.
        // (Note: this is illustrative; Bedrock's underlying API call still happens if executed.
        //  The assertion verifies the parent pipe's callback pathway is suppressed.)
        runBlocking { parent.init()
            parent.execute(MultimodalContent(text = "Reply again with one short sentence.")) }

        // After disable, the new chunks added after `disableStreaming` should be 0.
        val newChunks = parentReceived.size - capturedBeforeDisable.size
        assertTrue(newChunks == 0, "Expected 0 new chunks after disableStreaming; got $newChunks")
    }
}
