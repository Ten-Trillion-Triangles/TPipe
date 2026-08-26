package com.TTT.Pipeline

import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ServiceTierType
import aws.sdk.kotlin.services.bedrockruntime.model.SystemContentBlock
import com.TTT.Config.TPipeConfig
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Debug.TraceVisualizer
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Enums.ContextWindowSettings
import com.TTT.Enums.PumpStationHistoryTransport
import com.TTT.Enums.PumpStationLatestContentPosition
import com.TTT.Enums.ProviderName
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live PumpStation integration test against Amazon Bedrock GPT-OSS 20B.
 *
 * The test uses a real Bedrock dispatch agent and a deterministic local path so the
 * PumpStation loop reaches a second dispatch turn without paying for unnecessary path
 * calls. That second dispatch carries the first path result and therefore exercises the
 * configurable history transport and latest-output placement behavior.
 *
 * Run with `TPIPE_LIVE_LLM_TEST=true` and standard AWS credentials configured for Bedrock.
 */
class PumpStationGptOss20bLiveTest
{
//=========================================Constants================================================================

    companion object
    {
        /** AWS region selected from the live Bedrock model catalog. */
        private const val MODEL_REGION = "us-west-2"

        /** Live Bedrock GPT-OSS 20B model identifier. */
        private const val MODEL_ID = "openai.gpt-oss-20b-1:0"

        /** Foundation-model ARN returned by the AWS CLI catalog lookup. */
        private const val MODEL_ARN =
            "arn:aws:bedrock:us-west-2::foundation-model/openai.gpt-oss-20b-1:0"

        /** Maximum generated output requested from each Bedrock pipe. */
        private const val MAX_OUTPUT_TOKENS = 8192

        /** Full GPT-OSS context window requested from TPipe token budgeting. */
        private const val CONTEXT_WINDOW_TOKENS = 128000

        /** Unique marker emitted by the deterministic path into the next dispatch turn. */
        private const val LATEST_OUTPUT_MARKER = "GptOssLatestOutputMarker"

        /** Unique marker emitted only in the first-turn history payload. */
        private const val HISTORY_CONTEXT_MARKER = "GptOssHistoryContextOnlyMarker"

        /** Prompt challenge used to make the dispatch agent select the only path. */
        private const val CHALLENGE_PROMPT =
            "Evaluate this two-turn control problem. Preserve the marker semantics and " +
                "select the report path exactly as requested."

    }

//=========================================Scenario model===========================================================

    private data class PromptScenario(
        val name: String,
        val historyTransport: PumpStationHistoryTransport,
        val latestContentPosition: PumpStationLatestContentPosition,
        val latestContentInjectionEnabled: Boolean,
        val deduplicateLatestContentAgainstHistory: Boolean,
        val expectedLatestBlock: Boolean
    )

    private val scenarios = listOf(
        PromptScenario(
            name = "default-text-suffix",
            historyTransport = PumpStationHistoryTransport.TextOnly,
            latestContentPosition = PumpStationLatestContentPosition.Suffix,
            latestContentInjectionEnabled = true,
            deduplicateLatestContentAgainstHistory = true,
            expectedLatestBlock = true
        ),
        PromptScenario(
            name = "context-prefix",
            historyTransport = PumpStationHistoryTransport.ContextOnly,
            latestContentPosition = PumpStationLatestContentPosition.Prefix,
            latestContentInjectionEnabled = true,
            deduplicateLatestContentAgainstHistory = false,
            expectedLatestBlock = true
        ),
        PromptScenario(
            name = "text-and-context-before-history",
            historyTransport = PumpStationHistoryTransport.TextAndContext,
            latestContentPosition = PumpStationLatestContentPosition.BeforeHistory,
            latestContentInjectionEnabled = true,
            deduplicateLatestContentAgainstHistory = false,
            expectedLatestBlock = true
        ),
        PromptScenario(
            name = "text-after-history-disabled",
            historyTransport = PumpStationHistoryTransport.TextOnly,
            latestContentPosition = PumpStationLatestContentPosition.AfterHistory,
            latestContentInjectionEnabled = false,
            deduplicateLatestContentAgainstHistory = true,
            expectedLatestBlock = false
        )
    )

//=========================================Live test=================================================================

    /**
     * Runs every prompt-transport scenario against the live Bedrock-backed dispatch agent.
     *
     * The test is intentionally opt-in because it makes real Bedrock requests. When the
     * gate is closed, JUnit reports a skipped-equivalent successful invocation by return.
     */
    @Test
    fun liveGptOss20bPumpStationExercisesPromptTransportAndTracing() = runBlocking<Unit>
    {
        if(System.getenv("TPIPE_LIVE_LLM_TEST") != "true") return@runBlocking

        assertTrue(MODEL_ARN.endsWith(MODEL_ID), "Model ARN must identify MODEL_ID")
        scenarios.forEach { scenario -> runScenario(scenario) }
    }

//=========================================Bedrock builders=========================================================

    /**
     * Builds one Bedrock-backed pipeline with the complete live-test configuration.
     */
    private fun createBedrockPipeline(
        pipeName: String,
        traceConfig: TraceConfig
    ): Pipeline
    {
        val pipe = BedrockMultimodalPipe()
        pipe.setProvider(ProviderName.Aws)
        pipe.setPipeName(pipeName)
        pipe.setRegion(MODEL_REGION)
        pipe.setModel(MODEL_ID)
        pipe.useConverseApi()
        pipe.setJsonOutput(PathRequest())
        pipe.setServiceTier(BedrockPriorityTier.Flex)
        pipe.setReasoning("low")
        pipe.setMaxTokens(MAX_OUTPUT_TOKENS)
        pipe.setReadTimeout(900)
        pipe.pullPumpStationContext()
        pipe.autoInjectContext("Use the supplied PumpStation context, including prior conversation history, when selecting the next path.")
        pipe.setTokenBudget(
            TokenBudgetSettings(
                contextWindowSize = CONTEXT_WINDOW_TOKENS,
                maxTokens = MAX_OUTPUT_TOKENS,
                truncationMethod = ContextWindowSettings.TruncateTop
            )
        )
        pipe.enableTracing(traceConfig)

        val pipeline = Pipeline().setPipelineName(pipeName)
        pipeline.add(pipe)
        pipeline.enableTracing(traceConfig)
        runBlocking { pipeline.init(true) }
        return pipeline
    }

//=========================================Scenario runner===========================================================

    /**
     * Executes one two-turn scenario and verifies its persisted PumpStation trace.
     */
    private suspend fun runScenario(scenario: PromptScenario)
    {
        val scenarioDirectory = File(
            TPipeConfig.getTraceDir(),
            "Library/pumpstation-gpt-oss-20b-live/${scenario.name}"
        )
        scenarioDirectory.deleteRecursively()
        scenarioDirectory.mkdirs()

        val traceConfig = TraceConfig(
            enabled = true,
            maxHistory = 5000,
            outputFormat = TraceFormat.HTML,
            detailLevel = TraceDetailLevel.DEBUG,
            autoExport = true,
            exportPath = scenarioDirectory.absolutePath,
            includeContext = true,
            includeMetadata = true
        )
        val dispatchPipeline = createBedrockPipeline("dispatch-${scenario.name}", traceConfig)
        var pathInvocationCount = 0

        val station = pumpStation("pumpstation-gpt-oss-${scenario.name}")
        {
            dispatchAgent = dispatchPipeline
            maxHarnessTurns = 2
            judgeRunMode = PumpStationJudgeRunMode.FlagTriggered
            promptConfiguration {
                historyTransport = scenario.historyTransport
                latestContentInjectionEnabled = scenario.latestContentInjectionEnabled
                latestContentPosition = scenario.latestContentPosition
                deduplicateLatestContentAgainstHistory = scenario.deduplicateLatestContentAgainstHistory
            }
            tracingConfiguration = traceConfig
            systemTask = "You are testing PumpStation prompt transport. Select the report path."
            userGuidelines = "$CHALLENGE_PROMPT The only valid path is report."

            path("report")
            {
                description = "Deterministic report path that emits transport markers and needs two turns."
                setExecutionFunction { _, _, _, _ ->
                    pathInvocationCount += 1
                    val pathText = "$LATEST_OUTPUT_MARKER " +
                        "turn=$pathInvocationCount model=$MODEL_ID"
                    MultimodalContent(text = pathText).apply {
                        if(pathInvocationCount >= 2) passPipeline = true
                    }
                }
            }
        }
        station.setTokenBudgetRecursive(
            TokenBudgetSettings(
                contextWindowSize = CONTEXT_WINDOW_TOKENS,
                maxTokens = MAX_OUTPUT_TOKENS,
                truncationMethod = ContextWindowSettings.TruncateTop
            )
        )
        val configuredPipe = dispatchPipeline.getPipes().first() as BedrockMultimodalPipe

        val result = station.executeLocal(
            MultimodalContent(text = "$CHALLENGE_PROMPT $HISTORY_CONTEXT_MARKER")
        )
        val configuredRequest = configuredPipe.buildGptOssConverseRequest(
            MODEL_ID,
            listOf(ContentBlock.Text("configuration probe"))
        )
        val expectedSystemPrompt = assertNotNull(configuredPipe.toPipeSettings().systemPrompt)
        val actualSystemPrompt = configuredRequest.system.orEmpty()
            .filterIsInstance<SystemContentBlock.Text>()
            .joinToString("\n") { it.value }
        assertEquals(expectedSystemPrompt, actualSystemPrompt, "${scenario.name}: system prompt was not sent in full")
        assertTrue(
            actualSystemPrompt.contains("You are the dispatcher in an agentic harness."),
            "${scenario.name}: composed dispatch system prompt is missing"
        )
        assertTrue(
            actualSystemPrompt.contains("You are testing PumpStation prompt transport. Select the report path."),
            "${scenario.name}: system task is missing from the Bedrock system payload"
        )
        assertTrue(
            actualSystemPrompt.contains("The only valid path is report."),
            "${scenario.name}: user guidelines are missing from the Bedrock system payload"
        )
        assertTrue(
            actualSystemPrompt.contains("Use the supplied PumpStation context, including prior conversation history, when selecting the next path."),
            "${scenario.name}: context-injection instructions are missing from the Bedrock system payload"
        )
        assertTrue(
            actualSystemPrompt.contains("report"),
            "${scenario.name}: visible path descriptor is missing from the Bedrock system payload"
        )
        assertEquals(ServiceTierType.Flex, configuredRequest.serviceTier?.type)
        assertEquals(MAX_OUTPUT_TOKENS, configuredRequest.inferenceConfig?.maxTokens)
        val htmlReport = station.getTraceReport(TraceFormat.HTML)
        val agentTrace = exportDispatchTrace(scenario, scenarioDirectory)
        assertTrue(htmlReport.contains("<html"), "${scenario.name}: trace report is not HTML")
        assertEquals(TraceDetailLevel.DEBUG, traceConfig.detailLevel)
        assertEquals(
            PumpStationExitReason.PassSignal,
            station.getTaskState().exitReason,
            "${scenario.name}: deterministic path did not complete on turn two"
        )
        assertTrue(
            result.text.contains(LATEST_OUTPUT_MARKER),
            "${scenario.name}: final result lost the latest-output marker"
        )

        val traceFiles = scenarioDirectory.listFiles { file ->
            file.name.startsWith("pumpstation-") && file.name.endsWith(".html")
        } ?: emptyArray()
        assertTrue(traceFiles.isNotEmpty(), "${scenario.name}: no auto-exported PumpStation trace")
        assertTrue(
            traceFiles.all { it.length() > 5000 },
            "${scenario.name}: auto-exported trace is unexpectedly small"
        )
        assertTrue(
            traceFiles.all { it.parentFile.absolutePath == scenarioDirectory.absolutePath },
            "${scenario.name}: trace escaped TPipeConfig.getTraceDir()"
        )

        val traceText = traceFiles.maxBy { it.lastModified() }.readText()
        val agentTraceText = agentTrace.readText()
        assertTrue(
            traceText.contains("PUMP_STATION_DISPATCH_STARTED"),
            "${scenario.name}: dispatch event missing from PumpStation trace"
        )
        assertTrue(agentTraceText.contains(MODEL_ID), "${scenario.name}: model marker missing from agent trace")
        assertTrue(
            agentTraceText.contains("useModelReasoning:</strong> true"),
            "${scenario.name}: mandatory GPT-OSS reasoning was not enabled"
        )
        assertTrue(
            agentTraceText.contains(HISTORY_CONTEXT_MARKER),
            "${scenario.name}: history-only marker missing from persisted dispatch trace"
        )
        val latestBlockCount = Regex("\\[LATEST PRIOR AGENT OUTPUT\\]").findAll(agentTraceText).count()
        if(scenario.expectedLatestBlock)
        {
            assertTrue(latestBlockCount > 0, "${scenario.name}: latest-output block is missing")
            assertLatestContentPosition(scenario, agentTraceText)
        }
        else
        {
            assertEquals(0, latestBlockCount, "${scenario.name}: latest-output block should be disabled")
        }

        assertTrue(agentTrace.exists(), "${scenario.name}: dispatch agent trace was not written")
        assertTrue(
            PipeTracer.getAllTraces().values.flatten()
                .any { event -> event.pipeName == "dispatch-${scenario.name}" },
            "${scenario.name}: dispatch agent emitted no trace events"
        )
        assertTrue(agentTrace.length() > 1000, "${scenario.name}: dispatch trace artifact is too small")
    }

    /** Writes the current scenario's Bedrock dispatch events for post-run inspection. */
    private fun exportDispatchTrace(scenario: PromptScenario, scenarioDirectory: File): File
    {
        val events = PipeTracer.getAllTraces().values.flatten()
            .filter { event -> event.pipeName == "dispatch-${scenario.name}" }
        val traceFile = File(scenarioDirectory, "agent-${scenario.name}.html")
        traceFile.writeText(TraceVisualizer().generateHtmlReport(events))
        return traceFile
    }

    /** Verifies the configured latest-output block order against history delimiters. */
    private fun assertLatestContentPosition(scenario: PromptScenario, agentTraceText: String)
    {
        val latestStart = agentTraceText.indexOf("[LATEST PRIOR AGENT OUTPUT]")
        val historyStart = agentTraceText.indexOf("[CONVERSATION HISTORY]")
        val historyEnd = agentTraceText.indexOf("[/CONVERSATION HISTORY]")
        if(historyStart < 0 || historyEnd < 0)
        {
            assertTrue(latestStart >= 0, "${scenario.name}: latest-output block was not rendered")
            val historyMarker = agentTraceText.indexOf(HISTORY_CONTEXT_MARKER)
            when(scenario.latestContentPosition)
            {
                PumpStationLatestContentPosition.Prefix,
                PumpStationLatestContentPosition.BeforeHistory -> assertTrue(
                    historyMarker < 0 || latestStart < historyMarker,
                    "${scenario.name}: latest-output block is not before structured history"
                )
                PumpStationLatestContentPosition.AfterHistory,
                PumpStationLatestContentPosition.Suffix -> assertTrue(latestStart >= 0)
            }
            return
        }

        when(scenario.latestContentPosition)
        {
            PumpStationLatestContentPosition.Prefix -> assertTrue(latestStart < historyStart)
            PumpStationLatestContentPosition.BeforeHistory -> assertTrue(latestStart < historyStart)
            PumpStationLatestContentPosition.AfterHistory -> assertTrue(latestStart > historyEnd)
            PumpStationLatestContentPosition.Suffix -> assertTrue(latestStart > historyEnd)
        }
    }
}
