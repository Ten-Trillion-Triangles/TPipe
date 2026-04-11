package com.TTT.Pipeline

import com.TTT.Debug.TraceConfig
import com.TTT.Enums.ContextWindowSettings
import com.TTT.Enums.SummaryMode
import com.TTT.P2P.AgentRequest
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PConcurrencyMode
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PSkills
import com.TTT.P2P.P2PTransport
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.examplePromptFor
import kotlinx.coroutines.runBlocking

private const val DEFAULT_MANIFOLD_AGENT_PIPE_NAME = "Agent caller pipe"

/**
 * Restricts nested manifold builder receivers so manager, worker, and history configuration blocks do not leak
 * methods into one another.
 */
@DslMarker
annotation class ManifoldDslMarker

/**
 * Kotlin-first builder for creating a [Manifold] with early validation and automatic startup.
 *
 * This DSL is intended to reduce the amount of manual boilerplate normally required to assemble a manifold while
 * still preserving access to custom manager and worker pipelines when needed.
 *
 * @param block Builder block that declares the manager, workers, history settings, and optional hooks.
 * @return Fully initialized manifold ready for execution.
 */
fun manifold(block: ManifoldDsl.() -> Unit): Manifold
{
    val builder = ManifoldDsl()
    builder.block()
    return builder.build()
}

/**
 * Root manifold DSL. This coordinates manager setup, worker registration, manager shared-history configuration,
 * and optional runtime hooks before materializing an initialized [Manifold].
 */
@ManifoldDslMarker
class ManifoldDsl
{
    private var managerConfiguration: ManagerConfiguration? = null
    private val workerConfigurations = mutableListOf<WorkerConfiguration>()
    private var historyConfiguration: HistoryConfiguration? = null
    private var validationConfiguration: ValidationConfiguration? = null
    private var tracingConfiguration: TraceConfig? = null
    private var summaryPipelineConfiguration: SummaryPipelineConfiguration? = null
    private var concurrencyModeConfiguration: P2PConcurrencyMode = P2PConcurrencyMode.SHARED

    /**
     * Set the P2P concurrency mode for this manifold when registered with the P2P registry.
     *
     * @param mode SHARED routes all requests to one instance. ISOLATED clones per request.
     */
    fun concurrencyMode(mode: P2PConcurrencyMode)
    {
        concurrencyModeConfiguration = mode
    }

    /**
     * Read the configured concurrency mode.
     *
     * @return The concurrency mode set on this DSL.
     */
    fun getConcurrencyMode(): P2PConcurrencyMode = concurrencyModeConfiguration

    /**
     * Configure the manager pipeline used to orchestrate work across manifold workers.
     *
     * @param block Builder block that declares the manager pipeline source and any custom dispatch-pipe names.
     */
    fun manager(block: ManagerDsl.() -> Unit)
    {
        /**
         * The manager must only be declared once so the DSL always has a single authoritative orchestrator.
         */
        require(managerConfiguration == null) { "Manager has already been configured for this manifold DSL." }

        val builder = ManagerDsl()
        builder.block()
        managerConfiguration = builder.build()
    }

    /**
     * Configure manager shared-history control for this manifold.
     *
     * @param block Builder block that defines token budgeting, truncation behavior, or custom truncation logic.
     */
    fun history(block: HistoryDsl.() -> Unit)
    {
        val builder = HistoryDsl()
        builder.block()
        historyConfiguration = historyConfiguration?.mergeWith(builder.build()) ?: builder.build()
    }

    /**
     * Declare a worker agent that can be called by the manifold manager.
     *
     * @param agentName Public routing name for the worker.
     * @param block Builder block that describes the worker pipeline and optional metadata.
     */
    fun worker(agentName: String, block: WorkerDsl.() -> Unit)
    {
        val builder = WorkerDsl(agentName)
        builder.block()
        workerConfigurations.add(builder.build())
    }

    /**
     * Configure optional validation, failure-recovery, and transformation hooks that should run between manager
     * and worker turns.
     *
     * @param block Builder block that binds the hook functions.
     */
    fun validation(block: ValidationDsl.() -> Unit)
    {
        /**
         * Only one validation block is allowed so hook precedence stays obvious and deterministic.
         */
        require(validationConfiguration == null) { "Validation has already been configured for this manifold DSL." }

        val builder = ValidationDsl()
        builder.block()
        validationConfiguration = builder.build()
    }

    /**
     * Configure tracing for the manifold and its child pipelines.
     *
     * @param block Builder block that enables tracing and optionally overrides the trace config.
     */
    fun tracing(block: TracingDsl.() -> Unit)
    {
        /**
         * Tracing is a single manifold-wide concern, so multiple tracing blocks are rejected.
         */
        require(tracingConfiguration == null) { "Tracing has already been configured for this manifold DSL." }

        val builder = TracingDsl()
        builder.block()
        tracingConfiguration = builder.build()
    }

    /**
     * Configure the optional summarization pipeline that runs after each worker response.
     *
     * @param block Builder block that declares the summary pipeline.
     */
    fun summaryPipeline(block: SummaryPipelineDsl.() -> Unit)
    {
        require(summaryPipelineConfiguration == null) { "Summary pipeline has already been configured for this manifold DSL." }

        val builder = SummaryPipelineDsl()
        builder.block()
        summaryPipelineConfiguration = builder.build()
    }

    /**
     * Build and initialize the configured [Manifold].
     *
     * @return Fully initialized manifold ready for use.
     * @warning This method uses [runBlocking] to initialize the manifold. Use [buildSuspend] in coroutine contexts.
     */
    fun build(): Manifold
    {
        val manifold = configureManifold()
        runBlocking {
            manifold.init()
        }
        return manifold
    }

    /**
     * Build and initialize the configured [Manifold] asynchronously.
     *
     * @return Fully initialized manifold ready for use.
     */
    suspend fun buildSuspend(): Manifold
    {
        val manifold = configureManifold()
        manifold.init()
        return manifold
    }

    /**
     * Validate all DSL declarations and materialize a configured [Manifold] that is ready for [Manifold.init].
     *
     * This is the single source of truth for DSL-to-Manifold wiring so [build] and [buildSuspend] stay in sync.
     *
     * @return Configured but not yet initialized manifold.
     */
    private fun configureManifold(): Manifold
    {
        val managerSpec = managerConfiguration ?: throw IllegalArgumentException(
            "A manifold manager must be configured with manager { ... } before build()."
        )

        validateWorkers()
        validateManager(managerSpec)
        val resolvedHistory = resolveHistoryConfiguration(managerSpec)

        /**
         * Once the DSL has been validated we materialize the real manifold and route all configuration through the
         * existing public Manifold API so the DSL stays aligned with normal runtime behavior.
         */
        val manifold = Manifold()

        if(tracingConfiguration != null)
        {
            manifold.enableTracing(tracingConfiguration!!)
        }

        applyHistoryConfiguration(manifold, resolvedHistory)

        manifold.setManagerPipeline(
            managerSpec.pipeline,
            managerSpec.descriptor,
            managerSpec.requirements
        )
        manifold.setP2pAgentNames(managerSpec.resolveAgentPipeNames())

        for(worker in workerConfigurations)
        {
            manifold.addWorkerPipeline(
                pipeline = worker.pipeline,
                descriptor = worker.descriptor,
                requirements = worker.requirements,
                agentName = worker.agentName,
                agentDescription = worker.description,
                agentSkills = worker.skills
            )
        }

        applyValidationConfiguration(manifold)
        applySummaryPipelineConfiguration(manifold)

        return manifold
    }

    /**
     * Validate the worker declarations collected on the DSL before a manifold is built.
     */
    private fun validateWorkers()
    {
        if(workerConfigurations.isEmpty())
        {
            throw IllegalArgumentException("At least one worker { ... } block is required to build a manifold.")
        }

        /**
         * Worker names become routing identifiers inside the manifold, so duplicates would produce ambiguous dispatch
         * behavior and need to be rejected before any P2P registration happens.
         */
        val duplicateNames = workerConfigurations
            .groupBy { worker -> worker.agentName }
            .filter { groupedWorkers -> groupedWorkers.value.size > 1 }
            .keys
            .sorted()
        if(duplicateNames.isNotEmpty())
        {
            throw IllegalArgumentException("Worker agent names must be unique. Duplicate names: ${duplicateNames.joinToString()}")
        }

        /**
         * When custom descriptors are supplied the manager routes by descriptor agentName rather than the DSL worker
         * label, so we must validate collisions on the effective routing identity before registration happens.
         */
        val duplicateRoutingIdentities = workerConfigurations
            .groupBy { worker -> worker.getEffectiveRoutingIdentity() }
            .filter { groupedWorkers -> groupedWorkers.value.size > 1 }
            .keys
            .sorted()
        if(duplicateRoutingIdentities.isNotEmpty())
        {
            throw IllegalArgumentException(
                "Worker routing identities must be unique. Duplicate agent names: ${duplicateRoutingIdentities.joinToString()}"
            )
        }

        /**
         * The registry keys agents by transport, so duplicated custom transports would silently collide during worker
         * registration even when DSL worker names differ.
         */
        val duplicateTransportIdentities = workerConfigurations
            .mapNotNull { worker -> worker.getEffectiveTransportIdentity() }
            .groupBy { transport -> transport }
            .filter { groupedWorkers -> groupedWorkers.value.size > 1 }
            .keys
            .sortedBy { transport -> "${transport.transportMethod}:${transport.transportAddress}" }
        if(duplicateTransportIdentities.isNotEmpty())
        {
            val duplicateDescriptions = duplicateTransportIdentities.joinToString { transport ->
                "${transport.transportMethod}:${transport.transportAddress}"
            }
            throw IllegalArgumentException(
                "Worker P2P transport identities must be unique. Duplicate transports: $duplicateDescriptions"
            )
        }

        for(worker in workerConfigurations)
        {
            if(worker.usesUnsupportedLocalWorkerTransport())
            {
                val descriptor = worker.descriptor!!
                throw IllegalArgumentException(
                    "Worker '${worker.agentName}' uses unsupported transport '${descriptor.transport.transportMethod}' " +
                        "for a manifold-local worker. DSL-defined manifold workers must use Transport.Tpipe."
                )
            }

            if(worker.usesCustomLocalTpipeTransportWithMismatchedAddress())
            {
                val descriptor = worker.descriptor!!
                throw IllegalArgumentException(
                    "Worker '${worker.agentName}' uses a local TPipe descriptor whose transportAddress " +
                        "'${descriptor.transport.transportAddress}' does not match its routing agentName " +
                        "'${descriptor.agentName}'. Local TPipe workers must use matching agentName and transportAddress."
                )
            }

            if(worker.pipeline.getPipes().isEmpty())
            {
                throw IllegalArgumentException("Worker '${worker.agentName}' must contain at least one pipe.")
            }

            if(!worker.pipeline.hasContextOverflowProtectionConfigured())
            {
                val unsafePipeNames = worker.pipeline.getPipesWithoutContextOverflowProtection().joinToString { pipe ->
                    pipe.pipeName.ifEmpty { "<unnamed pipe>" }
                }
                throw IllegalArgumentException(
                    "Worker '${worker.agentName}' is missing overflow protection on: $unsafePipeNames"
                )
            }
        }
    }

    /**
     * Validate the manager declaration before a manifold is built.
     *
     * @param managerSpec Manager configuration captured by the DSL.
     */
    private fun validateManager(managerSpec: ManagerConfiguration)
    {
        if(managerSpec.pipeline.getPipes().isEmpty())
        {
            throw IllegalArgumentException("The manifold manager pipeline must contain at least one pipe.")
        }

        /**
         * The manager must be able to emit AgentRequest JSON or the manifold cannot delegate work to any worker.
         */
        val agentRequestSchema = examplePromptFor(AgentRequest::class)
        val agentRequestPipes = managerSpec.pipeline.getPipes().filter { pipe ->
            pipe.jsonOutput == agentRequestSchema
        }

        if(agentRequestPipes.isEmpty())
        {
            throw IllegalArgumentException("The manager pipeline must contain a pipe that emits AgentRequest JSON.")
        }

        /**
         * If the caller did not explicitly identify the dispatch pipe, infer it when there is exactly one clear match.
         * Multiple agent-calling pipes require the user to be explicit so the builder does not guess incorrectly.
         */
        if(managerSpec.explicitAgentPipeNames.isEmpty())
        {
            if(agentRequestPipes.size > 1)
            {
                throw IllegalArgumentException(
                    "Manager pipeline contains multiple AgentRequest pipes. Declare agentDispatchPipe(\"...\") explicitly."
                )
            }

            val inferredPipe = agentRequestPipes.first()
            if(inferredPipe.pipeName.isBlank())
            {
                throw IllegalArgumentException(
                    "Manager AgentRequest pipe is unnamed. Give it a pipe name or declare agentDispatchPipe(\"...\")."
                )
            }
        }

        for(agentPipeName in managerSpec.explicitAgentPipeNames)
        {
            val resolvedPipe = managerSpec.pipeline.getPipeByName(agentPipeName).second
            if(resolvedPipe == null)
            {
                throw IllegalArgumentException(
                    "Manager dispatch pipe '$agentPipeName' was not found in the configured manager pipeline."
                )
            }

            if(!agentRequestPipes.contains(resolvedPipe))
            {
                throw IllegalArgumentException(
                    "Manager dispatch pipe '$agentPipeName' must emit AgentRequest JSON."
                )
            }
        }
    }

    /**
     * Resolve the final manager-history policy for the manifold.
     *
     * @param managerSpec Manager configuration captured by the DSL.
     * @return Resolved history configuration that should be applied to the manifold before init().
     */
    private fun resolveHistoryConfiguration(managerSpec: ManagerConfiguration): HistoryConfiguration
    {
        /**
         * When the primary manager pipe already has overflow protection configured, the DSL can safely activate the
         * manifold's shared-history control automatically instead of making the caller repeat that intent.
         */
        val primaryManagerPipe = managerSpec.pipeline.getPipes().first()
        val inferredHistoryConfiguration = if(primaryManagerPipe.hasContextOverflowProtectionConfigured())
        {
            HistoryConfiguration(enableAutoTruncate = true)
        }

        else
        {
            null
        }

        if(historyConfiguration != null)
        {
            return inferredHistoryConfiguration?.mergeWith(historyConfiguration!!) ?: historyConfiguration!!
        }

        if(inferredHistoryConfiguration != null)
        {
            return inferredHistoryConfiguration
        }

        throw IllegalArgumentException(
            "Manager shared history is not configured. Add history { ... } or configure overflow protection on the primary manager pipe."
        )
    }

    /**
     * Apply the resolved manager-history configuration to the real [Manifold].
     *
     * @param manifold Target manifold being built.
     * @param configuration Resolved history configuration.
     */
    private fun applyHistoryConfiguration(manifold: Manifold, configuration: HistoryConfiguration)
    {
        /**
         * Truncation method and context-window size are compatibility controls for the built-in manager-history path.
         */
        if(configuration.truncationMethod != null)
        {
            manifold.setTruncationMethod(configuration.truncationMethod!!)
        }

        if(configuration.contextWindowSize != null)
        {
            manifold.setContextWindowSize(configuration.contextWindowSize!!)
        }

        /**
         * An explicit token budget implies manager history control and should be applied before any legacy auto-truncate
         * toggles so the manifold uses the richer budget definition.
         */
        if(configuration.managerTokenBudget != null)
        {
            manifold.setManagerTokenBudget(configuration.managerTokenBudget!!)
        }

        else if(configuration.enableAutoTruncate)
        {
            manifold.autoTruncateContext()
        }

        if(configuration.truncationFunction != null)
        {
            manifold.setContextTruncationFunction(configuration.truncationFunction!!)
        }
    }

    /**
     * Apply any optional runtime hooks captured by the validation DSL.
     *
     * @param manifold Target manifold being built.
     */
    private fun applyValidationConfiguration(manifold: Manifold)
    {
        val configuration = validationConfiguration ?: return

        if(configuration.validator != null)
        {
            manifold.setValidatorFunction(configuration.validator!!)
        }

        if(configuration.failureHandler != null)
        {
            manifold.setFailureFunction(configuration.failureHandler!!)
        }

        if(configuration.transformer != null)
        {
            manifold.setTransformationFunction(configuration.transformer!!)
        }
    }

    /**
     * Apply the summary pipeline configuration to the manifold.
     */
    private fun applySummaryPipelineConfiguration(manifold: Manifold)
    {
        val configuration = summaryPipelineConfiguration ?: return
        manifold.setSummaryPipeline(configuration.pipeline, configuration.descriptor, configuration.requirements)
        manifold.setSummaryMode(configuration.summaryMode)
    }
}

/**
 * Builder for the manifold manager section.
 */
@ManifoldDslMarker
class ManagerDsl
{
    private var pipeline: Pipeline? = null
    private var descriptor: P2PDescriptor? = null
    private var requirements: P2PRequirements? = null
    private val agentPipeNames = linkedSetOf<String>()

    /**
     * Supply a pre-built manager pipeline.
     *
     * @param pipeline Manager pipeline to use.
     * @param descriptor Optional explicit P2P descriptor override.
     * @param requirements Optional explicit P2P requirements override.
     */
    fun pipeline(
        pipeline: Pipeline,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null
    )
    {
        /**
         * A manager can only have one pipeline definition inside the DSL.
         */
        require(this.pipeline == null) { "Manager pipeline has already been configured." }
        this.pipeline = pipeline
        this.descriptor = descriptor
        this.requirements = requirements
    }

    /**
     * Build the manager pipeline inline inside the DSL.
     *
     * @param descriptor Optional explicit P2P descriptor override.
     * @param requirements Optional explicit P2P requirements override.
     * @param block Builder block used to configure the created [Pipeline].
     */
    fun pipeline(
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        block: Pipeline.() -> Unit
    )
    {
        val builtPipeline = Pipeline()
        builtPipeline.block()
        pipeline(builtPipeline, descriptor, requirements)
    }

    /**
     * Register the manager pipe name that should receive the discovered worker-agent list during manifold startup.
     *
     * @param name Pipe name that should act as the manager's worker-dispatch pipe.
     */
    fun agentDispatchPipe(name: String)
    {
        agentPipeNames.add(name)
    }

    /**
     * Build the immutable manager configuration captured by this DSL block.
     *
     * @return Manager configuration ready for manifold assembly.
     */
    internal fun build(): ManagerConfiguration
    {
        val resolvedPipeline = pipeline ?: throw IllegalArgumentException(
            "Manager pipeline is required. Supply it with pipeline(existingPipeline) or pipeline { ... }."
        )
        validatePairedP2PSettings(
            descriptor = descriptor,
            requirements = requirements,
            targetDescription = "Manager pipeline"
        )

        return ManagerConfiguration(
            pipeline = resolvedPipeline,
            descriptor = descriptor,
            requirements = requirements,
            explicitAgentPipeNames = agentPipeNames.toList()
        )
    }
}

/**
 * Builder for manager shared-history configuration.
 */
@ManifoldDslMarker
class HistoryDsl
{
    private var enableAutoTruncate = false
    private var contextWindowSize: Int? = null
    private var truncationMethod: ContextWindowSettings? = null
    private var managerTokenBudget: TokenBudgetSettings? = null
    private var truncationFunction: (suspend (content: MultimodalContent) -> Unit)? = null

    /**
     * Enable the manifold's built-in manager shared-history truncation path.
     */
    fun autoTruncate()
    {
        enableAutoTruncate = true
    }

    /**
     * Set the compatibility context-window override used by the built-in manager-history truncation path.
     *
     * @param size Maximum manager history context size in tokens.
     */
    fun contextWindowSize(size: Int)
    {
        contextWindowSize = size
        enableAutoTruncate = true
    }

    /**
     * Apply a defaults-seeded compatibility context-window override without implying that manager shared-history
     * truncation should be turned on. This is used to mirror legacy defaults-module behavior exactly.
     *
     * @param size Compatibility manager-history context-window override.
     */
    fun defaultsContextWindowSize(size: Int)
    {
        contextWindowSize = size
    }

    /**
     * Set the truncation strategy used by manager shared-history control.
     *
     * @param method Truncation method to apply when history exceeds the budget.
     */
    fun truncationMethod(method: ContextWindowSettings)
    {
        truncationMethod = method
        enableAutoTruncate = true
    }

    /**
     * Apply a defaults-seeded truncation method without implying that manager shared-history truncation should be
     * turned on. This is used to mirror legacy defaults-module behavior exactly.
     *
     * @param method Compatibility manager-history truncation method.
     */
    fun defaultsTruncationMethod(method: ContextWindowSettings)
    {
        truncationMethod = method
    }

    /**
     * Seed a defaults-only placeholder truncation hook that keeps startup valid while preserving the semantics of a
     * disabled built-in manager budgeting profile from TPipe-Defaults.
     */
    fun defaultsCompatibilityTruncationPlaceholder()
    {
        truncationFunction = { _ -> }
        usesPlaceholderTruncationFunction = true
    }

    /**
     * Set an explicit token budget for the manager shared-history path.
     *
     * @param budget Token budget to apply to manager shared-history control.
     */
    fun managerTokenBudget(budget: TokenBudgetSettings)
    {
        managerTokenBudget = budget
        enableAutoTruncate = true
    }

    /**
     * Supply a custom truncation function for manager shared history.
     *
     * @param function Custom truncation logic that mutates the supplied content.
     */
    fun truncationFunction(function: suspend (content: MultimodalContent) -> Unit)
    {
        truncationFunction = function
        usesPlaceholderTruncationFunction = false
    }

    /**
     * Build the immutable history configuration captured by this DSL block.
     *
     * @return Resolved history configuration.
     */
    internal fun build(): HistoryConfiguration
    {
        return HistoryConfiguration(
            enableAutoTruncate = enableAutoTruncate,
            contextWindowSize = contextWindowSize,
            truncationMethod = truncationMethod,
            managerTokenBudget = managerTokenBudget,
            truncationFunction = truncationFunction,
            usesPlaceholderTruncationFunction = usesPlaceholderTruncationFunction
        )
    }

    private var usesPlaceholderTruncationFunction = false
}

/**
 * Builder for a worker definition inside the manifold DSL.
 *
 * @param agentName Public routing name assigned to this worker.
 */
@ManifoldDslMarker
class WorkerDsl(private val agentName: String)
{
    private var pipeline: Pipeline? = null
    private var descriptor: P2PDescriptor? = null
    private var requirements: P2PRequirements? = null
    private var description = ""
    private val skills = mutableListOf<P2PSkills>()

    /**
     * Set a human-readable description for this worker agent.
     *
     * @param description Description shown to the manager when choosing a worker.
     */
    fun description(description: String)
    {
        this.description = description
    }

    /**
     * Add a worker skill descriptor.
     *
     * @param name Skill name to advertise.
     * @param description Skill description to advertise.
     */
    fun skill(name: String, description: String)
    {
        skills.add(P2PSkills(name, description))
    }

    /**
     * Replace the worker skills with the supplied list.
     *
     * @param skills Worker skills to advertise.
     */
    fun skills(skills: List<P2PSkills>)
    {
        this.skills.clear()
        this.skills.addAll(skills)
    }

    /**
     * Supply a pre-built worker pipeline.
     *
     * @param pipeline Worker pipeline to use.
     * @param descriptor Optional explicit P2P descriptor override.
     * @param requirements Optional explicit P2P requirements override.
     */
    fun pipeline(
        pipeline: Pipeline,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null
    )
    {
        /**
         * Workers can only bind a single pipeline definition so the DSL does not end up with ambiguous agent wiring.
         */
        require(this.pipeline == null) { "Worker '$agentName' already has a pipeline configured." }
        this.pipeline = pipeline
        this.descriptor = descriptor
        this.requirements = requirements
    }

    /**
     * Build the worker pipeline inline inside the DSL.
     *
     * @param descriptor Optional explicit P2P descriptor override.
     * @param requirements Optional explicit P2P requirements override.
     * @param block Builder block used to configure the created [Pipeline].
     */
    fun pipeline(
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        block: Pipeline.() -> Unit
    )
    {
        val builtPipeline = Pipeline()
        builtPipeline.block()
        pipeline(builtPipeline, descriptor, requirements)
    }

    /**
     * Build the immutable worker configuration captured by this DSL block.
     *
     * @return Worker configuration ready for manifold assembly.
     */
    internal fun build(): WorkerConfiguration
    {
        val resolvedPipeline = pipeline ?: throw IllegalArgumentException(
            "Worker '$agentName' requires a pipeline(existingPipeline) or pipeline { ... } declaration."
        )
        validatePairedP2PSettings(
            descriptor = descriptor,
            requirements = requirements,
            targetDescription = "Worker '$agentName'"
        )

        return WorkerConfiguration(
            agentName = agentName,
            pipeline = resolvedPipeline,
            descriptor = descriptor,
            requirements = requirements,
            description = description,
            skills = skills.toList().ifEmpty { null }
        )
    }
}

/**
 * Builder for optional manifold validation and transformation hooks.
 */
@ManifoldDslMarker
class ValidationDsl
{
    private var validator: (suspend (content: MultimodalContent, agent: Pipeline) -> Boolean)? = null
    private var failureHandler: (suspend (content: MultimodalContent, agent: Pipeline) -> Boolean)? = null
    private var transformer: (suspend (content: MultimodalContent) -> MultimodalContent)? = null

    /**
     * Set the worker-output validator.
     *
     * @param function Validation function invoked after worker execution.
     */
    fun validator(function: suspend (content: MultimodalContent, agent: Pipeline) -> Boolean)
    {
        validator = function
    }

    /**
     * Set the failure-recovery hook invoked after validator failures.
     *
     * @param function Failure handler that attempts to recover from a bad worker result.
     */
    fun failure(function: suspend (content: MultimodalContent, agent: Pipeline) -> Boolean)
    {
        failureHandler = function
    }

    /**
     * Set the transformation hook applied between manifold turns.
     *
     * @param function Transformation function for generated content.
     */
    fun transformer(function: suspend (content: MultimodalContent) -> MultimodalContent)
    {
        transformer = function
    }

    /**
     * Build the immutable validation configuration captured by this DSL block.
     *
     * @return Validation configuration ready for manifold assembly.
     */
    internal fun build(): ValidationConfiguration
    {
        return ValidationConfiguration(
            validator = validator,
            failureHandler = failureHandler,
            transformer = transformer
        )
    }
}

/**
 * Builder for optional manifold tracing configuration.
 */
@ManifoldDslMarker
class TracingDsl
{
    private var config = TraceConfig(enabled = true)

    /**
     * Enable tracing with the current or supplied configuration.
     */
    fun enabled()
    {
        config = config.copy(enabled = true)
    }

    /**
     * Replace the trace configuration for the manifold.
     *
     * @param configuration Trace configuration to apply.
     */
    fun config(configuration: TraceConfig)
    {
        config = configuration.copy(enabled = true)
    }

    /**
     * Build the immutable tracing configuration captured by this DSL block.
     *
     * @return Trace configuration ready for manifold assembly.
     */
    internal fun build(): TraceConfig
    {
        return config
    }
}

/**
 * Builder for optional manifold summary pipeline configuration.
 */
@ManifoldDslMarker
class SummaryPipelineDsl
{
    private var pipeline: Pipeline? = null
    private var descriptor: P2PDescriptor? = null
    private var requirements: P2PRequirements? = null
    private var summaryMode: SummaryMode = SummaryMode.APPEND

    /**
     * Supply a pre-built summary pipeline.
     *
     * @param pipeline Summary pipeline to use.
     * @param descriptor Optional explicit P2P descriptor override.
     * @param requirements Optional explicit P2P requirements override.
     */
    fun pipeline(
        pipeline: Pipeline,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null
    )
    {
        require(this.pipeline == null) { "Summary pipeline has already been configured." }
        this.pipeline = pipeline
        this.descriptor = descriptor
        this.requirements = requirements
    }

    /**
     * Build the summary pipeline inline inside the DSL.
     *
     * @param descriptor Optional explicit P2P descriptor override.
     * @param requirements Optional explicit P2P requirements override.
     * @param block Builder block used to configure the created [Pipeline].
     */
    fun pipeline(
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null,
        block: Pipeline.() -> Unit
    )
    {
        val builtPipeline = Pipeline()
        builtPipeline.block()
        pipeline(builtPipeline, descriptor, requirements)
    }

    /**
     * Set the summarization mode for this summary pipeline.
     *
     * @param mode APPEND or REGENERATE.
     */
    fun summaryMode(mode: SummaryMode)
    {
        summaryMode = mode
    }

    /**
     * Build the immutable summary pipeline configuration captured by this DSL block.
     *
     * @return Summary pipeline configuration ready for manifold assembly.
     */
    internal fun build(): SummaryPipelineConfiguration
    {
        return SummaryPipelineConfiguration(
            pipeline = pipeline ?: throw IllegalArgumentException("Summary pipeline is required."),
            descriptor = descriptor,
            requirements = requirements,
            summaryMode = summaryMode
        )
    }
}

/**
 * Immutable manager configuration captured by [ManagerDsl].
 *
 * @property pipeline Manager pipeline to register on the manifold.
 * @property descriptor Optional explicit P2P descriptor override.
 * @property requirements Optional explicit P2P requirements override.
 * @property explicitAgentPipeNames Explicit dispatch-pipe names supplied by the caller.
 */
internal data class ManagerConfiguration(
    val pipeline: Pipeline,
    val descriptor: P2PDescriptor?,
    val requirements: P2PRequirements?,
    val explicitAgentPipeNames: List<String>
)
{
    /**
     * Resolve the manager pipe names that should receive the worker-agent list during manifold startup.
     *
     * @return Resolved manager dispatch-pipe names.
     */
    fun resolveAgentPipeNames(): List<String>
    {
        if(explicitAgentPipeNames.isNotEmpty())
        {
            return explicitAgentPipeNames
        }

        /**
         * When the DSL user does not explicitly name the dispatch pipe, infer it from the single AgentRequest-producing
         * pipe and fall back to the defaults-module convention when that pipe already uses the standard name.
         */
        val agentRequestSchema = examplePromptFor(AgentRequest::class)
        val inferredPipe = pipeline.getPipes().first { pipe ->
            pipe.jsonOutput == agentRequestSchema
        }
        val inferredName = inferredPipe.pipeName.ifBlank { DEFAULT_MANIFOLD_AGENT_PIPE_NAME }
        return listOf(inferredName)
    }
}

/**
 * Immutable worker configuration captured by [WorkerDsl].
 *
 * @property agentName Public routing name for the worker.
 * @property pipeline Worker pipeline to register on the manifold.
 * @property descriptor Optional explicit P2P descriptor override.
 * @property requirements Optional explicit P2P requirements override.
 * @property description Human-readable worker description.
 * @property skills Optional advertised worker skills.
 */
internal data class WorkerConfiguration(
    val agentName: String,
    val pipeline: Pipeline,
    val descriptor: P2PDescriptor?,
    val requirements: P2PRequirements?,
    val description: String,
    val skills: List<P2PSkills>?
)
{
    /**
     * Resolve the worker identity that manager dispatch will actually use at runtime.
     *
     * @return Descriptor agent name when a custom descriptor is supplied, otherwise the DSL worker name.
     */
    fun getEffectiveRoutingIdentity() : String
    {
        return descriptor?.agentName ?: agentName
    }

    /**
     * Resolve the worker transport identity that the registry will use during registration.
     *
     * @return Custom descriptor transport when present, otherwise null because default transports are derived from
     * the already-validated effective routing identity.
     */
    fun getEffectiveTransportIdentity() : P2PTransport?
    {
        return descriptor?.transport ?: P2PTransport(
            transportMethod = Transport.Tpipe,
            transportAddress = getEffectiveRoutingIdentity()
        )
    }

    /**
     * Local TPipe workers are routed by agent name, so custom descriptors must keep the transport address aligned with
     * the advertised routing identity or manifold dispatch will not be able to find them.
     */
    fun usesCustomLocalTpipeTransportWithMismatchedAddress() : Boolean
    {
        val workerDescriptor = descriptor ?: return false
        return workerDescriptor.transport.transportMethod == Transport.Tpipe &&
            workerDescriptor.transport.transportAddress != workerDescriptor.agentName
    }

    /**
     * DSL-defined manifold workers are hosted locally inside the same process and must therefore use the internal
     * TPipe transport rather than an external transport type.
     */
    fun usesUnsupportedLocalWorkerTransport() : Boolean
    {
        val workerDescriptor = descriptor ?: return false
        return workerDescriptor.transport.transportMethod != Transport.Tpipe
    }
}

/**
 * Immutable manager shared-history configuration captured by [HistoryDsl].
 *
 * @property enableAutoTruncate Whether built-in manager shared-history control should be enabled.
 * @property contextWindowSize Optional compatibility context-window override.
 * @property truncationMethod Optional truncation strategy.
 * @property managerTokenBudget Optional explicit manager token budget.
 * @property truncationFunction Optional custom truncation function.
 */
internal data class HistoryConfiguration(
    val enableAutoTruncate: Boolean = false,
    val contextWindowSize: Int? = null,
    val truncationMethod: ContextWindowSettings? = null,
    val managerTokenBudget: TokenBudgetSettings? = null,
    val truncationFunction: (suspend (content: MultimodalContent) -> Unit)? = null,
    val usesPlaceholderTruncationFunction: Boolean = false
)
{
    /**
     * Merge a later history configuration block into this one so defaults can seed history settings and a later
     * user-supplied history block can override only the fields it explicitly changes.
     *
     * @param overrideConfiguration Later history configuration to layer on top of this one.
     * @return Combined history configuration with later explicit values taking precedence.
     */
    fun mergeWith(overrideConfiguration: HistoryConfiguration): HistoryConfiguration
    {
        val overrideSuppliesRealHistoryControl = overrideConfiguration.enableAutoTruncate ||
            overrideConfiguration.managerTokenBudget != null ||
            (!overrideConfiguration.usesPlaceholderTruncationFunction && overrideConfiguration.truncationFunction != null)

        val mergedTruncationFunction = when
        {
            !overrideConfiguration.usesPlaceholderTruncationFunction && overrideConfiguration.truncationFunction != null ->
                overrideConfiguration.truncationFunction
            usesPlaceholderTruncationFunction && overrideSuppliesRealHistoryControl ->
                null
            overrideConfiguration.usesPlaceholderTruncationFunction && !overrideSuppliesRealHistoryControl ->
                overrideConfiguration.truncationFunction
            else ->
                truncationFunction
        }

        val mergedPlaceholderFlag = when
        {
            !overrideConfiguration.usesPlaceholderTruncationFunction && overrideConfiguration.truncationFunction != null ->
                false
            usesPlaceholderTruncationFunction && overrideSuppliesRealHistoryControl ->
                false
            overrideConfiguration.usesPlaceholderTruncationFunction && !overrideSuppliesRealHistoryControl ->
                true
            else ->
                usesPlaceholderTruncationFunction
        }

        return HistoryConfiguration(
            enableAutoTruncate = enableAutoTruncate || overrideConfiguration.enableAutoTruncate,
            contextWindowSize = overrideConfiguration.contextWindowSize ?: contextWindowSize,
            truncationMethod = overrideConfiguration.truncationMethod ?: truncationMethod,
            managerTokenBudget = overrideConfiguration.managerTokenBudget ?: managerTokenBudget,
            truncationFunction = mergedTruncationFunction,
            usesPlaceholderTruncationFunction = mergedPlaceholderFlag
        )
    }
}

/**
 * Immutable hook configuration captured by [ValidationDsl].
 *
 * @property validator Optional worker-output validator.
 * @property failureHandler Optional worker-output recovery hook.
 * @property transformer Optional manifold content transformer.
 */
internal data class ValidationConfiguration(
    val validator: (suspend (content: MultimodalContent, agent: Pipeline) -> Boolean)? = null,
    val failureHandler: (suspend (content: MultimodalContent, agent: Pipeline) -> Boolean)? = null,
    val transformer: (suspend (content: MultimodalContent) -> MultimodalContent)? = null
)

/**
 * Immutable summary pipeline configuration captured by [SummaryPipelineDsl].
 *
 * @property pipeline Summary pipeline to register on the manifold.
 * @property descriptor Optional explicit P2P descriptor override.
 * @property requirements Optional explicit P2P requirements override.
 * @property summaryMode Summarization mode controlling append vs. regenerate behavior.
 */
internal data class SummaryPipelineConfiguration(
    val pipeline: Pipeline,
    val descriptor: P2PDescriptor?,
    val requirements: P2PRequirements?,
    val summaryMode: SummaryMode = SummaryMode.APPEND
)

/**
 * Ensure advanced P2P overrides are supplied as a complete pair so the DSL never forwards partial input that the
 * underlying Manifold APIs would silently replace with generated defaults.
 *
 * @param descriptor Optional custom P2P descriptor.
 * @param requirements Optional custom P2P requirements.
 * @param targetDescription Human-readable name of the DSL target being validated.
 */
private fun validatePairedP2PSettings(
    descriptor: P2PDescriptor?,
    requirements: P2PRequirements?,
    targetDescription: String
)
{
    if((descriptor == null) == (requirements == null))
    {
        return
    }

    throw IllegalArgumentException(
        "$targetDescription must provide both descriptor and requirements together, or omit both to use generated defaults."
    )
}
