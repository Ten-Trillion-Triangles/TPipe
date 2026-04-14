package bedrockPipe

import Defaults.BedrockConfiguration
import Defaults.reasoning.ReasoningBuilder
import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import Defaults.reasoning.ReasoningInjector
import Defaults.reasoning.ReasoningMethod
import Defaults.reasoning.ReasoningSettings
import com.TTT.Config.TPipeConfig
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TraceFormat
import com.TTT.Enums.ProviderName
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import com.TTT.Structs.BestIdeaResponse
import com.TTT.Structs.PipeSettings
import com.TTT.Util.deserialize
import com.TTT.Util.writeStringToFile
import env.bedrockEnv
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Live test to investigate BestIdea reasoning pipe behavior when attached to a regular pipe.
 * This test makes real AWS API calls and saves traces for analysis.
 */
class BestIdeaReasoningLiveTest
{
    private lateinit var qwenInferenceConfigFile: File

    companion object {
        private const val QWEN_30B_MODEL_ID = "qwen.qwen3-coder-30b-a3b-v1:0"
        private const val QWEN_30B_MODEL_ARN = "arn:aws:bedrock:us-west-2::foundation-model/qwen.qwen3-coder-30b-a3b-v1:0"
        private const val QWEN_30B_REGION = "us-west-2"
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
        PipeTracer.getAllTraces().keys.forEach { PipeTracer.clearTrace(it) }
        PipeTracer.disable()
        bedrockEnv.resetInferenceConfig()
        if(::qwenInferenceConfigFile.isInitialized && qwenInferenceConfigFile.exists())
        {
            qwenInferenceConfigFile.delete()
        }
    }

    /**
     * Test BestIdea reasoning pipe in isolation - no parent pipe, just the reasoning pipe itself.
     * This helps us understand if BestIdea is returning {} or malformed content.
     */
    @Test
    fun testBestIdeaReasoningPipeInIsolation() = runBlocking {
        val traceBaseDir = File("${TPipeConfig.getTraceDir()}/Library/best-idea-reasoning/qwen30b-isolation")
        traceBaseDir.mkdirs()

        val reasoningPipe = buildBestIdeaReasoningPipe("best-idea-isolated")

        val pipeline = Pipeline().apply {
            setPipelineName("best-idea-isolation-test")
            add(reasoningPipe)
            enableTracing(traceConfig())
        }

        try
        {
            pipeline.init(initPipes = true)
            val result = pipeline.execute(
                MultimodalContent(text = "Design a function to calculate factorial of a number")
            )

            println("BestIdea isolation result text: ${result.text}")
            println("BestIdea isolation pipeError: ${result.pipeError}")

            // Save trace
            val htmlTrace = pipeline.getTraceReport(TraceFormat.HTML)
            val jsonTrace = pipeline.getTraceReport(TraceFormat.JSON)
            val htmlPath = File(traceBaseDir, "isolation.html")
            val jsonPath = File(traceBaseDir, "isolation.json")
            writeStringToFile(htmlPath.absolutePath, htmlTrace)
            writeStringToFile(jsonPath.absolutePath, jsonTrace)

            // Analyze the result
            val bestIdeaResponse = deserialize<BestIdeaResponse>(result.text)
            println("Deserialized BestIdeaResponse: $bestIdeaResponse")
            println("BestIdeaResponse problemAnalysis: ${bestIdeaResponse?.problemAnalysis}")
            println("BestIdeaResponse selectedApproach: ${bestIdeaResponse?.selectedApproach}")

            // Assert the result is not empty
            assertTrue(result.text.isNotBlank(), "BestIdea should return non-blank text")
            assertTrue(result.text != "{}", "BestIdea should NOT return empty JSON {}")
            assertNotNull(bestIdeaResponse, "BestIdea should deserialize to BestIdeaResponse")
            assertTrue(bestIdeaResponse?.problemAnalysis?.isNotBlank() == true, "BestIdea should have problemAnalysis")

            println("Trace saved to: $htmlPath")
        }
        finally
        {
            PipeTracer.clearTrace(pipeline.getTraceId())
        }
    }

    /**
     * Test BestIdea reasoning pipe attached to a parent pipe that has wrapContentWithConverseHistory enabled.
     * This is the configuration that triggers the bug in the original bug report.
     */
    @Test
    fun testBestIdeaReasoningPipeWithConverseWrapping() = runBlocking {
        val traceBaseDir = File("${TPipeConfig.getTraceDir()}/Library/best-idea-reasoning/qwen30b-converse-wrapped")
        traceBaseDir.mkdirs()

        // First, create the reasoning pipe
        val reasoningPipe = buildBestIdeaReasoningPipe("best-idea-nested")

        // Create the parent pipe with BestIdea reasoning attached
        val parentPipe = BedrockMultimodalPipe().apply {
            setProvider(ProviderName.Aws)
            setModel(QWEN_30B_MODEL_ID)
            setRegion(QWEN_30B_REGION)
            useConverseApi()
            pipeName = "parent-with-best-idea"
            setMaxTokens(4000)
            setTemperature(0.3)
            setTopP(0.7)
            setReasoningPipe(reasoningPipe)
            setSystemPrompt("You are an AI assistant that helps solve programming problems.")
            enableTracing(traceConfig())
        }

        val pipeline = Pipeline().apply {
            setPipelineName("best-idea-converse-wrapped")
            add(parentPipe)
            enableTracing(traceConfig())
        }

        try
        {
            pipeline.init(initPipes = true)
            val result = pipeline.execute(
                MultimodalContent(text = "Debug this function: function add(a, b) { return a + b; }")
            )

            println("Converse wrapped result text length: ${result.text.length}")
            println("Converse wrapped result text: ${result.text}")
            println("Converse wrapped pipeError: ${result.pipeError}")

            // Save trace
            val htmlTrace = pipeline.getTraceReport(TraceFormat.HTML)
            val jsonTrace = pipeline.getTraceReport(TraceFormat.JSON)
            val htmlPath = File(traceBaseDir, "converse-wrapped.html")
            val jsonPath = File(traceBaseDir, "converse-wrapped.json")
            writeStringToFile(htmlPath.absolutePath, htmlTrace)
            writeStringToFile(jsonPath.absolutePath, jsonTrace)

            // Check for the bug: result text should NOT be "{}"
            assertTrue(result.text.isNotBlank(), "Parent pipe should return non-blank text")
            assertEquals("{}", result.text, "BUG: Parent pipe returned empty JSON {}")
            assertTrue(result.text != "{}", "Parent pipe should NOT return empty JSON {}")

            println("Trace saved to: $htmlPath")
        }
        finally
        {
            PipeTracer.clearTrace(pipeline.getTraceId())
        }
    }

    /**
     * Test BestIdea reasoning pipe with wrapContentWithConverseHistory enabled on the parent.
     * This is the exact configuration from the bug report.
     */
    @Test
    fun testBestIdeaReasoningPipeWithExplicitConverseWrapping() = runBlocking {
        val traceBaseDir = File("${TPipeConfig.getTraceDir()}/Library/best-idea-reasoning/qwen30b-explicit-converse")
        traceBaseDir.mkdirs()

        // First, create the reasoning pipe
        val reasoningPipe = buildBestIdeaReasoningPipe("best-idea-explicit")

        // Create the parent pipe with wrapContentWithConverseHistory enabled (the bug trigger)
        val parentPipe = BedrockMultimodalPipe().apply {
            setProvider(ProviderName.Aws)
            setModel(QWEN_30B_MODEL_ID)
            setRegion(QWEN_30B_REGION)
            useConverseApi()
            pipeName = "parent-explicit-converse"
            setMaxTokens(4000)
            setTemperature(0.3)
            setTopP(0.7)
            setReasoningPipe(reasoningPipe)
            setSystemPrompt("You are an AI assistant that helps solve programming problems.")
            // This is the key setting that triggers the bug
            wrapContentWithConverse(com.TTT.Context.ConverseRole.user)
            enableTracing(traceConfig())
        }

        val pipeline = Pipeline().apply {
            setPipelineName("best-idea-explicit-converse")
            add(parentPipe)
            enableTracing(traceConfig())
        }

        try
        {
            pipeline.init(initPipes = true)
            val result = pipeline.execute(
                MultimodalContent(text = "Debug this function: function add(a, b) { return a + b; }")
            )

            println("Explicit converse wrapped result text length: ${result.text.length}")
            println("Explicit converse wrapped result text: ${result.text.take(200)}...")
            println("Explicit converse wrapped pipeError: ${result.pipeError}")

            // Save trace
            val htmlTrace = pipeline.getTraceReport(TraceFormat.HTML)
            val jsonTrace = pipeline.getTraceReport(TraceFormat.JSON)
            val htmlPath = File(traceBaseDir, "explicit-converse.html")
            val jsonPath = File(traceBaseDir, "explicit-converse.json")
            writeStringToFile(htmlPath.absolutePath, htmlTrace)
            writeStringToFile(jsonPath.absolutePath, jsonTrace)

            // Analyze trace events
            analyzeTraceEvents(jsonTrace)

            // Check for the bug: result text should NOT be "{}"
            assertTrue(result.text.isNotBlank(), "Parent pipe should return non-blank text")
            assertEquals("{}", result.text, "BUG: Parent pipe returned empty JSON {}")
            assertTrue(result.text != "{}", "Parent pipe should NOT return empty JSON {}")

            println("Trace saved to: $htmlPath")
        }
        finally
        {
            PipeTracer.clearTrace(pipeline.getTraceId())
        }
    }

    private fun buildBestIdeaReasoningPipe(reasoningPipeName: String): BedrockMultimodalPipe {
        val reasoningSettings = ReasoningSettings(
            reasoningMethod = ReasoningMethod.BestIdea,
            depth = ReasoningDepth.Med,
            duration = ReasoningDuration.Med,
            reasoningInjector = ReasoningInjector.AfterUserPrompt,
            numberOfRounds = 1
        )

        val pipeSettings = PipeSettings(
            pipeName = reasoningPipeName,
            provider = ProviderName.Aws,
            model = QWEN_30B_MODEL_ID,
            temperature = 0.1,
            topP = 0.2,
            maxTokens = 2000,
            contextWindowSize = 8000
        )

        return (ReasoningBuilder.reasonWithBedrock(
            bedrockConfig = BedrockConfiguration(
                region = QWEN_30B_REGION,
                model = QWEN_30B_MODEL_ID,
                inferenceProfile = "",
                useConverseApi = true
            ),
            reasoningSettings = reasoningSettings,
            pipeSettings = pipeSettings
        ) as BedrockMultimodalPipe).apply {
            pipeName = reasoningPipeName
            setRegion(QWEN_30B_REGION)
            useConverseApi()
            setReadTimeout(600)
            enableMaxTokenOverflow()
            enableTracing(traceConfig())
        }
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

    private fun configureQwenInferenceBinding() {
        bedrockEnv.resetInferenceConfig()
        qwenInferenceConfigFile = File.createTempFile("tpipe-qwen-30b-inference", ".txt")
        qwenInferenceConfigFile.writeText("$QWEN_30B_MODEL_ID=$QWEN_30B_MODEL_ARN\n")
        qwenInferenceConfigFile.deleteOnExit()
        bedrockEnv.setInferenceConfigFile(qwenInferenceConfigFile)
        bedrockEnv.loadInferenceConfig()

        assertEquals(
            QWEN_30B_MODEL_ARN,
            bedrockEnv.getInferenceProfileId(QWEN_30B_MODEL_ID),
            "The test should bind the Qwen 30B model ID to the resolved Bedrock ARN"
        )
        println("Using Qwen 30B $QWEN_30B_MODEL_ID in $QWEN_30B_REGION")
    }

    private fun analyzeTraceEvents(jsonTrace: String) {
        println("\n=== TRACE EVENT ANALYSIS ===")
        try {
            val element = Json.parseToJsonElement(jsonTrace)
            element.jsonArray.forEach { item ->
                val obj = item.jsonObject
                val eventType = obj["eventType"]?.jsonPrimitive?.contentOrNull
                val pipeName = obj["pipeName"]?.jsonPrimitive?.contentOrNull
                val metadata = obj["metadata"]?.jsonObject

                when (eventType) {
                    TraceEventType.API_CALL_SUCCESS.name -> {
                        val text = obj["content"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                        println("API_CALL_SUCCESS: pipeName=$pipeName, textLength=${text?.length ?: 0}")
                        if (text != null && text.length < 500) {
                            println("  text: $text")
                        }
                    }
                    TraceEventType.MANAGER_DECISION.name -> {
                        val responseLength = metadata?.get("responseLength")
                        println("MANAGER_DECISION: responseLength=$responseLength")
                    }
                    TraceEventType.PIPE_FAILURE.name -> {
                        val error = metadata?.get("error")?.jsonPrimitive?.contentOrNull
                        println("PIPE_FAILURE: pipeName=$pipeName, error=$error")
                    }
                }
            }
        } catch (e: Exception) {
            println("Error analyzing trace: ${e.message}")
        }
    }
}
