package bedrockPipe

import Defaults.BedrockConfiguration
import Defaults.reasoning.ReasoningBuilder
import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import Defaults.reasoning.ReasoningInjector
import Defaults.reasoning.ReasoningMethod
import Defaults.reasoning.ReasoningSettings
import com.TTT.Config.TPipeConfig
import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TraceFormat
import com.TTT.Enums.ProviderName
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import com.TTT.Structs.PipeSettings
import com.TTT.Structs.StructuredCot
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

private const val QWEN_30B_MODEL_ID = "qwen.qwen3-coder-30b-a3b-v1:0"
private const val QWEN_30B_MODEL_ARN = "arn:aws:bedrock:us-west-2::foundation-model/qwen.qwen3-coder-30b-a3b-v1:0"
private const val QWEN_30B_REGION = "us-west-2"

class NestedReasoningPipeTest
{
    private lateinit var qwenInferenceConfigFile: File

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

    @Test
    fun liveQwenNestedReasoningPipelineUsesDistinctReasoningBuilderTypes()
    {
        runBlocking {
            val innerReasoningPipe = buildStructuredCotReasoningPipe(
                reasoningPipeName = "nested-inner-reasoning"
            )

            val outerReasoningPipe = buildComprehensivePlanReasoningPipe(
                reasoningPipeName = "nested-outer-reasoning"
            ).apply {
                setReasoningPipe(innerReasoningPipe)
            }

            val mainPipe = BedrockPipe().apply {
                setProvider(ProviderName.Aws)
                setModel(QWEN_30B_MODEL_ID)
                setRegion(QWEN_30B_REGION)
                useConverseApi()
                pipeName = "nested-main"
                setReasoningPipe(outerReasoningPipe)
                setMaxTokens(4000)
                setTemperature(0.1)
                setTopP(0.2)
                enableTracing(traceConfig())
            }

            val pipeline = Pipeline().apply {
                setPipelineName("nested-reasoning-regression")
                add(mainPipe)
                enableTracing(traceConfig())
            }

            val traceBaseDir = File("${TPipeConfig.getTraceDir()}/Library/nested-reasoning/qwen30b-nested-regression")
            traceBaseDir.mkdirs()
            val pipelineHtmlPath = File(traceBaseDir, "pipeline.html")
            val pipelineJsonPath = File(traceBaseDir, "pipeline.json")

            try
            {
                pipeline.init(initPipes = true)
                val result = pipeline.execute(
                    MultimodalContent(text = "What is 2+2? Answer in one word. Explain briefly.")
                )

                if(result.pipeError != null)
                {
                    println("Nested Bedrock pipeError: ${result.pipeError}")
                }

                assertTrue(result.text.isNotBlank(), "The nested Bedrock pipeline should return visible output")

                val htmlTrace = pipeline.getTraceReport(TraceFormat.HTML)
                val jsonTrace = pipeline.getTraceReport(TraceFormat.JSON)
                writeStringToFile(pipelineHtmlPath.absolutePath, htmlTrace)
                writeStringToFile(pipelineJsonPath.absolutePath, jsonTrace)

                assertTraceSaved(pipelineHtmlPath, htmlTrace, "nested pipeline HTML")
                assertTraceSaved(pipelineJsonPath, jsonTrace, "nested pipeline JSON")

                assertTrue(
                    htmlTrace.contains(innerReasoningPipe.pipeName),
                    "The saved trace should include the inner reasoning pipe name"
                )
                assertTrue(
                    htmlTrace.contains(outerReasoningPipe.pipeName),
                    "The saved trace should include the outer reasoning pipe name"
                )

                val innerReasoningContent = extractReasoningContentsFromTrace(
                    traceReportJson = jsonTrace,
                    reasoningPipeName = innerReasoningPipe.pipeName
                ).firstOrNull().orEmpty()
                assertStructuredReasoningPayload(
                    reasoningContent = innerReasoningContent,
                    label = "Inner reasoning payload"
                )

                val outerReasoningContent = extractReasoningContentsFromTrace(
                    traceReportJson = jsonTrace,
                    reasoningPipeName = outerReasoningPipe.pipeName
                ).firstOrNull().orEmpty()
                assertConverseHistoryWithPlanPayload(
                    reasoningContent = outerReasoningContent,
                    label = "Outer reasoning payload"
                )

                val mainOutputEvent = Json.parseToJsonElement(jsonTrace).jsonArray.firstOrNull { event ->
                    val objectValue = event.jsonObject
                    objectValue["pipeName"]?.jsonPrimitive?.contentOrNull == mainPipe.pipeName &&
                        objectValue["eventType"]?.jsonPrimitive?.contentOrNull == TraceEventType.API_CALL_SUCCESS.name
                }
                assertNotNull(mainOutputEvent, "The main pipe should emit a successful API call event")
                assertTrue(
                    mainOutputEvent?.jsonObject
                        ?.get("content")
                        ?.jsonObject
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.isNotBlank() == true,
                    "The main pipe should emit a nonblank visible answer"
                )
                assertFalse(
                    mainOutputEvent?.jsonObject
                        ?.get("content")
                        ?.jsonObject
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.contains("ROUND 1") == true,
                    "The main pipe should not receive a round label in single-round nested mode"
                )
                assertFalse(
                    mainOutputEvent?.jsonObject
                        ?.get("content")
                        ?.jsonObject
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.contains("FOCUS:") == true,
                    "The main pipe should not receive focus labels in single-round nested mode"
                )
            }
            finally
            {
                PipeTracer.clearTrace(pipeline.getTraceId())
            }
        }
    }

    private fun buildStructuredCotReasoningPipe(
        reasoningPipeName: String
    ): BedrockMultimodalPipe
    {
        return buildReasoningPipe(
            reasoningPipeName = reasoningPipeName,
            reasoningMethod = ReasoningMethod.StructuredCot,
            depth = ReasoningDepth.High,
            duration = ReasoningDuration.Long,
            reasoningInjector = ReasoningInjector.AfterUserPrompt
        )
    }

    private fun buildComprehensivePlanReasoningPipe(
        reasoningPipeName: String
    ): BedrockMultimodalPipe
    {
        return buildReasoningPipe(
            reasoningPipeName = reasoningPipeName,
            reasoningMethod = ReasoningMethod.ComprehensivePlan,
            depth = ReasoningDepth.High,
            duration = ReasoningDuration.Long,
            reasoningInjector = ReasoningInjector.BeforeUserPrompt
        )
    }

    private fun buildReasoningPipe(
        reasoningPipeName: String
        , reasoningMethod: ReasoningMethod
        , depth: ReasoningDepth
        , duration: ReasoningDuration
        , reasoningInjector: ReasoningInjector
    ): BedrockMultimodalPipe
    {
        return (ReasoningBuilder.reasonWithBedrock(
            bedrockConfig = BedrockConfiguration(
                region = QWEN_30B_REGION,
                model = QWEN_30B_MODEL_ID,
                inferenceProfile = "",
                useConverseApi = true
            ),
            reasoningSettings = ReasoningSettings(
                reasoningMethod = reasoningMethod,
                depth = depth,
                duration = duration,
                reasoningInjector = reasoningInjector,
                numberOfRounds = 1
            ),
            pipeSettings = PipeSettings(
                pipeName = reasoningPipeName,
                provider = ProviderName.Aws,
                model = QWEN_30B_MODEL_ID,
                temperature = 0.1,
                topP = 0.2,
                maxTokens = 4000,
                contextWindowSize = 12000
            )
        ) as BedrockMultimodalPipe).apply {
            pipeName = reasoningPipeName
            setRegion(QWEN_30B_REGION)
            useConverseApi()
            setReadTimeout(600)
            enableMaxTokenOverflow()
            enableTracing(traceConfig())
        }
    }

    private fun traceConfig(): TraceConfig
    {
        return TraceConfig(
            enabled = true,
            outputFormat = TraceFormat.HTML,
            detailLevel = TraceDetailLevel.DEBUG,
            includeContext = true,
            includeMetadata = true
        )
    }

    private fun configureQwenInferenceBinding()
    {
        bedrockEnv.resetInferenceConfig()
        qwenInferenceConfigFile = File.createTempFile("tpipe-qwen-30b-inference", ".txt")
        qwenInferenceConfigFile.writeText("$QWEN_30B_MODEL_ID=$QWEN_30B_MODEL_ARN\n")
        qwenInferenceConfigFile.deleteOnExit()
        bedrockEnv.setInferenceConfigFile(qwenInferenceConfigFile)
        bedrockEnv.loadInferenceConfig()

        assertEquals(
            QWEN_30B_MODEL_ARN,
            bedrockEnv.getInferenceProfileId(QWEN_30B_MODEL_ID),
            "The nested reasoning test should bind the Qwen 30B model ID to the resolved Bedrock ARN"
        )
        println("Using Qwen foundation model $QWEN_30B_MODEL_ID ($QWEN_30B_MODEL_ARN) in $QWEN_30B_REGION")
    }

    private fun extractReasoningContentsFromTrace(
        traceReportJson: String,
        reasoningPipeName: String
    ): List<String>
    {
        val events = Json.parseToJsonElement(traceReportJson).jsonArray
        return events.mapNotNull { event ->
            val objectValue = event.jsonObject
            if(
                objectValue["pipeName"]?.jsonPrimitive?.contentOrNull == reasoningPipeName &&
                objectValue["eventType"]?.jsonPrimitive?.contentOrNull == TraceEventType.API_CALL_SUCCESS.name &&
                objectValue["metadata"]?.jsonObject?.containsKey("reasoningContent") == true
            )
            {
                val metadata = objectValue["metadata"]?.jsonObject ?: return@mapNotNull null
                metadata["reasoningContent"]?.jsonPrimitive?.contentOrNull
            }
            else
            {
                null
            }
        }
    }

    private fun assertStructuredReasoningPayload(
        reasoningContent: String,
        label: String
    )
    {
        assertTrue(reasoningContent.isNotBlank(), "$label should not be blank")
        val reasoning = deserialize<StructuredCot>(reasoningContent)
            ?: error("$label should deserialize to StructuredCot")

        assertTrue(reasoning.componentIdentification.whatNeedsToBeSolved.isNotBlank(), "$label should identify the problem")
        assertTrue(reasoning.solutionDecomposition.subProblems.isNotEmpty(), "$label should include sub-problems")
        assertTrue(reasoning.systematicExecution.subProblemApproaches.isNotEmpty(), "$label should include sub-problem approaches")
        assertTrue(reasoning.reasoningSynthesis.reasoningConclusion.isNotBlank(), "$label should include a conclusion")
    }

    private fun assertConverseHistoryWithPlanPayload(
        reasoningContent: String,
        label: String
    )
    {
        assertTrue(reasoningContent.isNotBlank(), "$label should not be blank")
        assertTrue(reasoningContent.contains("\"history\""), "$label should preserve converse-history structure")

        val history = deserialize<ConverseHistory>(reasoningContent)
            ?: error("$label should deserialize to ConverseHistory")

        assertTrue(history.history.isNotEmpty(), "$label should contain at least one turn")
        assertTrue(
            history.history.first().role == ConverseRole.developer,
            "$label should begin with a developer turn"
        )
        assertTrue(
            history.history.any {
                it.role == ConverseRole.developer &&
                    it.content.text.contains("strategic planner")
            },
            "$label should carry the comprehensive-plan developer prompt"
        )
        assertTrue(
            history.history.any { it.role == ConverseRole.agent },
            "$label should include an agent reasoning turn"
        )

        val finalAgentTurn = history.history.lastOrNull { it.role == ConverseRole.agent }
            ?: error("$label should include a final agent reasoning turn")
        assertTrue(finalAgentTurn.content.text.isNotBlank(), "$label should carry a visible reasoning turn")
        assertFalse(
            finalAgentTurn.content.text.contains("ROUND 1"),
            "$label should not carry a round label in single-round nested mode"
        )
        assertFalse(
            finalAgentTurn.content.text.contains("FOCUS:"),
            "$label should not carry a focus label in single-round nested mode"
        )
        assertFalse(
            finalAgentTurn.content.text.contains("\"componentIdentification\""),
            "$label should not leak the inner structured-COT JSON"
        )
    }

    private fun assertTraceSaved(
        path: File,
        report: String,
        label: String
    )
    {
        assertTrue(path.exists(), "Trace file should exist for $label")
        assertTrue(path.length() > 0, "Trace file should not be empty for $label")
        assertTrue(report.isNotBlank(), "Trace report should not be blank for $label")
    }
}
