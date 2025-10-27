package com.TTT.Pipeline

import com.TTT.P2P.AgentDescriptor
import com.TTT.P2P.AgentRequest
import com.TTT.P2P.P2PInterface
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.Util.examplePromptFor

/**
 * Data class to pass around as each agent works on the task at hand. Allows the agents to see what the last task was
 * and explain to the next agent what the request they are making is. The multimodal content object's text prompt
 * will internally be a ConverseHistory object that has been serialized to string. This ensures each agent is aware
 * of exactly what the previous agent did. The agent is also allowed to issue a pcp request to execute some given action
 * and weather or not to call itself again as the next agent.
 *
 * @see com.TTT.Context.ConverseHistory
 * @see PcPRequest
 */
@kotlinx.serialization.Serializable
data class DistributionGridTask(
    var isTaskComplete: Boolean,
    var taskDescription: String, //Assigned by original dispatcher and always kept to that original value.
    var actionTaken: String,
    var requestToNextAgent: String,
    var nextAgentToCall: AgentRequest,
    var pcpRequest: PcPRequest? = null,
    var userContent: MultimodalContent? = null //Assigned by the coder and not visible to the agent.
)

data class DistributionGridJudgement(
    var isTaskComplete: Boolean = false,
    var previousAgent: String = "",
    var previousAgentResponse: MultimodalContent = MultimodalContent()
)

/**
 * The DistributionGrid is a decentralized agent network class that allows each AI agent to autonomously decide on which
 * agent to call next to complete a given task. A task flows to an initial dispatcher that decides the scope of the task
 * and determines which agent to call next. The task is then passed to that agent which operates on it. The agent
 * then decides if it thinks the task is finished. If not it decides what it should do which can either be:
 *
 * - Call itself again to iterate on the task.
 * - Call another agent to complete the next step of the task.
 *
 * Once an agent believes it has cleared the task, it will send it's work to a judge. The judge will monitor the history
 * of the task and determine if it's actually complete or not. If it isn't it will determine who to send the task to
 * next. This repeats until the task is complete.
 *
 * History of the task will be tracked using TPipe's ConverseHistory class.
 * @see com.TTT.Context.ConverseHistory
 *
 * Tokens will be counted overtime and will auto summarize the actions taken in the tasks to compress the tokens
 * under whatever is the lowest context window of the agents.
 *
 * Each pipeline must support p2p, implement the p2p interface, and support the DistributionGrid data class
 * as json output at the ending point where they make a p2p call.
 */
class DistributionGrid : P2PInterface
{
//=============================================Properties===============================================================

    /**
     * Entry pipeline that handles the initial task assignment and dispatching. It will assess the task, determine
     * what it thinks may be required to complete it, assess which agents are available to complete the task, and
     * then assign the task to the first agent.
     */
    private var entryPipeline: Pipeline? = null

    /**
     * Pipeline that's responsible for judging weather the claimed "completed" task is actually completed. If not,
     * it will determine who to send the task to next. If the task is actually completed. It will exit with the
     * content object and the job will be considered done.
     */
    private var judgePipeline: Pipeline? = null

    /**
     * Deployment of worker pipelines that are acting as callable agents. Each one is visible to all other worker
     * pipelines and is able to make a p2p call to another agent, or decide that it has "completed" the task.
     * Worker pipelines are also fully visible to the entry pipeline, and the judge pipeline in order to make initial,
     * or repeat determinations on the next agent to call.
     */
    private var workerPipelines: MutableList<Pipeline>? = null

    /**
     * List of all available agents. This is assgined as worker pipelines are added either by TPipe-Defaults builders
     * or directly by passing pipes here.
     */
    private var availableAgents: List<AgentDescriptor>? = null

    /**
     * Activates the TPipe tracing system if true. All events in this class, and all children inside this class
     * will be traced and applied to the same trace stream for debugging.
     */
    private var enableTracing = false

//==============================================P2PInterface============================================================



//==============================================Constructor=============================================================


    /**
     * Assign the entry pipeline that first receives the task. This pipeline must decide the first agent to call
     * and is the only central manager that is part of the DistributionGrid process. Once it decides which agent
     * to first dispatch the task to, the rest of the grid will be decentralized until an agent thinks it has
     * completed the task.
     *
     * @param pipeline Pipeline that will handle the initial task assignment and dispatching.
     * @throws Exception If the pipeline does not have a pipe with the required json output schema. At least one pipe
     * needs to be able to generate json output in the format this class expects in order to pass each step of the task
     * from one agent to another.
     */
    fun setEntryPipeline(pipeline: Pipeline)
    {
        /**
         * At least one pipe in this pipeline needs to be able to make an outbound call using the
         * DistributionGridTask class as it's json output schema. This is required to both preserve
         * core task data, and fit the expectations of the DistributionGrid as it works through each agent
         * and manages the task.
         */
        val requiredJsonOutputSchema = examplePromptFor(DistributionGridTask::class)

        var hasSchema = false

        for(pipe in pipeline.getPipes())
        {
            if(pipe.jsonOutput == requiredJsonOutputSchema)
            {
                hasSchema = true
                break
            }
        }

        if(!hasSchema) throw Exception("Entry pipeline must have a pipe with the following json output schema: $requiredJsonOutputSchema")

        entryPipeline = pipeline
    }




//================================================Methods===============================================================
}
