package bedrockPipe

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TraceFormat
import com.TTT.Enums.ProviderName
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipeline.DiscussionDecision
import com.TTT.Pipeline.DiscussionStrategy
import com.TTT.Pipeline.Junction
import com.TTT.Pipeline.JunctionWorkflowOutcome
import com.TTT.Pipeline.JunctionWorkflowPhase
import com.TTT.Pipeline.JunctionWorkflowPhaseResult
import com.TTT.Pipeline.JunctionWorkflowRecipe
import com.TTT.Pipeline.ModeratorDirective
import com.TTT.Pipeline.ParticipantOpinion
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.Transport as PipeTransport
import com.TTT.Util.deepCopy
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import com.TTT.Util.writeStringToFile
import env.bedrockEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Live Junction regression coverage backed by real Bedrock multimodal pipes.
 *
 * The test exercises every supported Junction configuration, saves trace artifacts under TPipe's default trace
 * directory, and verifies that the harness and its nested containers both emit trace data.
 */
class JunctionLiveBedrockIntegrationTest
{
    /**
     * Proves that a live Junction harness can execute across the full discussion and workflow matrix using real
     * Bedrock-backed role containers, while also persisting trace artifacts to the default TPipe trace directory.
     */
    @Test
    fun liveBedrockJunctionMatrixRunsAndSavesTraces()
    {
        TestCredentialUtils.requireAwsCredentials()

        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()

            PipeTracer.enable()

            val traceConfig = TraceConfig(
                enabled = true,
                outputFormat = TraceFormat.HTML,
                detailLevel = TraceDetailLevel.DEBUG,
                includeContext = true,
                includeMetadata = true
            )

            val failures = mutableListOf<String>()

            try {
                val runtimes = buildLiveMatrixRuntimes(traceConfig)
                for(runtime in runtimes)
                {
                    var caseFailure: Throwable? = null

                    try {
                        val result = runtime.junction.execute(runtime.input)
                        runtime.assertions.invoke(result)
                    }
                    catch(e: Throwable)
                    {
                        caseFailure = e
                        failures.add(buildCaseFailure(runtime.caseName, e))
                    }
                    finally
                    {
                        try {
                            saveTraceArtifacts(runtime)
                        }
                        catch(traceError: Throwable)
                        {
                            failures.add(
                                buildCaseFailure(
                                    runtime.caseName,
                                    traceError,
                                    label = "trace-save"
                                )
                            )
                        }
                    }

                    if(caseFailure != null)
                    {
                        println("Live Junction case '${runtime.caseName}' failed, but the matrix continued.")
                    }
                }
            }
            finally
            {
                PipeTracer.disable()
            }

            assertTrue(
                failures.isEmpty(),
                failures.joinToString(
                    separator = "\n\n",
                    prefix = "Live Junction Bedrock integration failures:\n"
                )
            )
        }
    }

    private suspend fun buildLiveMatrixRuntimes(traceConfig: TraceConfig): List<LiveCaseRuntime>
    {
        val runtimes = mutableListOf<LiveCaseRuntime>()

        DiscussionStrategy.values().forEach { strategy ->
            runtimes.add(buildDiscussionRuntime(strategy, traceConfig))
        }

        JunctionWorkflowRecipe.values()
            .filter { it != JunctionWorkflowRecipe.DISCUSSION_ONLY }
            .forEach { recipe ->
                runtimes.add(buildWorkflowRuntime(recipe, traceConfig))
            }

        return runtimes
    }

    private suspend fun buildDiscussionRuntime(
        strategy: DiscussionStrategy,
        traceConfig: TraceConfig
    ): LiveCaseRuntime
    {
        val caseName = "discussion-${strategy.name.lowercase()}"
        val moderatorBehavior = if(strategy == DiscussionStrategy.CONVERSATIONAL)
        {
            LiveRoleBehavior.DiscussionModerator(
                conversationalFollowUpParticipant = "participant-b",
                finalDecision = "Proceed"
            )
        }
        else
        {
            LiveRoleBehavior.DiscussionModerator(
                finalDecision = "Proceed"
            )
        }

        val moderator = createRoleContainer(
            roleName = "moderator",
            roleBehavior = moderatorBehavior,
            traceConfig = traceConfig,
            systemPrompt = """
                You are the live Junction moderator.
                Return a strict JSON object matching ModeratorDirective and keep the result short.
            """.trimIndent(),
            jsonSchemaDescription = """
                Return a strict ModeratorDirective JSON object.
                Keep notes short and use the first call to steer the discussion.
            """.trimIndent()
        )

        val participantA = createRoleContainer(
            roleName = "participant-a",
            roleBehavior = LiveRoleBehavior.DiscussionParticipant(vote = "Proceed"),
            traceConfig = traceConfig,
            systemPrompt = """
                You are live Junction participant A.
                Return a strict JSON object matching ParticipantOpinion and keep the vote concise.
            """.trimIndent(),
            jsonSchemaDescription = """
                Return a strict ParticipantOpinion JSON object.
                Keep the vote short and consistent.
            """.trimIndent()
        )

        val participantB = createRoleContainer(
            roleName = "participant-b",
            roleBehavior = LiveRoleBehavior.DiscussionParticipant(vote = "Proceed"),
            traceConfig = traceConfig,
            systemPrompt = """
                You are live Junction participant B.
                Return a strict JSON object matching ParticipantOpinion and keep the vote concise.
            """.trimIndent(),
            jsonSchemaDescription = """
                Return a strict ParticipantOpinion JSON object.
                Keep the vote short and consistent.
            """.trimIndent()
        )

        val junction = Junction()
            .setModerator(moderator)
            .addParticipants(
                "participant-a" to participantA,
                "participant-b" to participantB
            )
            .setStrategy(strategy)
            .setRounds(if(strategy == DiscussionStrategy.CONVERSATIONAL) 2 else 1)
            .setVotingThreshold(0.5)
            .setMaxNestedDepth(10)
            .enableTracing(traceConfig)

        junction.init()

        val input = MultimodalContent(
            text = "Live discussion matrix case '$caseName'. Decide the harness outcome and keep it concise."
        )

        val expectedJunctionMarkers = if(strategy == DiscussionStrategy.CONVERSATIONAL)
        {
            listOf(
                TraceEventType.JUNCTION_START,
                TraceEventType.JUNCTION_ROUND_START,
                TraceEventType.JUNCTION_PARTICIPANT_DISPATCH,
                TraceEventType.JUNCTION_PARTICIPANT_RESPONSE,
                TraceEventType.JUNCTION_VOTE_TALLY,
                TraceEventType.JUNCTION_CONSENSUS_CHECK,
                TraceEventType.JUNCTION_SUCCESS,
                TraceEventType.JUNCTION_END
            )
        }
        else
        {
            listOf(
                TraceEventType.JUNCTION_START,
                TraceEventType.JUNCTION_ROUND_START,
                TraceEventType.JUNCTION_PARTICIPANT_DISPATCH,
                TraceEventType.JUNCTION_PARTICIPANT_RESPONSE,
                TraceEventType.JUNCTION_VOTE_TALLY,
                TraceEventType.JUNCTION_CONSENSUS_CHECK,
                TraceEventType.JUNCTION_SUCCESS,
                TraceEventType.JUNCTION_END
            )
        }

        val roles = linkedMapOf(
            "moderator" to moderator,
            "participant-a" to participantA,
            "participant-b" to participantB
        )

        return LiveCaseRuntime(
            caseName = caseName,
            junction = junction,
            tracedRoles = roles,
            input = input,
            expectedJunctionMarkers = expectedJunctionMarkers,
            expectedRoleMarkers = listOf(
                TraceEventType.API_CALL_START,
                TraceEventType.API_CALL_SUCCESS
            ),
            assertions = { result ->
                val decision = deserialize<DiscussionDecision>(result.text)
                    ?: error("Discussion result should deserialize into DiscussionDecision")
                assertEquals(strategy, decision.strategy, "Discussion strategy should round-trip in the result")
                assertTrue(decision.decision.isNotBlank(), "Discussion decision should not be blank")
                assertTrue(result.passPipeline, "Junction should complete the discussion pass")
                assertTrue(moderator.requestCount >= 1, "Moderator should be consulted at least once")
                assertTrue(participantA.requestCount >= 1, "Participant A should be consulted at least once")
                assertTrue(participantB.requestCount >= 1, "Participant B should be consulted at least once")
                assertTrue(decision.roundsExecuted >= 1, "Discussion should execute at least one round")
            }
        )
    }

    private suspend fun buildWorkflowRuntime(
        recipe: JunctionWorkflowRecipe,
        traceConfig: TraceConfig
    ): LiveCaseRuntime
    {
        val caseName = "workflow-${recipe.name.lowercase()}"
        val repeatOnFirstCycle = recipe.repeats()

        val moderator = createRoleContainer(
            roleName = "moderator",
            roleBehavior = LiveRoleBehavior.DiscussionModerator(
                finalDecision = "Proceed"
            ),
            traceConfig = traceConfig,
            systemPrompt = """
                You are the live Junction workflow moderator.
                Return a strict JSON object matching ModeratorDirective and keep the result short.
            """.trimIndent(),
            jsonSchemaDescription = """
                Return a strict ModeratorDirective JSON object.
                Keep the workflow moving and keep notes concise.
            """.trimIndent()
        )

        val participantA = createRoleContainer(
            roleName = "participant-a",
            roleBehavior = LiveRoleBehavior.DiscussionParticipant(vote = "Proceed"),
            traceConfig = traceConfig,
            systemPrompt = """
                You are live Junction workflow participant A.
                Return a strict JSON object matching ParticipantOpinion and keep the vote concise.
            """.trimIndent(),
            jsonSchemaDescription = """
                Return a strict ParticipantOpinion JSON object.
                Keep the vote short and consistent.
            """.trimIndent()
        )

        val participantB = createRoleContainer(
            roleName = "participant-b",
            roleBehavior = LiveRoleBehavior.DiscussionParticipant(vote = "Proceed"),
            traceConfig = traceConfig,
            systemPrompt = """
                You are live Junction workflow participant B.
                Return a strict JSON object matching ParticipantOpinion and keep the vote concise.
            """.trimIndent(),
            jsonSchemaDescription = """
                Return a strict ParticipantOpinion JSON object.
                Keep the vote short and consistent.
            """.trimIndent()
        )

        val tracedRoles = linkedMapOf<String, LiveBedrockRoleContainer>()
        tracedRoles["participant-a"] = participantA
        tracedRoles["participant-b"] = participantB

        val junction = Junction()
            .setModerator(moderator)
            .addParticipants(
                "participant-a" to participantA,
                "participant-b" to participantB
            )
            .setWorkflowRecipe(recipe)
            .setRounds(if(repeatOnFirstCycle) 2 else 1)
            .setVotingThreshold(0.5)
            .setMaxNestedDepth(10)
            .enableTracing(traceConfig)

        when(recipe)
        {
            JunctionWorkflowRecipe.VOTE_ACT_VERIFY_REPEAT ->
            {
                val actor = createRoleContainer(
                    roleName = "actor",
                    roleBehavior = LiveRoleBehavior.WorkflowPhase(
                        phase = JunctionWorkflowPhase.ACT
                    ),
                    traceConfig = traceConfig,
                    systemPrompt = """
                        You are the live Junction actor.
                        Return a strict JSON object matching JunctionWorkflowPhaseResult for ACT.
                    """.trimIndent(),
                    jsonSchemaDescription = """
                        Return a strict JunctionWorkflowPhaseResult JSON object for ACT.
                    """.trimIndent()
                )
                val verifier = createRoleContainer(
                    roleName = "verifier",
                    roleBehavior = LiveRoleBehavior.WorkflowPhase(
                        phase = JunctionWorkflowPhase.VERIFY,
                        repeatOnFirstCycle = true
                    ),
                    traceConfig = traceConfig,
                    systemPrompt = """
                        You are the live Junction verifier.
                        Return a strict JSON object matching JunctionWorkflowPhaseResult for VERIFY.
                        On the first call request another cycle, then approve on later calls.
                    """.trimIndent(),
                    jsonSchemaDescription = """
                        Return a strict JunctionWorkflowPhaseResult JSON object for VERIFY.
                        Request another cycle on the first call and approve on later calls.
                    """.trimIndent()
                )
                tracedRoles["actor"] = actor
                tracedRoles["verifier"] = verifier
                junction.setActor(actor)
                junction.setVerifier(verifier)
            }

            JunctionWorkflowRecipe.ACT_VOTE_VERIFY_REPEAT ->
            {
                val actor = createRoleContainer(
                    roleName = "actor",
                    roleBehavior = LiveRoleBehavior.WorkflowPhase(
                        phase = JunctionWorkflowPhase.ACT
                    ),
                    traceConfig = traceConfig,
                    systemPrompt = """
                        You are the live Junction actor.
                        Return a strict JSON object matching JunctionWorkflowPhaseResult for ACT.
                    """.trimIndent(),
                    jsonSchemaDescription = """
                        Return a strict JunctionWorkflowPhaseResult JSON object for ACT.
                    """.trimIndent()
                )
                val verifier = createRoleContainer(
                    roleName = "verifier",
                    roleBehavior = LiveRoleBehavior.WorkflowPhase(
                        phase = JunctionWorkflowPhase.VERIFY,
                        repeatOnFirstCycle = true
                    ),
                    traceConfig = traceConfig,
                    systemPrompt = """
                        You are the live Junction verifier.
                        Return a strict JSON object matching JunctionWorkflowPhaseResult for VERIFY.
                        On the first call request another cycle, then approve on later calls.
                    """.trimIndent(),
                    jsonSchemaDescription = """
                        Return a strict JunctionWorkflowPhaseResult JSON object for VERIFY.
                        Request another cycle on the first call and approve on later calls.
                    """.trimIndent()
                )
                tracedRoles["actor"] = actor
                tracedRoles["verifier"] = verifier
                junction.setActor(actor)
                junction.setVerifier(verifier)
            }

            JunctionWorkflowRecipe.VOTE_PLAN_ACT_VERIFY_REPEAT ->
            {
                val planner = createRoleContainer(
                    roleName = "planner",
                    roleBehavior = LiveRoleBehavior.WorkflowPhase(
                        phase = JunctionWorkflowPhase.PLAN
                    ),
                    traceConfig = traceConfig,
                    systemPrompt = """
                        You are the live Junction planner.
                        Return a strict JSON object matching JunctionWorkflowPhaseResult for PLAN.
                    """.trimIndent(),
                    jsonSchemaDescription = """
                        Return a strict JunctionWorkflowPhaseResult JSON object for PLAN.
                    """.trimIndent()
                )
                val actor = createRoleContainer(
                    roleName = "actor",
                    roleBehavior = LiveRoleBehavior.WorkflowPhase(
                        phase = JunctionWorkflowPhase.ACT
                    ),
                    traceConfig = traceConfig,
                    systemPrompt = """
                        You are the live Junction actor.
                        Return a strict JSON object matching JunctionWorkflowPhaseResult for ACT.
                    """.trimIndent(),
                    jsonSchemaDescription = """
                        Return a strict JunctionWorkflowPhaseResult JSON object for ACT.
                    """.trimIndent()
                )
                val verifier = createRoleContainer(
                    roleName = "verifier",
                    roleBehavior = LiveRoleBehavior.WorkflowPhase(
                        phase = JunctionWorkflowPhase.VERIFY,
                        repeatOnFirstCycle = true
                    ),
                    traceConfig = traceConfig,
                    systemPrompt = """
                        You are the live Junction verifier.
                        Return a strict JSON object matching JunctionWorkflowPhaseResult for VERIFY.
                        On the first call request another cycle, then approve on later calls.
                    """.trimIndent(),
                    jsonSchemaDescription = """
                        Return a strict JunctionWorkflowPhaseResult JSON object for VERIFY.
                        Request another cycle on the first call and approve on later calls.
                    """.trimIndent()
                )
                tracedRoles["planner"] = planner
                tracedRoles["actor"] = actor
                tracedRoles["verifier"] = verifier
                junction.setPlanner(planner)
                junction.setActor(actor)
                junction.setVerifier(verifier)
            }

            JunctionWorkflowRecipe.PLAN_VOTE_ACT_VERIFY_REPEAT ->
            {
                val planner = createRoleContainer(
                    roleName = "planner",
                    roleBehavior = LiveRoleBehavior.WorkflowPhase(
                        phase = JunctionWorkflowPhase.PLAN
                    ),
                    traceConfig = traceConfig,
                    systemPrompt = """
                        You are the live Junction planner.
                        Return a strict JSON object matching JunctionWorkflowPhaseResult for PLAN.
                    """.trimIndent(),
                    jsonSchemaDescription = """
                        Return a strict JunctionWorkflowPhaseResult JSON object for PLAN.
                    """.trimIndent()
                )
                val actor = createRoleContainer(
                    roleName = "actor",
                    roleBehavior = LiveRoleBehavior.WorkflowPhase(
                        phase = JunctionWorkflowPhase.ACT
                    ),
                    traceConfig = traceConfig,
                    systemPrompt = """
                        You are the live Junction actor.
                        Return a strict JSON object matching JunctionWorkflowPhaseResult for ACT.
                    """.trimIndent(),
                    jsonSchemaDescription = """
                        Return a strict JunctionWorkflowPhaseResult JSON object for ACT.
                    """.trimIndent()
                )
                val verifier = createRoleContainer(
                    roleName = "verifier",
                    roleBehavior = LiveRoleBehavior.WorkflowPhase(
                        phase = JunctionWorkflowPhase.VERIFY,
                        repeatOnFirstCycle = true
                    ),
                    traceConfig = traceConfig,
                    systemPrompt = """
                        You are the live Junction verifier.
                        Return a strict JSON object matching JunctionWorkflowPhaseResult for VERIFY.
                        On the first call request another cycle, then approve on later calls.
                    """.trimIndent(),
                    jsonSchemaDescription = """
                        Return a strict JunctionWorkflowPhaseResult JSON object for VERIFY.
                        Request another cycle on the first call and approve on later calls.
                    """.trimIndent()
                )
                tracedRoles["planner"] = planner
                tracedRoles["actor"] = actor
                tracedRoles["verifier"] = verifier
                junction.setPlanner(planner)
                junction.setActor(actor)
                junction.setVerifier(verifier)
            }

            JunctionWorkflowRecipe.VOTE_PLAN_OUTPUT_EXIT ->
            {
                val planner = createRoleContainer(
                    roleName = "planner",
                    roleBehavior = LiveRoleBehavior.WorkflowPhase(
                        phase = JunctionWorkflowPhase.PLAN
                    ),
                    traceConfig = traceConfig,
                    systemPrompt = """
                        You are the live Junction planner.
                        Return a strict JSON object matching JunctionWorkflowPhaseResult for PLAN.
                    """.trimIndent(),
                    jsonSchemaDescription = """
                        Return a strict JunctionWorkflowPhaseResult JSON object for PLAN.
                    """.trimIndent()
                )
                val outputHandler = createRoleContainer(
                    roleName = "output-handler",
                    roleBehavior = LiveRoleBehavior.WorkflowPhase(
                        phase = JunctionWorkflowPhase.OUTPUT
                    ),
                    traceConfig = traceConfig,
                    systemPrompt = """
                        You are the live Junction output handler.
                        Return a strict JSON object matching JunctionWorkflowPhaseResult for OUTPUT.
                    """.trimIndent(),
                    jsonSchemaDescription = """
                        Return a strict JunctionWorkflowPhaseResult JSON object for OUTPUT.
                    """.trimIndent()
                )
                tracedRoles["planner"] = planner
                tracedRoles["output-handler"] = outputHandler
                junction.setPlanner(planner)
                junction.setOutputHandler(outputHandler)
            }

            JunctionWorkflowRecipe.PLAN_VOTE_ADJUST_OUTPUT_EXIT ->
            {
                val planner = createRoleContainer(
                    roleName = "planner",
                    roleBehavior = LiveRoleBehavior.WorkflowPhase(
                        phase = JunctionWorkflowPhase.PLAN
                    ),
                    traceConfig = traceConfig,
                    systemPrompt = """
                        You are the live Junction planner.
                        Return a strict JSON object matching JunctionWorkflowPhaseResult for PLAN.
                    """.trimIndent(),
                    jsonSchemaDescription = """
                        Return a strict JunctionWorkflowPhaseResult JSON object for PLAN.
                    """.trimIndent()
                )
                val adjuster = createRoleContainer(
                    roleName = "adjuster",
                    roleBehavior = LiveRoleBehavior.WorkflowPhase(
                        phase = JunctionWorkflowPhase.ADJUST
                    ),
                    traceConfig = traceConfig,
                    systemPrompt = """
                        You are the live Junction adjuster.
                        Return a strict JSON object matching JunctionWorkflowPhaseResult for ADJUST.
                    """.trimIndent(),
                    jsonSchemaDescription = """
                        Return a strict JunctionWorkflowPhaseResult JSON object for ADJUST.
                    """.trimIndent()
                )
                val outputHandler = createRoleContainer(
                    roleName = "output-handler",
                    roleBehavior = LiveRoleBehavior.WorkflowPhase(
                        phase = JunctionWorkflowPhase.OUTPUT
                    ),
                    traceConfig = traceConfig,
                    systemPrompt = """
                        You are the live Junction output handler.
                        Return a strict JSON object matching JunctionWorkflowPhaseResult for OUTPUT.
                    """.trimIndent(),
                    jsonSchemaDescription = """
                        Return a strict JunctionWorkflowPhaseResult JSON object for OUTPUT.
                    """.trimIndent()
                )
                tracedRoles["planner"] = planner
                tracedRoles["adjuster"] = adjuster
                tracedRoles["output-handler"] = outputHandler
                junction.setPlanner(planner)
                junction.setAdjuster(adjuster)
                junction.setOutputHandler(outputHandler)
            }

            else -> error("Unhandled workflow recipe: $recipe")
        }

        junction.init()

        val expectedJunctionMarkers = buildList {
            add(TraceEventType.JUNCTION_WORKFLOW_START)
            add(TraceEventType.JUNCTION_PHASE_START)
            add(TraceEventType.JUNCTION_PHASE_END)
            if(recipe.endsWithHandoff())
            {
                add(TraceEventType.JUNCTION_HANDOFF)
            }
            add(TraceEventType.JUNCTION_WORKFLOW_SUCCESS)
            add(TraceEventType.JUNCTION_WORKFLOW_END)
        }

        val input = MultimodalContent(
            text = "Live workflow matrix case '$caseName'. Execute the requested recipe and keep the output concise."
        )

        return LiveCaseRuntime(
            caseName = caseName,
            junction = junction,
            tracedRoles = tracedRoles,
            input = input,
            expectedJunctionMarkers = expectedJunctionMarkers,
            expectedRoleMarkers = listOf(
                TraceEventType.API_CALL_START,
                TraceEventType.API_CALL_SUCCESS
            ),
            assertions = { result ->
                val outcome = deserialize<JunctionWorkflowOutcome>(result.text)
                    ?: error("Workflow result should deserialize into JunctionWorkflowOutcome")
                assertEquals(recipe, outcome.recipe, "Workflow recipe should round-trip in the result")
                assertTrue(outcome.completed, "Workflow should complete successfully")
                assertTrue(outcome.phaseResults.isNotEmpty(), "Workflow should record at least one phase result")
                assertTrue(result.passPipeline, "Junction should complete the workflow pass")
                assertTrue(outcome.cyclesExecuted >= 1, "Workflow should execute at least one cycle")

                if(recipe.endsWithHandoff())
                {
                    assertTrue(outcome.handoffOnly, "Handoff recipes should mark the outcome as handoff-only")
                    assertTrue(outcome.finalText.isNotBlank(), "Handoff recipes should emit final instructions")
                }

                tracedRoles.values.forEach { role ->
                    assertTrue(role.requestCount >= 1, "Every traced workflow role should be exercised at least once")
                }
            }
        )
    }

    private suspend fun createRoleContainer(
        roleName: String,
        roleBehavior: LiveRoleBehavior,
        traceConfig: TraceConfig,
        systemPrompt: String,
        jsonSchemaDescription: String
    ): LiveBedrockRoleContainer
    {
        val pipeline = Pipeline()
            .setPipelineName(roleName)

        val pipe = BedrockMultimodalPipe()
        pipe.setProvider(ProviderName.Aws)
        pipe.setModel(LIVE_MODEL_ID)
        pipe.setRegion(LIVE_MODEL_REGION)
        pipe.useConverseApi()
        pipe.setReadTimeout(300)
        pipe.setTemperature(0.1)
        pipe.setTopP(0.2)
        pipe.setMaxTokens(256)
        pipe.setSystemPrompt(systemPrompt)
        pipe.setJsonOutputInstructions(jsonSchemaDescription)
        pipe.setJsonOutput(roleBehavior.templateObject(roleName)::class)
        pipe.requireJsonPromptInjection(true)

        pipeline.add(pipe)
        pipeline.enableTracing(traceConfig)

        val container = LiveBedrockRoleContainer(
            roleName = roleName,
            roleBehavior = roleBehavior,
            pipeline = pipeline
        )

        pipeline.setContainerObject(container)
        pipeline.init(initPipes = true)

        return container
    }

    private fun saveTraceArtifacts(runtime: LiveCaseRuntime)
    {
        val baseDir = "${TPipeConfig.getTraceDir()}/Library/junction-live-bedrock/${runtime.caseName}"
        val junctionTrace = runtime.junction.getTraceReport(TraceFormat.HTML)
        val junctionPath = "$baseDir/junction.html"
        val junctionEvents = PipeTracer.getTrace(runtime.junction.getTraceId())

        writeStringToFile(junctionPath, junctionTrace)
        assertTraceSaved(junctionPath, junctionTrace, "${runtime.caseName} junction")
        assertTraceEvents(junctionEvents.map { it.eventType }, runtime.expectedJunctionMarkers, "${runtime.caseName} junction")
        assertTrue(junctionEvents.isNotEmpty(), "Junction trace should contain live events for ${runtime.caseName}")

        runtime.tracedRoles.forEach { (roleName, role) ->
            val roleTrace = role.pipeline.getTraceReport(TraceFormat.HTML)
            val rolePath = "$baseDir/${sanitizeFileName(roleName)}.html"
            val roleEvents = PipeTracer.getTrace(role.pipeline.getTraceId())

            writeStringToFile(rolePath, roleTrace)
            assertTraceSaved(rolePath, roleTrace, "${runtime.caseName} $roleName")
            assertTraceEvents(roleEvents.map { it.eventType }, runtime.expectedRoleMarkers, "${runtime.caseName} $roleName")
            assertTrue(roleEvents.isNotEmpty(), "Role trace should contain live Bedrock events for ${runtime.caseName} $roleName")
        }
    }

    private fun assertTraceSaved(
        path: String,
        report: String,
        label: String
    )
    {
        val file = File(path)
        assertTrue(file.exists(), "Trace file should exist for $label")
        assertTrue(file.length() > 0, "Trace file should not be empty for $label")
        assertTrue(report.isNotBlank(), "Trace report should not be blank for $label")
    }

    private fun assertTraceEvents(
        events: List<TraceEventType>,
        markers: List<TraceEventType>,
        label: String
    )
    {
        markers.forEach { marker ->
            assertTrue(
                events.contains(marker),
                "Trace events for $label should contain ${marker.name}"
            )
        }
    }

    private fun buildCaseFailure(
        caseName: String,
        error: Throwable,
        label: String = "execution"
    ): String
    {
        return buildString {
            appendLine("Case: $caseName")
            appendLine("Stage: $label")
            appendLine("Error: ${error::class.qualifiedName}")
            appendLine("Message: ${error.message ?: "unknown"}")
            appendLine(error.stackTraceToString())
        }
    }

    private fun sanitizeFileName(value: String): String
    {
        return value.lowercase().replace(Regex("[^a-z0-9._-]+"), "-")
    }

    private sealed class LiveRoleBehavior
    {
        data class DiscussionModerator(
            val conversationalFollowUpParticipant: String? = null,
            val finalDecision: String = "Proceed"
        ) : LiveRoleBehavior()

        data class DiscussionParticipant(
            val vote: String = "Proceed"
        ) : LiveRoleBehavior()

        data class WorkflowPhase(
            val phase: JunctionWorkflowPhase,
            val repeatOnFirstCycle: Boolean = false
        ) : LiveRoleBehavior()

        fun templateObject(roleName: String): Any
        {
            return when(this)
            {
                is DiscussionModerator -> ModeratorDirective(
                    continueDiscussion = conversationalFollowUpParticipant != null,
                    selectedParticipants = conversationalFollowUpParticipant
                        ?.let { mutableListOf(it) }
                        ?: mutableListOf(),
                    finalDecision = finalDecision,
                    notes = ""
                )

                is DiscussionParticipant -> ParticipantOpinion(
                    participantName = roleName,
                    opinion = vote,
                    vote = vote,
                    confidence = 0.75,
                    reasoning = "",
                    rawOutput = ""
                )

                is WorkflowPhase -> JunctionWorkflowPhaseResult(
                    phase = phase,
                    participantName = roleName,
                    text = "",
                    instructions = "",
                    passed = !repeatOnFirstCycle,
                    repeatRequested = repeatOnFirstCycle,
                    terminateRequested = false,
                    notes = "",
                    rawOutput = ""
                )
            }
        }

        fun promptHint(roleName: String, requestCount: Int): String
        {
            return when(this)
            {
                is DiscussionModerator ->
                {
                    if(conversationalFollowUpParticipant != null && requestCount == 1)
                    {
                        """
                            First conversational round: continueDiscussion true and select ${conversationalFollowUpParticipant}.
                            Keep the finalDecision blank for the first round.
                        """.trimIndent()
                    }
                    else
                    {
                        """
                            Close the discussion with finalDecision '$finalDecision'.
                            ContinueDiscussion should be false.
                        """.trimIndent()
                    }
                }

                is DiscussionParticipant ->
                {
                    """
                        Respond as participant '$roleName' with vote '$vote' and concise reasoning.
                    """.trimIndent()
                }

                is WorkflowPhase ->
                {
                    val repeatHint = if(repeatOnFirstCycle && requestCount == 1)
                    {
                        "On this first cycle, set repeatRequested true and passed false."
                    }
                    else
                    {
                        "Set repeatRequested false and passed true."
                    }

                    """
                        Respond as workflow role '$roleName' for phase ${phase.name}.
                        $repeatHint
                    """.trimIndent()
                }
            }
        }

        fun normalizeOutput(
            roleName: String,
            requestCount: Int,
            rawOutput: String
        ): MultimodalContent
        {
            return when(this)
            {
                is DiscussionModerator ->
                {
                    val parsed = runCatching { deserialize<ModeratorDirective>(rawOutput) }.getOrNull()
                    val normalized = parsed ?: ModeratorDirective()
                    val response = normalized.copy(
                        continueDiscussion = if(conversationalFollowUpParticipant != null)
                        {
                            requestCount == 1
                        }
                        else
                        {
                            false
                        },
                        selectedParticipants = if(conversationalFollowUpParticipant != null && requestCount == 1)
                        {
                            mutableListOf(conversationalFollowUpParticipant)
                        }
                        else
                        {
                            mutableListOf()
                        },
                        finalDecision = normalized.finalDecision.ifBlank { finalDecision },
                        notes = normalized.notes.ifBlank { rawOutput.take(160) }
                    )

                    MultimodalContent(
                        text = serialize(response),
                        modelReasoning = rawOutput.take(256)
                    )
                }

                is DiscussionParticipant ->
                {
                    val parsed = runCatching { deserialize<ParticipantOpinion>(rawOutput) }.getOrNull()
                    val normalized = parsed ?: ParticipantOpinion()
                    val response = normalized.copy(
                        participantName = normalized.participantName.ifBlank { roleName },
                        roundNumber = if(normalized.roundNumber == 0) requestCount else normalized.roundNumber,
                        opinion = normalized.opinion.ifBlank { vote },
                        vote = normalized.vote.ifBlank { vote },
                        confidence = if(normalized.confidence <= 0.0) 0.75 else normalized.confidence,
                        reasoning = normalized.reasoning.ifBlank { rawOutput.take(160) },
                        rawOutput = rawOutput
                    )

                    MultimodalContent(
                        text = serialize(response),
                        modelReasoning = rawOutput.take(256)
                    )
                }

                is WorkflowPhase ->
                {
                    val parsed = runCatching { deserialize<JunctionWorkflowPhaseResult>(rawOutput) }.getOrNull()
                    val normalized = parsed ?: JunctionWorkflowPhaseResult()
                    val shouldRepeat = repeatOnFirstCycle && requestCount == 1
                    val response = normalized.copy(
                        phase = phase,
                        cycleNumber = if(normalized.cycleNumber == 0) requestCount else normalized.cycleNumber,
                        participantName = normalized.participantName.ifBlank { roleName },
                        text = normalized.text.ifBlank { rawOutput.take(160) },
                        instructions = normalized.instructions.ifBlank { rawOutput.take(160) },
                        passed = !shouldRepeat,
                        repeatRequested = shouldRepeat,
                        terminateRequested = false,
                        notes = normalized.notes.ifBlank { rawOutput.take(160) },
                        rawOutput = rawOutput
                    )

                    MultimodalContent(
                        text = serialize(response),
                        modelReasoning = rawOutput.take(256)
                    ).also { content ->
                        content.repeatPipe = shouldRepeat
                    }
                }
            }
        }
    }

    private data class LiveCaseRuntime(
        val caseName: String,
        val junction: Junction,
        val tracedRoles: LinkedHashMap<String, LiveBedrockRoleContainer>,
        val input: MultimodalContent,
        val expectedJunctionMarkers: List<TraceEventType>,
        val expectedRoleMarkers: List<TraceEventType>,
        val assertions: (MultimodalContent) -> Unit
    )

    private class LiveBedrockRoleContainer(
        private val roleName: String,
        private val roleBehavior: LiveRoleBehavior,
        val pipeline: Pipeline
    ) : P2PInterface
    {
        var requestCount: Int = 0
            private set

        private var descriptor: P2PDescriptor? = null
        private var requirements: P2PRequirements? = null
        private var transport: P2PTransport? = null
        private var containerRef: Any? = null

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
        }

        override fun getP2pDescription(): P2PDescriptor?
        {
            return descriptor
        }

        override fun setP2pTransport(transport: P2PTransport)
        {
            this.transport = transport
        }

        override fun getP2pTransport(): P2PTransport?
        {
            return transport
        }

        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
        }

        override fun getP2pRequirements(): P2PRequirements?
        {
            return requirements
        }

        override fun getContainerObject(): Any?
        {
            return containerRef
        }

        override fun setContainerObject(container: Any)
        {
            containerRef = container
        }

        override fun getPipelinesFromInterface(): List<Pipeline>
        {
            return listOf(pipeline)
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            requestCount++

            val workingRequest = request.deepCopy<P2PRequest>()
            workingRequest.prompt.addText(roleBehavior.promptHint(roleName, requestCount))

            val workingContent = workingRequest.prompt.deepCopy()
            workingRequest.context?.let { context ->
                workingContent.context = context.deepCopy()
            }

            val rawResult = pipeline.execute(workingContent)
            val rawOutput = rawResult.text
            val normalized = roleBehavior.normalizeOutput(roleName, requestCount, rawOutput)

            return P2PResponse(output = normalized)
        }

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            requestCount++
            val workingContent = content.deepCopy()
            workingContent.addText(roleBehavior.promptHint(roleName, requestCount))
            val rawResult = pipeline.execute(workingContent)
            return roleBehavior.normalizeOutput(roleName, requestCount, rawResult.text)
        }

        init
        {
            descriptor = P2PDescriptor(
                agentName = roleName,
                agentDescription = "Live Bedrock role container for $roleName",
                transport = P2PTransport(
                    transportMethod = PipeTransport.Tpipe,
                    transportAddress = roleName
                ),
                requiresAuth = false,
                usesConverse = true,
                allowsAgentDuplication = true,
                allowsCustomContext = true,
                allowsCustomAgentJson = true,
                recordsInteractionContext = false,
                recordsPromptContent = false,
                allowsExternalContext = true,
                contextProtocol = ContextProtocol.none,
                supportedContentTypes = mutableListOf(SupportedContentTypes.text)
            )
            transport = descriptor?.transport
            requirements = P2PRequirements(
                requireConverseInput = false,
                allowAgentDuplication = true,
                allowCustomContext = true,
                allowCustomJson = true,
                allowExternalConnections = true,
                acceptedContent = mutableListOf(SupportedContentTypes.text),
                maxTokens = 4096
            )
        }
    }

    companion object
    {
        private const val LIVE_MODEL_ID = "nvidia.nemotron-nano-3-30b"
        private const val LIVE_MODEL_REGION = "us-west-2"
    }
}
