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
import com.TTT.Context.Dictionary
import com.TTT.Context.MiniBank
import com.TTT.Enums.ContextWindowSettings
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
import com.TTT.Pipe.TruncationSettings
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.deepCopy
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import com.TTT.Util.RuntimeState
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
    @RuntimeState
    private var containerObject: Any? = null

    private var moderatorBinding: JunctionBinding? = null
    private val participantBindings = mutableListOf<JunctionBinding>()
    private val participantBindingsByName = mutableMapOf<String, JunctionBinding>()
    private var plannerBinding: JunctionBinding? = null
    private var actorBinding: JunctionBinding? = null
    private var verifierBinding: JunctionBinding? = null
    private var adjusterBinding: JunctionBinding? = null
    private var outputBinding: JunctionBinding? = null

    @RuntimeState
    private var discussionState = DiscussionState()
    private var workflowRecipe = JunctionWorkflowRecipe.DISCUSSION_ONLY
    @RuntimeState
    private var workflowState = JunctionWorkflowState()
    private var tracingEnabled = false
    private var traceConfig = TraceConfig()
    @RuntimeState
    private val junctionId = UUID.randomUUID().toString()
    @RuntimeState
    private var isPaused = false
    @RuntimeState
    private val resumeSignal = Channel<Unit>(1)
    private var moderatorInterventionEnabled = true
    private var defaultMaxNestedDepth = 8
    private var junctionMemoryPolicy = JunctionMemoryPolicy()

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
     * Set the memory policy that governs outbound prompt compaction.
     *
     * @param policy The policy to apply to future participant and workflow requests.
     * @return This Junction object for method chaining.
     */
    fun setMemoryPolicy(policy: JunctionMemoryPolicy): Junction
    {
        junctionMemoryPolicy = policy.copy()
        return this
    }

    /**
     * Configure the memory policy inline.
     *
     * @param block Mutable configuration block for the outbound memory policy.
     * @return This Junction object for method chaining.
     */
    fun memoryPolicy(block: JunctionMemoryPolicy.() -> Unit): Junction
    {
        val policy = JunctionMemoryPolicy()
        policy.block()
        junctionMemoryPolicy = policy
        return this
    }

    /**
     * Read back the current memory policy.
     *
     * @return A copy of the active memory policy.
     */
    fun getMemoryPolicy(): JunctionMemoryPolicy
    {
        return junctionMemoryPolicy.copy()
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
        workingContent.metadata["junctionMemoryPolicy"] = junctionMemoryPolicy.copy()
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
        workingContent.metadata["junctionMemoryPolicy"] = junctionMemoryPolicy.copy()
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
        workingContent.metadata["junctionMemoryPolicy"] = junctionMemoryPolicy.copy()
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
        finalOutput.metadata["junctionMemoryPolicy"] = junctionMemoryPolicy.copy()

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
                if(finalOutput.passPipeline) TraceEventType.JUNCTION_WORKFLOW_SUCCESS else TraceEventType.JUNCTION_WORKFLOW_FAILURE,
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

    /**
     * Resolve the token-counting settings Junction should use when compacting one outbound request.
     *
     * The binding can contribute its own budgeting preferences, but Junction always clones those settings so
     * request shaping never mutates shared configuration.
     *
     * @param binding The target binding whose token-counting settings should be honored, if present.
     * @return A copy of the token-counting settings to use for this request.
     */
    private fun resolveMemoryTruncationSettings(binding: JunctionBinding?): TruncationSettings
    {
        return binding?.requirements?.tokenCountingSettings?.copy() ?: TruncationSettings()
    }

    /**
     * Resolve the safe outbound token ceiling for one binding.
     *
     * Junction budgets against the smallest safe value across its own policy and the participant's advertised
     * limits so a loosely configured harness never pushes an oversized prompt downstream.
     *
     * @param binding The participant or workflow binding being targeted.
     * @return The smallest safe token ceiling available for the request.
     */
    private fun resolveOutboundBudget(binding: JunctionBinding?): Int
    {
        val policyBudget = junctionMemoryPolicy.outboundTokenBudget.takeIf { it > 0 } ?: Int.MAX_VALUE
        val descriptorBudget = binding?.descriptor?.contextWindowSize?.takeIf { it > 0 } ?: Int.MAX_VALUE
        val requirementsBudget = binding?.requirements?.maxTokens?.takeIf { it > 0 } ?: Int.MAX_VALUE
        return minOf(policyBudget, descriptorBudget, requirementsBudget)
    }

    /**
     * Truncate a text block to the requested budget using TPipe's dictionary-backed token accounting.
     *
     * Junction uses this helper for all prompt compaction so the outbound envelope is deterministic and does
     * not depend on a language model to remain safe.
     *
     * @param text Text to compact.
     * @param tokenBudget Maximum tokens allowed for the returned text.
     * @param settings Token-counting settings to reuse for the estimate.
     * @param preserveStart Whether the front or back of the text should be preserved first.
     * @return The compacted text or an empty string when nothing useful can be kept.
     */
    private fun budgetText(
        text: String,
        tokenBudget: Int,
        settings: TruncationSettings,
        preserveStart: Boolean = true
    ): String
    {
        if(text.isBlank() || tokenBudget <= 0)
        {
            return ""
        }

        val method = if(preserveStart) ContextWindowSettings.TruncateBottom else ContextWindowSettings.TruncateTop
        return Dictionary.truncate(
            text,
            tokenBudget,
            1,
            method,
            settings.countSubWordsInFirstWord,
            settings.favorWholeWords,
            settings.countOnlyFirstWordFound,
            settings.splitForNonWordChar,
            settings.alwaysSplitIfWholeWordExists,
            settings.countSubWordsIfSplit,
            settings.nonWordSplitCount,
            settings.tokenCountingBias
        )
    }

    /**
     * Estimate the token cost of a text block using the same settings Junction uses for compaction.
     *
     * @param text Text to inspect.
     * @param settings Token-counting settings to apply.
     * @return Approximate token count for the text.
     */
    private fun countBudgetedTokens(text: String, settings: TruncationSettings): Int
    {
        if(text.isBlank())
        {
            return 0
        }

        return Dictionary.countTokens(
            text,
            settings.countSubWordsInFirstWord,
            settings.favorWholeWords,
            settings.countOnlyFirstWordFound,
            settings.splitForNonWordChar,
            settings.alwaysSplitIfWholeWordExists,
            settings.countSubWordsIfSplit,
            settings.nonWordSplitCount,
            settings.tokenCountingBias
        )
    }

    /**
     * Render a compact section label and its lines as a single prompt block.
     *
     * Empty sections are dropped so the outbound envelope does not spend budget on boilerplate.
     *
     * @param title Section title to prepend.
     * @param lines Lines of content to include under the title.
     * @return A compact block or an empty string if all input lines are blank.
     */
    private fun buildSectionText(title: String, lines: List<String>): String
    {
        val filtered = lines.filter { line -> line.isNotBlank() }
        if(filtered.isEmpty())
        {
            return ""
        }

        return buildString {
            appendLine("$title:")
            filtered.forEach { line ->
                appendLine("- $line")
            }
        }.trim()
    }

    /**
     * Build the optional summary block for older history tails.
     *
     * Summarization is only a support mechanism here. The output is still bounded by deterministic budgeting
     * and falls back to the raw tail when the summarizer produces nothing useful.
     *
     * @param summaryLabel Human-readable label for the summary section.
     * @param summarySeed Older-history content used as the basis for optional summarization.
     * @param summaryBudget Token budget reserved for the summary.
     * @param settings Token-counting settings to reuse for the summary.
     * @return A compact summary section or an empty string if no safe summary exists.
     */
    private fun buildSummaryText(
        summaryLabel: String,
        summarySeed: String,
        summaryBudget: Int,
        settings: TruncationSettings
    ): String
    {
        if(summarySeed.isBlank() || summaryBudget <= 0)
        {
            return ""
        }

        val trimmedSeed = summarySeed.take(junctionMemoryPolicy.maxSummaryCharacters)
        val summarized = if(junctionMemoryPolicy.enableSummarization && junctionMemoryPolicy.summarizer != null)
        {
            runCatching { junctionMemoryPolicy.summarizer?.invoke(trimmedSeed).orEmpty() }
                .getOrDefault("")
                .ifBlank { trimmedSeed }
        }
        else
        {
            trimmedSeed
        }

        val summarySection = buildSectionText(summaryLabel, listOf(summarized))
        return budgetText(summarySection, summaryBudget, settings, preserveStart = true)
    }

    /**
     * Build the compact [ContextWindow] that will be handed to a downstream participant.
     *
     * The sections are staged first and then truncated together so the final prompt stays within the same
     * resolved budget as the envelope that owns it.
     *
     * @param sections Ordered prompt sections to include.
     * @param budget Token ceiling for the assembled prompt window.
     * @param settings Token-counting and truncation settings to reuse.
     * @param inputText Original caller content used to preserve explicit text matches.
     * @return A compact context window containing the budgeted sections.
     */
    private fun buildContextWindowForPrompt(
        sections: List<String>,
        budget: Int,
        settings: TruncationSettings,
        inputText: String
    ): ContextWindow
    {
        val promptWindow = ContextWindow()
        sections.filter { it.isNotBlank() }.forEach { section ->
            promptWindow.contextElements.add(section)
        }

        if(promptWindow.contextElements.isEmpty())
        {
            return promptWindow
        }

        promptWindow.truncateContextElements(
            budget,
            1,
            ContextWindowSettings.TruncateBottom,
            settings.countSubWordsInFirstWord,
            settings.favorWholeWords,
            settings.countOnlyFirstWordFound,
            settings.splitForNonWordChar,
            settings.alwaysSplitIfWholeWordExists,
            settings.countSubWordsIfSplit,
            settings.nonWordSplitCount,
            settings.tokenCountingBias,
            inputText = inputText,
            preserveTextMatches = true
        )

        return promptWindow
    }

    /**
     * Convert the compact sections into a [MiniBank] for receivers that expect page-based prompt context.
     *
     * @param sections Named section payloads to place into the mini bank.
     * @return A mini bank mirroring the compact prompt sections.
     */
    private fun buildMiniBankFromSections(sections: Map<String, String>): MiniBank
    {
        val miniBank = MiniBank()
        sections.forEach { (key, text) ->
            if(text.isBlank())
            {
                return@forEach
            }

            miniBank.contextMap[key] = ContextWindow().apply {
                contextElements.add(text)
            }
        }

        return miniBank
    }

    /**
     * Build a complete outbound memory envelope for one participant or workflow binding.
     *
     * This is the core governance step for Junction: it resolves the request budget, allocates it across the
     * critical/recent/summary sections, optionally summarizes older history, and returns a compact payload
     * that can be sent downstream without leaking the full live harness state.
     *
     * @param roleName Logical role name the envelope is being built for.
     * @param roleKind High-level role category used for tracing and diagnostics.
     * @param binding Binding that will receive the request, if one exists.
     * @param workingContent Current run content that seeded the request.
     * @param criticalLines Live state that must survive compaction.
     * @param recentLines Near-term context that should usually be preserved verbatim.
     * @param summarySeed Older-history text that may be summarized as a secondary support mechanism.
     * @param summaryLabel Human-readable label for the summary section.
     * @return A fully budgeted memory envelope ready to render into a prompt.
     * @throws IllegalStateException If the resolved budget cannot safely hold Junction's minimum critical state.
     */
    private fun budgetEnvelope(
        roleName: String,
        roleKind: JunctionMemoryRole,
        binding: JunctionBinding?,
        workingContent: MultimodalContent,
        criticalLines: List<String>,
        recentLines: List<String>,
        summarySeed: String,
        summaryLabel: String
    ): JunctionMemoryEnvelope
    {
        val settings = resolveMemoryTruncationSettings(binding)
        val resolvedBudget = resolveOutboundBudget(binding)
        val availableBudget = resolvedBudget - junctionMemoryPolicy.safetyReserveTokens

        if(availableBudget < junctionMemoryPolicy.minimumCriticalBudget)
        {
            val reason = "Junction memory budget $availableBudget is below the minimum critical budget of ${junctionMemoryPolicy.minimumCriticalBudget}."
            trace(
                TraceEventType.JUNCTION_FAILURE,
                TracePhase.ORCHESTRATION,
                workingContent,
                metadata = mapOf(
                    "roleName" to roleName,
                    "roleKind" to roleKind.name,
                    "resolvedBudget" to resolvedBudget,
                    "availableBudget" to availableBudget,
                    "reason" to reason
                )
            )
            throw IllegalStateException(reason)
        }

        var criticalBudget = maxOf(junctionMemoryPolicy.minimumCriticalBudget, availableBudget / 2)
        criticalBudget = minOf(criticalBudget, availableBudget)
        var recentBudget = maxOf(0, availableBudget - criticalBudget)
        if(recentBudget > 0)
        {
            recentBudget = minOf(recentBudget, maxOf(junctionMemoryPolicy.minimumRecentBudget, availableBudget / 3))
        }
        val summaryBudget = maxOf(0, availableBudget - criticalBudget - recentBudget)

        val criticalRaw = buildSectionText("Critical state", criticalLines)
        val criticalText = budgetText(criticalRaw, criticalBudget, settings, preserveStart = true)
        val recentRaw = buildSectionText("Recent history", recentLines)
        val recentText = budgetText(recentRaw, recentBudget, settings, preserveStart = true)
        val summaryText = buildSummaryText(summaryLabel, summarySeed, summaryBudget, settings)

        val sections = mutableListOf<JunctionMemorySection>()
        sections.add(
            JunctionMemorySection(
                name = "critical",
                tokenBudget = criticalBudget,
                text = criticalText,
                truncated = countBudgetedTokens(criticalText, settings) < countBudgetedTokens(criticalRaw, settings),
                tokenCount = countBudgetedTokens(criticalText, settings)
            )
        )
        if(recentText.isNotBlank())
        {
            sections.add(
                JunctionMemorySection(
                    name = "recent",
                    tokenBudget = recentBudget,
                    text = recentText,
                    truncated = countBudgetedTokens(recentText, settings) < countBudgetedTokens(recentRaw, settings),
                    tokenCount = countBudgetedTokens(recentText, settings)
                )
            )
        }
        if(summaryText.isNotBlank())
        {
            sections.add(
                JunctionMemorySection(
                    name = "summary",
                    tokenBudget = summaryBudget,
                    text = summaryText,
                    truncated = countBudgetedTokens(summaryText, settings) < countBudgetedTokens(summarySeed, settings),
                    tokenCount = countBudgetedTokens(summaryText, settings)
                )
            )
        }

        val promptWindow = buildContextWindowForPrompt(
            listOf(
                criticalText,
                recentText,
                summaryText
            ),
            availableBudget,
            settings,
            workingContent.text
        )

        val promptText = promptWindow.contextElements.joinToString("\n\n")
        val miniBank = buildMiniBankFromSections(
            linkedMapOf(
                "critical" to criticalText,
                "recent" to recentText,
                "summary" to summaryText
            )
        )

        val compacted = sections.any { section ->
            if(section.name == "summary")
            {
                countBudgetedTokens(section.text, settings) < countBudgetedTokens(summarySeed, settings)
            }
            else
            {
                section.truncated
            }
        } || promptText.length < criticalRaw.length + recentRaw.length + summaryText.length

        return JunctionMemoryEnvelope(
            roleName = roleName,
            roleKind = roleKind,
            resolvedBudget = resolvedBudget,
            availableBudget = availableBudget,
            safetyReserveTokens = junctionMemoryPolicy.safetyReserveTokens,
            criticalBudget = criticalBudget,
            recentBudget = recentBudget,
            summaryBudget = summaryBudget,
            summarizationUsed = junctionMemoryPolicy.enableSummarization && junctionMemoryPolicy.summarizer != null && summarySeed.isNotBlank(),
            compacted = compacted,
            sections = sections,
            contextWindow = promptWindow,
            miniBank = miniBank
        )
    }

    /**
     * Build the live discussion facts that must survive compaction for a participant.
     *
     * These are the "do not lose" lines: topic, round, voting state, consensus status, and the current
     * content excerpt that grounds the participant in the caller's request.
     *
     * @param workingContent The current discussion payload.
     * @param roundNumber The round being executed.
     * @param binding The participant binding receiving the request.
     * @return Compact critical-state lines for the outbound envelope.
     */
    private fun buildDiscussionCriticalLines(
        workingContent: MultimodalContent,
        roundNumber: Int,
        binding: JunctionBinding
    ): List<String>
    {
        val currentVoteSummary = discussionState.voteResults.firstOrNull()?.let { result ->
            "${result.option} (${result.percentage * 100.0}% / weight ${result.weight})"
        }.orEmpty()

        return listOf(
            "Role: ${binding.roleName}",
            "Topic: ${discussionState.topic}",
            "Round: $roundNumber of ${discussionState.maxRounds}",
            "Strategy: ${discussionState.strategy.name}",
            "Consensus threshold: ${discussionState.consensusThreshold}",
            "Consensus reached: ${discussionState.consensusReached}",
            "Final decision: ${discussionState.finalDecision.ifBlank { "<pending>" }}",
            "Moderator notes: ${discussionState.moderatorNotes.ifBlank { "<none>" }}",
            "Selected participants: ${discussionState.selectedParticipants.joinToString().ifBlank { "<all>" }}",
            "Current vote leader: ${currentVoteSummary.ifBlank { "<none>" }}",
            "Latest content excerpt: ${compactContentExcerpt(workingContent.text, binding)}"
        )
    }

    /**
     * Build the near-term discussion history that should usually remain visible without summarization.
     *
     * The returned lines are intentionally short and ordered so the participant can recover the recent voting
     * context without Junction forwarding the full round log.
     *
     * @return A bounded list of recent discussion lines.
     */
    private fun buildDiscussionRecentLines(): List<String>
    {
        val recentLogs = discussionState.roundLog
            .takeLast(junctionMemoryPolicy.recentDiscussionEntries)
            .reversed()
            .map { entry -> "Log: $entry" }

        val recentOpinions = discussionState.participantOpinions.values
            .sortedWith(compareBy<ParticipantOpinion> { it.roundNumber }.thenBy { it.participantName })
            .takeLast(junctionMemoryPolicy.recentOpinionCount)
            .reversed()
            .map { opinion ->
                "Opinion from ${opinion.participantName} (round ${opinion.roundNumber}): ${opinion.vote.ifBlank { opinion.opinion }}"
            }

        val recentVotes = discussionState.voteResults
            .takeLast(junctionMemoryPolicy.recentOpinionCount)
            .reversed()
            .map { result ->
                "Vote ${result.option}: ${result.percentage * 100.0}% support, supporters=${result.supporters.joinToString()}"
            }

        return recentLogs + recentOpinions + recentVotes
    }

    /**
     * Build the older discussion tail that may be summarized when extra budget is available.
     *
     * This text is not the source of truth. It is only the seed for optional summarization when the harness
     * can afford to provide a more compact long-range memory hint.
     *
     * @return Older discussion context suitable for optional summarization.
     */
    private fun buildDiscussionSummarySeed(): String
    {
        val omittedLogs = discussionState.roundLog.dropLast(junctionMemoryPolicy.recentDiscussionEntries)
        val omittedOpinions = discussionState.participantOpinions.values
            .sortedWith(compareBy<ParticipantOpinion> { it.roundNumber }.thenBy { it.participantName })
            .dropLast(junctionMemoryPolicy.recentOpinionCount)

        return buildString {
            if(omittedLogs.isNotEmpty())
            {
                appendLine("Omitted discussion logs: ${omittedLogs.size}")
                omittedLogs.takeLast(5).forEach { appendLine(it) }
            }
            if(omittedOpinions.isNotEmpty())
            {
                appendLine("Omitted participant opinions: ${omittedOpinions.size}")
                omittedOpinions.takeLast(5).forEach { opinion ->
                    appendLine("${opinion.participantName}: ${opinion.vote.ifBlank { opinion.opinion }}")
                }
            }
            if(discussionState.voteResults.isNotEmpty())
            {
                appendLine("Latest vote tally:")
                discussionState.voteResults.forEach { result ->
                    appendLine("${result.option}: ${result.percentage * 100.0}% support")
                }
            }
        }.trim()
    }

    /**
     * Build the live workflow facts that must survive compaction for a workflow role.
     *
     * The section keeps the current recipe, cycle, discussion state, and phase-specific instruction visible so
     * the downstream container can act without inheriting the whole accumulated history.
     *
     * @param workingContent Current workflow payload.
     * @param phase Workflow phase being executed.
     * @param cycleNumber Current workflow cycle.
     * @param binding Binding that will receive the phase request, if any.
     * @return Critical workflow lines for the outbound envelope.
     */
    private fun buildWorkflowCriticalLines(
        workingContent: MultimodalContent,
        phase: JunctionWorkflowPhase,
        cycleNumber: Int,
        binding: JunctionBinding?
    ): List<String>
    {
        return buildList {
            add("Role: ${binding?.roleName ?: phase.name.lowercase()}")
            add("Phase: ${phase.name}")
            add("Cycle: $cycleNumber of ${workflowState.maxCycles}")
            add("Workflow recipe: ${workflowState.recipe.name}")
            add("Topic: ${discussionState.topic}")
            add("Discussion round: ${discussionState.currentRound} of ${discussionState.maxRounds}")
            add("Consensus reached: ${discussionState.consensusReached}")
            add("Current decision: ${discussionState.finalDecision.ifBlank { "<pending>" }}")
            add("Workflow status: completed=${workflowState.completed}, repeatRequested=${workflowState.repeatRequested}, verificationPassed=${workflowState.verificationPassed}")
            add("Latest content excerpt: ${compactContentExcerpt(workingContent.text, binding)}")
            add(
                when(phase)
                {
                    JunctionWorkflowPhase.PLAN -> "Plan current actions and dependencies."
                    JunctionWorkflowPhase.VOTE -> "Vote against the current decision and verify whether consensus exists."
                    JunctionWorkflowPhase.ACT -> "Carry out the action or emit safe handoff instructions."
                    JunctionWorkflowPhase.VERIFY -> "Verify the current workflow output and request repetition if needed."
                    JunctionWorkflowPhase.ADJUST -> "Adjust the current plan based on the latest vote and verification state."
                    JunctionWorkflowPhase.OUTPUT -> "Produce the final handoff or output artifact."
                }
            )
        }
    }

    /**
     * Build the near-term workflow history that should stay visible without summarization.
     *
     * This captures the latest phase results and logs so a planner or verifier can continue without losing
     * the immediate execution story.
     *
     * @return A bounded list of recent workflow lines.
     */
    private fun buildWorkflowRecentLines(): List<String>
    {
        val recentLogs = workflowState.roundLog
            .takeLast(junctionMemoryPolicy.recentPhaseResultCount)
            .reversed()
            .map { entry -> "Log: $entry" }

        val recentPhaseResults = workflowState.phaseResults
            .takeLast(junctionMemoryPolicy.recentPhaseResultCount)
            .reversed()
            .map { result ->
                "Phase ${result.phase.name} cycle ${result.cycleNumber}: ${result.text.ifBlank { result.instructions }}"
            }

        return recentLogs + recentPhaseResults
    }

    /**
     * Build the older workflow tail that may be summarized when extra budget remains.
     *
     * The seed includes omitted logs, omitted phase results, and cached phase text so a summarizer can retain
     * the important arc of the workflow without replaying the full trace.
     *
     * @return Older workflow context suitable for optional summarization.
     */
    private fun buildWorkflowSummarySeed(): String
    {
        val omittedPhaseResults = workflowState.phaseResults.dropLast(junctionMemoryPolicy.recentPhaseResultCount)
        val omittedLogs = workflowState.roundLog.dropLast(junctionMemoryPolicy.recentPhaseResultCount)

        return buildString {
            if(omittedLogs.isNotEmpty())
            {
                appendLine("Omitted workflow logs: ${omittedLogs.size}")
                omittedLogs.takeLast(5).forEach { appendLine(it) }
            }
            if(omittedPhaseResults.isNotEmpty())
            {
                appendLine("Omitted phase results: ${omittedPhaseResults.size}")
                omittedPhaseResults.takeLast(5).forEach { result ->
                    appendLine("${result.phase.name} cycle ${result.cycleNumber}: ${result.notes.ifBlank { result.text }}")
                }
            }
            if(workflowState.planText.isNotBlank())
            {
                appendLine("Plan cache: ${workflowState.planText}")
            }
            if(workflowState.actText.isNotBlank())
            {
                appendLine("Action cache: ${workflowState.actText}")
            }
            if(workflowState.verifyText.isNotBlank())
            {
                appendLine("Verification cache: ${workflowState.verifyText}")
            }
            if(workflowState.adjustText.isNotBlank())
            {
                appendLine("Adjustment cache: ${workflowState.adjustText}")
            }
        }.trim()
    }

    /**
     * Produce a short excerpt of the live content so the role keeps enough grounding without receiving the
     * entire working payload.
     *
     * @param text Raw content to excerpt.
     * @param binding Binding that defines the relevant token-counting behavior.
     * @return A compact excerpt suitable for inclusion in the critical section.
     */
    private fun compactContentExcerpt(text: String, binding: JunctionBinding?): String
    {
        val settings = resolveMemoryTruncationSettings(binding)
        val excerptBudget = maxOf(junctionMemoryPolicy.minimumCriticalBudget / 2, 128)
        return budgetText(text, excerptBudget, settings, preserveStart = true)
    }

    /**
     * Convert an envelope into trace metadata so the debugger can explain how the request was compacted.
     *
     * @param envelope The envelope that was built for the outbound request.
     * @return A compact trace-safe metadata map.
     */
    private fun envelopeMetadata(envelope: JunctionMemoryEnvelope): Map<String, Any>
    {
        return mapOf(
            "memoryRole" to envelope.roleKind.name,
            "memoryRoleName" to envelope.roleName,
            "resolvedBudget" to envelope.resolvedBudget,
            "availableBudget" to envelope.availableBudget,
            "safetyReserveTokens" to envelope.safetyReserveTokens,
            "criticalBudget" to envelope.criticalBudget,
            "recentBudget" to envelope.recentBudget,
            "summaryBudget" to envelope.summaryBudget,
            "summarizationUsed" to envelope.summarizationUsed,
            "compacted" to envelope.compacted,
            "sections" to envelope.sections.map { section ->
                mapOf(
                    "name" to section.name,
                    "tokenBudget" to section.tokenBudget,
                    "tokenCount" to section.tokenCount,
                    "truncated" to section.truncated
                )
            }
        )
    }

    /**
     * Render the compact memory envelope into a [MultimodalContent] prompt.
     *
     * The returned content carries deep-copied context objects so downstream code sees a stable snapshot
     * rather than a live alias back into Junction's working state.
     *
     * @param envelope The compact memory envelope to render.
     * @param workingContent Source content used to preserve binary payloads.
     * @return A prompt ready to send through P2P.
     * @throws IllegalStateException If the envelope could not produce any usable prompt text.
     */
    private fun buildPromptFromEnvelope(envelope: JunctionMemoryEnvelope, workingContent: MultimodalContent): MultimodalContent
    {
        val promptText = envelope.contextWindow.contextElements.joinToString("\n\n")
        if(promptText.isBlank())
        {
            throw IllegalStateException("Junction could not build a compact prompt for ${envelope.roleName}.")
        }

        return MultimodalContent(
            text = promptText,
            binaryContent = workingContent.binaryContent.toMutableList(),
            terminatePipeline = false,
            context = envelope.contextWindow.deepCopy(),
            miniBankContext = envelope.miniBank.deepCopy()
        )
    }

    /**
     * Return every currently registered binding in a stable order.
     *
     * The ordered view keeps uniqueness checks, trace metadata, and graph validation aligned across both the
     * discussion and workflow sides of the harness.
     *
     * @return A flat list of all registered Junction bindings.
     */
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

    /**
     * Return the set of all registered binding names.
     *
     * @return Stable names for every configured binding.
     */
    private fun allBindingNames(): Set<String>
    {
        return allBindings().map { binding -> binding.roleName }.toSet()
    }

    /**
     * Attach Junction as the container object when the participant is not already owned by another harness.
     *
     * This preserves nested harness ancestry while still giving standalone participants a Junction parent for
     * cycle detection and trace reasoning.
     *
     * @param component The P2P-capable component being bound.
     */
    private fun bindContainerReference(component: P2PInterface)
    {
        // Preserve an existing container owner if the component already belongs to a larger harness graph;
        // Junction only claims the container slot when it has not already been assigned.
        if(component.getContainerObject() == null)
        {
            component.setContainerObject(this)
        }
    }

    /**
     * Normalize and register one workflow binding.
     *
     * Workflow roles share the same binding model as discussion roles so the harness can synthesize descriptors,
     * requirements, and trace output consistently for any P2P participant.
     *
     * @param roleName Logical role name to register.
     * @param component Participant or container that will execute the role.
     * @param kind High-level workflow role category.
     * @param descriptor Optional explicit descriptor.
     * @param requirements Optional explicit requirements.
     * @param description Human-readable description used when synthesizing a descriptor.
     * @return The registered binding object.
     */
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

    /**
     * Validate the discussion participant graph before execution begins.
     *
     * @throws IllegalStateException If a participant ancestry chain contains a cycle or exceeds the depth cap.
     */
    private fun validateParticipantGraphs()
    {
        // Validate discussion participants and the moderator together so any container ancestry problem is
        // discovered before Junction starts dispatching votes.
        listOfNotNull(moderatorBinding).plus(participantBindings).forEach { binding ->
            validateContainerAncestry("participant '${binding.roleName}'", binding.component)
        }
    }

    /**
     * Validate the workflow participant graph before execution begins.
     *
     * Workflow bindings are checked separately because each recipe can wire different containers into the
     * planner, actor, verifier, adjuster, and output phases.
     *
     * @throws IllegalStateException If a workflow ancestry chain contains a cycle or exceeds the depth cap.
     */
    private fun validateWorkflowGraphs()
    {
        // Workflow bindings are checked separately because a recipe can bind different P2P containers for the
        // planner, actor, verifier, adjuster, and output steps.
        listOfNotNull(plannerBinding, actorBinding, verifierBinding, adjusterBinding, outputBinding)
            .forEach { binding ->
                validateContainerAncestry("workflow binding '${binding.roleName}'", binding.component)
            }
    }

    /**
     * Execute one workflow phase, using a configured binding when available and a synthesized fallback when not.
     *
     * The fallback path is intentional: some recipes are meant to hand off instructions or keep running even
     * when a phase role has not been configured yet. That keeps Junction usable as a harness instead of forcing
     * every recipe to be fully populated.
     *
     * @param phase Workflow phase to execute.
     * @param workingContent Current workflow payload.
     * @param cycleNumber Active workflow cycle number.
     * @return A structured phase result, either from a bound participant or from a synthesized fallback.
     */
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

    /**
     * Execute the vote phase by synthesizing a structured vote result from the current discussion state.
     *
     * Vote is intentionally local because it is derived from the participant opinions Junction already
     * collected. The result is still routed through the same workflow-state cache so later phases can inspect it.
     *
     * @param workingContent Current workflow payload.
     * @param cycleNumber Active workflow cycle number.
     * @return Structured vote-phase result.
     */
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

    /**
     * Execute a workflow phase through a bound P2P participant, or fall back to a safe synthesized result.
     *
     * When a binding is present, Junction renders the phase prompt through the compact memory envelope,
     * dispatches the request, and normalizes the response back into a structured result. When no binding is
     * available, the caller receives the default result instead of a hard failure so recipe variants can remain
     * handoff-friendly.
     *
     * @param phase Workflow phase being executed.
     * @param binding Bound participant responsible for the phase, if configured.
     * @param workingContent Current workflow payload.
     * @param cycleNumber Active workflow cycle number.
     * @param defaultResult Safe fallback result to use when no binding exists or the call fails.
     * @return Structured phase result for the workflow engine.
     */
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
        val envelope = request.prompt.metadata["junctionMemoryEnvelope"] as? JunctionMemoryEnvelope
        request.context = request.prompt.context.deepCopy()

        trace(
            TraceEventType.JUNCTION_PARTICIPANT_DISPATCH,
            TracePhase.AGENT_COMMUNICATION,
            workingContent,
            metadata = buildMap {
                put("participant", binding.roleName)
                put("phase", phase.name)
                put("cycle", cycleNumber)
                if(envelope != null)
                {
                    putAll(envelopeMetadata(envelope))
                }
            }
        )

        return try
        {
            // The participant sees a fully rendered prompt that includes the current discussion and workflow
            // state, then Junction tries to deserialize the response back into a phase result.
            val response = binding.component.executeP2PRequest(request)
            val output = response?.output?.text.orEmpty()
            trace(
                TraceEventType.JUNCTION_PARTICIPANT_RESPONSE,
                TracePhase.AGENT_COMMUNICATION,
                response?.output,
                metadata = buildMap {
                    put("participant", binding.roleName)
                    put("phase", phase.name)
                    put("cycle", cycleNumber)
                    put("hasRejection", response?.rejection != null)
                    if(envelope != null)
                    {
                        putAll(envelopeMetadata(envelope))
                    }
                }
            )
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

    /**
     * Build the prompt for one workflow phase by attaching the compact memory envelope to the working content.
     *
     * @param binding Target workflow binding.
     * @param phase Phase being executed.
     * @param workingContent Current workflow payload.
     * @param cycleNumber Active workflow cycle number.
     * @return A prompt ready to dispatch through P2P.
     */
    private fun buildWorkflowPhasePrompt(
        binding: JunctionBinding,
        phase: JunctionWorkflowPhase,
        workingContent: MultimodalContent,
        cycleNumber: Int
    ): MultimodalContent
    {
        val envelope = buildWorkflowMemoryEnvelope(binding, phase, workingContent, cycleNumber)
        val prompt = buildPromptFromEnvelope(envelope, workingContent)
        prompt.metadata["junctionMemoryEnvelope"] = envelope.deepCopy()
        return prompt
    }

    /**
     * Build a structured fallback workflow result.
     *
     * This keeps the recipe engine moving when a phase is intentionally unbound or a safe local response is
     * better than failing the whole harness.
     *
     * @param phase Workflow phase being represented.
     * @param cycleNumber Active workflow cycle number.
     * @param participantName Name to record as the phase owner.
     * @param text Primary text content for the phase result.
     * @param passed Whether the phase should be treated as successful.
     * @param repeatRequested Whether the recipe should repeat after this phase.
     * @param terminateRequested Whether the recipe should stop immediately after this phase.
     * @param notes Human-readable explanation for the fallback.
     * @return A normalized phase result.
     */
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

    /**
     * Build the fallback plan text used when no planner is configured.
     *
     * @param cycleNumber Workflow cycle number for the synthesized plan.
     * @return A simple planning summary.
     */
    private fun buildDefaultPlanText(cycleNumber: Int): String
    {
        return buildString {
            appendLine("Plan for cycle $cycleNumber")
            appendLine("Topic: ${discussionState.topic}")
            appendLine("Focus on the vote results and the current workflow state.")
        }
    }

    /**
     * Build the fallback act text used when no actor is configured.
     *
     * @param cycleNumber Workflow cycle number for the synthesized action.
     * @return A simple action summary.
     */
    private fun buildDefaultActText(cycleNumber: Int): String
    {
        return buildString {
            appendLine("Action for cycle $cycleNumber")
            appendLine("Execute or hand off the plan using the current workflow state.")
        }
    }

    /**
     * Build the fallback verify text used when no verifier is configured.
     *
     * @return A simple verification summary.
     */
    private fun buildDefaultVerifyText(): String
    {
        return buildString {
            appendLine("Verification summary")
            appendLine("Confirm that the workflow output is ready for the next step.")
        }
    }

    /**
     * Build the fallback adjust text used when no adjuster is configured.
     *
     * @return A simple adjustment summary.
     */
    private fun buildDefaultAdjustText(): String
    {
        return buildString {
            appendLine("Adjustment summary")
            appendLine("Refine the current plan based on the latest vote and verification state.")
        }
    }

    /**
     * Build the compact discussion envelope for a participant request.
     *
     * @param binding Participant binding that will receive the request.
     * @param workingContent Current discussion payload.
     * @param roundNumber Active discussion round number.
     * @return A memory envelope tailored to a discussion participant.
     */
    private fun buildParticipantMemoryEnvelope(
        binding: JunctionBinding,
        workingContent: MultimodalContent,
        roundNumber: Int
    ): JunctionMemoryEnvelope
    {
        val criticalLines = buildDiscussionCriticalLines(workingContent, roundNumber, binding)
        val recentLines = buildDiscussionRecentLines()
        val summarySeed = buildDiscussionSummarySeed()
        return budgetEnvelope(
            roleName = binding.roleName,
            roleKind = JunctionMemoryRole.DISCUSSION_PARTICIPANT,
            binding = binding,
            workingContent = workingContent,
            criticalLines = criticalLines,
            recentLines = recentLines,
            summarySeed = summarySeed,
            summaryLabel = "Older discussion summary"
        )
    }

    /**
     * Build the compact discussion envelope for the moderator.
     *
     * The moderator receives a slightly richer snapshot because it needs the current vote tally and the
     * participant opinions that produced it, but the envelope is still bounded by the same deterministic budget.
     *
     * @param workingContent Current discussion payload.
     * @param opinions Participant opinions collected this round.
     * @param voteResults Weighted vote tally for the round.
     * @return A memory envelope tailored to the moderator.
     */
    private fun buildModeratorMemoryEnvelope(
        workingContent: MultimodalContent,
        opinions: List<ParticipantOpinion>,
        voteResults: List<VotingResult>
    ): JunctionMemoryEnvelope
    {
        val binding = moderatorBinding ?: throw IllegalStateException("Junction moderator is not configured.")
        val opinionLines = opinions
            .sortedWith(compareBy<ParticipantOpinion> { it.roundNumber }.thenBy { it.participantName })
            .reversed()
            .map { opinion ->
                "${opinion.participantName} voted ${opinion.vote.ifBlank { opinion.opinion }} with confidence ${opinion.confidence}"
            }
        val voteLines = voteResults.map { result ->
            "${result.option}: ${result.percentage * 100.0}% support from ${result.supporters.joinToString()}"
        }
        val criticalLines = buildList {
            add("Role: ${binding.roleName}")
            add("Topic: ${discussionState.topic}")
            add("Round: ${discussionState.currentRound} of ${discussionState.maxRounds}")
            add("Strategy: ${discussionState.strategy.name}")
            add("Consensus threshold: ${discussionState.consensusThreshold}")
            add("Consensus reached: ${discussionState.consensusReached}")
            add("Final decision: ${discussionState.finalDecision.ifBlank { "<pending>" }}")
            add("Moderator notes: ${discussionState.moderatorNotes.ifBlank { "<none>" }}")
            add("Current vote tally:")
            addAll(voteLines.take(4))
            add("Current participant opinions:")
            addAll(opinionLines.take(4))
            add("Latest content excerpt: ${compactContentExcerpt(workingContent.text, binding)}")
        }

        val recentLines = buildList {
            addAll(discussionState.roundLog.takeLast(junctionMemoryPolicy.recentDiscussionEntries).reversed().map { "Log: $it" })
            addAll(opinionLines.take(junctionMemoryPolicy.recentOpinionCount))
        }

        return budgetEnvelope(
            roleName = binding.roleName,
            roleKind = JunctionMemoryRole.DISCUSSION_MODERATOR,
            binding = binding,
            workingContent = workingContent,
            criticalLines = criticalLines,
            recentLines = recentLines,
            summarySeed = buildString {
                if(discussionState.roundLog.size > junctionMemoryPolicy.recentDiscussionEntries)
                {
                    appendLine("Older round log entries omitted: ${discussionState.roundLog.size - junctionMemoryPolicy.recentDiscussionEntries}")
                }
                if(opinionLines.size > junctionMemoryPolicy.recentOpinionCount)
                {
                    appendLine("Older participant opinions omitted: ${opinionLines.size - junctionMemoryPolicy.recentOpinionCount}")
                }
                if(voteLines.isNotEmpty())
                {
                    appendLine("Current vote tally:")
                    voteLines.forEach { appendLine(it) }
                }
            }.trim(),
            summaryLabel = "Older moderator summary"
        )
    }

    /**
     * Build the compact memory envelope for one workflow phase.
     *
     * @param binding Workflow binding receiving the phase request.
     * @param phase Workflow phase being executed.
     * @param workingContent Current workflow payload.
     * @param cycleNumber Active workflow cycle number.
     * @return A memory envelope tailored to the workflow role.
     */
    private fun buildWorkflowMemoryEnvelope(
        binding: JunctionBinding,
        phase: JunctionWorkflowPhase,
        workingContent: MultimodalContent,
        cycleNumber: Int
    ): JunctionMemoryEnvelope
    {
        val criticalLines = buildWorkflowCriticalLines(workingContent, phase, cycleNumber, binding)
        val recentLines = buildWorkflowRecentLines()
        val summarySeed = buildWorkflowSummarySeed()
        return budgetEnvelope(
            roleName = binding.roleName,
            roleKind = when(phase)
            {
                JunctionWorkflowPhase.PLAN -> JunctionMemoryRole.WORKFLOW_PLANNER
                JunctionWorkflowPhase.ACT -> JunctionMemoryRole.WORKFLOW_ACTOR
                JunctionWorkflowPhase.VERIFY -> JunctionMemoryRole.WORKFLOW_VERIFIER
                JunctionWorkflowPhase.ADJUST -> JunctionMemoryRole.WORKFLOW_ADJUSTER
                JunctionWorkflowPhase.OUTPUT -> JunctionMemoryRole.WORKFLOW_OUTPUT
                JunctionWorkflowPhase.VOTE -> JunctionMemoryRole.DISCUSSION_PARTICIPANT
            },
            binding = binding,
            workingContent = workingContent,
            criticalLines = criticalLines,
            recentLines = recentLines,
            summarySeed = summarySeed,
            summaryLabel = "Older workflow summary"
        )
    }

    /**
     * Build the fallback workflow summary when no explicit output handler is configured.
     *
     * @return A bounded human-readable workflow summary.
     */
    private fun buildWorkflowSummaryText(): String
    {
        // When no explicit output handler is configured, Junction synthesizes a human-readable summary from the
        // phase cache so the caller still receives a usable handoff artifact.
        val settings = resolveMemoryTruncationSettings(outputBinding ?: plannerBinding ?: actorBinding ?: verifierBinding ?: adjusterBinding)
        val rawSummary = buildString {
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
        }.trim()

        return budgetText(
            rawSummary,
            maxOf(junctionMemoryPolicy.minimumCriticalBudget, junctionMemoryPolicy.outboundTokenBudget / 2),
            settings,
            preserveStart = true
        )
    }

    /**
     * Cache one workflow phase result into Junction's live workflow state.
     *
     * The state is kept both as structured data and as a concise log so the final workflow outcome can be
     * reconstructed without replaying the entire trace.
     *
     * @param result Phase result to cache.
     */
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

    /**
     * Decide whether the workflow should run another cycle.
     *
     * @param cycleResults Results from the cycle that just completed.
     * @return True when the recipe should loop again.
     */
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

    /**
     * Finish a workflow run by emitting the final output or handoff artifact.
     *
     * Handoff recipes stop early because they are intended to return instructions rather than force a second
     * output dispatch that could hide the caller's next step.
     *
     * @param workingContent Current workflow payload.
     * @return The final content object to return to the caller.
     */
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

    /**
     * Build the final discussion decision from the live discussion state.
     *
     * @return A structured discussion decision payload.
     */
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

    /**
     * Build the final workflow outcome from the live workflow state and returned content.
     *
     * @param topic Topic that seeded the workflow.
     * @param finalContent Final content returned to the caller.
     * @return Structured workflow outcome for the harness result.
     */
    /**
     * Build the final workflow outcome from the current workflow state and the returned content.
     *
     * @param topic Topic that seeded the workflow.
     * @param finalContent Final content returned to the caller.
     * @return Structured workflow outcome for the harness result.
     */
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

    /**
     * Validate a container ancestry chain with identity-based cycle detection.
     *
     * Junction cares about object-graph recursion, not class reuse, so the check uses identity tracking and
     * fails fast when it sees the same container twice or the chain becomes too deep to be safe.
     *
     * @param label Friendly label used in the error message.
     * @param component The starting P2P component to validate.
     * @throws IllegalStateException When the ancestry graph contains a cycle or exceeds the nesting limit.
     */
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

    /**
     * Render a human-readable container description for diagnostics and trace errors.
     *
     * @param container Object to describe.
     * @return A stable readable label for the container.
     */
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

    /**
     * Build and normalize one binding so Junction can talk to any P2PInterface uniformly.
     *
     * The helper synthesizes missing descriptor and requirement objects instead of forcing every caller to
     * preconfigure them, which keeps the harness usable with nested containers and lightweight test doubles.
     *
     * @param roleName Logical role name to register.
     * @param component Component that will execute the role.
     * @param kind Binding category used for trace and default descriptor synthesis.
     * @param descriptor Optional explicit descriptor.
     * @param requirements Optional explicit requirements.
     * @param description Human-readable description for synthesized descriptors.
     * @param weight Vote weight used when the binding participates in discussion.
     * @return A fully normalized binding record.
     */
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

    /**
     * Resolve the most useful role name available for a participant.
     *
     * @param component Component to inspect.
     * @param fallback Name to use when the component does not advertise one.
     * @return A stable role name for tracing and binding registration.
     */
    private fun resolveRoleName(component: P2PInterface, fallback: String): String
    {
        val descriptorName = component.getP2pDescription()?.agentName.orEmpty()
        if(descriptorName.isNotBlank())
        {
            return descriptorName
        }

        return component::class.simpleName?.takeIf { it.isNotBlank() } ?: fallback
    }

    /**
     * Resolve the participant set that should speak in the current round.
     *
     * Conversational mode honors moderator-selected participants when available, but Junction falls back to the
     * full registered participant list if the selection is empty or invalid so the discussion does not dead-end.
     *
     * @return The participant bindings to use for the current round.
     */
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

    /**
     * Run one discussion round across the selected participants.
     *
     * Simultaneous mode fans out through coroutines, while the sequential modes stay ordered so pause
     * checkpoints and moderator-driven speaker selection remain deterministic.
     *
     * @param roundParticipants Bindings that will speak this round.
     * @param workingContent Current discussion payload.
     * @param roundNumber Round number being executed.
     * @return Participant opinions collected for the round.
     */
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

    /**
     * Dispatch one participant request and normalize the response into a participant opinion.
     *
     * Failures are converted into neutral opinions so one broken participant does not tear down the entire
     * harness, but the failure is still traced for inspection.
     *
     * @param binding Participant binding to dispatch.
     * @param workingContent Current discussion payload.
     * @param roundNumber Active round number.
     * @return A participant opinion derived from the response or a neutral fallback.
     */
    private suspend fun dispatchParticipant(
        binding: JunctionBinding,
        workingContent: MultimodalContent,
        roundNumber: Int
    ): ParticipantOpinion
    {
        val request = buildParticipantRequest(binding, workingContent, roundNumber)
        val envelope = request.prompt.metadata["junctionMemoryEnvelope"] as? JunctionMemoryEnvelope

        // Dispatch and response events are traced separately so the visualizer can show both the outbound
        // request and the eventual participant reply for every round.
        trace(
            TraceEventType.JUNCTION_PARTICIPANT_DISPATCH,
            TracePhase.AGENT_COMMUNICATION,
            workingContent,
            metadata = buildMap {
                put("participant", binding.roleName)
                put("round", roundNumber)
                if(envelope != null)
                {
                    putAll(envelopeMetadata(envelope))
                }
            }
        )

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

    /**
     * Build the outbound discussion request for one participant.
     *
     * @param binding Participant binding to dispatch.
     * @param workingContent Current discussion payload.
     * @param roundNumber Active round number.
     * @return A fully formed P2P request with compact memory attached.
     */
    private fun buildParticipantRequest(
        binding: JunctionBinding,
        workingContent: MultimodalContent,
        roundNumber: Int
    ): P2PRequest
    {
        val envelope = buildParticipantMemoryEnvelope(binding, workingContent, roundNumber)
        val prompt = buildPromptFromEnvelope(envelope, workingContent)
        prompt.metadata["junctionMemoryEnvelope"] = envelope.deepCopy()

        return P2PRequest(
            transport = binding.transport,
            returnAddress = p2pTransport ?: P2PTransport(),
            prompt = prompt,
            context = prompt.context.deepCopy()
        )
    }

    /**
     * Parse a participant response into a structured opinion.
     *
     * Plain-text fallbacks are accepted so a partially cooperative participant can still contribute to the
     * discussion without needing perfect JSON output.
     *
     * @param participantName Name to stamp onto the opinion if the response leaves it blank.
     * @param roundNumber Round number to stamp onto the opinion if the response leaves it blank.
     * @param output Raw response text from the participant.
     * @return A structured participant opinion.
     */
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

    /**
     * Tally participant opinions into weighted vote results.
     *
     * Blank votes are ignored so noisy participants do not create meaningless tallies, and weights are read
     * from the registered binding so the discussion contract stays consistent with the harness setup.
     *
     * @param opinions Participant opinions to tally.
     * @return Weighted vote results ordered from strongest to weakest.
     */
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

    /**
     * Normalize a raw vote string so vote tallying can compare options consistently.
     *
     * @param value Raw vote text.
     * @return Trimmed vote string with normalized whitespace.
     */
    private fun normalizeVote(value: String): String
    {
        return value.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Ask the moderator for guidance or fall back to a safe default directive.
     *
     * The moderator is advisory, so a failure here does not tear down the discussion. Junction instead
     * preserves the current vote state and keeps the loop recoverable.
     *
     * @param workingContent Current discussion payload.
     * @param opinions Participant opinions collected this round.
     * @param voteResults Weighted vote tally for the round.
     * @return Moderator guidance or a deterministic fallback directive.
     */
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
        val envelope = prompt.metadata["junctionMemoryEnvelope"] as? JunctionMemoryEnvelope
        request.context = request.prompt.context.deepCopy()

        trace(
            TraceEventType.JUNCTION_PARTICIPANT_DISPATCH,
            TracePhase.AGENT_COMMUNICATION,
            workingContent,
            metadata = buildMap {
                put("participant", binding.roleName)
                put("round", discussionState.currentRound)
                if(envelope != null)
                {
                    putAll(envelopeMetadata(envelope))
                }
            }
        )

        return try
        {
            val response = binding.component.executeP2PRequest(request)
            val output = response?.output?.text.orEmpty()
            val parsed = deserialize<ModeratorDirective>(output)
            trace(
                TraceEventType.JUNCTION_PARTICIPANT_RESPONSE,
                TracePhase.AGENT_COMMUNICATION,
                response?.output,
                metadata = buildMap {
                    put("participant", binding.roleName)
                    put("round", discussionState.currentRound)
                    put("hasRejection", response?.rejection != null)
                    if(envelope != null)
                    {
                        putAll(envelopeMetadata(envelope))
                    }
                }
            )
            parsed ?: buildDefaultDirective(voteResults)
        }
        catch(e: Exception)
        {
            discussionState.roundLog.add("Moderator directive failed: ${e.message}")
            buildDefaultDirective(voteResults)
        }
    }

    /**
     * Build the outbound moderator prompt from the current discussion state.
     *
     * @param workingContent Current discussion payload.
     * @param opinions Participant opinions collected this round.
     * @param voteResults Weighted vote tally for the round.
     * @return A prompt ready to dispatch to the moderator binding.
     */
    private fun buildModeratorPrompt(
        workingContent: MultimodalContent,
        opinions: List<ParticipantOpinion>,
        voteResults: List<VotingResult>
    ): MultimodalContent
    {
        val envelope = buildModeratorMemoryEnvelope(workingContent, opinions, voteResults)
        val prompt = buildPromptFromEnvelope(envelope, workingContent)
        prompt.metadata["junctionMemoryEnvelope"] = envelope.deepCopy()
        return prompt
    }

    /**
     * Build the fallback moderator directive used when no moderator is available or the moderator fails.
     *
     * @param voteResults Current vote tally for the round.
     * @return A deterministic directive that keeps the harness moving safely.
     */
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

    /**
     * Honor a pending pause request only at a safe checkpoint.
     *
     * Junction deliberately waits between rounds or before a new dispatch so it never suspends in the middle
     * of a state merge, a vote tally, or a workflow phase transition.
     *
     * @param content Current content snapshot used for pause/resume tracing.
     */
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

    /**
     * Emit a Junction-specific trace event when tracing is enabled.
     *
     * Junction keeps its own event family so the visualizer can distinguish harness orchestration from the
     * lower-level pipe and P2P traces that it may be wrapping.
     *
     * @param eventType Junction event type to record.
     * @param phase Trace phase for the event.
     * @param content Optional content snapshot to attach.
     * @param metadata Additional compact metadata for the event.
     * @param error Optional error to record alongside the event.
     */
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

    /**
     * Build the compact metadata payload used by Junction trace events.
     *
     * @param baseMetadata Event-specific metadata to merge into the trace record.
     * @param error Optional error to serialize into the trace record.
     * @return A trace-safe metadata snapshot.
     */
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

    /**
     * Reset only the per-run runtime state of the harness.
     *
     * Configuration, bindings, and trace settings stay intact so a reused Junction instance behaves like a
     * fresh run without losing its setup.
     */
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

    /**
     * Decide whether a trace event should carry the full content snapshot.
     *
     * @param detailLevel Active trace detail level.
     * @return True when full content should be attached.
     */
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

    /**
     * Decide whether a trace event should carry the context snapshot.
     *
     * @param detailLevel Active trace detail level.
     * @return True when context should be attached.
     */
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
