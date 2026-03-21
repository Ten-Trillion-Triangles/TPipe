package bedrockPipe

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Enums.ProviderName
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import com.TTT.Structs.PipeSettings
import Defaults.BedrockConfiguration
import Defaults.reasoning.ReasoningBuilder
import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import Defaults.reasoning.ReasoningInjector
import Defaults.reasoning.ReasoningMethod
import Defaults.reasoning.ReasoningSettings
import com.TTT.Util.buildSemanticDecompressionInstructions
import com.TTT.Util.semanticCompress
import com.TTT.Util.writeStringToFile
import env.bedrockEnv
import java.io.File
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val QWEN_30B_MODEL_ID = "qwen.qwen3-coder-30b-a3b-v1:0"
private const val QWEN_30B_MODEL_ARN = "arn:aws:bedrock:us-west-2::foundation-model/qwen.qwen3-coder-30b-a3b-v1:0"
private const val QWEN_30B_REGION = "us-west-2"

class QwenSemanticCompressionRoundTripTest
{
    @Test
    fun liveQwenAgentReconstructsCompressedPromptAndSavesTrace()
    {
        TestCredentialUtils.requireAwsCredentials()

        val traceConfig = TraceConfig(
            enabled = true,
            outputFormat = TraceFormat.HTML,
            detailLevel = TraceDetailLevel.DEBUG,
            includeContext = true,
            includeMetadata = true
        )

        println("Using Qwen foundation model $QWEN_30B_MODEL_ID ($QWEN_30B_MODEL_ARN) in $QWEN_30B_REGION")

        val inferenceConfigPath = Files.createTempFile("tpipe-qwen-inference", ".txt")
        val originalPrompt = buildRoundTripPromptFixture()
        val compression = semanticCompress(originalPrompt)
        val compressedPrompt = if(compression.legend.isNotBlank())
        {
            "${compression.legend}\n\n${compression.compressedText}"
        }
        else
        {
            compression.compressedText
        }

        assertTrue(
            compression.compressedText.length < originalPrompt.length,
            "Semantic compression should reduce the prompt body before it is sent to the model"
        )
        assertTrue(
            compression.legend.isNotBlank(),
            "The round-trip fixture should produce a legend because it repeats proper nouns"
        )

        val resultTraceDir = "${TPipeConfig.getTraceDir()}/Library/qwen-semantic-compression-round-trip"
        val pipelineTracePath = "$resultTraceDir/pipeline.html"
        val agentTracePath = "$resultTraceDir/agent.html"

        bedrockEnv.resetInferenceConfig()
        bedrockEnv.setInferenceConfigFile(inferenceConfigPath.toFile())
        inferenceConfigPath.toFile().writeText("$QWEN_30B_MODEL_ID=\n")

        val reasoningPipe = (ReasoningBuilder.reasonWithBedrock(
            BedrockConfiguration(
                region = QWEN_30B_REGION,
                model = QWEN_30B_MODEL_ID,
                inferenceProfile = "",
                useConverseApi = true
            ),
            ReasoningSettings(
                reasoningMethod = ReasoningMethod.ExplicitCot,
                depth = ReasoningDepth.High,
                duration = ReasoningDuration.Long,
                reasoningInjector = ReasoningInjector.BeforeUserPrompt,
                numberOfRounds = 1,
                focusPoints = mutableMapOf()
            ),
            PipeSettings(
                pipeName = "qwen semantic compression reasoning",
                provider = ProviderName.Aws,
                model = QWEN_30B_MODEL_ID,
                temperature = 0.1,
                topP = 0.2,
                maxTokens = 4096,
                contextWindowSize = 10000
            )
        ) as BedrockMultimodalPipe).apply {
            setPipeName("qwen semantic compression reasoning")
            setReadTimeout(600)
            enableMaxTokenOverflow()
            enableTracing(traceConfig)
        }

        val qwenPipe = BedrockMultimodalPipe().apply {
            setProvider(ProviderName.Aws)
            setModel(QWEN_30B_MODEL_ID)
            setRegion(QWEN_30B_REGION)
            useConverseApi()
            setPipeName("qwen semantic compression decompressor")
            setTemperature(0.1)
            setTopP(0.2)
            setMaxTokens(8192)
            setReadTimeout(600)
            enableMaxTokenOverflow()
            setReasoningPipe(reasoningPipe)
            setSystemPrompt(
                buildSemanticDecompressionInstructions() + "\n\n" + """
                    You are a reconstruction agent for a TPipe semantic-compressed prompt.
                    Rebuild the original text as faithfully as possible.
                    Preserve quoted spans exactly.
                    Return only the reconstructed text with no summary and no commentary.
                """.trimIndent()
            )
            enableTracing(traceConfig)
        }

        val pipeline = Pipeline().apply {
            setPipelineName("qwen semantic compression round trip")
            add(qwenPipe)
            enableTracing(traceConfig)
        }

        PipeTracer.enable()

        try
        {
            runBlocking(Dispatchers.IO)
            {
                pipeline.init(initPipes = true)
                val result = pipeline.execute(MultimodalContent(text = compressedPrompt))

                assertNotNull(result.text, "The live Qwen result should not be null")
                assertTrue(result.text.isNotBlank(), "The live Qwen result should not be blank")
                assertTrue(
                    result.text.contains("Aster Ridge Labs"),
                    "The reconstructed output should restore repeated proper nouns from the legend"
                )
                assertTrue(
                    result.text.contains("North Harbor Systems"),
                    "The reconstructed output should preserve the original proper-noun content"
                )
                assertTrue(
                    result.text.contains("\"The flag stays off until the rehearsal is over.\""),
                    "Quoted text should survive semantic compression and decompression exactly"
                )

                val recompressed = semanticCompress(result.text)
                assertFalse(
                    recompressed.compressedText.isBlank(),
                    "Recompressing the output should still produce meaningful compressed text"
                )
                assertEquals(
                    compression.legendMap.values.toSet(),
                    recompressed.legendMap.values.toSet(),
                    "Repeated proper nouns should round-trip through the semantic legend"
                )

            }
        }
        finally
        {
            try
            {
                writeTraceArtifacts(
                    pipeline = pipeline,
                    qwenPipe = qwenPipe,
                    pipelineTracePath = pipelineTracePath,
                    agentTracePath = agentTracePath
                )
            }
            catch(traceError: Throwable)
            {
                println("Unable to save Qwen round-trip traces: ${traceError.message}")
                traceError.printStackTrace()
            }

            PipeTracer.disable()
            bedrockEnv.resetInferenceConfig()
            inferenceConfigPath.deleteIfExists()
        }
    }

    private fun writeTraceArtifacts(
        pipeline: Pipeline,
        qwenPipe: com.TTT.Pipe.Pipe,
        pipelineTracePath: String,
        agentTracePath: String
    )
    {
        val pipelineTrace = PipeTracer.exportTrace(pipeline.getTraceId(), TraceFormat.HTML)
        val agentTrace = PipeTracer.exportTrace(resolveTraceId(qwenPipe), TraceFormat.HTML)

        writeStringToFile(pipelineTracePath, pipelineTrace)
        writeStringToFile(agentTracePath, agentTrace)

        assertTraceSaved(pipelineTracePath, pipelineTrace, "pipeline")
        assertTraceSaved(agentTracePath, agentTrace, "agent")
    }

    private fun assertTraceSaved(path: String, report: String, label: String)
    {
        val file = File(path)
        assertTrue(file.exists(), "Trace file should exist for $label")
        assertTrue(file.length() > 0, "Trace file should not be empty for $label")
        assertTrue(report.isNotBlank(), "Trace report should not be blank for $label")
    }

    private fun resolveTraceId(pipe: com.TTT.Pipe.Pipe): String
    {
        val field = com.TTT.Pipe.Pipe::class.java.getDeclaredField("pipeId")
        field.isAccessible = true
        return field.get(pipe) as String
    }

    private fun buildRoundTripPromptFixture(): String
    {
        return """
            Aster Ridge Labs finished the launch review with Maya Chen and North Harbor Systems on Tuesday morning.
            Maya Chen asked the team to verify the migration checklist, confirm the safety gate, and preserve the note
            that "The flag stays off until the rehearsal is over." Aster Ridge Labs then asked North Harbor Systems
            to audit the telemetry dashboards, rerun the reliability script, and report whether the build output,
            the deployment plan, and the partner handoff were all ready before the release window opened.

            The follow-up memo said Aster Ridge Labs should keep the same priorities for the second pass: compare the
            dashboard alerts, check the rollback path, and make sure North Harbor Systems records any missing data
            before the release goes live. Maya Chen also wanted the team to keep the quoted note exact, preserve the
            launch sequence, and avoid dropping any detail that could change the final release decision.

            On the final review, Aster Ridge Labs asked for a short status note that kept the exact intent, the
            original names, the quoted instruction, and the full list of checks that had already been approved.
        """.trimIndent()
    }
}
