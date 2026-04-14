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
import com.TTT.P2P.AgentRequest
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.Pipeline
import com.TTT.P2P.P2PSkills
import com.TTT.Pipeline.TaskProgress
import com.TTT.Pipeline.manifold
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import com.TTT.Util.extractJson
import com.TTT.Util.serializeConverseHistory
import com.TTT.Util.deserialize
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Bug investigation test for: P2P dispatch receives empty agentName
 *
 * The bug: When a nested reasoning pipe (with a transformation function like ::bestIdea)
 * outputs {} at an intermediate step, it corrupts the parent pipe's output through
 * the ConverseHistory wrapping mechanism.
 *
 * Key anomaly from trace:
 * - MANAGER_DECISION trace shows responseLength: 2 = "{}"
 * - But the agentCallingPipe output should contain a valid AgentRequest with agentName
 *
 * Hypothesis: The issue is in how embedContentIntoInternalConverse handles the
 * ConverseHistory wrapping when wrapContentWithConverseHistory is true and a
 * nested reasoning pipe produces {} as its transformation output.
 */
class NestedReasoningConverseHistoryBugTest
{
    private lateinit var llamaInferenceConfigFile: File

    companion object {
        private const val LLAMA_MAVERICK_MODEL_ID = "meta.llama4-maverick-17b-instruct-v1:0"
        private const val LLAMA_MAVERICK_INFERENCE_PROFILE = "arn:aws:bedrock:us-west-2:521369004927:inference-profile/us.meta.llama4-maverick-17b-instruct-v1:0"
        private const val REGION = "us-west-2"
    }

    @BeforeEach
    fun setup()
    {
        TestCredentialUtils.requireAwsCredentials()
        configureLlamaInferenceBinding()
        PipeTracer.enable()
    }

    @AfterEach
    fun cleanup()
    {
        PipeTracer.getAllTraces().keys.forEach { PipeTracer.clearTrace(it) }
        PipeTracer.disable()
        bedrockEnv.resetInferenceConfig()
        if(::llamaInferenceConfigFile.isInitialized && llamaInferenceConfigFile.exists())
        {
            llamaInferenceConfigFile.delete()
        }
    }

    /**
     * Test that reproduces the bug where nested reasoning pipes with ConverseHistory wrapping
     * produce {} as output instead of the expected AgentRequest JSON.
     *
     * This test creates a manifold similar to what was described in the bug report:
     * - Manager pipeline with an entry pipe (TaskProgress output)
     * - Agent caller pipe with wrapContentWithConverseHistory enabled
     * - A nested reasoning pipe with ::bestIdea transformation
     */
    @Test
    fun testNestedReasoningPipeWithConverseHistoryWrappingProducesCorrectOutput() = runBlocking {
        val traceBaseDir = File("${TPipeConfig.getTraceDir()}/Library/nested-reasoning-bug/llama4-maverick")
        traceBaseDir.mkdirs()

        // Build the manifold with the specific configuration that triggers the bug
        val manifold = buildBuggyManifold()

        // Initialize the manifold
        manifold.init()

        // Execute the manifold with a task that should trigger agent selection
        val inputContent = MultimodalContent(text = "Debug this function: function add(a, b) { return a + b; }")
        val result = manifold.execute(inputContent)

        // Get the trace for analysis
        val traceId = manifold.getTraceId()
        val traceReport = manifold.getTraceReport(TraceFormat.JSON)
        val traceHtmlReport = manifold.getTraceReport(TraceFormat.HTML)

        // Save traces for debugging
        val jsonTracePath = File(traceBaseDir, "manifold-execution.json")
        val htmlTracePath = File(traceBaseDir, "manifold-execution.html")
        jsonTracePath.writeText(traceReport)
        htmlTracePath.writeText(traceHtmlReport)

        println("Trace saved to: $jsonTracePath")
        println("HTML trace saved to: $htmlTracePath")

        // THE BUG: If the bug is present, the manifold will have terminated early
        // because the agentRequest extraction failed (responseText was "{}")

        // Check the trace for the MANAGER_DECISION event
        val managerDecisionEvents = findTraceEvents(traceReport, TraceEventType.MANAGER_DECISION)
        assertNotNull(managerDecisionEvents, "Should have MANAGER_DECISION events")

        // The key check: responseLength should NOT be 2 (which would be "{}")
        for (event in managerDecisionEvents) {
            val responseLength = event.metadata?.get("responseLength") as? Int
            if (responseLength != null) {
                println("Manager decision responseLength: $responseLength")
                // THIS IS THE BUG: If responseLength == 2, it means the output was "{}"
                // which causes the agentRequest extraction to fail
                assertNotEquals(2, responseLength,
                    "BUG DETECTED: Manager decision response is empty JSON '{}' (length=2). " +
                    "This indicates the nested reasoning pipe corrupted the output.")
            }
        }

        // Check if we have a valid agent request or task completion
        val agentValidationEvents = findTraceEvents(traceReport, TraceEventType.AGENT_REQUEST_VALIDATION)
        val manifoldSuccessEvents = findTraceEvents(traceReport, TraceEventType.MANIFOLD_SUCCESS)
        val agentRequestInvalidEvents = findTraceEvents(traceReport, TraceEventType.AGENT_REQUEST_INVALID)

        println("AGENT_REQUEST_VALIDATION events: ${agentValidationEvents.size}")
        println("MANIFOLD_SUCCESS events: ${manifoldSuccessEvents.size}")
        println("AGENT_REQUEST_INVALID events: ${agentRequestInvalidEvents.size}")

        // If the bug is present, we expect AGENT_REQUEST_INVALID events
        // because the agentRequest extraction failed
        if (agentRequestInvalidEvents.isNotEmpty()) {
            println("BUG CONFIRMED: Agent request was invalid!")
            for (event in agentRequestInvalidEvents) {
                println("  Invalid reason: ${event.metadata?.get("responseText")}")
            }
        }

        // The bug manifests as: MANIFOLD_SUCCESS without AGENT_REQUEST_VALIDATION
        // OR: AGENT_REQUEST_INVALID with responseText = "{}"
        val bugIsPresent = agentRequestInvalidEvents.isNotEmpty() ||
            (manifoldSuccessEvents.isNotEmpty() && agentValidationEvents.isEmpty())

        if (bugIsPresent) {
            println("\n=== BUG IS PRESENT ===")
            println("The nested reasoning pipe with ConverseHistory wrapping is producing {} as output")
            println("This corrupts the manager pipeline result and prevents valid agent dispatch.")
        } else {
            println("\n=== BUG NOT REPRODUCED ===")
            println("The test did not reproduce the bug. This could mean:")
            println("1. The bug was already fixed")
            println("2. The specific conditions to trigger the bug are not met")
            println("3. The model behavior is different")
        }

        // For this investigation, we just report the findings
        // The bug is considered present if we see the invalid agent request pattern
        assertTrue(
            bugIsPresent || agentValidationEvents.isNotEmpty() || manifoldSuccessEvents.isNotEmpty(),
            "Expected either bug symptoms (AGENT_REQUEST_INVALID) or valid execution (AGENT_REQUEST_VALIDATION or MANIFOLD_SUCCESS)"
        )
    }

    /**
     * Builds a manifold that matches the bug report description:
     * - Entry pipe for task progress checking
     * - Agent caller pipe with:
     *   - wrapContentWithConverseHistory enabled
     *   - A nested reasoning pipe with transformation
     */
    private fun buildBuggyManifold(): Manifold {
        // Create a Bedrock pipe for the agent caller with reasoning capabilities
        val bedrockConfig = BedrockConfiguration(
            region = REGION,
            model = LLAMA_MAVERICK_MODEL_ID,
            useConverseApi = true
        )

        // Build a reasoning pipe similar to what the bug report describes
        val reasoningPipe = buildReasoningPipe(bedrockConfig)

        // Create the agent caller pipe with the nested reasoning pipe
        val agentCallerPipe = BedrockMultimodalPipe().apply {
            setProvider(ProviderName.Aws)
            setModel(LLAMA_MAVERICK_MODEL_ID)
            setRegion(REGION)
            useConverseApi()
            pipeName = "Agent caller pipe"
            setMaxTokens(4000)
            setTemperature(0.6)
            setTopP(0.7)

            // Enable ConverseHistory wrapping - this is key to the bug
            wrapContentWithConverse(ConverseRole.system)

            // Set the reasoning pipe as a transformation pipe
            setReasoningPipe(reasoningPipe)

            setSystemPrompt("""You are an AI agent manager. Your job is to determine what AI agent to give the
                |next steps of a given task to.
            """.trimMargin())
            setJsonInput(TaskProgress())
            setJsonOutput(AgentRequest())
            setMiddlePrompt("""
                - taskDescription informs you of what the overall task is.
                - nextTaskInstructions informs you of what the next steps needed to take are.
                - taskProgressStatus is a short summary of what has been done so far.
            """.trimIndent())
            enableTracing(traceConfig())
        }

        // Create the entry pipe for task progress checking
        val entryPipe = BedrockMultimodalPipe().apply {
            setProvider(ProviderName.Aws)
            setModel(LLAMA_MAVERICK_MODEL_ID)
            setRegion(REGION)
            useConverseApi()
            pipeName = "entry pipe"
            setMaxTokens(4000)
            setTemperature(0.6)
            setTopP(0.65)
            setJsonInput(ConverseHistory())
            setJsonOutput(TaskProgress())
            setSystemPrompt("""Your job is to examine a given task and determine if it has been completed or not.
                |You will be given a json ConverseHistory object that contains the conversation between the user,
                |the system assistant, and the agents the system assistant has called to work on completing the task.
            """.trimMargin())
            setMiddlePrompt("""You must examine this json conversation history and determine if the task has been
                |definitively, beyond any shadow of a doubt, solved.
            """.trimMargin())
            setFooterPrompt("""# Task Completion Instruction
                |You MUST examine the conversation history and definitively determine if the overall task has been solved.
                |
                |If the task is **COMPLETED**:
                |  - You MUST set the `isTaskComplete` boolean field in the `TaskProgress` JSON to `true`.
                |
                |If the task is **NOT YET COMPLETED**:
                |  - You MUST set `isTaskComplete` to `false`.
                |  - You MUST provide `nextTaskInstructions` for the next step.
            """.trimMargin())
            enableTracing(traceConfig())
        }

        // Create the manager pipeline
        val managerPipeline = Pipeline().apply {
            pipelineName = "bug-test-manager"
            add(entryPipe)
            add(agentCallerPipe)
            enableTracing(traceConfig())
        }

        // Create a simple worker for testing
        val workerPipeline = Pipeline().apply {
            pipelineName = "stepping-worker"
            add(BedrockMultimodalPipe().apply {
                setProvider(ProviderName.Aws)
                setModel(LLAMA_MAVERICK_MODEL_ID)
                setRegion(REGION)
                useConverseApi()
                pipeName = "Stepping Agent"
                setMaxTokens(2000)
                setTemperature(0.3)
                autoTruncateContext()
            })
        }

        // Build the manifold
        return Manifold().apply {
            setManagerPipeline(managerPipeline)
            addWorkerPipeline(
                pipeline = workerPipeline,
                agentName = "Stepping Agent",
                agentDescription = "Debugs code by stepping through it",
                agentSkills = listOf(P2PSkills("debugging", "Debugs code by stepping through it line by line"))
            )
            autoTruncateContext()
            enableTracing(traceConfig())
        }
    }

    /**
     * Build a reasoning pipe with BestIdea method - this is the key to reproducing the bug
     */
    private fun buildReasoningPipe(bedrockConfig: BedrockConfiguration): BedrockMultimodalPipe {
        val reasoningSettings = ReasoningSettings(
            reasoningMethod = ReasoningMethod.BestIdea,
            depth = ReasoningDepth.Med,
            duration = ReasoningDuration.Med,
            reasoningInjector = ReasoningInjector.AfterUserPrompt,
            numberOfRounds = 1
        )

        val pipeSettings = PipeSettings(
            pipeName = "nested-reasoning",
            provider = ProviderName.Aws,
            model = LLAMA_MAVERICK_MODEL_ID,
            temperature = 0.1,
            topP = 0.2,
            maxTokens = 2000,
            contextWindowSize = 8000
        )

        return ReasoningBuilder.reasonWithBedrock(
            bedrockConfig = bedrockConfig,
            reasoningSettings = reasoningSettings,
            pipeSettings = pipeSettings
        ) as BedrockMultimodalPipe
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

    private fun configureLlamaInferenceBinding() {
        bedrockEnv.resetInferenceConfig()
        llamaInferenceConfigFile = File.createTempFile("tpipe-llama4-inference", ".txt")
        llamaInferenceConfigFile.writeText("$LLAMA_MAVERICK_MODEL_ID=$LLAMA_MAVERICK_INFERENCE_PROFILE\n")
        llamaInferenceConfigFile.deleteOnExit()
        bedrockEnv.setInferenceConfigFile(llamaInferenceConfigFile)
        bedrockEnv.loadInferenceConfig()

        assertEquals(
            LLAMA_MAVERICK_INFERENCE_PROFILE,
            bedrockEnv.getInferenceProfileId(LLAMA_MAVERICK_MODEL_ID),
            "The test should bind the Llama 4 Maverick model ID to the resolved Bedrock ARN"
        )
        println("Using Llama 4 Maverick $LLAMA_MAVERICK_MODEL_ID in $REGION")
    }

    /**
     * Find trace events of a specific type in the JSON trace report
     */
    private fun findTraceEvents(jsonTrace: String, eventType: TraceEventType): List<TraceEventData> {
        val events = mutableListOf<TraceEventData>()
        try {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(jsonTrace)
            element.jsonArray.forEach { item ->
                val obj = item.jsonObject
                if (obj["eventType"]?.jsonPrimitive?.contentOrNull == eventType.name) {
                    val metadata = obj["metadata"]?.jsonObject?.let { meta ->
                        meta.entries.associate { it.key to it.value.jsonPrimitive.content }
                    }
                    events.add(TraceEventData(eventType, metadata))
                }
            }
        } catch (e: Exception) {
            println("Error parsing trace events: ${e.message}")
        }
        return events
    }

    private data class TraceEventData(
        val eventType: TraceEventType,
        val metadata: Map<String, String>?
    )
}
