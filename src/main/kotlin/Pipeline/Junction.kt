package com.TTT.Pipeline

import com.TTT.Debug.EventPriorityMapper
import com.TTT.Debug.FailureAnalysis
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceEvent
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TraceFormat
import com.TTT.Debug.TracePhase
import com.TTT.Context.ContextWindow
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.P2PSkills
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.deepCopy
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Container for one participant inside a Junction harness.
 *
 * @param roleName Stable routing name used inside the discussion.
 * @param component The actual P2P-capable participant or moderator.
 * @param weight Vote weight used when tallying results.
 * @param descriptor P2P descriptor used to advertise the participant.
 * @param transport P2P transport identity for the participant.
 * @param requirements Security and compatibility requirements for the participant.
 */
private data class JunctionBinding(
    val roleName: String,
    val component: P2PInterface,
    val kind: JunctionBindingKind,
    val weight: Double,
    val descriptor: P2PDescriptor,
    val transport: P2PTransport,
    val requirements: P2PRequirements
)

/**
 * Labels the runtime role that a Junction binding is serving.
 */
private enum class JunctionBindingKind
{
    MODERATOR,
    PARTICIPANT,
    PLANNER,
    ACTOR,
    VERIFIER,
    ADJUSTER,
    OUTPUT
}

/**
 * Junction is TPipe's discussion harness for democratic, multi-participant decision making.
 *
 * The harness accepts any [P2PInterface] as a moderator or participant, including nested containers such as
 * [Manifold], and coordinates rounds until a final decision is reached or the configured limit is hit.
 * It also owns the workflow recipe layer, which reuses the same harness bindings to orchestrate plan, vote,
 * act, verify, adjust, and output phases without flattening nested harnesses into plain pipelines.
 */
class Junction : P2PInterface
{
    private var p2pDescriptor: P2PDescriptor? = null
    private var p2pTransport: P2PTransport? = null
    private var p2pRequirements: P2PRequirements? = null
    private var containerObject: Any? = null

    private var moderatorBinding: JunctionBinding? = null
    private val participantBindings = mutableListOf<JunctionBinding>()
    private val participantBindingsByName = mutableMapOf<String, JunctionBinding>()
    private var plannerBinding: JunctionBinding? = null
    private var actorBinding: JunctionBinding? = null
    private var verifierBinding: JunctionBinding? = null
    private var adjusterBinding: JunctionBinding? = null
    private var outputBinding: JunctionBinding? = null

    private var discussionState = DiscussionState()
    private var workflowRecipe = JunctionWorkflowRecipe.DISCUSSION_ONLY
    private var workflowState = JunctionWorkflowState()
    private var tracingEnabled = false
    private var traceConfig = TraceConfig()
    private val junctionId = UUID.randomUUID().toString()
    private var isPaused = false
    private val resumeSignal = Channel<Unit>(1)
    private var moderatorInterventionEnabled = true
    private var defaultMaxNestedDepth = 8

//----------------------------------------------P2P Interface------------------------------------------------------------

    override fun setP2pDescription(description: P2PDescriptor)
    {
        p2pDescriptor = description
    }

    override fun getP2pDescription(): P2PDescriptor?
    {
        return p2pDescriptor
    }

    override fun setP2pTransport(transport: P2PTransport)
    {
        p2pTransport = transport
    }

    override fun getP2pTransport(): P2PTransport?
    {
        return p2pTransport
    }

    override fun setP2pRequirements(requirements: P2PRequirements)
    {
        p2pRequirements = requirements
    }

    override fun getP2pRequirements(): P2PRequirements?
    {
        return p2pRequirements
    }

    override fun getContainerObject(): Any?
    {
        return containerObject
    }

    override fun setContainerObject(container: Any)
    {
        containerObject = container
    }

    override fun getPipelinesFromInterface(): List<Pipeline>
    {
        // Junction exposes the pipelines from every registered binding so nested harnesses keep their own
        // pipeline graphs while still appearing as one composite P2P container to the outside world.
        val pipelineSet = linkedSetOf<Pipeline>()

        allBindings().forEach { binding ->
            binding.component.getPipelinesFromInterface().forEach { pipeline ->
                pipelineSet.add(pipeline)
            }
        }

        return pipelineSet.toList()
    }

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
    {
        // Normalize the incoming request to a mutable snapshot so nested callers do not see their prompt or
        // context mutated while Junction reuses the same execution path for in-process and P2P invocations.
        val inputContent = request.prompt.deepCopy()
        if(request.context != null)
        {
            inputContent.context = request.context!!.deepCopy()
        }

        val result = execute(inputContent)
        return P2PResponse(output = result)
    }

    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
    {
        // Local execution is intentionally the same as the generic execution path so the harness behaves
        // identically whether it is invoked directly or through the P2P surface.
        return execute(content)
    }

//----------------------------------------------Configuration------------------------------------------------------------

    /**
     * Assign the moderator for this Junction.
     *
     * @param roleName Stable moderator name used for routing and trace output.
     * @param component The P2P-capable moderator container or pipeline.
     * @param descriptor Optional explicit P2P descriptor. If omitted, a safe local default is generated.
     * @param requirements Optional explicit P2P requirements. If omitted, a safe local default is generated.
     * @param description Optional human-readable moderator description.
     * @return This Junction object for method chaining.
     */
    fun setModerator(
        roleName: String,
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    ): Junction
    {
        require(component !== this) { "Junction cannot register itself as its own moderator." }
        require(roleName.isNotBlank()) { "Moderator role names must not be blank." }
        require(roleName !in allBindingNames()) { "Binding '$roleName' is already registered." }

        moderatorBinding = buildBinding(
            roleName = roleName.ifBlank { resolveRoleName(component, "junction-moderator") },
            component = component,
            kind = JunctionBindingKind.MODERATOR,
            descriptor = descriptor,
            requirements = requirements,
            description = description.ifBlank { "Moderator container for Junction discussion control" },
            weight = 1.0
        )

        bindContainerReference(component)

        return this
    }

    /**
     * Assign the moderator for this Junction using a generated routing name.
     *
     * @param component The P2P-capable moderator container or pipeline.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable moderator description.
     * @return This Junction object for method chaining.
     */
    fun setModerator(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    ): Junction
    {
        return setModerator(
            roleName = resolveRoleName(component, "junction-moderator"),
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Add a participant to the discussion.
     *
     * @param roleName Stable participant name used for routing and voting.
     * @param component The P2P-capable participant container or pipeline.
     * @param weight Voting weight for this participant.
     * @param descriptor Optional explicit P2P descriptor. If omitted, a safe local default is generated.
     * @param requirements Optional explicit P2P requirements. If omitted, a safe local default is generated.
     * @param description Optional human-readable participant description.
     * @return This Junction object for method chaining.
     */
    fun addParticipant(
        roleName: String,
        component: P2PInterface,
        weight: Double = 1.0,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    ): Junction
    {
        require(component !== this) { "Junction cannot register itself as a participant." }
        require(roleName.isNotBlank()) { "Participant role names must not be blank." }
        require(roleName !in allBindingNames()) { "Binding '$roleName' is already registered." }
        require(roleName !in participantBindingsByName) { "Participant '$roleName' is already registered." }
        require(weight > 0.0) { "Participant '$roleName' must have a positive vote weight." }

        val binding = buildBinding(
            roleName = roleName,
            component = component,
            kind = JunctionBindingKind.PARTICIPANT,
            descriptor = descriptor,
            requirements = requirements,
            description = description.ifBlank { "Specialized Junction participant" },
            weight = weight
        )

        participantBindings.add(binding)
        participantBindingsByName[roleName] = binding

        bindContainerReference(component)

        return this
    }

    /**
     * Add a participant using a generated routing name.
     *
     * @param component The P2P-capable participant container or pipeline.
     * @param weight Voting weight for this participant.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable participant description.
     * @return This Junction object for method chaining.
     */
    fun addParticipant(
        component: P2PInterface,
        weight: Double = 1.0,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    ): Junction
    {
        return addParticipant(
            roleName = resolveRoleName(component, "junction-participant-${participantBindings.size + 1}"),
            component = component,
            weight = weight,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Add several participants in one call.
     *
     * @param participants Participant role and component pairs.
     * @return This Junction object for method chaining.
     */
    fun addParticipants(vararg participants: Pair<String, P2PInterface>): Junction
    {
        participants.forEach { (roleName, component) ->
            addParticipant(roleName, component)
        }
        return this
    }

    /**
     * Set the discussion strategy for the harness.
     *
     * @param strategy The strategy to use when dispatching participant turns.
     * @return This Junction object for method chaining.
     */
    fun setStrategy(strategy: DiscussionStrategy): Junction
    {
        discussionState.strategy = strategy
        return this
    }

    /**
     * Switch the harness to simultaneous discussion mode.
     *
     * @return This Junction object for method chaining.
     */
    fun simultaneous(): Junction
    {
        return setStrategy(DiscussionStrategy.SIMULTANEOUS)
    }

    /**
     * Switch the harness to conversational discussion mode.
     *
     * @return This Junction object for method chaining.
     */
    fun conversational(): Junction
    {
        return setStrategy(DiscussionStrategy.CONVERSATIONAL)
    }

    /**
     * Switch the harness to round-robin discussion mode.
     *
     * @return This Junction object for method chaining.
     */
    fun roundRobin(): Junction
    {
        return setStrategy(DiscussionStrategy.ROUND_ROBIN)
    }

    /**
     * Set the maximum number of rounds.
     *
     * @param rounds Maximum number of discussion rounds to run.
     * @return This Junction object for method chaining.
     */
    fun setRounds(rounds: Int): Junction
    {
        require(rounds > 0) { "Junction must have at least one round." }
        discussionState.maxRounds = rounds
        workflowState.maxCycles = rounds
        return this
    }

    /**
     * Set the consensus threshold.
     *
     * @param threshold Threshold expressed as a ratio from 0.0 to 1.0.
     * @return This Junction object for method chaining.
     */
    fun setVotingThreshold(threshold: Double): Junction
    {
        require(threshold > 0.0 && threshold <= 1.0) { "Voting threshold must be greater than 0.0 and at most 1.0." }
        discussionState.consensusThreshold = threshold
        return this
    }

    /**
     * Enable or disable moderator intervention.
     *
     * @param enabled Whether the moderator may override or end the discussion.
     * @return This Junction object for method chaining.
     */
    fun setModeratorIntervention(enabled: Boolean): Junction
    {
        moderatorInterventionEnabled = enabled
        return this
    }

    /**
     * Set the maximum nested P2P depth allowed during execution.
     *
     * @param depth Maximum nested dispatch depth.
     * @return This Junction object for method chaining.
     */
    fun setMaxNestedDepth(depth: Int): Junction
    {
        require(depth > 0) { "Nested depth must be greater than zero." }
        defaultMaxNestedDepth = depth
        discussionState.maxNestedDepth = depth
        return this
    }

    /**
     * Enable tracing for this Junction.
     *
     * @param config The tracing configuration to use.
     * @return This Junction object for method chaining.
     */
    fun enableTracing(config: TraceConfig = TraceConfig(enabled = true)): Junction
    {
        tracingEnabled = true
        traceConfig = config
        PipeTracer.enable()
        return this
    }

    /**
     * Disable tracing for this Junction.
     *
     * @return This Junction object for method chaining.
     */
    fun disableTracing(): Junction
    {
        tracingEnabled = false
        return this
    }

    /**
     * Clear only the runtime state that belongs to the currently executing harness run.
     *
     * This preserves the Junction configuration, binding graph, trace configuration, and workflow recipe while
     * releasing per-run data such as round logs, vote state, workflow phase results, and pause tokens.
     */
    fun clearRuntimeState()
    {
        resetRuntimeState()
    }

    /**
     * Clear the accumulated trace history for this Junction instance.
     *
     * The trace configuration remains untouched so the harness can keep tracing after a manual clear.
     */
    fun clearTrace()
    {
        PipeTracer.clearTrace(junctionId)
    }

    /**
     * Request a pause at the next safe checkpoint.
     *
     * The harness checks this flag between rounds and before the next participant dispatch.
     */
    fun pause()
    {
        isPaused = true
        trace(TraceEventType.JUNCTION_PAUSE, TracePhase.ORCHESTRATION, metadata = mapOf("requested" to true))
    }

    /**
     * Resume the harness if a pause has been requested.
     */
    fun resume()
    {
        isPaused = false
        resumeSignal.trySend(Unit)
        trace(TraceEventType.JUNCTION_RESUME, TracePhase.ORCHESTRATION, metadata = mapOf("requested" to false))
    }

    /**
     * Check whether a pause has been requested.
     *
     * @return True when the harness is waiting for resume.
     */
    fun isPaused(): Boolean
    {
        return isPaused
    }

    /**
     * Check whether the harness can be paused.
     *
     * @return True when the harness has a moderator or participant loop that can honor pause checkpoints.
     */
    fun canPause(): Boolean
    {
        return allBindings().isNotEmpty()
    }

    /**
     * Select the workflow recipe that Junction should execute.
     *
     * @param recipe The workflow recipe to use.
     * @return This Junction object for method chaining.
     */
    fun setWorkflowRecipe(recipe: JunctionWorkflowRecipe): Junction
    {
        workflowRecipe = recipe
        workflowState.recipe = recipe
        workflowState.phaseOrder = recipe.phases().toMutableList()
        workflowState.handoffOnly = recipe.endsWithHandoff()
        return this
    }

    /**
     * Switch Junction back to discussion-only execution.
     *
     * @return This Junction object for method chaining.
     */
    fun discussionOnly(): Junction
    {
        return setWorkflowRecipe(JunctionWorkflowRecipe.DISCUSSION_ONLY)
    }

    /**
     * Switch Junction to the vote -> act -> verify -> repeat workflow recipe.
     *
     * @return This Junction object for method chaining.
     */
    fun voteActVerifyRepeat(): Junction
    {
        return setWorkflowRecipe(JunctionWorkflowRecipe.VOTE_ACT_VERIFY_REPEAT)
    }

    /**
     * Switch Junction to the act -> vote -> verify -> repeat workflow recipe.
     *
     * @return This Junction object for method chaining.
     */
    fun actVoteVerifyRepeat(): Junction
    {
        return setWorkflowRecipe(JunctionWorkflowRecipe.ACT_VOTE_VERIFY_REPEAT)
    }

    /**
     * Switch Junction to the vote -> plan -> act -> verify -> repeat workflow recipe.
     *
     * @return This Junction object for method chaining.
     */
    fun votePlanActVerifyRepeat(): Junction
    {
        return setWorkflowRecipe(JunctionWorkflowRecipe.VOTE_PLAN_ACT_VERIFY_REPEAT)
    }

    /**
     * Switch Junction to the plan -> vote -> act -> verify -> repeat workflow recipe.
     *
     * @return This Junction object for method chaining.
     */
    fun planVoteActVerifyRepeat(): Junction
    {
        return setWorkflowRecipe(JunctionWorkflowRecipe.PLAN_VOTE_ACT_VERIFY_REPEAT)
    }

    /**
     * Switch Junction to the vote -> plan -> output -> exit workflow recipe.
     *
     * @return This Junction object for method chaining.
     */
    fun votePlanOutputExit(): Junction
    {
        return setWorkflowRecipe(JunctionWorkflowRecipe.VOTE_PLAN_OUTPUT_EXIT)
    }

    /**
     * Switch Junction to the plan -> vote -> adjust -> output -> exit workflow recipe.
     *
     * @return This Junction object for method chaining.
     */
    fun planVoteAdjustOutputExit(): Junction
    {
        return setWorkflowRecipe(JunctionWorkflowRecipe.PLAN_VOTE_ADJUST_OUTPUT_EXIT)
    }

    /**
     * Set the planner role for workflow-oriented Junction execution.
     *
     * @param roleName Stable planner routing name.
     * @param component The P2P-capable planner container or pipeline.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable planner description.
     * @return This Junction object for method chaining.
     */
    fun setPlanner(
        roleName: String,
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    ): Junction
    {
        plannerBinding = registerWorkflowBinding(
            roleName = roleName,
            component = component,
            kind = JunctionBindingKind.PLANNER,
            descriptor = descriptor,
            requirements = requirements,
            description = description.ifBlank { "Planner container for Junction workflow setup" }
        )
        return this
    }

    /**
     * Set the planner role using a generated routing name.
     *
     * @param component The P2P-capable planner container or pipeline.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable planner description.
     * @return This Junction object for method chaining.
     */
    fun setPlanner(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    ): Junction
    {
        return setPlanner(
            roleName = resolveRoleName(component, "junction-planner"),
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Set the actor role for workflow-oriented Junction execution.
     *
     * @param roleName Stable actor routing name.
     * @param component The P2P-capable actor container or pipeline.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable actor description.
     * @return This Junction object for method chaining.
     */
    fun setActor(
        roleName: String,
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    ): Junction
    {
        actorBinding = registerWorkflowBinding(
            roleName = roleName,
            component = component,
            kind = JunctionBindingKind.ACTOR,
            descriptor = descriptor,
            requirements = requirements,
            description = description.ifBlank { "Actor container for Junction workflow execution" }
        )
        return this
    }

    /**
     * Set the actor role using a generated routing name.
     *
     * @param component The P2P-capable actor container or pipeline.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable actor description.
     * @return This Junction object for method chaining.
     */
    fun setActor(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    ): Junction
    {
        return setActor(
            roleName = resolveRoleName(component, "junction-actor"),
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Set the verifier role for workflow-oriented Junction execution.
     *
     * @param roleName Stable verifier routing name.
     * @param component The P2P-capable verifier container or pipeline.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable verifier description.
     * @return This Junction object for method chaining.
     */
    fun setVerifier(
        roleName: String,
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    ): Junction
    {
        verifierBinding = registerWorkflowBinding(
            roleName = roleName,
            component = component,
            kind = JunctionBindingKind.VERIFIER,
            descriptor = descriptor,
            requirements = requirements,
            description = description.ifBlank { "Verifier container for Junction workflow verification" }
        )
        return this
    }

    /**
     * Set the verifier role using a generated routing name.
     *
     * @param component The P2P-capable verifier container or pipeline.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable verifier description.
     * @return This Junction object for method chaining.
     */
    fun setVerifier(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    ): Junction
    {
        return setVerifier(
            roleName = resolveRoleName(component, "junction-verifier"),
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Set the adjuster role for workflow-oriented Junction execution.
     *
     * @param roleName Stable adjuster routing name.
     * @param component The P2P-capable adjuster container or pipeline.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable adjuster description.
     * @return This Junction object for method chaining.
     */
    fun setAdjuster(
        roleName: String,
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    ): Junction
    {
        adjusterBinding = registerWorkflowBinding(
            roleName = roleName,
            component = component,
            kind = JunctionBindingKind.ADJUSTER,
            descriptor = descriptor,
            requirements = requirements,
            description = description.ifBlank { "Adjuster container for Junction workflow refinement" }
        )
        return this
    }

    /**
     * Set the adjuster role using a generated routing name.
     *
     * @param component The P2P-capable adjuster container or pipeline.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable adjuster description.
     * @return This Junction object for method chaining.
     */
    fun setAdjuster(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    ): Junction
    {
        return setAdjuster(
            roleName = resolveRoleName(component, "junction-adjuster"),
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Set the output role for workflow-oriented Junction execution.
     *
     * @param roleName Stable output routing name.
     * @param component The P2P-capable output container or pipeline.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable output description.
     * @return This Junction object for method chaining.
     */
    fun setOutputHandler(
        roleName: String,
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    ): Junction
    {
        outputBinding = registerWorkflowBinding(
            roleName = roleName,
            component = component,
            kind = JunctionBindingKind.OUTPUT,
            descriptor = descriptor,
            requirements = requirements,
            description = description.ifBlank { "Output container for Junction workflow handoff" }
        )
        return this
    }

    /**
     * Set the output role using a generated routing name.
     *
     * @param component The P2P-capable output container or pipeline.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable output description.
     * @return This Junction object for method chaining.
     */
    fun setOutputHandler(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    ): Junction
    {
        return setOutputHandler(
            roleName = resolveRoleName(component, "junction-output"),
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Prepare the Junction for execution.
     *
     * @throws IllegalStateException When the moderator or participants are missing or invalid.
     * @return This Junction object for method chaining.
     */
    suspend fun init(): Junction
    {
        // Validate the harness contract up front so discussion and workflow failures happen during setup,
        // not halfway through a round or phase dispatch.
        require(moderatorBinding != null) { "Junction requires a moderator before init()." }
        require(participantBindings.isNotEmpty()) { "Junction requires at least one participant before init()." }
        require(discussionState.maxRounds > 0) { "Junction must have at least one round." }
        require(discussionState.consensusThreshold > 0.0 && discussionState.consensusThreshold <= 1.0) {
            "Junction consensus threshold must be greater than 0.0 and at most 1.0."
        }
        require(defaultMaxNestedDepth > 0) { "Junction nested depth must be greater than zero." }

        // Keep role names unique even when they are generated automatically, because those names are reused
        // for routing, trace grouping, voting tallies, and workflow metadata.
        val duplicateNames = participantBindings
            .groupBy { binding -> binding.roleName }
            .filter { entry -> entry.value.size > 1 }
            .keys
        require(duplicateNames.isEmpty()) {
            "Duplicate participant names are not allowed: ${duplicateNames.joinToString()}"
        }

        // Validate the ancestry graph before any execution begins so nested P2P containers fail fast if they
        // form a cycle or exceed the maximum supported nesting depth.
        validateParticipantGraphs()
        validateWorkflowGraphs()
        discussionState.maxNestedDepth = defaultMaxNestedDepth
        workflowState.recipe = workflowRecipe
        workflowState.phaseOrder = workflowRecipe.phases().toMutableList()
        workflowState.maxCycles = discussionState.maxRounds
        workflowState.handoffOnly = workflowRecipe.endsWithHandoff()
        return this
    }

    /**
     * Execute the Junction discussion and return the final multimodal result.
     *
     * @param content The input content that seeds the discussion topic.
     * @return The final discussion content with structured decision metadata.
     */
    suspend fun execute(content: MultimodalContent): MultimodalContent
    {
        // Every execution starts from a clean runtime snapshot so repeated runs cannot inherit stale pause
        // state, round counters, workflow cycles, or queued resume signals.
        resetRuntimeState()
        init()

        // Discussion-only execution and workflow execution share the same harness entrypoint, but the workflow
        // recipe layer needs its own orchestration loop because it may repeat, hand off, or short-circuit.
        if(workflowRecipe != JunctionWorkflowRecipe.DISCUSSION_ONLY)
        {
            return executeWorkflow(content)
        }

        if(tracingEnabled)
        {
            PipeTracer.startTrace(junctionId)
            trace(
                TraceEventType.JUNCTION_START,
                TracePhase.ORCHESTRATION,
                content,
                metadata = mapOf(
                    "participantCount" to participantBindings.size,
                    "strategy" to discussionState.strategy.name,
                    "roundLimit" to discussionState.maxRounds
                )
            )
        }

        val workingContent = content.deepCopy()
        // The topic is always resolved from the current content first, then metadata, so the harness can be
        // seeded from either a direct topic string or a higher-level caller that already attached metadata.
        val topic = workingContent.text.ifBlank {
            workingContent.metadata["topic"]?.toString() ?: "Untitled Junction Discussion"
        }

        discussionState = discussionState.copy(
            topic = topic,
            strategy = discussionState.strategy,
            currentRound = 0,
            consensusReached = false,
            finalDecision = "",
            moderatorNotes = "",
            participantOpinions = mutableMapOf(),
            voteResults = mutableListOf(),
            roundLog = mutableListOf()
        )

        workingContent.metadata["junctionId"] = junctionId
        workingContent.metadata["junctionState"] = discussionState.deepCopy()

        while(discussionState.currentRound < discussionState.maxRounds && !discussionState.consensusReached)
        {
            // Pause checkpoints only happen between rounds so in-flight participant dispatches never resume in
            // the middle of a vote tally or half-built decision.
            awaitPauseCheckpoint(workingContent)

            discussionState.currentRound++
            discussionState.roundLog.add("Round ${discussionState.currentRound} started.")

            trace(
                TraceEventType.JUNCTION_ROUND_START,
                TracePhase.ORCHESTRATION,
                workingContent,
                metadata = mapOf(
                    "round" to discussionState.currentRound,
                    "strategy" to discussionState.strategy.name
                )
            )

            val roundParticipants = resolveRoundParticipants()
            // Each round fans out to the current participant subset for the selected strategy, then the
            // resulting opinions are normalized into the shared state before any moderator decision is applied.
            val participantOpinions = runParticipantRound(
                roundParticipants,
                workingContent,
                discussionState.currentRound
            )

            participantOpinions.forEach { opinion ->
                discussionState.participantOpinions[opinion.participantName] = opinion
            }

            val voteResults = tallyVotes(participantOpinions)
            discussionState.voteResults = voteResults.toMutableList()
            discussionState.consensusReached = voteResults.firstOrNull()?.thresholdMet == true

            trace(
                TraceEventType.JUNCTION_VOTE_TALLY,
                TracePhase.ORCHESTRATION,
                metadata = mapOf(
                    "round" to discussionState.currentRound,
                    "voteResults" to voteResults.map { result ->
                        mapOf(
                            "option" to result.option,
                            "weight" to result.weight,
                            "percentage" to result.percentage,
                            "thresholdMet" to result.thresholdMet
                        )
                    }
                )
            )

            val moderatorDirective = if(moderatorInterventionEnabled)
            {
                resolveModeratorDirective(workingContent, participantOpinions, voteResults)
            }
            else
            {
                // When moderator intervention is disabled, the harness still needs a directive-like object so
                // the rest of the discussion loop can keep using one control path.
                buildDefaultDirective(voteResults)
            }

            discussionState.moderatorNotes = moderatorDirective.notes
            if(moderatorDirective.selectedParticipants.isNotEmpty())
            {
                discussionState.selectedParticipants = moderatorDirective.selectedParticipants.toMutableList()
            }

            if(moderatorDirective.finalDecision.isNotBlank())
            {
                discussionState.finalDecision = moderatorDirective.finalDecision
            }

            if(moderatorDirective.continueDiscussion.not())
            {
                discussionState.consensusReached = true
            }

            if(discussionState.consensusReached)
            {
                discussionState.finalDecision = discussionState.finalDecision.ifBlank {
                    voteResults.firstOrNull()?.option ?: topic
                }
            }

            trace(
                TraceEventType.JUNCTION_CONSENSUS_CHECK,
                TracePhase.ORCHESTRATION,
                metadata = mapOf(
                    "round" to discussionState.currentRound,
                    "consensusReached" to discussionState.consensusReached,
                    "finalDecision" to discussionState.finalDecision
                )
            )

            trace(
                TraceEventType.JUNCTION_ROUND_END,
                TracePhase.ORCHESTRATION,
                metadata = mapOf(
                    "round" to discussionState.currentRound,
                    "consensusReached" to discussionState.consensusReached
                )
            )

            if(!discussionState.consensusReached && discussionState.currentRound < discussionState.maxRounds)
            {
                awaitPauseCheckpoint(workingContent)
            }
        }

        if(discussionState.finalDecision.isBlank())
        {
            discussionState.finalDecision = discussionState.voteResults.firstOrNull()?.option ?: topic
        }

        val decision = DiscussionDecision(
            topic = discussionState.topic,
            decision = discussionState.finalDecision,
            consensusReached = discussionState.consensusReached,
            roundsExecuted = discussionState.currentRound,
            strategy = discussionState.strategy,
            moderatorNotes = discussionState.moderatorNotes,
            participantOpinions = discussionState.participantOpinions.toMutableMap(),
            voteResults = discussionState.voteResults.toMutableList()
        )

        workingContent.text = serialize(decision)
        workingContent.metadata["junctionState"] = discussionState.deepCopy()
        workingContent.metadata["junctionDecision"] = decision.deepCopy()
        workingContent.passPipeline = true

        if(tracingEnabled)
        {
            trace(
                if(discussionState.consensusReached) TraceEventType.JUNCTION_SUCCESS else TraceEventType.JUNCTION_FAILURE,
                TracePhase.CLEANUP,
                workingContent,
                metadata = mapOf(
                    "roundsExecuted" to discussionState.currentRound,
                    "consensusReached" to discussionState.consensusReached,
                    "decision" to discussionState.finalDecision
                )
            )

            trace(TraceEventType.JUNCTION_END, TracePhase.CLEANUP, workingContent)
        }

        return workingContent
    }

    /**
     * Execute the configured workflow recipe and return the final multimodal result.
     *
     * @param content The input content that seeds the workflow.
     * @return The final workflow content with structured outcome metadata.
     */
    private suspend fun executeWorkflow(content: MultimodalContent): MultimodalContent
    {
        if(tracingEnabled)
        {
            // Workflow traces use their own start/end events because the recipe engine can stop at handoff,
            // repeat after verification, or emit a final output step that discussion-only Junction never sees.
            PipeTracer.startTrace(junctionId)
            trace(
                TraceEventType.JUNCTION_WORKFLOW_START,
                TracePhase.ORCHESTRATION,
                content,
                metadata = mapOf(
                    "recipe" to workflowRecipe.name,
                    "participantCount" to participantBindings.size,
                    "maxCycles" to discussionState.maxRounds
                )
            )
        }

        val workingContent = content.deepCopy()
        // Workflow state is reset independently from discussion state so repeated recipe cycles do not inherit
        // stale phase text or verification flags from a previous run.
        val topic = workingContent.text.ifBlank {
            workingContent.metadata["topic"]?.toString() ?: "Untitled Junction Workflow"
        }

        discussionState = discussionState.copy(
            topic = topic,
            strategy = discussionState.strategy,
            currentRound = 0,
            consensusReached = false,
            finalDecision = "",
            moderatorNotes = "",
            participantOpinions = mutableMapOf(),
            voteResults = mutableListOf(),
            roundLog = mutableListOf()
        )

        workflowState = JunctionWorkflowState(
            recipe = workflowRecipe,
            currentCycle = 0,
            maxCycles = discussionState.maxRounds,
            phaseOrder = workflowRecipe.phases().toMutableList(),
            completed = false,
            handoffOnly = workflowRecipe.endsWithHandoff(),
            repeatRequested = false,
            verificationPassed = true
        )

        // If no explicit actor exists, the recipe is effectively handoff-only because Junction should not
        // invent a side-effectful action step on behalf of the caller.
        workflowState.handoffOnly = workflowState.handoffOnly || actorBinding == null

        workingContent.metadata["junctionId"] = junctionId
        workingContent.metadata["junctionWorkflowState"] = workflowState.deepCopy()

        while(workflowState.currentCycle < workflowState.maxCycles && !workflowState.completed)
        {
            // Pause checkpoints are also honored between workflow cycles so the caller can safely resume on
            // the next cycle boundary without losing the current phase state.
            awaitPauseCheckpoint(workingContent)
            workflowState.currentCycle++
            workflowState.repeatRequested = false
            workflowState.verificationPassed = true
            workflowState.roundLog.add("Workflow cycle ${workflowState.currentCycle} started.")

            val cycleResults = mutableListOf<JunctionWorkflowPhaseResult>()
            var terminationRequested = false

            for(phase in workflowState.phaseOrder)
            {
                // Each phase is traced separately so the visualizer can reconstruct the recipe, including the
                // cases where a phase falls back to a synthesized result instead of a bound P2P participant.
                awaitPauseCheckpoint(workingContent)
                trace(
                    TraceEventType.JUNCTION_PHASE_START,
                    TracePhase.ORCHESTRATION,
                    workingContent,
                    metadata = mapOf(
                        "cycle" to workflowState.currentCycle,
                        "phase" to phase.name
                    )
                )
                val result = executeWorkflowPhase(phase, workingContent, workflowState.currentCycle)
                cycleResults.add(result)
                recordWorkflowPhaseResult(result)

                trace(
                    TraceEventType.JUNCTION_PHASE_END,
                    TracePhase.ORCHESTRATION,
                    workingContent,
                    metadata = mapOf(
                        "cycle" to workflowState.currentCycle,
                        "phase" to phase.name,
                        "passed" to result.passed,
                        "repeatRequested" to result.repeatRequested,
                        "terminateRequested" to result.terminateRequested
                    )
                )

                if(result.terminateRequested)
                {
                    // Termination wins over repeat semantics so a phase can halt the recipe immediately when
                    // the actor, verifier, or output step asks for a hard stop.
                    terminationRequested = true
                    break
                }
            }

            workflowState.repeatRequested = shouldRepeatWorkflowCycle(cycleResults)
            if(terminationRequested)
            {
                workflowState.repeatRequested = false
                workflowState.completed = false
                break
            }

            if(!workflowState.repeatRequested)
            {
                workflowState.completed = true
                break
            }

            if(workflowState.currentCycle >= workflowState.maxCycles)
            {
                break
            }
        }

        val finalOutput = finalizeWorkflowOutput(workingContent)
        val outcome = buildWorkflowOutcome(topic, finalOutput)
        finalOutput.text = serialize(outcome)
        finalOutput.metadata["junctionWorkflowState"] = workflowState.deepCopy()
        finalOutput.metadata["junctionWorkflowOutcome"] = outcome.deepCopy()

        if(workflowState.completed)
        {
            finalOutput.passPipeline = true
        }
        else
        {
            finalOutput.terminatePipeline = true
        }

        if(tracingEnabled)
        {
            trace(
                if(workflowState.completed) TraceEventType.JUNCTION_WORKFLOW_SUCCESS else TraceEventType.JUNCTION_WORKFLOW_FAILURE,
                TracePhase.CLEANUP,
                finalOutput,
                metadata = mapOf(
                    "recipe" to workflowState.recipe.name,
                    "cyclesExecuted" to workflowState.currentCycle,
                    "completed" to workflowState.completed,
                    "handoffOnly" to workflowState.handoffOnly
                )
            )

            trace(
                TraceEventType.JUNCTION_WORKFLOW_END,
                TracePhase.CLEANUP,
                finalOutput,
                metadata = mapOf(
                    "recipe" to workflowState.recipe.name,
                    "completed" to workflowState.completed
                )
            )
        }

        return finalOutput
    }

    /**
     * Execute the Junction discussion through the shorter semantic name used by the docs.
     *
     * @param content The input content that seeds the discussion topic.
     * @return The final discussion content with structured decision metadata.
     */
    suspend fun conductDiscussion(content: MultimodalContent): MultimodalContent
    {
        return execute(content)
    }

    /**
     * Execute the configured Junction workflow and return the resulting content.
     *
     * @param content The input content that seeds the workflow.
     * @return The final workflow content.
     */
    suspend fun conductWorkflow(content: MultimodalContent): MultimodalContent
    {
        require(workflowRecipe != JunctionWorkflowRecipe.DISCUSSION_ONLY) {
            "Junction is currently configured for discussion-only execution."
        }

        return execute(content)
    }

    /**
     * Gets trace report for this Junction in the specified format.
     *
     * @param format Output format for the trace report.
     * @return Formatted trace report string.
     */
    fun getTraceReport(format: TraceFormat = traceConfig.outputFormat): String
    {
        return PipeTracer.exportTrace(junctionId, format)
    }

    /**
     * Gets failure analysis for this Junction if tracing is enabled.
     *
     * @return Failure analysis or null when tracing is disabled.
     */
    fun getFailureAnalysis(): FailureAnalysis?
    {
        return if(tracingEnabled) PipeTracer.getFailureAnalysis(junctionId) else null
    }

    /**
     * Gets the unique trace ID for this Junction.
     *
     * @return Junction trace ID string.
     */
    fun getTraceId(): String = junctionId

//----------------------------------------------Internal Helpers---------------------------------------------------------

    private fun allBindings(): List<JunctionBinding>
    {
        // A single ordered view of every binding makes it easier to keep uniqueness checks, trace metadata,
        // and graph validation aligned across both discussion and workflow roles.
        val bindings = mutableListOf<JunctionBinding>()
        moderatorBinding?.let { bindings.add(it) }
        bindings.addAll(participantBindings)
        plannerBinding?.let { bindings.add(it) }
        actorBinding?.let { bindings.add(it) }
        verifierBinding?.let { bindings.add(it) }
        adjusterBinding?.let { bindings.add(it) }
        outputBinding?.let { bindings.add(it) }
        return bindings
    }

    private fun allBindingNames(): Set<String>
    {
        return allBindings().map { binding -> binding.roleName }.toSet()
    }

    private fun bindContainerReference(component: P2PInterface)
    {
        // Preserve an existing container owner if the component already belongs to a larger harness graph;
        // Junction only claims the container slot when it has not already been assigned.
        if(component.getContainerObject() == null)
        {
            component.setContainerObject(this)
        }
    }

    private fun registerWorkflowBinding(
        roleName: String,
        component: P2PInterface,
        kind: JunctionBindingKind,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    ): JunctionBinding
    {
        require(component !== this) { "Junction cannot register itself as a workflow participant." }
        require(roleName.isNotBlank()) { "Workflow role names must not be blank." }
        require(roleName !in allBindingNames()) { "Binding '$roleName' is already registered." }

        // Workflow roles are normalized through the same binding model as discussion roles so the harness can
        // synthesize descriptors, requirements, and trace output consistently for any P2P participant.
        val binding = buildBinding(
            roleName = roleName,
            component = component,
            kind = kind,
            descriptor = descriptor,
            requirements = requirements,
            description = description,
            weight = 1.0
        )

        bindContainerReference(component)

        when(kind)
        {
            JunctionBindingKind.MODERATOR ->
            {
                moderatorBinding = binding
            }

            JunctionBindingKind.PARTICIPANT ->
            {
                participantBindings.add(binding)
                participantBindingsByName[roleName] = binding
            }

            JunctionBindingKind.PLANNER ->
            {
                plannerBinding = binding
            }

            JunctionBindingKind.ACTOR ->
            {
                actorBinding = binding
            }

            JunctionBindingKind.VERIFIER ->
            {
                verifierBinding = binding
            }

            JunctionBindingKind.ADJUSTER ->
            {
                adjusterBinding = binding
            }

            JunctionBindingKind.OUTPUT ->
            {
                outputBinding = binding
            }
        }

        return binding
    }

    private fun validateParticipantGraphs()
    {
        // Validate discussion participants and the moderator together so any container ancestry problem is
        // discovered before Junction starts dispatching votes.
        listOfNotNull(moderatorBinding).plus(participantBindings).forEach { binding ->
            validateContainerAncestry("participant '${binding.roleName}'", binding.component)
        }
    }

    private fun validateWorkflowGraphs()
    {
        // Workflow bindings are checked separately because a recipe can bind different P2P containers for the
        // planner, actor, verifier, adjuster, and output steps.
        listOfNotNull(plannerBinding, actorBinding, verifierBinding, adjusterBinding, outputBinding)
            .forEach { binding ->
                validateContainerAncestry("workflow binding '${binding.roleName}'", binding.component)
            }
    }

    private suspend fun executeWorkflowPhase(
        phase: JunctionWorkflowPhase,
        workingContent: MultimodalContent,
        cycleNumber: Int
    ): JunctionWorkflowPhaseResult
    {
        // Each phase either dispatches to a bound P2P participant or falls back to a synthesized, safe
        // default so the recipe can still finish in configurations that intentionally omit a role.
        return when(phase)
        {
            JunctionWorkflowPhase.PLAN ->
            {
                runWorkflowBindingPhase(
                    phase = phase,
                    binding = plannerBinding ?: moderatorBinding,
                    workingContent = workingContent,
                    cycleNumber = cycleNumber,
                    defaultResult = buildDefaultWorkflowPhaseResult(
                        phase = phase,
                        cycleNumber = cycleNumber,
                        participantName = (plannerBinding ?: moderatorBinding)?.roleName ?: "planner",
                        text = buildDefaultPlanText(cycleNumber),
                        passed = true,
                        repeatRequested = false,
                        terminateRequested = false,
                        notes = "Planner fallback synthesized from the current topic and workflow state."
                    )
                )
            }

            JunctionWorkflowPhase.VOTE ->
            {
                runVoteWorkflowPhase(workingContent, cycleNumber)
            }

            JunctionWorkflowPhase.ACT ->
            {
                runWorkflowBindingPhase(
                    phase = phase,
                    binding = actorBinding,
                    workingContent = workingContent,
                    cycleNumber = cycleNumber,
                    defaultResult = buildDefaultWorkflowPhaseResult(
                        phase = phase,
                        cycleNumber = cycleNumber,
                        participantName = actorBinding?.roleName ?: "actor",
                        text = buildDefaultActText(cycleNumber),
                        passed = true,
                        repeatRequested = false,
                        terminateRequested = false,
                        notes = if(actorBinding == null)
                        {
                            "No actor was configured, so Junction produced handoff instructions."
                        }
                        else
                        {
                            "Actor fallback synthesized a safe execution summary."
                        }
                    )
                )
            }

            JunctionWorkflowPhase.VERIFY ->
            {
                runWorkflowBindingPhase(
                    phase = phase,
                    binding = verifierBinding,
                    workingContent = workingContent,
                    cycleNumber = cycleNumber,
                    defaultResult = buildDefaultWorkflowPhaseResult(
                        phase = phase,
                        cycleNumber = cycleNumber,
                        participantName = verifierBinding?.roleName ?: "verifier",
                        text = buildDefaultVerifyText(),
                        passed = true,
                        repeatRequested = false,
                        terminateRequested = false,
                        notes = "Verification fallback accepted the workflow state."
                    )
                )
            }

            JunctionWorkflowPhase.ADJUST ->
            {
                runWorkflowBindingPhase(
                    phase = phase,
                    binding = adjusterBinding,
                    workingContent = workingContent,
                    cycleNumber = cycleNumber,
                    defaultResult = buildDefaultWorkflowPhaseResult(
                        phase = phase,
                        cycleNumber = cycleNumber,
                        participantName = adjusterBinding?.roleName ?: "adjuster",
                        text = buildDefaultAdjustText(),
                        passed = true,
                        repeatRequested = false,
                        terminateRequested = false,
                        notes = "Adjustment fallback refined the workflow output locally."
                    )
                )
            }

            JunctionWorkflowPhase.OUTPUT ->
            {
                runWorkflowBindingPhase(
                    phase = phase,
                    binding = outputBinding,
                    workingContent = workingContent,
                    cycleNumber = cycleNumber,
                    defaultResult = buildDefaultWorkflowPhaseResult(
                        phase = phase,
                        cycleNumber = cycleNumber,
                        participantName = outputBinding?.roleName ?: "output",
                        text = buildWorkflowSummaryText(),
                        passed = true,
                        repeatRequested = false,
                        terminateRequested = false,
                        notes = "Output fallback produced the final handoff text."
                    )
                )
            }
        }
    }

    private suspend fun runVoteWorkflowPhase(
        workingContent: MultimodalContent,
        cycleNumber: Int
    ): JunctionWorkflowPhaseResult
    {
        // Voting reuses the discussion engine so recipe execution and discussion-only execution stay aligned
        // on participant selection, opinion parsing, and consensus semantics.
        discussionState.currentRound = cycleNumber

        val roundParticipants = resolveRoundParticipants()
        val participantOpinions = runParticipantRound(roundParticipants, workingContent, cycleNumber)

        participantOpinions.forEach { opinion ->
            discussionState.participantOpinions[opinion.participantName] = opinion
        }

        val voteResults = tallyVotes(participantOpinions)
        discussionState.voteResults = voteResults.toMutableList()
        discussionState.consensusReached = voteResults.firstOrNull()?.thresholdMet == true

        val decision = buildDiscussionDecision()
        workflowState.discussionDecision = decision
        workflowState.voteText = serialize(decision)

        return JunctionWorkflowPhaseResult(
            phase = JunctionWorkflowPhase.VOTE,
            cycleNumber = cycleNumber,
            participantName = "participants",
            text = serialize(decision),
            instructions = decision.decision,
            passed = discussionState.consensusReached,
            repeatRequested = workflowRecipe.repeats() && !discussionState.consensusReached,
            terminateRequested = false,
            notes = if(discussionState.consensusReached)
            {
                "Consensus reached in vote phase."
            }
            else
            {
                "Vote phase did not reach consensus yet."
            },
            rawOutput = serialize(decision)
        )
    }

    private suspend fun runWorkflowBindingPhase(
        phase: JunctionWorkflowPhase,
        binding: JunctionBinding?,
        workingContent: MultimodalContent,
        cycleNumber: Int,
        defaultResult: JunctionWorkflowPhaseResult
    ): JunctionWorkflowPhaseResult
    {
        if(binding == null)
        {
            // Missing workflow roles are not treated as hard failures because some recipes intentionally use
            // Junction's safe defaults to produce handoff text instead of executing a bound container.
            return defaultResult
        }

        val request = P2PRequest(
            transport = binding.transport,
            returnAddress = p2pTransport ?: P2PTransport(),
            prompt = buildWorkflowPhasePrompt(binding, phase, workingContent, cycleNumber)
        )

        return try
        {
            // The participant sees a fully rendered prompt that includes the current discussion and workflow
            // state, then Junction tries to deserialize the response back into a phase result.
            val response = binding.component.executeP2PRequest(request)
            val output = response?.output?.text.orEmpty()
            val parsed = deserialize<JunctionWorkflowPhaseResult>(output)
            val result = parsed?.copy(
                phase = phase,
                cycleNumber = cycleNumber,
                participantName = binding.roleName.ifBlank { parsed.participantName.ifBlank { binding.roleName } },
                repeatRequested = parsed.repeatRequested || (response?.output?.repeatPipe == true),
                terminateRequested = parsed.terminateRequested || (response?.output?.terminatePipeline == true),
                rawOutput = output
            ) ?: defaultResult.copy(
                participantName = binding.roleName,
                rawOutput = output,
                text = output.ifBlank { defaultResult.text },
                repeatRequested = defaultResult.repeatRequested || (response?.output?.repeatPipe == true),
                terminateRequested = defaultResult.terminateRequested || (response?.output?.terminatePipeline == true)
            )

            val normalizedResult = result.copy(
                passed = result.passed && response?.output?.terminatePipeline != true,
                repeatRequested = result.repeatRequested || (response?.output?.repeatPipe == true),
                terminateRequested = result.terminateRequested || (response?.output?.terminatePipeline == true)
            )

            when(phase)
            {
                JunctionWorkflowPhase.VERIFY ->
                {
                    // Verification updates the workflow's repeat gate because a failed verify step should force
                    // another cycle even when the recipe itself is repeatable.
                    workflowState.verificationPassed = normalizedResult.passed
                    if(normalizedResult.repeatRequested)
                    {
                        workflowState.repeatRequested = true
                    }
                }

                JunctionWorkflowPhase.OUTPUT ->
                {
                    // Output is cached separately because handoff recipes need the final instructions even when
                    // the content text is later replaced by a serialized outcome object.
                    workflowState.outputText = normalizedResult.text.ifBlank { normalizedResult.instructions }
                }

                else ->
                {
                    // No extra state mutation needed beyond the record helper.
                }
            }

            normalizedResult
        }
        catch(e: Exception)
        {
            // Any participant failure is converted into a structured phase result so the recipe can stop
            // cleanly, trace the error, and preserve the workflow state for inspection.
            trace(
                TraceEventType.JUNCTION_WORKFLOW_FAILURE,
                TracePhase.AGENT_COMMUNICATION,
                error = e,
                metadata = mapOf(
                    "cycle" to cycleNumber,
                    "phase" to phase.name,
                    "participant" to binding.roleName
                )
            )

            defaultResult.copy(
                participantName = binding.roleName,
                rawOutput = e.message ?: "",
                passed = false,
                terminateRequested = true,
                notes = e.message ?: "Workflow phase execution failed."
            )
        }
    }

    private fun buildWorkflowPhasePrompt(
        binding: JunctionBinding,
        phase: JunctionWorkflowPhase,
        workingContent: MultimodalContent,
        cycleNumber: Int
    ): MultimodalContent
    {
        // The prompt intentionally includes both the discussion state and the workflow state so a bound P2P
        // container can make a phase decision without needing to reconstruct Junction's internal state machine.
        val promptText = buildString {
            appendLine("You are the Junction ${phase.name.lowercase()} role.")
            appendLine("Return a JSON object matching JunctionWorkflowPhaseResult.")
            appendLine("Phase: ${phase.name}")
            appendLine("Cycle: $cycleNumber")
            appendLine("Workflow recipe: ${workflowRecipe.name}")
            appendLine("Role name: ${binding.roleName}")
            appendLine("Topic:")
            appendLine(discussionState.topic)
            appendLine("Current discussion state:")
            appendLine(serialize(discussionState))
            appendLine("Current workflow state:")
            appendLine(serialize(workflowState))
            appendLine("Latest content:")
            appendLine(workingContent.text)

            when(phase)
            {
                JunctionWorkflowPhase.PLAN ->
                {
                    appendLine("Plan the next actions. Summarize the approach and any dependencies.")
                }

                JunctionWorkflowPhase.ACT ->
                {
                    appendLine("Carry out the action or produce safe handoff instructions if side effects are not configured.")
                }

                JunctionWorkflowPhase.VERIFY ->
                {
                    appendLine("Verify the current workflow state. Set passed to true or false and request repeat when needed.")
                }

                JunctionWorkflowPhase.ADJUST ->
                {
                    appendLine("Refine the plan or action based on the current vote and verification state.")
                }

                JunctionWorkflowPhase.OUTPUT ->
                {
                    appendLine("Produce the final handoff instructions or output artifact for the caller.")
                }

                JunctionWorkflowPhase.VOTE ->
                {
                    appendLine("Vote is handled by the participants and should not be emitted here.")
                }
            }
        }

        return MultimodalContent(
            text = promptText,
            binaryContent = workingContent.binaryContent.toMutableList(),
            terminatePipeline = false
        )
    }

    private fun buildDefaultWorkflowPhaseResult(
        phase: JunctionWorkflowPhase,
        cycleNumber: Int,
        participantName: String,
        text: String,
        passed: Boolean,
        repeatRequested: Boolean,
        terminateRequested: Boolean,
        notes: String
    ): JunctionWorkflowPhaseResult
    {
        return JunctionWorkflowPhaseResult(
            phase = phase,
            cycleNumber = cycleNumber,
            participantName = participantName,
            text = text,
            instructions = text,
            passed = passed,
            repeatRequested = repeatRequested,
            terminateRequested = terminateRequested,
            notes = notes,
            rawOutput = text
        )
    }

    private fun buildDefaultPlanText(cycleNumber: Int): String
    {
        return buildString {
            appendLine("Plan for cycle $cycleNumber")
            appendLine("Topic: ${discussionState.topic}")
            appendLine("Focus on the vote results and the current workflow state.")
        }
    }

    private fun buildDefaultActText(cycleNumber: Int): String
    {
        return buildString {
            appendLine("Action for cycle $cycleNumber")
            appendLine("Execute or hand off the plan using the current workflow state.")
        }
    }

    private fun buildDefaultVerifyText(): String
    {
        return buildString {
            appendLine("Verification summary")
            appendLine("Confirm that the workflow output is ready for the next step.")
        }
    }

    private fun buildDefaultAdjustText(): String
    {
        return buildString {
            appendLine("Adjustment summary")
            appendLine("Refine the current plan based on the latest vote and verification state.")
        }
    }

    private fun buildWorkflowSummaryText(): String
    {
        // When no explicit output handler is configured, Junction synthesizes a human-readable summary from the
        // phase cache so the caller still receives a usable handoff artifact.
        return buildString {
            appendLine("Workflow summary for ${discussionState.topic.ifBlank { "the current topic" }}")
            appendLine("Recipe: ${workflowRecipe.name}")
            if(workflowState.planText.isNotBlank())
            {
                appendLine("Plan:")
                appendLine(workflowState.planText)
            }
            if(workflowState.voteText.isNotBlank())
            {
                appendLine("Vote:")
                appendLine(workflowState.voteText)
            }
            if(workflowState.actText.isNotBlank())
            {
                appendLine("Action:")
                appendLine(workflowState.actText)
            }
            if(workflowState.verifyText.isNotBlank())
            {
                appendLine("Verification:")
                appendLine(workflowState.verifyText)
            }
            if(workflowState.adjustText.isNotBlank())
            {
                appendLine("Adjustment:")
                appendLine(workflowState.adjustText)
            }
        }
    }

    private fun recordWorkflowPhaseResult(result: JunctionWorkflowPhaseResult)
    {
        // Phase results are cached into the workflow state as both machine-readable output and a concise log so
        // the final outcome can be reconstructed without replaying the entire trace.
        workflowState.phaseResults.add(result)
        workflowState.roundLog.add("${result.phase.name}: ${result.notes.ifBlank { result.text.take(120) }}")

        when(result.phase)
        {
            JunctionWorkflowPhase.PLAN ->
            {
                workflowState.planText = result.text.ifBlank { result.instructions }
            }

            JunctionWorkflowPhase.VOTE ->
            {
                workflowState.voteText = result.text.ifBlank { result.instructions }
                workflowState.repeatRequested = workflowState.repeatRequested || result.repeatRequested
            }

            JunctionWorkflowPhase.ACT ->
            {
                workflowState.actText = result.text.ifBlank { result.instructions }
            }

            JunctionWorkflowPhase.VERIFY ->
            {
                workflowState.verifyText = result.text.ifBlank { result.instructions }
                workflowState.verificationPassed = result.passed
                workflowState.repeatRequested = workflowState.repeatRequested || result.repeatRequested || !result.passed
            }

            JunctionWorkflowPhase.ADJUST ->
            {
                workflowState.adjustText = result.text.ifBlank { result.instructions }
            }

            JunctionWorkflowPhase.OUTPUT ->
            {
                workflowState.outputText = result.text.ifBlank { result.instructions }
            }
        }

        if(result.terminateRequested)
        {
            workflowState.completed = false
        }

        if(result.repeatRequested)
        {
            workflowState.repeatRequested = true
        }
    }

    private fun shouldRepeatWorkflowCycle(cycleResults: List<JunctionWorkflowPhaseResult>): Boolean
    {
        // Repeat only when the recipe is designed to repeat and no phase explicitly asked to terminate.
        if(!workflowRecipe.repeats())
        {
            return false
        }

        if(cycleResults.any { result -> result.terminateRequested })
        {
            return false
        }

        if(cycleResults.any { result -> result.repeatRequested })
        {
            return true
        }

        return !workflowState.verificationPassed || !discussionState.consensusReached
    }

    private suspend fun finalizeWorkflowOutput(workingContent: MultimodalContent): MultimodalContent
    {
        // Handoff recipes stop here because they are meant to return instructions, not force an extra output
        // dispatch that could mask the caller's own next step.
        if(workflowRecipe.endsWithHandoff())
        {
            trace(
                TraceEventType.JUNCTION_HANDOFF,
                TracePhase.CLEANUP,
                workingContent,
                metadata = mapOf(
                    "recipe" to workflowState.recipe.name,
                    "cyclesExecuted" to workflowState.currentCycle,
                    "handoffOnly" to workflowState.handoffOnly
                )
            )

            return workingContent
        }

        trace(
            TraceEventType.JUNCTION_PHASE_START,
            TracePhase.ORCHESTRATION,
            workingContent,
            metadata = mapOf(
                "cycle" to workflowState.currentCycle + 1,
                "phase" to JunctionWorkflowPhase.OUTPUT.name
            )
        )

        val outputResult = if(outputBinding != null)
        {
            executeWorkflowPhase(JunctionWorkflowPhase.OUTPUT, workingContent, workflowState.currentCycle + 1)
        }
        else
        {
            buildDefaultWorkflowPhaseResult(
                phase = JunctionWorkflowPhase.OUTPUT,
                cycleNumber = workflowState.currentCycle + 1,
                participantName = "output",
                text = buildWorkflowSummaryText(),
                passed = true,
                repeatRequested = false,
                terminateRequested = false,
                notes = "Workflow summary generated without an explicit output handler."
            )
        }

        recordWorkflowPhaseResult(outputResult)
        workflowState.outputText = outputResult.text.ifBlank { outputResult.instructions }

        trace(
            TraceEventType.JUNCTION_PHASE_END,
            TracePhase.ORCHESTRATION,
            workingContent,
            metadata = mapOf(
                "cycle" to workflowState.currentCycle + 1,
                "phase" to JunctionWorkflowPhase.OUTPUT.name,
                "passed" to outputResult.passed,
                "repeatRequested" to outputResult.repeatRequested,
                "terminateRequested" to outputResult.terminateRequested
            )
        )

        if(workflowState.handoffOnly || workflowRecipe.endsWithHandoff())
        {
            trace(
                TraceEventType.JUNCTION_HANDOFF,
                TracePhase.CLEANUP,
                workingContent,
                metadata = mapOf(
                    "recipe" to workflowState.recipe.name,
                    "cyclesExecuted" to workflowState.currentCycle,
                    "handoffOnly" to workflowState.handoffOnly
                )
            )
        }

        return workingContent
    }

    private fun buildDiscussionDecision(): DiscussionDecision
    {
        return DiscussionDecision(
            topic = discussionState.topic,
            decision = discussionState.finalDecision.ifBlank {
                discussionState.voteResults.firstOrNull()?.option ?: discussionState.topic
            },
            consensusReached = discussionState.consensusReached,
            roundsExecuted = discussionState.currentRound,
            strategy = discussionState.strategy,
            moderatorNotes = discussionState.moderatorNotes,
            participantOpinions = discussionState.participantOpinions.toMutableMap(),
            voteResults = discussionState.voteResults.toMutableList()
        )
    }

    private fun buildWorkflowOutcome(
        topic: String,
        finalContent: MultimodalContent
    ): JunctionWorkflowOutcome
    {
        return JunctionWorkflowOutcome(
            topic = topic,
            recipe = workflowState.recipe,
            completed = workflowState.completed,
            cyclesExecuted = workflowState.currentCycle,
            handoffOnly = workflowState.handoffOnly,
            finalText = workflowState.outputText.ifBlank { finalContent.text },
            discussionDecision = workflowState.discussionDecision,
            phaseResults = workflowState.phaseResults.toMutableList(),
            notes = workflowState.roundLog.joinToString("\n")
        )
    }

    private fun validateContainerAncestry(
        label: String,
        component: P2PInterface
    )
    {
        // Identity-based tracking matters here because nested harnesses may reuse the same type but represent
        // different runtime objects, and the cycle we care about is object graph recursion, not class reuse.
        val ancestry = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var current: Any? = component
        var depth = 0

        while(current is P2PInterface)
        {
            if(!ancestry.add(current))
            {
                // A repeated object in the ancestry chain means a cycle, which would otherwise recurse forever
                // through nested P2P dispatches.
                throw IllegalStateException(
                    "Junction detected a cycle while validating $label. " +
                        "Repeated container: ${describeContainer(current)}"
                )
            }

            val next = current.getContainerObject() ?: return
            depth++
            if(depth > defaultMaxNestedDepth)
            {
                // Depth is only a guardrail; it protects the harness from pathological nesting even when the
                // graph is technically acyclic.
                throw IllegalStateException(
                    "Junction exceeded the nested depth limit of $defaultMaxNestedDepth while validating $label. " +
                        "Last container: ${describeContainer(next)}"
                )
            }

            current = next
        }
    }

    private fun describeContainer(container: Any): String
    {
        return when(container)
        {
            is P2PInterface ->
            {
                container.getP2pDescription()?.agentName
                    ?.takeIf { it.isNotBlank() }
                    ?: container::class.simpleName
                    ?: "P2PInterface"
            }

            else ->
            {
                container::class.simpleName ?: container.toString()
            }
        }
    }

    private fun buildBinding(
        roleName: String,
        component: P2PInterface,
        kind: JunctionBindingKind,
        descriptor: P2PDescriptor?,
        requirements: P2PRequirements?,
        description: String,
        weight: Double
    ): JunctionBinding
    {
        val resolvedTransport = descriptor?.transport ?: component.getP2pTransport() ?: P2PTransport(
            transportMethod = Transport.Tpipe,
            transportAddress = roleName
        )

        // Binding synthesis keeps Junction usable with any P2PInterface, even when the caller did not prefill
        // a descriptor or requirements object for that participant.
        val resolvedDescriptor = descriptor ?: component.getP2pDescription() ?: P2PDescriptor(
            agentName = roleName,
            agentDescription = description,
            transport = resolvedTransport,
            requiresAuth = false,
            usesConverse = true,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = true,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none,
            agentSkills = mutableListOf(
                when(kind)
                {
                    JunctionBindingKind.MODERATOR ->
                    {
                        P2PSkills("Moderate", "Coordinates discussion rounds and decides when consensus is reached")
                    }

                    JunctionBindingKind.PARTICIPANT ->
                    {
                        P2PSkills("Participate", "Contributes discussion opinions and votes")
                    }

                    JunctionBindingKind.PLANNER ->
                    {
                        P2PSkills("Plan", "Builds or refines the workflow plan")
                    }

                    JunctionBindingKind.ACTOR ->
                    {
                        P2PSkills("Act", "Executes workflow actions or produces handoff instructions")
                    }

                    JunctionBindingKind.VERIFIER ->
                    {
                        P2PSkills("Verify", "Checks workflow outputs and decides whether another cycle is needed")
                    }

                    JunctionBindingKind.ADJUSTER ->
                    {
                        P2PSkills("Adjust", "Refines workflow plans or actions after review")
                    }

                    JunctionBindingKind.OUTPUT ->
                    {
                        P2PSkills("Output", "Formats the final workflow instructions or handoff artifact")
                    }
                }
            )
        )

        val resolvedRequirements = requirements ?: component.getP2pRequirements() ?: P2PRequirements(
            requireConverseInput = false,
            allowAgentDuplication = false,
            allowCustomContext = false,
            allowCustomJson = false,
            allowExternalConnections = false,
            acceptedContent = mutableListOf(SupportedContentTypes.text),
            maxTokens = 30000,
            allowMultiPageContext = true
        )

        component.setP2pDescription(resolvedDescriptor)
        component.setP2pTransport(resolvedTransport)
        component.setP2pRequirements(resolvedRequirements)

        return JunctionBinding(
            roleName = roleName,
            component = component,
            kind = kind,
            weight = weight,
            descriptor = resolvedDescriptor,
            transport = resolvedTransport,
            requirements = resolvedRequirements
        )
    }

    private fun resolveRoleName(component: P2PInterface, fallback: String): String
    {
        val descriptorName = component.getP2pDescription()?.agentName.orEmpty()
        if(descriptorName.isNotBlank())
        {
            return descriptorName
        }

        return component::class.simpleName?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun resolveRoundParticipants(): List<JunctionBinding>
    {
        // Conversational strategy uses the moderator-selected subset when one is present, but falls back to
        // the full participant list so a bad or empty directive does not dead-end the discussion.
        if(discussionState.strategy != DiscussionStrategy.CONVERSATIONAL || discussionState.selectedParticipants.isEmpty())
        {
            return participantBindings.toList()
        }

        val selected = discussionState.selectedParticipants.toSet()
        val filtered = participantBindings.filter { binding -> binding.roleName in selected }
        return if(filtered.isEmpty()) participantBindings.toList() else filtered
    }

    private suspend fun runParticipantRound(
        roundParticipants: List<JunctionBinding>,
        workingContent: MultimodalContent,
        roundNumber: Int
    ): List<ParticipantOpinion>
    {
        // Simultaneous mode fans out through coroutines, while the other modes stay sequential so pause points
        // and moderator-directed ordering remain deterministic.
        return when(discussionState.strategy)
        {
            DiscussionStrategy.SIMULTANEOUS ->
            {
                coroutineScope {
                    roundParticipants.map { binding ->
                        async {
                            dispatchParticipant(binding, workingContent, roundNumber)
                        }
                    }.awaitAll()
                }
            }

            DiscussionStrategy.ROUND_ROBIN ->
            {
                val results = mutableListOf<ParticipantOpinion>()
                for(binding in roundParticipants)
                {
                    awaitPauseCheckpoint(workingContent)
                    results.add(dispatchParticipant(binding, workingContent, roundNumber))
                }
                results
            }

            DiscussionStrategy.CONVERSATIONAL ->
            {
                val results = mutableListOf<ParticipantOpinion>()
                for(binding in roundParticipants)
                {
                    awaitPauseCheckpoint(workingContent)
                    results.add(dispatchParticipant(binding, workingContent, roundNumber))
                }
                results
            }
        }
    }

    private suspend fun dispatchParticipant(
        binding: JunctionBinding,
        workingContent: MultimodalContent,
        roundNumber: Int
    ): ParticipantOpinion
    {
        // Dispatch and response events are traced separately so the visualizer can show both the outbound
        // request and the eventual participant reply for every round.
        trace(
            TraceEventType.JUNCTION_PARTICIPANT_DISPATCH,
            TracePhase.AGENT_COMMUNICATION,
            workingContent,
            metadata = mapOf(
                "participant" to binding.roleName,
                "round" to roundNumber
            )
        )

        val request = buildParticipantRequest(binding, workingContent, roundNumber)

        return try
        {
            val response = binding.component.executeP2PRequest(request)
            val output = response?.output?.text.orEmpty()

            trace(
                TraceEventType.JUNCTION_PARTICIPANT_RESPONSE,
                TracePhase.AGENT_COMMUNICATION,
                response?.output,
                metadata = mapOf(
                    "participant" to binding.roleName,
                    "round" to roundNumber,
                    "hasRejection" to (response?.rejection != null)
                )
            )

            parseParticipantOpinion(binding.roleName, roundNumber, output)
        }
        catch(e: Exception)
        {
            // Participant failures are folded into a neutral opinion instead of crashing the entire harness so
            // the moderator can still inspect a partial round and decide how to continue.
            trace(
                TraceEventType.JUNCTION_FAILURE,
                TracePhase.AGENT_COMMUNICATION,
                error = e,
                metadata = mapOf(
                    "participant" to binding.roleName,
                    "round" to roundNumber
                )
            )

            ParticipantOpinion(
                participantName = binding.roleName,
                roundNumber = roundNumber,
                opinion = "",
                vote = "",
                confidence = 0.0,
                reasoning = e.message ?: "Participant dispatch failed",
                rawOutput = e.message ?: ""
            )
        }
    }

    private fun buildParticipantRequest(
        binding: JunctionBinding,
        workingContent: MultimodalContent,
        roundNumber: Int
    ): P2PRequest
    {
        // The participant prompt always includes the latest state snapshot so each participant can answer in
        // the context of the same round, even if its own harness has deeper internal logic.
        val promptText = buildString {
            appendLine("You are participant '${binding.roleName}' in a TPipe Junction discussion.")
            appendLine("Return a JSON object matching the ParticipantOpinion shape.")
            appendLine("Vote using a short option string. Explain your reasoning clearly.")
            appendLine("Round: $roundNumber")
            appendLine("Topic:")
            appendLine(discussionState.topic)
            appendLine("Current discussion state:")
            appendLine(serialize(discussionState))
            appendLine("Latest content:")
            appendLine(workingContent.text)
        }

        val prompt = MultimodalContent(
            text = promptText,
            binaryContent = workingContent.binaryContent.toMutableList(),
            terminatePipeline = false
        )

        return P2PRequest(
            transport = binding.transport,
            returnAddress = p2pTransport ?: P2PTransport(),
            prompt = prompt
        )
    }

    private fun parseParticipantOpinion(
        participantName: String,
        roundNumber: Int,
        output: String
    ): ParticipantOpinion
    {
        // Prefer JSON when a participant provides it, but gracefully downgrade to a plain-text opinion so a
        // partially cooperative participant can still contribute to the vote.
        val parsed = deserialize<ParticipantOpinion>(output)
        return parsed?.copy(
            participantName = participantName.ifBlank { parsed.participantName },
            roundNumber = if(parsed.roundNumber == 0) roundNumber else parsed.roundNumber,
            rawOutput = output
        ) ?: ParticipantOpinion(
            participantName = participantName,
            roundNumber = roundNumber,
            opinion = output.trim(),
            vote = output.trim(),
            reasoning = output.trim(),
            rawOutput = output
        )
    }

    private fun tallyVotes(opinions: List<ParticipantOpinion>): List<VotingResult>
    {
        // Votes are weighted by the registered participant binding, and blank opinions are ignored so a noisy
        // participant does not accidentally create a meaningless tallied option.
        val tallies = linkedMapOf<String, VotingResult>()
        val totalWeight = opinions.sumOf { opinion ->
            participantBindingsByName[opinion.participantName]?.weight ?: 1.0
        }.takeIf { it > 0.0 } ?: 1.0

        opinions.forEach { opinion ->
            val binding = participantBindingsByName[opinion.participantName]
            val weight = binding?.weight ?: 1.0
            val vote = normalizeVote(opinion.vote.ifBlank { opinion.opinion })
            if(vote.isBlank())
            {
                return@forEach
            }

            val result = tallies.getOrPut(vote) {
                VotingResult(option = vote)
            }

            result.weight += weight
            result.supporters.add(opinion.participantName)
        }

        tallies.values.forEach { result ->
            result.percentage = result.weight / totalWeight
            result.thresholdMet = result.percentage >= discussionState.consensusThreshold
        }

        return tallies.values.sortedByDescending { result -> result.weight }
    }

    private fun normalizeVote(value: String): String
    {
        return value.trim().replace(Regex("\\s+"), " ")
    }

    private suspend fun resolveModeratorDirective(
        workingContent: MultimodalContent,
        opinions: List<ParticipantOpinion>,
        voteResults: List<VotingResult>
    ): ModeratorDirective
    {
        // The moderator is advisory: if it fails or is absent, Junction keeps moving with a safe default so the
        // discussion loop remains recoverable.
        val binding = moderatorBinding ?: return buildDefaultDirective(voteResults)
        val prompt = buildModeratorPrompt(workingContent, opinions, voteResults)
        val request = P2PRequest(
            transport = binding.transport,
            returnAddress = p2pTransport ?: P2PTransport(),
            prompt = prompt
        )

        return try
        {
            val response = binding.component.executeP2PRequest(request)
            val output = response?.output?.text.orEmpty()
            val parsed = deserialize<ModeratorDirective>(output)
            parsed ?: buildDefaultDirective(voteResults)
        }
        catch(e: Exception)
        {
            discussionState.roundLog.add("Moderator directive failed: ${e.message}")
            buildDefaultDirective(voteResults)
        }
    }

    private fun buildModeratorPrompt(
        workingContent: MultimodalContent,
        opinions: List<ParticipantOpinion>,
        voteResults: List<VotingResult>
    ): MultimodalContent
    {
        // Moderator prompts include the raw participant opinions and vote tally so a moderator harness can
        // make an informed override decision without reconstructing the round from traces.
        val promptText = buildString {
            appendLine("You are the Junction moderator.")
            appendLine("Decide whether discussion should continue or terminate.")
            appendLine("Return a JSON object matching the ModeratorDirective shape.")
            appendLine("Topic:")
            appendLine(discussionState.topic)
            appendLine("Current state:")
            appendLine(serialize(discussionState))
            appendLine("Latest participant opinions:")
            appendLine(serialize(opinions))
            appendLine("Vote tally:")
            appendLine(serialize(voteResults))
            appendLine("Latest content:")
            appendLine(workingContent.text)
        }

        return MultimodalContent(
            text = promptText,
            binaryContent = workingContent.binaryContent.toMutableList(),
            terminatePipeline = false
        )
    }

    private fun buildDefaultDirective(voteResults: List<VotingResult>): ModeratorDirective
    {
        // The fallback directive mirrors what a minimal moderator would do: continue only when the harness has
        // not already converged and still has rounds available.
        val topVote = voteResults.firstOrNull()
        return ModeratorDirective(
            continueDiscussion = !discussionState.consensusReached && discussionState.currentRound < discussionState.maxRounds,
            selectedParticipants = mutableListOf(),
            finalDecision = topVote?.option.orEmpty(),
            nextRoundPrompt = "",
            notes = if(topVote == null) "No votes were cast." else "Default moderator fallback selected ${topVote.option}."
        )
    }

    private suspend fun awaitPauseCheckpoint(content: MultimodalContent)
    {
        // Pause is only honored at safe checkpoints so the harness never suspends in the middle of a response
        // merge, vote tally, or workflow phase transition.
        if(!isPaused)
        {
            return
        }

        trace(
            TraceEventType.JUNCTION_PAUSE,
            TracePhase.ORCHESTRATION,
            content,
            metadata = mapOf(
                "round" to discussionState.currentRound,
                "paused" to true
            )
        )

        resumeSignal.receive()
        isPaused = false

        trace(
            TraceEventType.JUNCTION_RESUME,
            TracePhase.ORCHESTRATION,
            content,
            metadata = mapOf(
                "round" to discussionState.currentRound,
                "paused" to false
            )
        )
    }

    private fun trace(
        eventType: TraceEventType,
        phase: TracePhase,
        content: MultimodalContent? = null,
        metadata: Map<String, Any> = emptyMap(),
        error: Throwable? = null
    )
    {
        // Junction emits its own trace family so the debugger can distinguish harness orchestration from the
        // lower-level pipe or P2P events it may be wrapping.
        if(!tracingEnabled)
        {
            return
        }

        if(!EventPriorityMapper.shouldTrace(eventType, traceConfig.detailLevel))
        {
            return
        }

        val event = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = junctionId,
            pipeName = "Junction-${moderatorBinding?.roleName ?: "unbound"}",
            eventType = eventType,
            phase = phase,
            content = if(shouldIncludeContent(traceConfig.detailLevel)) content else null,
            contextSnapshot = if(shouldIncludeContext(traceConfig.detailLevel)) content?.context else null,
            metadata = if(traceConfig.includeMetadata) buildMetadata(metadata, error) else emptyMap(),
            error = error
        )

        PipeTracer.addEvent(junctionId, event)
    }

    private fun buildMetadata(baseMetadata: Map<String, Any>, error: Throwable?): Map<String, Any>
    {
        // Every Junction event carries a compact runtime snapshot so the trace viewer and failure analysis can
        // reconstruct what the harness knew at the moment the event was emitted.
        val metadata = baseMetadata.toMutableMap()
        metadata["junctionId"] = junctionId
        metadata["strategy"] = discussionState.strategy.name
        metadata["round"] = discussionState.currentRound
        metadata["maxRounds"] = discussionState.maxRounds
        metadata["consensusReached"] = discussionState.consensusReached
        metadata["participantCount"] = participantBindings.size
        metadata["moderatorSet"] = moderatorBinding != null

        if(error != null)
        {
            metadata["error"] = error.message ?: "Unknown error"
            metadata["errorType"] = error::class.simpleName ?: "Unknown"
        }

        return metadata
    }

    private fun resetRuntimeState()
    {
        // Preserve configuration and bindings, but ensure transient run state cannot leak from one execution
        // into the next.
        isPaused = false
        while(resumeSignal.tryReceive().isSuccess)
        {
            // Drain any queued resume tokens left behind by a prior run.
        }

        discussionState = discussionState.copy(
            topic = "",
            currentRound = 0,
            consensusReached = false,
            finalDecision = "",
            moderatorNotes = "",
            selectedParticipants = mutableListOf(),
            participantOpinions = mutableMapOf(),
            voteResults = mutableListOf(),
            roundLog = mutableListOf()
        )

        workflowState = JunctionWorkflowState(
            recipe = workflowRecipe,
            currentCycle = 0,
            maxCycles = discussionState.maxRounds,
            phaseOrder = workflowRecipe.phases().toMutableList(),
            completed = false,
            handoffOnly = workflowRecipe.endsWithHandoff(),
            repeatRequested = false,
            verificationPassed = true,
            discussionDecision = DiscussionDecision()
        )
    }

    private fun shouldIncludeContent(detailLevel: TraceDetailLevel): Boolean
    {
        return when(detailLevel)
        {
            TraceDetailLevel.MINIMAL -> false
            TraceDetailLevel.NORMAL -> false
            TraceDetailLevel.VERBOSE -> traceConfig.includeContext
            TraceDetailLevel.DEBUG -> traceConfig.includeContext
        }
    }

    private fun shouldIncludeContext(detailLevel: TraceDetailLevel): Boolean
    {
        return when(detailLevel)
        {
            TraceDetailLevel.MINIMAL -> false
            TraceDetailLevel.NORMAL -> false
            TraceDetailLevel.VERBOSE -> traceConfig.includeContext
            TraceDetailLevel.DEBUG -> traceConfig.includeContext
        }
    }
}
