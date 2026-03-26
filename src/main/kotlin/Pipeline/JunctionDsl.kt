package com.TTT.Pipeline

import com.TTT.Debug.TraceConfig
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequirements
import kotlinx.coroutines.runBlocking

/**
 * Restricts nested Junction DSL receivers so moderator, participant, and tracing configuration do not leak across
 * blocks.
 */
@DslMarker
annotation class JunctionDslMarker

/**
 * Kotlin-first builder for creating a [Junction] with early validation and automatic startup.
 *
 * @param block Builder block that declares the moderator, participants, strategy, and optional tracing settings.
 * @return Fully initialized Junction ready for execution.
 */
fun junction(block: JunctionDsl.() -> Unit): Junction {
    val builder = JunctionDsl()
    builder.block()
    return builder.build()
}

/**
 * Root Junction DSL.
 *
 * This builder coordinates the moderator, the participants, the discussion strategy, and optional tracing before
 * materializing an initialized [Junction].
 */
@JunctionDslMarker
class JunctionDsl
{
    private var moderatorConfigured = false
    private val junction = Junction()

    /**
     * Configure the discussion moderator.
     *
     * @param roleName Stable moderator routing name.
     * @param component The P2P-capable moderator container or pipeline.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable moderator description.
     */
    fun moderator(
        roleName: String,
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    )
    {
        require(!moderatorConfigured) { "Junction DSL already has a moderator block." }
        moderatorConfigured = true
        junction.setModerator(
            roleName = roleName,
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Configure the discussion moderator without an explicit role name.
     *
     * @param component The P2P-capable moderator container or pipeline.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable moderator description.
     */
    fun moderator(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    )
    {
        require(!moderatorConfigured) { "Junction DSL already has a moderator block." }
        moderatorConfigured = true
        junction.setModerator(
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Declare a participant that can speak during the Junction discussion.
     *
     * @param roleName Stable participant routing name.
     * @param component The P2P-capable participant container or pipeline.
     * @param weight Vote weight for this participant.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable participant description.
     */
    fun participant(
        roleName: String,
        component: P2PInterface,
        weight: Double = 1.0,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    )
    {
        junction.addParticipant(
            roleName = roleName,
            component = component,
            weight = weight,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Declare a participant using a generated routing name.
     *
     * @param component The P2P-capable participant container or pipeline.
     * @param weight Vote weight for this participant.
     * @param descriptor Optional explicit P2P descriptor.
     * @param requirements Optional explicit P2P requirements.
     * @param description Optional human-readable participant description.
     */
    fun participant(
        component: P2PInterface,
        weight: Double = 1.0,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    )
    {
        junction.addParticipant(
            component = component,
            weight = weight,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Select the workflow recipe for the Junction.
     *
     * @param recipe The workflow recipe to use.
     */
    fun workflowRecipe(recipe: JunctionWorkflowRecipe)
    {
        junction.setWorkflowRecipe(recipe)
    }

    /**
     * Switch the DSL back to discussion-only execution.
     */
    fun discussionOnly()
    {
        junction.discussionOnly()
    }

    /**
     * Configure the vote -> act -> verify -> repeat workflow recipe.
     */
    fun voteActVerifyRepeat()
    {
        junction.voteActVerifyRepeat()
    }

    /**
     * Configure the act -> vote -> verify -> repeat workflow recipe.
     */
    fun actVoteVerifyRepeat()
    {
        junction.actVoteVerifyRepeat()
    }

    /**
     * Configure the vote -> plan -> act -> verify -> repeat workflow recipe.
     */
    fun votePlanActVerifyRepeat()
    {
        junction.votePlanActVerifyRepeat()
    }

    /**
     * Configure the plan -> vote -> act -> verify -> repeat workflow recipe.
     */
    fun planVoteActVerifyRepeat()
    {
        junction.planVoteActVerifyRepeat()
    }

    /**
     * Configure the vote -> plan -> output -> exit workflow recipe.
     */
    fun votePlanOutputExit()
    {
        junction.votePlanOutputExit()
    }

    /**
     * Configure the plan -> vote -> adjust -> output -> exit workflow recipe.
     */
    fun planVoteAdjustOutputExit()
    {
        junction.planVoteAdjustOutputExit()
    }

    /**
     * Configure the P2P descriptor for this Junction.
     *
     * @param descriptor The descriptor to use for this Junction.
     */
    fun descriptor(descriptor: P2PDescriptor)
    {
        junction.setP2pDescription(descriptor)
    }

    /**
     * Configure the P2P requirements for this Junction.
     *
     * @param requirements The requirements to use for this Junction.
     */
    fun requirements(requirements: P2PRequirements)
    {
        junction.setP2pRequirements(requirements)
    }

    /**
     * Configure the outbound memory policy used by the Junction harness.
     */
    fun memoryPolicy(block: JunctionMemoryPolicy.() -> Unit)
    {
        junction.memoryPolicy(block)
    }

    /**
     * Configure the planner role for workflow-oriented Junction execution.
     */
    fun planner(
        roleName: String,
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    )
    {
        junction.setPlanner(
            roleName = roleName,
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Configure the planner role without an explicit role name.
     */
    fun planner(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    )
    {
        junction.setPlanner(
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Configure the actor role for workflow-oriented Junction execution.
     */
    fun actor(
        roleName: String,
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    )
    {
        junction.setActor(
            roleName = roleName,
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Configure the actor role without an explicit role name.
     */
    fun actor(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    )
    {
        junction.setActor(
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Configure the verifier role for workflow-oriented Junction execution.
     */
    fun verifier(
        roleName: String,
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    )
    {
        junction.setVerifier(
            roleName = roleName,
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Configure the verifier role without an explicit role name.
     */
    fun verifier(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    )
    {
        junction.setVerifier(
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Configure the adjuster role for workflow-oriented Junction execution.
     */
    fun adjuster(
        roleName: String,
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    )
    {
        junction.setAdjuster(
            roleName = roleName,
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Configure the adjuster role without an explicit role name.
     */
    fun adjuster(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    )
    {
        junction.setAdjuster(
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Configure the output role for workflow-oriented Junction execution.
     */
    fun outputHandler(
        roleName: String,
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    )
    {
        junction.setOutputHandler(
            roleName = roleName,
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Configure the output role without an explicit role name.
     */
    fun outputHandler(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        description: String = ""
    )
    {
        junction.setOutputHandler(
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            description = description
        )
    }

    /**
     * Select the discussion strategy.
     *
     * @param strategy The strategy to use for turn scheduling.
     */
    fun strategy(strategy: DiscussionStrategy)
    {
        junction.setStrategy(strategy)
    }

    /**
     * Set the maximum number of discussion rounds.
     *
     * @param rounds Maximum number of rounds to execute.
     */
    fun rounds(rounds: Int)
    {
        junction.setRounds(rounds)
    }

    /**
     * Set the consensus threshold.
     *
     * @param threshold Voting threshold from 0.0 to 1.0.
     */
    fun threshold(threshold: Double)
    {
        junction.setVotingThreshold(threshold)
    }

    /**
     * Enable or disable moderator intervention.
     *
     * @param enabled Whether the moderator may override the discussion.
     */
    fun intervention(enabled: Boolean)
    {
        junction.setModeratorIntervention(enabled)
    }

    /**
     * Set the maximum nested P2P dispatch depth.
     *
     * @param depth Maximum recursion depth for nested containers.
     */
    fun maxNestedDepth(depth: Int)
    {
        junction.setMaxNestedDepth(depth)
    }

    /**
     * Configure tracing for the Junction harness.
     *
     * @param config The tracing configuration to use.
     */
    fun tracing(config: TraceConfig = TraceConfig(enabled = true))
    {
        junction.enableTracing(config)
    }

    /**
     * Build and initialize the configured Junction.
     *
     * The DSL performs initialization here so bad moderator/participant graphs fail before the caller gets
     * a half-configured harness object back. This keeps the builder ergonomic while still enforcing Junction's
     * runtime contract at construction time.
     *
     * @return Fully initialized Junction ready for execution.
     */
    fun build(): Junction
    {
        // Junction requires an authentication mechanism when the descriptor is configured to require auth,
        // ensuring that P2P requests cannot bypass security due to a missing validation lambda.
        val descriptor = junction.getP2pDescription()
        val requirements = junction.getP2pRequirements()

        if(descriptor?.requiresAuth == true)
        {
            require(requirements?.authMechanism != null) {
                "Junction requires an authMechanism when requiresAuth is enabled in the descriptor."
            }
        }

        // Run the actual harness initialization inside the builder so graph validation happens immediately
        // instead of being deferred to the first execution call.
        runBlocking {
            junction.init()
        }
        return junction
    }
}
