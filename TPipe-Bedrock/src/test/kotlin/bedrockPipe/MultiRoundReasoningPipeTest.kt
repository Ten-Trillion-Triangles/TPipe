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
import com.TTT.Structs.ExplicitReasoningDetailed
import com.TTT.Util.deserialize
import com.TTT.Util.writeStringToFile
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import env.bedrockEnv

private const val BEDROCK_REGION = "us-west-2"
private const val QWEN_30B_MODEL_ID = "qwen.qwen3-coder-30b-a3b-v1:0"
private const val QWEN_30B_MODEL_ARN = "arn:aws:bedrock:us-west-2::foundation-model/qwen.qwen3-coder-30b-a3b-v1:0"

class MultiRoundReasoningPipeTest
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
    fun testBedrockSingleRoundReasoningPipeStoresTrace()
    {
        runBlocking {
            val runtime = buildExplicitCotRuntime(
                caseName = "single-round",
                inputText = "What is 2+2? Answer in one word.",
                reasoningRounds = 1,
                focusPoints = mutableMapOf()
            )

            try
            {
                runtime.pipeline.init(initPipes = true)
                val result = runtime.pipeline.execute(MultimodalContent(text = runtime.inputText))
                if(result.pipeError != null)
                {
                    println("Single round Bedrock pipeError: ${result.pipeError}")
                }
                assertTrue(result.text.isNotBlank(), "Single round result should contain actual output")
                val artifacts = saveTraceArtifacts(runtime)
                assertExplicitCotTrace(
                    traceReportJson = artifacts.json,
                    reasoningPipeName = runtime.reasoningPipe.pipeName,
                    expectedFocusMarkers = emptyList()
                )
            }
            finally
            {
                PipeTracer.clearTrace(runtime.pipeline.getTraceId())
            }
        }
    }

    @Test
    fun testBedrockMultiRoundReasoningPipeStoresTraceAndHonorsFocusPoints()
    {
        runBlocking {
            val runtime = buildExplicitCotRuntime(
                caseName = "multi-round-focus",
                inputText = "A chrome llama is juggling seven glowing turnips while a kazoo-powered rover circles a lemon moon.",
                reasoningRounds = 2,
                focusPoints = mutableMapOf(
                    1 to "FOCUS-ROUND-1 :: analyze the timing relationship between the chrome llama juggling and the lemon moon orbit",
                    2 to "FOCUS-ROUND-2 :: count the exact objects and verify there are seven glowing turnips and one kazoo-powered rover"
                )
            )

            try
            {
                assertEquals(
                    ReasoningMethod.ExplicitCot.toString(),
                    runtime.reasoningPipe.pipeMetadata["reasoningMethod"],
                    "The official Bedrock reasoning builder should be configured for explicit COT"
                )
                assertEquals(
                    2,
                    runtime.reasoningPipe.pipeMetadata["reasoningRounds"],
                    "The official Bedrock reasoning builder should preserve the configured round count"
                )
                assertTrue(
                    runtime.reasoningPipe.pipeMetadata["focusPoints"] is Map<*, *>,
                    "The official Bedrock reasoning builder should preserve the round-indexed focus map"
                )

                runtime.pipeline.init(initPipes = true)
                val result = runtime.pipeline.execute(MultimodalContent(text = runtime.inputText))
                if(result.pipeError != null)
                {
                    println("Multi round Bedrock pipeError: ${result.pipeError}")
                }
                assertTrue(result.text.isNotBlank(), "Multi round result should contain actual output")
                val artifacts = saveTraceArtifacts(runtime)
                assertMultiRoundFocusTrace(
                    traceReportJson = artifacts.json,
                    traceReportHtml = artifacts.html,
                    pipelineId = runtime.pipeline.getTraceId(),
                    reasoningPipeName = runtime.reasoningPipe.pipeName,
                    mainPipeName = runtime.mainPipeName,
                    expectedFocusMarkers = listOf(
                        "FOCUS-ROUND-1 :: analyze the timing relationship",
                        "FOCUS-ROUND-2 :: count the exact objects"
                    )
                )
            }
            finally
            {
                PipeTracer.clearTrace(runtime.pipeline.getTraceId())
            }
        }
    }

    @Test
    fun testNestedReasoningPipeStoresTrace()
    {
        runBlocking {
            val innerReasoningPipe = buildExplicitCotReasoningPipe(
                reasoningPipeName = "InnerReasoning",
                reasoningRounds = 1,
                focusPoints = mutableMapOf()
            )

            val outerReasoningPipe = buildExplicitCotReasoningPipe(
                reasoningPipeName = "OuterReasoning",
                reasoningRounds = 1,
                focusPoints = mutableMapOf()
            ).apply {
                setReasoningPipe(innerReasoningPipe)
            }

            val mainPipe = BedrockPipe().apply {
                setProvider(ProviderName.Aws)
                setModel(QWEN_30B_MODEL_ID)
                setRegion(BEDROCK_REGION)
                useConverseApi()
                pipeName = "Main"
                setReasoningPipe(outerReasoningPipe)
                setMaxTokens(2000)
                enableTracing(traceConfig())
            }

            val pipeline = Pipeline().apply {
                setPipelineName("nested-reasoning")
                add(mainPipe)
                enableTracing(traceConfig())
            }

            val traceBaseDir = File("${TPipeConfig.getTraceDir()}/Library/multi-round-reasoning/nested")
            traceBaseDir.mkdirs()
            val pipelineTracePath = File(traceBaseDir, "pipeline.html")

            try
            {
                pipeline.init(initPipes = true)
                val result = pipeline.execute(MultimodalContent(text = "What is 2+2? Answer in one word. Explain briefly."))
                if(result.pipeError != null)
                {
                    println("Nested Bedrock pipeError: ${result.pipeError}")
                }
                assertTrue(result.text.isNotBlank(), "Nested reasoning result should contain actual output")
                val traceArtifacts = TraceArtifacts(
                    html = pipeline.getTraceReport(TraceFormat.HTML),
                    json = pipeline.getTraceReport(TraceFormat.JSON)
                )
                saveTraceArtifacts(
                    pipeline = pipeline,
                    htmlPath = pipelineTracePath,
                    jsonPath = File(traceBaseDir, "pipeline.json")
                )
                assertExplicitCotTrace(
                    traceReportJson = traceArtifacts.json,
                    reasoningPipeName = innerReasoningPipe.pipeName,
                    expectedFocusMarkers = emptyList()
                )
                assertConverseHistoryTrace(
                    traceReportJson = traceArtifacts.json,
                    reasoningPipeName = outerReasoningPipe.pipeName,
                    expectedFocusMarkers = emptyList()
                )
            }
            finally
            {
                PipeTracer.clearTrace(pipeline.getTraceId())
            }
        }
    }

    private fun buildExplicitCotRuntime(
        caseName: String,
        inputText: String,
        reasoningRounds: Int,
        focusPoints: MutableMap<Int, String>
    ): LiveReasoningRuntime
    {
        val reasoningPipe = buildExplicitCotReasoningPipe(
            reasoningPipeName = "$caseName-reasoning",
            reasoningRounds = reasoningRounds,
            focusPoints = focusPoints
        )

        val mainPipe = BedrockPipe().apply {
            setProvider(ProviderName.Aws)
            setModel(QWEN_30B_MODEL_ID)
            setRegion(BEDROCK_REGION)
            useConverseApi()
            pipeName = "$caseName-main"
            setReasoningPipe(reasoningPipe)
            setMaxTokens(2000)
            enableTracing(traceConfig())
        }

        val pipeline = Pipeline().apply {
            setPipelineName(caseName)
            add(mainPipe)
            enableTracing(traceConfig())
        }

        val traceBaseDir = File("${TPipeConfig.getTraceDir()}/Library/multi-round-reasoning/$caseName")
        traceBaseDir.mkdirs()

        return LiveReasoningRuntime(
            inputText = inputText,
            pipeline = pipeline,
            mainPipeName = mainPipe.pipeName,
            reasoningPipe = reasoningPipe,
            pipelineTracePath = File(traceBaseDir, "pipeline.html"),
            pipelineJsonTracePath = File(traceBaseDir, "pipeline.json")
        )
    }

    private fun buildExplicitCotReasoningPipe(
        reasoningPipeName: String,
        reasoningRounds: Int,
        focusPoints: MutableMap<Int, String>
    ): BedrockMultimodalPipe
    {
        return (ReasoningBuilder.reasonWithBedrock(
            bedrockConfig = BedrockConfiguration(
                region = BEDROCK_REGION,
                model = QWEN_30B_MODEL_ID,
                inferenceProfile = "",
                useConverseApi = true
            ),
            reasoningSettings = ReasoningSettings(
                reasoningMethod = ReasoningMethod.ExplicitCot,
                depth = ReasoningDepth.Med,
                duration = ReasoningDuration.Med,
                reasoningInjector = ReasoningInjector.BeforeUserPrompt,
                numberOfRounds = reasoningRounds,
                focusPoints = focusPoints
            ),
            pipeSettings = PipeSettings(
                pipeName = reasoningPipeName,
                provider = ProviderName.Aws,
                model = QWEN_30B_MODEL_ID,
                temperature = 0.1,
                topP = 0.2,
                maxTokens = 2000,
                contextWindowSize = 10000
            )
        ) as BedrockMultimodalPipe).apply {
            pipeName = reasoningPipeName
            setRegion(BEDROCK_REGION)
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

    private fun saveTraceArtifacts(
        runtime: LiveReasoningRuntime
    ): TraceArtifacts
    {
        val html = runtime.pipeline.getTraceReport(TraceFormat.HTML)
        val json = runtime.pipeline.getTraceReport(TraceFormat.JSON)
        saveTraceArtifacts(
            pipeline = runtime.pipeline,
            htmlPath = runtime.pipelineTracePath,
            jsonPath = runtime.pipelineJsonTracePath,
            htmlTrace = html,
            jsonTrace = json
        )
        return TraceArtifacts(html = html, json = json)
    }

    private fun saveTraceArtifacts(
        pipeline: Pipeline,
        htmlPath: File,
        jsonPath: File,
        htmlTrace: String = pipeline.getTraceReport(TraceFormat.HTML),
        jsonTrace: String = pipeline.getTraceReport(TraceFormat.JSON)
    )
    {
        writeStringToFile(htmlPath.absolutePath, htmlTrace)
        writeStringToFile(jsonPath.absolutePath, jsonTrace)

        assertTrue(htmlPath.exists(), "Pipeline HTML trace should be saved to the default trace directory")
        assertTrue(htmlPath.length() > 0, "Pipeline HTML trace file should not be empty")
        assertTrue(jsonPath.exists(), "Pipeline JSON trace should be saved to the default trace directory")
        assertTrue(jsonPath.length() > 0, "Pipeline JSON trace file should not be empty")
        println("Saved pipeline HTML trace: ${htmlPath.absolutePath}")
        println("Saved pipeline JSON trace: ${jsonPath.absolutePath}")
    }

    private fun assertExplicitCotTrace(
        traceReportJson: String,
        reasoningPipeName: String,
        expectedFocusMarkers: List<String>
    )
    {
        val reasoningContent = extractReasoningContentsFromTrace(
            traceReportJson = traceReportJson,
            reasoningPipeName = reasoningPipeName
        ).firstOrNull().orEmpty()
        assertExplicitReasoningPayload(
            reasoningContent = reasoningContent,
            label = "Single-round reasoning payload"
        )

        expectedFocusMarkers.forEach { marker ->
            assertTrue(
                traceReportJson.contains(marker),
                "Reasoning trace should include focus marker: $marker"
            )
        }
    }

    private fun assertMultiRoundFocusTrace(
        traceReportJson: String,
        traceReportHtml: String,
        pipelineId: String,
        reasoningPipeName: String,
        mainPipeName: String,
        expectedFocusMarkers: List<String>
    )
    {
        val trace = PipeTracer.getTrace(pipelineId)
        expectedFocusMarkers.forEach { marker ->
            assertTrue(
                traceReportJson.contains(marker) || traceReportHtml.contains(marker),
                "Multi-round reasoning trace should include focus marker: $marker"
            )
        }

        val reasoningContents = extractReasoningContentsFromTrace(
            traceReportJson = traceReportJson,
            reasoningPipeName = reasoningPipeName
        )
        assertTrue(
            reasoningContents.size >= expectedFocusMarkers.size,
            "Multi-round reasoning should emit one explicit-COT payload per configured round"
        )
        reasoningContents.take(expectedFocusMarkers.size).forEachIndexed { index, reasoningContent ->
            assertExplicitReasoningPayload(
                reasoningContent = reasoningContent,
                label = "Round ${index + 1} reasoning payload"
            )
        }

        val reasoningSnapshots = extractReasoningRoundSnapshotsFromTrace(
            traceReportJson = traceReportJson,
            snapshotPipeName = mainPipeName
        )
        assertTrue(reasoningSnapshots.isNotEmpty(), "Multi-round reasoning should emit reasoning snapshots")

        val roundOneHistory = deserialize<ConverseHistory>(reasoningSnapshots[0])
            ?: error("Round 1 reasoning payload should deserialize to ConverseHistory")
        assertHistoryPrefix(
            history = roundOneHistory,
            expectedRoles = listOf(ConverseRole.developer, ConverseRole.user, ConverseRole.agent),
            label = "Round 1 converse history"
        )
        assertTrue(
            roundOneHistory.history[1].content.text.contains(expectedFocusMarkers[0]),
            "Round 1 user turn should include the first focus marker"
        )
        assertExplicitReasoningPayload(
            reasoningContent = roundOneHistory.history[2].content.text,
            label = "Round 1 agent turn"
        )
        assertRoundFocusTheme(
            reasoningContent = roundOneHistory.history[2].content.text,
            requiredTerms = listOf("timing", "correlation", "orbit"),
        )

        val roundTwoHistory = deserialize<ConverseHistory>(reasoningSnapshots[1])
            ?: error("Round 2 reasoning payload should deserialize to ConverseHistory")
        assertHistoryPrefix(
            history = roundTwoHistory,
            expectedRoles = listOf(
                ConverseRole.developer,
                ConverseRole.user,
                ConverseRole.agent,
                ConverseRole.system,
                ConverseRole.agent
            ),
            label = "Round 2 converse history"
        )
        assertTrue(
            roundTwoHistory.history[1].content.text.contains(expectedFocusMarkers[0]),
            "Round 2 history should retain the first focus marker in the earlier user turn"
        )
        assertExplicitReasoningPayload(
            reasoningContent = roundTwoHistory.history[2].content.text,
            label = "Round 2 carried-forward round 1 agent turn"
        )
        assertTrue(
            roundTwoHistory.history[3].content.text.contains(expectedFocusMarkers[1]),
            "Round 2 history should include the second focus marker in the system turn"
        )
        assertExplicitReasoningPayload(
            reasoningContent = roundTwoHistory.history[4].content.text,
            label = "Round 2 agent turn"
        )
        assertRoundFocusTheme(
            reasoningContent = roundTwoHistory.history[4].content.text,
            requiredTerms = listOf("count", "verify", "seven", "one"),
        )

        assertTrue(
            traceReportHtml.contains("chrome llama") &&
                traceReportHtml.contains("kazoo-powered rover") &&
                traceReportHtml.contains("glowing turnips") &&
                traceReportHtml.contains("lemon moon"),
                "The distinct prompt should remain visible in the saved trace"
        )

        val mainOutputEvent = trace.lastOrNull {
            it.pipeName == mainPipeName && it.eventType == TraceEventType.API_CALL_SUCCESS
        }
        assertNotNull(mainOutputEvent, "The main pipe should emit a successful API call event")
        assertTrue(
            mainOutputEvent!!.content?.text?.isNotBlank() == true,
            "The main pipe should emit visible output after multi-round reasoning"
        )
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

    private fun extractReasoningRoundSnapshotsFromTrace(
        traceReportJson: String,
        snapshotPipeName: String
    ): List<String>
    {
        val events = Json.parseToJsonElement(traceReportJson).jsonArray
        return events.mapNotNull { event ->
            val objectValue = event.jsonObject
            val round = objectValue["metadata"]?.jsonObject?.get("reasoningRound")?.jsonPrimitive?.intOrNull
            if(
                objectValue["pipeName"]?.jsonPrimitive?.contentOrNull == snapshotPipeName &&
                round != null
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

    private fun assertHistoryPrefix(
        history: ConverseHistory,
        expectedRoles: List<ConverseRole>,
        label: String
    )
    {
        val actualRoles = history.history.map { it.role }
        assertTrue(
            actualRoles.size >= expectedRoles.size,
            "$label should contain at least ${expectedRoles.size} turns"
        )
        assertEquals(
            expectedRoles,
            actualRoles.take(expectedRoles.size),
            "$label should start with the expected role sequence"
        )
    }

    private fun assertExplicitReasoningPayload(
        reasoningContent: String,
        label: String
    )
    {
        assertTrue(reasoningContent.isNotBlank(), "$label should not be blank")
        val reasoning = deserialize<ExplicitReasoningDetailed>(reasoningContent)
            ?: error("$label should deserialize to ExplicitReasoningDetailed")

        assertTrue(
            reasoning.coreAnalysis.analysisSubject.isNotBlank(),
            "$label should include an analysis subject"
        )
        assertTrue(
            reasoning.logicalBreakdown.isNotEmpty(),
            "$label should include a logical breakdown"
        )
        assertTrue(
            reasoning.sequentialReasoning.steps.isNotEmpty(),
            "$label should include step-by-step reasoning"
        )
        assertTrue(
            reasoning.recommendedSteps.isNotBlank(),
            "$label should include recommended steps"
        )
    }

    private fun assertRoundFocusTheme(
        reasoningContent: String,
        requiredTerms: List<String>
    )
    {
        val lower = reasoningContent.lowercase()
        assertTrue(
            requiredTerms.any { lower.contains(it.lowercase()) },
            "The round reasoning should reflect at least one of the intended focus terms: ${requiredTerms.joinToString()}"
        )
    }

    private fun assertConverseHistoryTrace(
        traceReportJson: String,
        reasoningPipeName: String,
        expectedFocusMarkers: List<String>
    )
    {
        val reasoningContent = extractReasoningContentsFromTrace(
            traceReportJson = traceReportJson,
            reasoningPipeName = reasoningPipeName
        ).firstOrNull().orEmpty()
        assertTrue(reasoningContent.isNotBlank(), "The reasoning pipe should emit converse history")
        assertTrue(
            reasoningContent.contains("\"history\""),
            "Converse-history reasoning should include a history block"
        )

        val reasoningHistory = deserialize<ConverseHistory>(reasoningContent)
            ?: error("The reasoning payload should deserialize to ConverseHistory")
        assertHistoryPrefix(
            history = reasoningHistory,
            expectedRoles = listOf(
                ConverseRole.developer,
                ConverseRole.developer,
                ConverseRole.user,
                ConverseRole.agent
            ),
            label = "Converse-history reasoning"
        )
        assertTrue(
            reasoningHistory.history.last().content.text.contains("\"coreAnalysis\""),
            "The outer converse history should wrap the inner explicit-COT payload"
        )

        expectedFocusMarkers.forEach { marker ->
            assertTrue(
                traceReportJson.contains(marker),
                "Converse-history trace should include focus marker: $marker"
            )
        }
    }

    private data class LiveReasoningRuntime(
        val inputText: String,
        val pipeline: Pipeline,
        val mainPipeName: String,
        val reasoningPipe: BedrockMultimodalPipe,
        val pipelineTracePath: File,
        val pipelineJsonTracePath: File
    )

    private data class TraceArtifacts(
        val html: String,
        val json: String
    )

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
            "The live test should bind the Qwen 30B model ID to the verified Bedrock ARN"
        )
        println("Using Qwen foundation model $QWEN_30B_MODEL_ID ($QWEN_30B_MODEL_ARN) in $BEDROCK_REGION")
    }
}
