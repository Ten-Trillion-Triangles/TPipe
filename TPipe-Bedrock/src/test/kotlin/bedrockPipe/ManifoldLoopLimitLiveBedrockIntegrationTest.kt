package bedrockPipe

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Enums.ProviderName
import com.TTT.P2P.AgentRequest
import com.TTT.Pipeline.ManifoldLoopLimitExceededException
import com.TTT.Pipeline.manifold
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.writeStringToFile
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Live Manifold integration test that verifies the loop limit safety system is enforced
 * when using Qwen 30B with real Bedrock API calls.
 *
 * This test exercises the full manager-worker loop with a low iteration limit (3) to confirm
 * that ManifoldLoopLimitExceededException is thrown when the limit is hit, and that HTML traces
 * are correctly saved.
 */
class ManifoldLoopLimitLiveBedrockIntegrationTest
{
    private lateinit var qwenInferenceConfigFile: File

    companion object {
        private const val QWEN_30B_MODEL_ID = "qwen.qwen3-coder-30b-a3b-v1:0"
        private const val QWEN_30B_MODEL_ARN = "arn:aws:bedrock:us-west-2::foundation-model/qwen.qwen3-coder-30b-a3b-v1:0"
        private const val QWEN_30B_REGION = "us-west-2"
        private const val LOOP_LIMIT = 3
        private const val TRACE_SUBDIRECTORY = "Library/manifold-loop-limit-live-bedrock"
    }

    @BeforeEach
    fun setup()
    {
        TestCredentialUtils.requireAwsCredentials()
        configureQwenInferenceBinding()
        PipeTracer.enable()
    }

    @AfterEach
    fun cleanup()
    {
        PipeTracer.disable()
        bedrockEnv.resetInferenceConfig()
        if(::qwenInferenceConfigFile.isInitialized && qwenInferenceConfigFile.exists())
        {
            qwenInferenceConfigFile.delete()
        }
    }

    /**
     * Verifies that the manifold loop limit is enforced at 3 iterations when the manager
     * continuously dispatches work to a worker. Confirms that ManifoldLoopLimitExceededException
     * is thrown with the correct iteration count and that an HTML trace is saved.
     */
    @Test
    fun manifoldLoopLimitEnforcedAtThreeIterations() = runBlocking {
        val traceBaseDir = File("${TPipeConfig.getTraceDir()}/$TRACE_SUBDIRECTORY")
        traceBaseDir.mkdirs()

        val traceConfig = traceConfig()

        // Build the manifold using the DSL with a low iteration limit
        val manifold = manifold {
            tracing {
                config(traceConfig)
            }
            maxIterations(LOOP_LIMIT)
            history {
                autoTruncate()
            }

            manager {
                pipeline {
                    pipelineName = "loop-limit-manager"
                    add(BedrockMultimodalPipe().apply {
                        setProvider(ProviderName.Aws)
                        setModel(QWEN_30B_MODEL_ID)
                        setRegion(QWEN_30B_REGION)
                        useConverseApi()
                        setReadTimeout(300)
                        setTemperature(0.1)
                        setTopP(0.2)
                        setMaxTokens(512)
                        pipeName = "manager-dispatch"
                        setSystemPrompt(
                            """
                            You are a manager that orchestrates task execution through worker agents.
                            Dispatch work to the "loop-worker" agent to continue processing.
                            Always respond with valid JSON matching the provided schema.
                            """.trimIndent()
                        )
                        setJsonOutput(AgentRequest())
                        requireJsonPromptInjection(true)
                        autoTruncateContext()
                        enableMaxTokenOverflow()
                    })
                }
                agentDispatchPipe("manager-dispatch")
            }

            worker("loop-worker") {
                description("Simple echo worker that processes tasks")
                pipeline {
                    pipelineName = "loop-worker-pipeline"
                    add(BedrockMultimodalPipe().apply {
                        setProvider(ProviderName.Aws)
                        setModel(QWEN_30B_MODEL_ID)
                        setRegion(QWEN_30B_REGION)
                        useConverseApi()
                        setReadTimeout(300)
                        setTemperature(0.1)
                        setTopP(0.2)
                        setMaxTokens(256)
                        pipeName = "worker-echo"
                        setSystemPrompt(
                            """
                            You are a worker agent. Process the task in the user prompt and respond with a brief echo.
                            Return a short response (under 50 words) acknowledging the work was done.
                            """.trimIndent()
                        )
                        autoTruncateContext()
                        enableMaxTokenOverflow()
                    })
                }
            }
        }

        val input = MultimodalContent(text = "Start the loop limit test")

        // The loop limit should be hit after LOOP_LIMIT iterations
        val exception = try {
            manifold.execute(input)
            null
        } catch (e: ManifoldLoopLimitExceededException) {
            e
        } catch (e: Throwable) {
            // Rethrow unexpected exceptions
            throw e
        }

        // Assert the loop limit exception was thrown with correct values
        assertNotNull(exception, "ManifoldLoopLimitExceededException should be thrown when loop limit is hit")
        val caughtException = exception!!
        assertEquals(LOOP_LIMIT, caughtException.iterationsReached, "Iterations reached should equal the configured limit")
        assertEquals(LOOP_LIMIT, caughtException.maxIterations, "Max iterations should match the configured limit")

        // Save the HTML trace
        val htmlTrace = manifold.getTraceReport(TraceFormat.HTML)
        val htmlTracePath = File(traceBaseDir, "manifold-loop-limit.html")
        writeStringToFile(htmlTracePath.absolutePath, htmlTrace)

        // Verify the trace file was saved and has content
        assertTrue(htmlTracePath.exists(), "HTML trace file should exist at ${htmlTracePath.absolutePath}")
        assertTrue(htmlTracePath.length() > 0, "HTML trace file should not be empty")
        assertTrue(htmlTrace.isNotBlank(), "HTML trace content should not be blank")

        println("Loop limit test passed: ManifoldLoopLimitExceededException thrown at ${caughtException.iterationsReached}/${caughtException.maxIterations}")
        println("HTML trace saved to: ${htmlTracePath.absolutePath}")
        println("HTML trace size: ${htmlTracePath.length()} bytes")
    }

    private fun configureQwenInferenceBinding() {
        bedrockEnv.resetInferenceConfig()
        qwenInferenceConfigFile = File.createTempFile("tpipe-qwen-30b-inference", ".txt")
        qwenInferenceConfigFile.writeText("$QWEN_30B_MODEL_ID=$QWEN_30B_MODEL_ARN\n")
        qwenInferenceConfigFile.deleteOnExit()
        bedrockEnv.setInferenceConfigFile(qwenInferenceConfigFile)
        bedrockEnv.loadInferenceConfig()
    }

    private fun traceConfig(): TraceConfig {
        return TraceConfig(
            enabled = true,
            outputFormat = TraceFormat.HTML,
            detailLevel = TraceDetailLevel.DEBUG,
            includeContext = true,
            includeMetadata = true
        )
    }
}
