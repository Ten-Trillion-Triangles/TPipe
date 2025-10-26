package com.TTT.Pipeline

import com.TTT.Context.ContextWindow
import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Context.Dictionary
import com.TTT.Enums.ProviderName
import com.TTT.P2P.AgentDescriptor
import com.TTT.P2P.AgentRequest
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PSkills
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import com.TTT.Debug.*
import com.TTT.Enums.ContextWindowSettings
import com.TTT.Pipe.Pipe
import com.TTT.Util.exampleFor
import com.TTT.Util.getLowestContextWindowSize
import java.util.UUID

/**
 * Helper class to assist the programmer with tracking the progression of agentic task being managed by the Manifold
 * class. Allows the llm to state if the task is finished, issue the next things it thinks needs to be done,
 * and how far the progress on the task is thus far.
 */
@kotlinx.serialization.Serializable
data class TaskProgress(
    var taskDescription: String = "",
    var nextTaskInstructions: String = "",
    var taskProgressStatus: String = "",
    var isTaskComplete: Boolean = false
)

/**
 * A manifold is a class that allows for controlled task management and orchestration by a manager pipeline that
 * is able to select which worker pipelines to call to handle specialized steps of the task. The worker pipelines can
 * be called either by the coder's control over the manager's "human in the loop" functions, or by allowing the
 * manager pipeline to issue a call directly to the next pipeline it needs. The worker pipeline will perform the task
 * then return back to the manager pipeline allowing it to decide which pipeline to use for the next step of the task.
 * This repeats until the task is considered to be completed and the Multimodal content object will be released as
 * the result.
 */
class Manifold : P2PInterface
{
//============================================== P2PInterface ==========================================================

    private var p2pDescriptor: P2PDescriptor? = null
    private var p2pTransport: P2PTransport? = null
    private var p2PRequirements: P2PRequirements? = null
    private var containerObject: Any? = null

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
        p2PRequirements = requirements
    }

    override fun getP2pRequirements(): P2PRequirements?
    {
        return p2PRequirements
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
        return listOf(managerPipeline) + workerPipelines
    }

    /**
     * Wraps the execution function into a p2p response and allows agentic calls in.
     */
    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
    {
        val promptResult = execute(request.prompt)
        val newResponse = P2PResponse(output = promptResult)
        return newResponse
    }

//=============================================Properties===============================================================

    private var managerPipeline: Pipeline = Pipeline()
    private var workerPipelines: MutableList<Pipeline> = mutableListOf()

    /**
     * Most critical property in this class. This is the content object that will be worked on by every llm agent
     * inside the manifold. Perhaps we should move it to its own, more distinct and visible area in this file?
     */
    private var workingContentObject = MultimodalContent()

    /**
     * Local agent paths registered to be converted to an agent listing that can be supplied to the pipes in this
     * object. This is used to track which agents are registered to which pipelines.
     */
    private var agentPaths = mutableListOf<AgentDescriptor>()

    /**
     * Name of the pipe in the managerPipeline that is responsible for making p2p calls to the worker agents.
     * This pipe will need to have all the available agents passed back into it as pcp context, and then re-apply
     * it's system prompt to get them loaded into place.
     *
     * This is defaulted to the pipe name that will come with the standard pipeline to handle the given task.
     * By default, we'll provide standard pipelines that can be templated into functional agents to help idiot-proof,
     * and ensure that we users will have something functional out of the box that works for many general cases without
     * needing to directly fiddle with the setup in an extreme way.
     *
     * We'll invoke this in the init function in order to ensure that all agents are registered and ready to go.
     * @see init
     */
    private var agentPipeNames = mutableListOf<String>("Agent caller pipe")

    /**
     * If true the contents of the task will be truncated if it approaches the max tokens limit of the smallest
     * context window supported by a given worker agent, or the manager agent.
     */
    private var autoTruncateContext = false

    /**
     * Default to truncate top, but is adjustable in this container's settings.
     */
    private var truncationMethod = ContextWindowSettings.TruncateTop

    /**
     * Defaults to 32K but will be adjusted to whatever the lowest context window of the workers, or of any pipe
     * in the manager pipe or worker pipes.
     */
    private var contextWindowSize = 32000

    /**
     * Optional human in the loop funciton that allows for custom handling of context and context overflow.
     * Allows the coder to decide how to handle keeping context contained in the context window as expected.
     *
     * @param content The content object that is being worked on by the llm agent.
     */
    private var contextTruncationFunction: (suspend (content: MultimodalContent) -> Unit)? = null

    /**
     * Optional validation function for human in the loop intervention every time an agent completes
     * the task the manager pipeline has given it. Allows the coder to inspect, interrupt, or fail
     * the manifold task if need be.
     *
     * @param content The content object that is being worked on by the llm agent.
     * @param agent The agent that just completed the task.
     */
    private var workerValidatorFunction: (suspend (content: MultimodalContent, agent: Pipeline) -> Boolean)? = null

    /**
     * Human in the loop function to handle a failure before the manifold is shut down. Allows the coder to
     * attempt to rectify whatever the issue is.
     */
    private var failureFunction: (suspend (content: MultimodalContent, agent: Pipeline) -> Boolean)? = null

    /**
     * Human in the loop function to allow for content transformation upon a successful llm call. Also, can be used
     * to execute any pcp tools.
     */
    private var transformationFunction: (suspend (content: MultimodalContent) -> MultimodalContent)? = null



    /**
     * Tracing system properties for debugging and monitoring Manifold execution.
     */
    private var tracingEnabled = false
    private var traceConfig = TraceConfig()
    private val manifoldId = UUID.randomUUID().toString()
    private var currentTaskProgress = TaskProgress()
    private var loopIterationCount = 0
    private val agentInteractionMap = mutableMapOf<String, Int>()


//=============================================Exceptions===============================================================

    /**
     * Handle cases where pipes do not implement the required p2p setup.
     */
    fun hasP2P(pipeline: Pipeline)
    {
        var pipeHitCount = 0
        for(pipe in pipeline.getPipes())
        {
            if(pipe.getP2PAgentList() != null)
            {
                pipeHitCount++
            }
        }

        if(pipeHitCount <= 0) throw Exception("No P2P agent found in pipeline ${pipeline.pipelineName}")
    }


//=============================================Constructor==============================================================

    /**
     * If true, the manifold will automatically truncate the context of the workers and agent to enusre it stays
     * in safe token budgets.
     */
    fun autoTruncateContext() : Manifold
    {
        autoTruncateContext = true
        return this
    }

    /**
     * Defines how to truncate context. Allows for chopping off the top, the bottom, or the middle.
     */
    fun setTruncationMethod(method: ContextWindowSettings) : Manifold
    {
        truncationMethod = method
        return this
    }

    /**
     * Defines the maximum context window size. This is automatically set to the lowest pipe's context window size
     * to prevent an overflow that would crash the manifold.
     */
    fun setContextWindowSize(size: Int) : Manifold
    {
        contextWindowSize = size
        return this
    }

    /**
     * Allows the coder to define a function that will be called when the manifold is truncating the context
     * of the workers and the agent. This allows the coder to decide how to handle the truncation.
     */
    fun setContextTruncationFunction(func: suspend (content: MultimodalContent) -> Unit) : Manifold
    {
        contextTruncationFunction = func
        return this
    }


    /**
     * Allows the coder to define a function that will be called when the manifold is validating the content
     * of the workers and the agent. This allows the coder to decide how to handle the validation, and ensure
     * that the manifold has not produced any breaking, or otherwise invalid output. If not supplied validtion
     * attempts will be skipped.
     */
    fun setValidatorFunction(func: suspend (content: MultimodalContent, agent: Pipeline) -> Boolean) : Manifold
    {
        workerValidatorFunction = func
        return this
    }

    /**
     * Defines a branch failure function to attempt to recover the state of the manifold in the event of a validation
     * failure. This can only be called if the validation function is also valid.
     */
    fun setFailureFunction(func: suspend (content: MultimodalContent, agent: Pipeline) -> Boolean) : Manifold
    {
        failureFunction = func
        return this
    }

    /**
     * Allows the coder to transform the output of the pipelines after it has been produced. This will always be called
     * after the manager pipeline, or the worker pipelines are done running at each step if supplied.
     */
    fun setTransformationFunction(func: suspend (content: MultimodalContent) -> MultimodalContent) : Manifold
    {
        transformationFunction = func
        return this
    }

    

    /**
     * Adds new registered names to look for when sending agent lists to pipes that can make agent calls.
     * This doesn't need to be invoked unless you are not using the default manager pipe from TPipe-Defaults,
     * or you have a setup with multiple branching points where an agent would be called from the manifold.
     */
    fun addP2pAgentNames(pipe: Pipe)
    {
        agentPipeNames.add(pipe.pipeName)
    }

    fun addP2pAgentNames(name: String)
    {
        agentPipeNames.add(name)
    }

    /**
     * Assign the manager pipeline to this Manifold. The manager pipeline is the controller that determines the
     * progression of the task, and which worker pipeline must be called to perform a specific specialized action
     * to work towards completing the task.
     */
    fun setManagerPipeline(pipeline: Pipeline, descriptor: P2PDescriptor? = null, requirements: P2PRequirements? = null) : Manifold
    {
        managerPipeline = pipeline //Copy pipeline into our manager slot. Replaces the default pipeline.

        P2PRegistry.remove(managerPipeline) //Remove if we're registered somewhere else.


        /**
         * Check to ensure that at least one pipe in the manager pipeline is able to make the expected outbound
         * call to the worker agents. If not we need to throw an exception here.
         */
        var canCallAgents = false

        for(pipe in managerPipeline.getPipes())
        {
            val expectedSchema = exampleFor(AgentRequest::class).toString()

            if(pipe.jsonOutput == expectedSchema)
            {
                canCallAgents = true
                break
            }
        }

        if(!canCallAgents) throw Exception("No pipe in the manager pipeline can make agent calls.")


        /**
         * Auto generate the required settings for p2p registration based on general common default cases for
         * the Manifold class. This is required if the user does not pass through their own settings.
         * The P2PRegistry will throw if the settings are null when it tries to read from the interface.
         */
        if(descriptor == null || requirements == null)
        {
            val transport = P2PTransport()
            transport.transportAddress = "${managerPipeline.pipelineName}" //Standard to use pipeline name as address.
            transport.transportMethod = Transport.Tpipe

            /**
             * By default, we should pick a secure set of requirements that disallows external access, and
             * does not allow for agent duplication or custom context. Converse input is also being required
             * since the default manager pipeline will be using it to better track what each agent did.
             */
            val requirements = P2PRequirements(
                allowAgentDuplication = false,
                allowCustomContext = false,
                allowExternalConnections = false,
                requireConverseInput = true
            )

            /**
             * Most cases would likely be text only, and not accepting binary files. This covers typical cases
             * and also retains a decent level of security.
             */
            requirements.acceptedContent = mutableListOf(SupportedContentTypes.text)

            /**
             * Define the two core basic skills the manager does. Which is to dispatch jobs, and to validate the
             * progress on the task. Technically the Manifold class will be making the direct call in of itself.
             * So the agent won't need this description. However, it's best to include it to handle unexpected edge
             * cases the user causes.
             */
            val skills = mutableListOf<P2PSkills>()
            skills.add(P2PSkills("Validate", "Validate weather a task has been completed."))
            skills.add(P2PSkills("Dispatch", "Designate an agent to complete a given task"))


            /**
             * Define descriptor with typical defaults balancing standard use cases and security for agent
             * access. By default, only the agents inside this Manifold can call here.
             */
            p2pDescriptor = P2PDescriptor(
                agentName = "${managerPipeline.pipelineName}-Manifold-Manager",
                agentDescription = "Task orchestration system with manager and worker pipelines",
                transport = transport,
                requiresAuth = false,
                usesConverse = false,
                allowsAgentDuplication = false,
                allowsCustomContext = false,
                allowsCustomAgentJson = false,
                recordsInteractionContext = false,
                recordsPromptContent = false,
                allowsExternalContext = false,
                contextProtocol = com.TTT.P2P.ContextProtocol.pcp,
                agentSkills = skills
            )

            //Bind transport to make non null.
            val requestTemplate = P2PRequest()
            requestTemplate.transport = transport

            p2pTransport = transport
            p2PRequirements = requirements

           //Define now because things will break if the interface can't pull this later.
            managerPipeline.setP2pDescription(p2pDescriptor!!)
            managerPipeline.setP2pRequirements(requirements)
            managerPipeline.setP2pTransport(transport)

            /**
             * Finally, register the pipeline with the P2PRegistry. The transport and descriptor literally can't be null
             * This makes the need for !! very annoying, but it seems the kotlin compiler can't detect this specific case.
             * They are however, never null when registered from this function, so it's safe to use !! here.
             */
            P2PRegistry.register(managerPipeline, p2pTransport!!, p2pDescriptor!!, requirements)
            return this
        }

        /**
         * We need to bind this before we register. If we don't it will become global, and we'll end up
         * missing the agents when we pull the local agents in init. This obviously would cause a catastrophic
         * failure if we don't have any agents registered. We don't want it being registered as global unless
         * the user supplied a descriptor or requirements that makes the pipeline global.
         */
        managerPipeline.setContainerObject(this)

        P2PRegistry.remove(managerPipeline) //Remove to ensure we aren't double registered.

        /**
         * Register using assigned settings. None of them can be null otherwise we would not hit here.
         * So assume non-null and try to register.
         */
        P2PRegistry.register(managerPipeline, descriptor.transport, descriptor, requirements)

        return this
    }

    /**
     * Getter to read our manager pipeline. Provides some saftey since we want any sets to correctly register.
     */
    fun getManagerPipeline(): Pipeline
    {
        return managerPipeline
    }


    /**
     * Add a worker pipeline to this Manifold. Worker pipelines are specialized agents that perform
     * specific tasks as directed by the manager pipeline. Each worker is registered with the P2P system
     * to enable communication and task delegation from the manager.
     *
     * @param pipeline The worker pipeline to add to this Manifold's worker collection.
     * @param descriptor Optional P2P descriptor for custom worker configuration. If null, secure defaults are generated.
     * @param requirements Optional P2P requirements for custom worker settings. If null, secure defaults are generated.
     *
     * @return This Manifold object for method chaining.
     */
    fun addWorkerPipeline(pipeline: Pipeline,
                          descriptor: P2PDescriptor? = null,
                          requirements: P2PRequirements? = null,
                          agentName : String = "",
                          agentDescription: String = "",
                          agentSkills: List<P2PSkills>? = null): Manifold
    {
        workerPipelines.add(pipeline) //Add pipeline to our worker collection.

        P2PRegistry.remove(pipeline) //Remove if we're registered somewhere else.

        /**
         * Auto generate the required settings for p2p registration based on general common default cases for
         * worker pipelines in the Manifold class. This is required if the user does not pass through their own settings.
         * The P2PRegistry will throw if the settings are null when it tries to read from the interface.
         */
        if(descriptor == null || requirements == null)
        {
            val resolvedAgentName = agentName.ifEmpty { "${pipeline.pipelineName}-Manifold-Worker" }
            val transport = P2PTransport()
            transport.transportAddress = resolvedAgentName // Must match agentName for routing.
            transport.transportMethod = Transport.Tpipe

            /**
             * Worker pipelines should have slightly more permissive settings than the manager since they need
             * to accept tasks and context from the manager. However, external access is still restricted for security.
             * Converse input is required to track worker interactions with the manager.
             */
            val requirements = P2PRequirements(
                allowAgentDuplication = false,
                allowCustomContext = false,
                allowExternalConnections = false,
                requireConverseInput = true
            )


            requirements.acceptedContent = mutableListOf(SupportedContentTypes.text)

            /**
             * Define the core skill that all workers have - executing specialized tasks as directed by the manager.
             * Individual workers may have additional specialized skills, but this covers the basic worker capability.
             */
            var skills = mutableListOf<P2PSkills>()


            if(agentSkills == null)
            {
                skills.add(P2PSkills("Execute", "Execute specialized tasks as directed by the manager pipeline"))
            }

            else
            {
                skills = agentSkills.toMutableList()
            }

            //Define default description if not provided.
            var defaultDescription = let { agentDescription.ifEmpty { "Specialized worker pipeline for task execution within Manifold orchestration" } }

            /**
             * Define descriptor with worker-appropriate defaults. Workers are more permissive than managers
             * but still maintain security boundaries for external access.
             */
            val workerDescriptor = P2PDescriptor(
                agentName = resolvedAgentName,
                agentDescription = defaultDescription,
                transport = transport,
                requiresAuth = false,
                usesConverse = true, //Workers use converse to communicate with manager
                allowsAgentDuplication = false,
                allowsCustomContext = false,
                allowsCustomAgentJson = false,
                recordsInteractionContext = true, //Track interactions for debugging
                recordsPromptContent = true,
                allowsExternalContext = false,
                contextProtocol = com.TTT.P2P.ContextProtocol.pcp,
                agentSkills = skills
            )

            /**
             * We need to bind this before we register. If we don't it will become global, and we'll end up
             * missing the agents when we pull the local agents in init. This obviously would cause a catastrophic
             * failure if we don't have any agents registered.
             */
            pipeline.setContainerObject(this)

            /**
             * Register the worker pipeline with the P2PRegistry using generated settings.
             */
            P2PRegistry.register(pipeline, transport, workerDescriptor, requirements)
            return this
        }

        P2PRegistry.remove(pipeline) //Remove to ensure we aren't double registered.

        /**
         * Register using assigned settings. None of them can be null otherwise we would not hit here.
         * So assume non-null and try to register.
         */
        P2PRegistry.register(pipeline, descriptor.transport, descriptor, requirements)
        return this
    }



    /**
     * Critical startup function for the Manifold class. This handles binding the agent list to all the pipes that
     * need it. This is especially important if the user remaps the default manager pipeline, or the worker pipelines.
     *
     * @throws Exception When manager pipeline has no pipes configured - prevents manifold from functioning
     * @throws Exception When no worker pipelines are registered - manifold requires workers to delegate tasks
     * @throws Exception When manager pipeline descriptor is null - required for P2P agent communication
     * @throws Exception When agent calling pipe cannot be found by name - breaks task delegation system
     */
    suspend fun init()
    {
        //Step 1: Check for empty pipelines. If any are empty we need to throw on the spot.
        if(managerPipeline.getPipes().isEmpty() || workerPipelines.isEmpty()) throw Exception("One or more manager or worker pipelines are empty. Cannot start the manifold.")

        //Bind our container to ensure our manifold manager is local. Init to ensure it's ready to make llm calls.
        managerPipeline.setContainerObject(this)
        
        // Setup tracing propagation to manager pipeline if enabled
        if (tracingEnabled)
        {
            managerPipeline.enableTracing(traceConfig)
            // Set currentPipelineId on individual pipes in the manager pipeline
            for (pipe in managerPipeline.getPipes())
            {
                pipe.currentPipelineId = manifoldId
            }
        }
        
        managerPipeline.init(true)

       //Fetch our descriptor so that we can build our agent descriptor.
        val managerDescriptor = managerPipeline.getP2pDescription() ?: throw Exception("Manager pipeline descriptor is null. Cannot start the manifold.")
        val managerAgentDescriptor = AgentDescriptor.buildFromDescriptor(managerDescriptor)

        val localAgents = P2PRegistry.listLocalAgents(this).toMutableList()
        localAgents.remove(managerDescriptor) //Remove manager so that we don't broadcast this to the workers.

        /**
         * All agents inside the manifold class need to be registered locally, since they will always be called
         * by the manager pipeline through local scope by default.
         */
        for(descriptor in localAgents)
        {
            val agentDescriptor = AgentDescriptor.buildFromDescriptor(descriptor)
            agentPaths.add(agentDescriptor)
        }

        /**
         * Dispatch the agent list to every manager pipe that needs to be able to call the worker pipelines/agents.
         */
        for(agentPipeName in agentPipeNames)
        {
            val agentCallingManagerPipe = managerPipeline.getPipeByName(agentPipeName)

            /**
             * If no pipe matches the expected names stored, we will find the very last pipe and assign that to
             * store the agent list. This is a fallback to ensure we don't break the manifold. Common setups including
             * the default setup from TPipe-Defaults uses the very last pipe as the agent calling pipe. So this
             * should safely adress most cases.
             *
             * todo: This behavior needs to be covered in the documentation for TPipe.
             */
            if(agentCallingManagerPipe.second == null)
            {
                managerPipeline.getPipes().last().setP2PAgentList(agentPaths)
                    .applySystemPrompt()
            }

            /**
             * Bind the descriptors to the agent calling pipe so that it is able to do its job and know which pipelines
             * in this manifold it can call.
             */
            agentCallingManagerPipe.second?.setP2PAgentList(agentPaths)
                ?.applySystemPrompt()
        }

        //Activate all the worker pipes to ensure they are ready to make llm calls.
        for(workerPipe in workerPipelines)
        {
            workerPipe.setContainerObject(this)
            
            // Setup tracing propagation to worker pipelines if enabled
            if (tracingEnabled)
            {
                workerPipe.enableTracing(traceConfig)
                // Set currentPipelineId on individual pipes in the worker pipeline
                for (pipe in workerPipe.getPipes())
                {
                    pipe.currentPipelineId = manifoldId
                }
            }
            
            workerPipe.init(true)
        }

        /**
         * Finally, we need to determine what the maximum token budget for context, and prompts will be. This is
         * determined by whatever pipe has the smallest context window assigned. Since if we overflow for any it
         * will bring down the agent, and that will break the manifold.
         */
        if(!autoTruncateContext && contextTruncationFunction == null)
        {
            throw Exception("No method of managing context was found. This means that context can overflow" +
                    " and bring down the entire Manifold if misconfigured.")
        }

        var smallestWorkerSize = Int.MAX_VALUE
        for(pipeline in workerPipelines)
        {
            val contextSize = getLowestContextWindowSize(pipeline.getPipes())
            if(contextSize < smallestWorkerSize) smallestWorkerSize = contextSize
        }

        var smallestManagerContextSize = getLowestContextWindowSize(managerPipeline.getPipes())

        //Whichever is lower wins becoming the max context size we should allow when passing prompts.
        contextWindowSize =
            if(smallestManagerContextSize < smallestWorkerSize) smallestManagerContextSize else smallestWorkerSize

    }

//=================================================Execution============================================================

    /**
     * Executes the manifold orchestration system, coordinating between manager and worker pipelines
     * to complete complex tasks through agent delegation and task management.
     *
     * @param content The initial task content to be processed by the manifold system
     * @return The final processed content after all agent interactions are complete
     * @throws Exception When converse history extraction fails - required for tracking agent interactions
     */
    suspend fun execute(content: MultimodalContent) : MultimodalContent
    {
        /**
         * Throw if our manager pipeline does not have agents assigned. In this event we would have a silent but
         * likely catastrophic failure. So we need to throw here.
         */
        hasP2P(managerPipeline)

        // === TRACING: Initialize execution tracing ===
        if (tracingEnabled)
        {
            PipeTracer.startTrace(manifoldId)
            trace(TraceEventType.MANIFOLD_START, TracePhase.ORCHESTRATION, content,
                  metadata = mapOf(
                      "inputContentSize" to content.text.length,
                      "hasBinaryContent" to content.binaryContent.isNotEmpty()
                  ))
        }

        //We can skip this if the data is already formated in the required converse history format.
        val isConverseHistory = extractJson<ConverseHistory>(content.text)
        if(isConverseHistory == null)
        {
            // === TRACING: Converse history creation ===
            if (tracingEnabled)
            {
                trace(TraceEventType.CONVERSE_HISTORY_UPDATE, TracePhase.ORCHESTRATION,
                      metadata = mapOf("action" to "createInitialHistory"))
            }
            
            /**
             * We need to build an initial converse starter because the manager pipeline is expected converse by default.
             * This is also the intended use case to structure a custom manager pipeline if the default were to be
             * overridden. By using TPipe's converse system, we're able to ensure optimal tracking of the task
             * and the history of events that have occurred to attempt to resolve the task.
             */
            val newConverseHistory = ConverseHistory()
            newConverseHistory.add(ConverseRole.user, content)

            val converseHistoryAsJson = serialize(newConverseHistory, encodedefault = false)
            content.text = converseHistoryAsJson
            workingContentObject = MultimodalContent(content.text, content.binaryContent.toMutableList(), content.terminatePipeline)
        }

        //If we're skipping, we can push directly to the text portion of the object which forms our user prompt space.
        else
        {
            workingContentObject = MultimodalContent(content.text, content.binaryContent.toMutableList(), content.terminatePipeline)
        }

        // === TRACING: Reset loop counter ===
        loopIterationCount = 0

        /**
         * Execution will continue in this sequence as long as a terminate call or pass flag has not been set
         * in the content object. If you're using a custom manager pipeline, it's mission-critical that you validate
         * if the llm has decided the job is done and end the pipeline with a pass call. Otherwise, the manifold will
         * never terminate and the llm will loop in this pattern forever.
         */
        while(!workingContentObject.terminatePipeline && !workingContentObject.passPipeline)
        {
            // === TRACING: Loop iteration tracking ===
            loopIterationCount++
            if (tracingEnabled)
            {
                trace(TraceEventType.MANIFOLD_LOOP_ITERATION, TracePhase.ORCHESTRATION,
                      metadata = mapOf(
                          "iteration" to loopIterationCount,
                          "terminateFlag" to workingContentObject.terminatePipeline,
                          "passFlag" to workingContentObject.passPipeline
                      ))
            }

            //Step 1: Execute the manager pipeline and get the initial result of its actions.
            // === TRACING: Manager task analysis ===
            if (tracingEnabled)
            {
                trace(TraceEventType.MANAGER_TASK_ANALYSIS, TracePhase.ORCHESTRATION,
                      workingContentObject,
                      metadata = mapOf("managerPipeline" to managerPipeline.pipelineName))
            }

            /**
             * Automatically truncate the converse history if enabled. This prevents context window overflow and
             * protects from a pipeline crashing and bringing down the manifold unexpectedly. This will be ran
             * in favor of truncation function if both are enabled.
             */
            if(autoTruncateContext)
            {
                //Get the truncation settings for accurate dictionary parsing.
                val pipeTruncationSettings = managerPipeline.getPipes()[0].getTruncationSettings()

                //Get the converse history thus far. May be empty which will just return nothing in that case.
                var converseHistory = extractJson<ConverseHistory>(workingContentObject.text) ?: ConverseHistory()

                //Required boilerplate.
                val holdingWindow = ContextWindow()
                holdingWindow.converseHistory = converseHistory

                val usedTokens = Dictionary.countTokens(converseHistory.toString(), pipeTruncationSettings)
                if(usedTokens > contextWindowSize)
                {
                    /**
                     * Truncate into the given context window size, which is based on which pipe in the pipelines
                     * that are being ran in this manifold has the smallest context window.
                     */
                    holdingWindow.truncateConverseHistoryWithObject(
                        contextWindowSize,
                        0,
                        truncationMethod,
                        pipeTruncationSettings)

                    //Save back to our converseHistory object for the next stage.
                    converseHistory = holdingWindow.converseHistory
                    val newJson = serialize(converseHistory, encodedefault = false)
                    workingContentObject.text = newJson
                }
            }

            //Attempt to run the provided contextTruncation function instead if we aren't auto-truncating.
            else if(contextTruncationFunction != null)
            {
                contextTruncationFunction?.invoke(workingContentObject)
            }

            //Throw now before any llm's are ran to avoid any catastrophic outcomes created by user error.
            else
            {
                throw Exception("No method of managing context was found. This means that context can overflow" +
                        " and bring down the entire Manifold if misconfigured.")
            }

            //Get the result of the manager pipeline's execution and assessment of task status.
            val managerResult = managerPipeline.execute(workingContentObject)

            // === TRACING: Manager decision ===
            if (tracingEnabled)
            {
                trace(TraceEventType.MANAGER_DECISION, TracePhase.ORCHESTRATION, managerResult,
                      metadata = mapOf(
                          "responseLength" to managerResult.text.length,
                          "iteration" to loopIterationCount
                      ))
            }

            /**
             * Execute validation function if bound. This allows the human to check to see if the llm has broken
             * or otherwise, done something unacceptable. In that case, the issue can be attempted to be repaired,
             * and if we cannot repair it, we'll exit the manifold task to avoid harmful content, or actions
             * that could cause damage to the codebase, system, application, or end user.
             *
             * If not provided this check will be skipped and passed forward regardless of what the outcome was.
             */
            if(workerValidatorFunction != null)
            {
                trace(TraceEventType.VALIDATION_START, TracePhase.ORCHESTRATION,
                    metadata = mapOf("validatorFunction" to workerValidatorFunction.toString()))

                //Handle branch failure state if our validation function did not pass.
                if(workerValidatorFunction?.invoke(managerResult, managerPipeline) == false)
                {
                    //Execute branch failure if provided. This gives one last change to not end the manifold.
                    if(failureFunction != null)
                    {
                        trace(TraceEventType.VALIDATION_FAILURE, TracePhase.ORCHESTRATION,
                            metadata = mapOf("failureFunction" to failureFunction.toString()))

                        //Bail on the manifold if we can't pass this. Otherwise, we can resume the task.
                        if(failureFunction?.invoke(managerResult, managerPipeline) == false)
                        {
                            /**
                             * Optional: Allow provision of error message by using the multimodal object's metadata
                             * map which allows for any arbitrary data to be stored.
                             *
                             * todo: We should add this to the regular pipe class too.
                             */
                            val errorMessage = managerResult.metadata["error"] ?: "Unable to recover using branch failure function."

                            trace(TraceEventType.PIPE_FAILURE, TracePhase.ORCHESTRATION,
                                metadata = mapOf("failureFunction" to failureFunction.toString(),
                                    "reason" to errorMessage.toString()))

                            workingContentObject.terminate()
                            break
                        }

                    }

                    //No branch failure provided, but we were not able to proceed through the validator.
                    else
                    {
                        workingContentObject.terminate()
                        break
                    }
                }
            }



            //Get our response text which should hold our target p2p call.
            val responseText = managerResult.text

            /**
             * We're expecting that the result should always be an agent request. If it isn't we have to terminate
             * in a broken state and exit. It's not entirely impossible that a poorly formatted system prompt or
             * misbehaving llm might not produce the json as intended, or correctly. And in that case could prevent
             * the manifold from continuing.
             */
            var agentBatchCount = 0
            var agentRequest = extractJson<List<AgentRequest>>(responseText)?.let { list ->
                agentBatchCount = list.size
                list.firstOrNull()
            }

            if(agentRequest == null)
            {
                agentRequest = extractJson<AgentRequest>(responseText)
            }

            //Kill with an error if we didn't get a valid agent request as the final output.
            if(agentRequest == null)
            {
                // === TRACING: Invalid agent request ===
                if (tracingEnabled)
                {
                    trace(TraceEventType.AGENT_REQUEST_INVALID, TracePhase.ORCHESTRATION,
                          managerResult,
                          error = Exception("Invalid agent request format"),
                          metadata = mapOf(
                              "responseText" to responseText.take(200),
                              "iteration" to loopIterationCount
                          ))
                }


                workingContentObject.terminatePipeline = true
                break
            }

            // === TRACING: Valid agent request ===
            if (tracingEnabled)
            {
                trace(TraceEventType.AGENT_REQUEST_VALIDATION, TracePhase.ORCHESTRATION,
                      metadata = mapOf(
                          "agentName" to agentRequest.agentName,
                          "requestValid" to true,
                          "requestSource" to if(agentBatchCount > 0) "array" else "object",
                          "batchSize" to if(agentBatchCount > 0) agentBatchCount else 1,
                          "iteration" to loopIterationCount
                      ))
            }

            /**
             * Grab the converse history from the content object if it exists so that we can record this action
             * that the manager pipeline took. This is important since the manager pipeline needs to be able to
             * track both what it decided to do next, and what each agent did as the manifold works through
             * the task at large.
             */
            val previousConverseHistory = extractJson<ConverseHistory>(workingContentObject.text)
            if(previousConverseHistory != null)
            {
                previousConverseHistory.add(ConverseRole.system, managerResult)
                workingContentObject.text = serialize(previousConverseHistory, encodedefault = false)
                
                // === TRACING: Converse history update ===
                if (tracingEnabled)
                {
                    trace(TraceEventType.CONVERSE_HISTORY_UPDATE, TracePhase.ORCHESTRATION,
                          metadata = mapOf(
                              "action" to "addManagerDecision",
                              "historySize" to previousConverseHistory.history.size
                          ))
                }
            }

            /**
             * Try to invoke the agent the manager pipeline is trying to call. If we can, then we can proceed.
             * Otherwise, we can't continue the Manifold.
             *
             * todo: Should we consider a retry option in the event that the llm is misbehaving? Or should we place
             * the full onus on the coder to ensure the llm actually produces json and they didn't mess up their
             * system prompt?
             */
            try{
                // === TRACING: Agent interaction tracking ===
                agentInteractionMap[agentRequest.agentName] = 
                    agentInteractionMap.getOrDefault(agentRequest.agentName, 0) + 1

                if (tracingEnabled)
                {
                    trace(TraceEventType.AGENT_DISPATCH, TracePhase.AGENT_COMMUNICATION,
                          metadata = mapOf(
                              "agentName" to agentRequest.agentName,
                              "interactionCount" to (agentInteractionMap[agentRequest.agentName] as Any),
                              "iteration" to (loopIterationCount as Any)
                          ))
                }

                /**
                 * Invoke the agent request system which will route and reach the local agent we have here.
                 * That agent will run through its task set as a pipeline, and return the result.
                 */
                val response = P2PRegistry.sendP2pRequest(agentRequest)

                //In the event a rejection occurs we'll need to exit the manifold.
                val rejection = response.rejection

                if(rejection != null)
                {
                    // === TRACING: P2P request rejection ===
                    if (tracingEnabled)
                    {
                        trace(TraceEventType.P2P_REQUEST_FAILURE, TracePhase.AGENT_COMMUNICATION,
                              error = Exception("P2P request rejected: $rejection"),
                              metadata = mapOf(
                                  "agentName" to agentRequest.agentName,
                                  "rejection" to rejection,
                                  "iteration" to loopIterationCount
                              ))
                    }
                    workingContentObject.terminatePipeline = true
                    break
                }

                // === TRACING: Successful agent response ===
                if (tracingEnabled)
                {
                    trace(TraceEventType.AGENT_RESPONSE, TracePhase.AGENT_COMMUNICATION,
                          response.output,
                          metadata = mapOf(
                              "agentName" to agentRequest.agentName,
                              "responseLength" to (response.output?.text?.length ?: 0),
                              "hasBinaryContent" to (response.output?.binaryContent?.isNotEmpty() ?: false),
                              "iteration" to loopIterationCount
                          ))
                }

                /**
                 * Agent ran as intended and produced a result. Now we need to build all this into a proper converse
                 * history and push it back. Then allow the while loop to continue.
                 */
                val output = response.output?.text ?: ""
                val converseHistory = extractJson<ConverseHistory>(workingContentObject.text) ?: throw Exception("Converse history is null. Cannot extract manifold agent's response.")


                /**
                 * Final human in the loop steps for this while loop. Runs the bound validation function if present, branches
                 * to failure if required, and then finally invokes the transformation function.
                 *
                 *
                 * Execute validation function if bound for worker pipeline response. This allows the human to check 
                 * if the worker agent has broken or produced unacceptable output. The issue can be attempted to be 
                 * repaired, and if we cannot repair it, we'll exit the manifold task.
                 */
                if(workerValidatorFunction != null)
                {
                    trace(TraceEventType.VALIDATION_START, TracePhase.ORCHESTRATION,
                        metadata = mapOf("validatorFunction" to workerValidatorFunction.toString()))

                    //Get the worker pipeline that just executed
                    val workerPipeline = workerPipelines.find { 
                        it.getP2pDescription()?.agentName == agentRequest.agentName 
                    }

                    //Handle branch failure state if our validation function did not pass.
                    if(workerPipeline != null && workerValidatorFunction?.invoke(response.output!!, workerPipeline) == false)
                    {
                        //Execute branch failure if provided. This gives one last chance to not end the manifold.
                        if(failureFunction != null)
                        {
                            trace(TraceEventType.VALIDATION_FAILURE, TracePhase.ORCHESTRATION,
                                metadata = mapOf("failureFunction" to failureFunction.toString()))

                            //Bail on the manifold if we can't pass this. Otherwise, we can resume the task.
                            if(failureFunction?.invoke(response.output!!, workerPipeline) == false)
                            {
                                val errorMessage = response.output?.metadata?.get("error") ?: "Unable to recover using branch failure function."

                                trace(TraceEventType.PIPE_FAILURE, TracePhase.ORCHESTRATION,
                                    metadata = mapOf("failureFunction" to failureFunction.toString(),
                                        "reason" to errorMessage.toString()))

                                workingContentObject.terminate()
                                break
                            }
                        }

                        //No branch failure provided, but we were not able to proceed through the validator.
                        else
                        {
                            workingContentObject.terminate()
                            break
                        }
                    }
                }

                /**
                 * Execute transformation function if bound. This allows for content transformation upon a 
                 * successful worker agent call and can be used to execute any pcp tools.
                 */
                if(transformationFunction != null)
                {
                    trace(TraceEventType.TRANSFORMATION_START, TracePhase.ORCHESTRATION,
                        response.output,
                        metadata = mapOf("inputText" to (response.output?.text ?: "")))
                    
                    response.output = transformationFunction?.invoke(response.output!!) ?: response.output
                    
                    trace(TraceEventType.TRANSFORMATION_SUCCESS, TracePhase.ORCHESTRATION,
                        response.output,
                        metadata = mapOf("outputText" to (response.output?.text ?: "")))
                }


                //Update the converse history with the agent's response.
                try {
                    converseHistory?.add(ConverseRole.agent, response.output!!)
                    
                    // === TRACING: Agent response added to history ===
                    if (tracingEnabled)
                    {
                        trace(TraceEventType.CONVERSE_HISTORY_UPDATE, TracePhase.ORCHESTRATION,
                              metadata = mapOf(
                                  "action" to "addAgentResponse",
                                  "agentName" to agentRequest.agentName,
                                  "historySize" to converseHistory.history.size
                              ))
                    }
                } catch(e: Exception) {
                    // === TRACING: Converse history update failure ===
                    if (tracingEnabled)
                    {
                        trace(TraceEventType.MANIFOLD_FAILURE, TracePhase.ORCHESTRATION,
                              error = e,
                              metadata = mapOf(
                                  "failurePoint" to "converseHistoryUpdate",
                                  "agentName" to agentRequest.agentName
                              ))
                    }
                    workingContentObject.terminatePipeline = true
                    break
                }

                //Push agent's response back to the converse history and working content object.
                workingContentObject.text = serialize(converseHistory, encodedefault = false)

                /**
                 * Next, it's very important that we now wipe the text of the response so that we can safely merge
                 * the contents of the response into our working content object. This ensures we push back
                 * anything like binary and non text data back in one go correctly.
                 */
                try {
                    response.output?.text = ""
                    workingContentObject.merge(response.output!!)
                    
                    // === TRACING: Content merge successful ===
                    if (tracingEnabled)
                    {
                        trace(TraceEventType.AGENT_RESPONSE_PROCESSING, TracePhase.ORCHESTRATION,
                              workingContentObject,
                              metadata = mapOf(
                                  "agentName" to agentRequest.agentName,
                                  "mergeSuccessful" to true,
                                  "finalContentSize" to workingContentObject.text.length
                              ))
                    }
                } catch(e: Exception) {
                    // === TRACING: Content merge failure ===
                    if (tracingEnabled)
                    {
                        trace(TraceEventType.MANIFOLD_FAILURE, TracePhase.ORCHESTRATION,
                              error = e,
                              metadata = mapOf(
                                  "failurePoint" to "contentMerge",
                                  "agentName" to agentRequest.agentName
                              ))
                    }
                    workingContentObject.terminatePipeline = true
                    break
                }

            } catch(e: Exception) {
                // === TRACING: P2P communication failure ===
                if (tracingEnabled)
                {
                    trace(TraceEventType.P2P_COMMUNICATION_FAILURE, TracePhase.AGENT_COMMUNICATION,
                          error = e,
                          metadata = mapOf(
                              "agentName" to agentRequest.agentName,
                              "errorType" to (e::class.simpleName ?: "Unknown"),
                              "iteration" to (loopIterationCount as Any)
                          ))
                }
                workingContentObject.terminatePipeline = true; break
            }
            
            // === TRACING: Termination condition check ===
            if (tracingEnabled)
            {
                trace(TraceEventType.MANIFOLD_TERMINATION_CHECK, TracePhase.ORCHESTRATION,
                      metadata = mapOf(
                          "terminateFlag" to workingContentObject.terminatePipeline,
                          "passFlag" to workingContentObject.passPipeline,
                          "iteration" to loopIterationCount
                      ))
            }



        }

        // === TRACING: Final execution result ===
        if (tracingEnabled)
        {
            val finalEventType = if (workingContentObject.terminatePipeline) {
                TraceEventType.MANIFOLD_FAILURE
            } else {
                TraceEventType.MANIFOLD_SUCCESS
            }
            
            trace(finalEventType, TracePhase.CLEANUP, workingContentObject,
                  metadata = mapOf(
                      "totalIterations" to (loopIterationCount as Any),
                      "finalContentSize" to workingContentObject.text.length,
                      "agentInteractions" to agentInteractionMap.size
                  ))
            
            trace(TraceEventType.MANIFOLD_END, TracePhase.CLEANUP, workingContentObject)
        }

        return workingContentObject
    }

//==============================================Tracing Methods========================================================

    /**
     * Enables tracing for this Manifold with the specified configuration.
     * Propagates tracing settings to all child pipelines and P2P components.
     * 
     * @param config The tracing configuration to use
     * @return This Manifold object for method chaining
     */
    fun enableTracing(config: TraceConfig = TraceConfig(enabled = true)): Manifold
    {
        this.tracingEnabled = true
        this.traceConfig = config
        PipeTracer.enable() // Enable global tracer
        return this
    }

    /**
     * Disables tracing for this Manifold and all child components.
     * 
     * @return This Manifold object for method chaining
     */
    fun disableTracing(): Manifold
    {
        this.tracingEnabled = false
        return this
    }

    /**
     * Internal tracing method following same pattern as Pipe class.
     * Handles verbosity filtering and metadata building for Manifold events.
     * 
     * @param eventType The type of event being traced
     * @param phase The execution phase context
     * @param content Optional multimodal content to include
     * @param metadata Additional event metadata
     * @param error Optional exception details
     */
    private fun trace(
        eventType: TraceEventType,
        phase: TracePhase,
        content: MultimodalContent? = null,
        metadata: Map<String, Any> = emptyMap(),
        error: Throwable? = null
    )
    {
        if (!tracingEnabled) return
        
        // Verbosity filtering using existing EventPriorityMapper
        if (!EventPriorityMapper.shouldTrace(eventType, traceConfig.detailLevel)) return
        
        // Build enhanced metadata based on verbosity level
        val enhancedMetadata = buildManifoldMetadata(metadata, traceConfig.detailLevel, eventType, error)
        
        // Create event with conditional content inclusion
        val event = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = manifoldId,
            pipeName = "Manifold-${managerPipeline.pipelineName}",
            eventType = eventType,
            phase = phase,
            content = if (shouldIncludeContent(traceConfig.detailLevel)) content else null,
            contextSnapshot = if (shouldIncludeContext(traceConfig.detailLevel)) managerPipeline.context else null,
            metadata = if (traceConfig.includeMetadata) enhancedMetadata else emptyMap(),
            error = error
        )
        
        // Submit to central tracer using manifold ID
        PipeTracer.addEvent(manifoldId, event)
    }

    /**
     * Builds Manifold-specific metadata based on verbosity level.
     * Follows same pattern as Pipe.buildMetadataForLevel() method.
     * 
     * @param baseMetadata Base metadata to enhance
     * @param detailLevel Current verbosity level
     * @param eventType Type of event being traced
     * @param error Optional error information
     * @return Enhanced metadata map
     */
    private fun buildManifoldMetadata(
        baseMetadata: Map<String, Any>,
        detailLevel: TraceDetailLevel,
        eventType: TraceEventType,
        error: Throwable?
    ): Map<String, Any>
    {
        val metadata = baseMetadata.toMutableMap()
        
        when (detailLevel)
        {
            TraceDetailLevel.MINIMAL ->
            {
                if (error != null)
                {
                    metadata["error"] = error.message ?: "Unknown error"
                }
            }
            
            TraceDetailLevel.NORMAL ->
            {
                metadata["manifoldId"] = manifoldId
                metadata["managerPipeline"] = managerPipeline.pipelineName
                metadata["workerCount"] = workerPipelines.size
                metadata["loopIteration"] = loopIterationCount
                if (error != null)
                {
                    metadata["error"] = error.message ?: "Unknown error"
                    metadata["errorType"] = error::class.simpleName ?: "Unknown"
                }
            }
            
            TraceDetailLevel.VERBOSE ->
            {
                metadata["manifoldClass"] = this::class.qualifiedName ?: "Manifold"
                metadata["manifoldId"] = manifoldId
                metadata["managerPipeline"] = managerPipeline.pipelineName
                metadata["workerPipelines"] = workerPipelines.map { it.pipelineName }
                metadata["agentPaths"] = agentPaths.map { it.agentName }
                metadata["loopIteration"] = loopIterationCount
                metadata["taskComplete"] = currentTaskProgress.isTaskComplete
                metadata["taskProgress"] = currentTaskProgress.taskProgressStatus
                metadata["agentInteractions"] = agentInteractionMap.size
                if (error != null)
                {
                    metadata["error"] = error.message ?: "Unknown error"
                    metadata["errorType"] = error::class.simpleName ?: "Unknown"
                }
            }
            
            TraceDetailLevel.DEBUG ->
            {
                // Everything from VERBOSE plus detailed debug information
                metadata["manifoldClass"] = this::class.qualifiedName ?: "Manifold"
                metadata["manifoldId"] = manifoldId
                metadata["managerPipeline"] = managerPipeline.pipelineName
                metadata["workerPipelines"] = workerPipelines.map { it.pipelineName }
                metadata["agentPaths"] = agentPaths.map { "${it.agentName}:${it.description}" }
                metadata["agentPipeNames"] = agentPipeNames
                metadata["loopIteration"] = loopIterationCount
                metadata["taskComplete"] = currentTaskProgress.isTaskComplete
                metadata["taskProgress"] = currentTaskProgress.taskProgressStatus
                metadata["nextTaskInstructions"] = currentTaskProgress.nextTaskInstructions
                metadata["agentInteractionMap"] = agentInteractionMap
                metadata["workingContentSize"] = workingContentObject.text.length
                metadata["p2pDescriptor"] = p2pDescriptor?.agentName ?: "null"
                if (error != null)
                {
                    metadata["error"] = error.message ?: "Unknown error"
                    metadata["errorType"] = error::class.simpleName ?: "Unknown"
                    metadata["stackTrace"] = error.stackTraceToString()
                }
            }
        }
        
        return metadata
    }

    /**
     * Determines if content should be included based on verbosity level.
     * Follows same logic as Pipe class for consistency.
     * 
     * @param detailLevel Current verbosity level
     * @return True if content should be included
     */
    private fun shouldIncludeContent(detailLevel: TraceDetailLevel): Boolean
    {
        return when (detailLevel)
        {
            TraceDetailLevel.MINIMAL -> false
            TraceDetailLevel.NORMAL -> false
            TraceDetailLevel.VERBOSE -> traceConfig.includeContext
            TraceDetailLevel.DEBUG -> traceConfig.includeContext
        }
    }

    /**
     * Determines if context should be included based on verbosity level.
     * Follows same logic as Pipe class for consistency.
     * 
     * @param detailLevel Current verbosity level
     * @return True if context should be included
     */
    private fun shouldIncludeContext(detailLevel: TraceDetailLevel): Boolean
    {
        return when (detailLevel)
        {
            TraceDetailLevel.MINIMAL -> false
            TraceDetailLevel.NORMAL -> false
            TraceDetailLevel.VERBOSE -> traceConfig.includeContext
            TraceDetailLevel.DEBUG -> traceConfig.includeContext
        }
    }

    /**
     * Gets trace report for this Manifold in the specified format.
     * 
     * @param format Output format (defaults to config setting)
     * @return Formatted trace report string
     */
    fun getTraceReport(format: TraceFormat = traceConfig.outputFormat): String
    {
        return PipeTracer.exportTrace(manifoldId, format)
    }

    /**
     * Gets failure analysis for this Manifold if tracing is enabled.
     * 
     * @return FailureAnalysis object or null if tracing is disabled
     */
    fun getFailureAnalysis(): FailureAnalysis?
    {
        return if (tracingEnabled) PipeTracer.getFailureAnalysis(manifoldId) else null
    }

    /**
     * Gets the unique trace ID for this Manifold.
     * 
     * @return Manifold trace ID string
     */
    fun getTraceId(): String = manifoldId


//=================================================Builders=============================================================

    /**
     * Builder that constructs a solid default pipeline already configured to act as a manager for the worker pipes
     * provides a solid set of instructions to cover most general cases that would need a manifold without asking the
     * user to build their own pipeline from scratch.
     *
     * @param provider The provider name of the llm to use
     * @param model The model name of the llm to use
     */
    fun buildDefaultManagerPipeline(provider: ProviderName, model: String, region: String = "", url: String = "", port: Int = 0)
    {
        var newManagerPipeline = Pipeline()


    }

}
