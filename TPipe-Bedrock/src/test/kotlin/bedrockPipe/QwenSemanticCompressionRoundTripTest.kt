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
    fun liveQwenSemanticDecompressionReasoningPipeReconstructsCompressedPromptAndSavesTrace()
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
        val reasoningTracePath = "$resultTraceDir/reasoning.html"

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
                reasoningMethod = ReasoningMethod.SemanticDecompression,
                depth = ReasoningDepth.High,
                duration = ReasoningDuration.Long,
                reasoningInjector = ReasoningInjector.BeforeUserPrompt,
                numberOfRounds = 1,
                focusPoints = mutableMapOf()
            ),
            PipeSettings(
                pipeName = "qwen semantic decompression reasoning",
                provider = ProviderName.Aws,
                model = QWEN_30B_MODEL_ID,
                temperature = 0.1,
                topP = 0.2,
                maxTokens = 4096,
                contextWindowSize = 10000
            )
        ) as BedrockMultimodalPipe).apply {
            setPipeName("qwen semantic decompression reasoning")
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
                    Rebuild the original text into normal, fully readable English.
                    Restore omitted grammar, glue words, and syntax wherever needed to recover the original meaning.
                    Preserve quoted spans exactly.
                    Return only the reconstructed text with no summary, no commentary, and no compressed style.
                """.trimIndent()
            )
            enableTracing(traceConfig)
        }

        assertEquals(
            ReasoningMethod.SemanticDecompression.toString(),
            reasoningPipe.pipeMetadata["reasoningMethod"],
            "The live regression should exercise the official semantic-decompression reasoning method"
        )

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
                assertFalse(
                    result.text.contains("CONTINUE FROM PREVIOUS THINKING"),
                    "The decompressed answer should not echo the reasoning scaffold"
                )
                assertFalse(
                    result.text.contains("USER PROMPT:"),
                    "The decompressed answer should not leak prompt-injection markers"
                )
                assertFalse(
                    result.text.contains("Legend:"),
                    "The decompressed answer should not echo the semantic-compression legend"
                )
                assertTrue(
                    result.text.contains("The flag stays off until the rehearsal is over."),
                    "Quoted instruction content should survive semantic compression and decompression"
                )

                val originalScore = reconstructionSimilarityScore(originalPrompt, result.text)
                val compressedScore = reconstructionSimilarityScore(originalPrompt, compressedPrompt)
                println("Reconstruction similarity score: output=$originalScore compressed=$compressedScore")
                assertTrue(
                    originalScore > compressedScore + 0.05,
                    "The reconstructed output should be materially closer to the original fixture than the compressed prompt"
                )
                assertTrue(
                    originalScore >= 0.45,
                    "The reconstructed output should recover most of the original token sequence"
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
                    reasoningPipe = reasoningPipe,
                    pipelineTracePath = pipelineTracePath,
                    agentTracePath = agentTracePath,
                    reasoningTracePath = reasoningTracePath
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
        reasoningPipe: com.TTT.Pipe.Pipe,
        pipelineTracePath: String,
        agentTracePath: String,
        reasoningTracePath: String
    )
    {
        val pipelineTrace = PipeTracer.exportTrace(pipeline.getTraceId(), TraceFormat.HTML)
        val agentTrace = PipeTracer.exportTrace(resolveTraceId(qwenPipe), TraceFormat.HTML)
        val reasoningTrace = PipeTracer.exportTrace(resolveTraceId(reasoningPipe), TraceFormat.HTML)

        writeStringToFile(pipelineTracePath, pipelineTrace)
        writeStringToFile(agentTracePath, agentTrace)
        writeStringToFile(reasoningTracePath, reasoningTrace)

        assertTraceSaved(pipelineTracePath, pipelineTrace, "pipeline")
        assertTraceSaved(agentTracePath, agentTrace, "agent")
        assertTraceSaved(reasoningTracePath, reasoningTrace, "reasoning")
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
        return buildString {
            appendLine("Aster Ridge Labs and North Harbor Systems are reviewing the launch plan together.")
            repeat(4) {
                appendLine(
                    "Aster Ridge Labs asked North Harbor Systems to confirm the migration checklist, and North Harbor " +
                        "Systems replied to Aster Ridge Labs with the release notes."
                )
            }
            appendLine(
                "The quoted instruction must remain exact: \"The flag stays off until the rehearsal is over.\""
            )
            appendLine(
                "Aster Ridge Labs and North Harbor Systems need the final status note, the release sequence, and the " +
                    "handoff checklist preserved."
            )
            appendLine(
                "Aster Ridge Labs should keep the same names, North Harbor Systems should keep the same names, and the " +
                    "reconstructed answer should keep the same names."
            )
        }.trimEnd()
    }

    private fun reconstructionSimilarityScore(reference: String, candidate: String): Double
    {
        val referenceTokens = tokenize(reference)
        val candidateTokens = tokenize(candidate)
        if(referenceTokens.isEmpty())
        {
            return 0.0
        }

        val dp = Array(referenceTokens.size + 1) { IntArray(candidateTokens.size + 1) }

        for(i in referenceTokens.indices)
        {
            for(j in candidateTokens.indices)
            {
                dp[i + 1][j + 1] = if(referenceTokens[i] == candidateTokens[j])
                {
                    dp[i][j] + 1
                }
                else
                {
                    maxOf(dp[i][j + 1], dp[i + 1][j])
                }
            }
        }

        return dp[referenceTokens.size][candidateTokens.size].toDouble() / referenceTokens.size.toDouble()
    }

    private fun tokenize(text: String): List<String>
    {
        return Regex("[A-Za-z0-9']+").findAll(text)
            .map { it.value.lowercase() }
            .toList()
    }
}
