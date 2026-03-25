package com.TTT.P2P

import com.TTT.Pipeline.DistributionGridNodeMetadata
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.Transport
import javax.management.Descriptor

/**
 * Defines the context protocol a TPipe agent accepts.
 * @see PipeContextProtocol for more details on TPipe's internal pcp system.
 */
enum class ContextProtocol
{
    pcp,
    mcp,
    provider,
    none
}

/**
 * Defines the content types a TPipe agent accepts.
 */
enum class SupportedContentTypes
{
    text,
    image,
    video,
    audio,
    application,
    other,
    none
}

/**
 * Defines method of input a TPipe agent accepts. This is as part of a smaller schema to teach an llm how to call
 * a TPipe agent.
 */
enum class InputSchema
{
    plainText,
    json,
    xml,
    html,
    csv,
    tsv,
    yaml,
    markdown,
    bytes,
    other,
    none
}

/**
 * Defines transport method for a p2p connection to a TPipe agent.
 * @param transportMethod Method of communication. Supports http, stdio, and TPipe's internal channels.
 * @param transportAddress Depending on the method this may either be a url, a path to the program on the system,
 * @param transportAuthBody Auth body used by an http REST api request body. Separate from a p2p auth body that
 * is intended as whatever the p2p server's internal auth system might be.
 * or the TPipe container name used to address the agent on the TPipe internal p2p registry.
 */
@kotlinx.serialization.Serializable
data class P2PTransport(
    var transportMethod: Transport = Transport.Tpipe,
    var transportAddress: String = "",
    var transportAuthBody: String = ""
)

/**
 * Granular definition of skills that an agent supports. Helps the llm reading the description better understand
 * what the agent can and can't do.
 */
@kotlinx.serialization.Serializable
data class P2PSkills(
    var skillName: String,
    var skillDescription: String,
    )

/**
 * Class that defines a p2p description. This describes the capabilities of an agent, what content it accepts,
 * the actions it can perform, and the formatting needed to address it.
 *
 * @param agentName The name of the agent.
 *
 * @param agentDescription A description of the agent. This should be clear and concise covering exactly what the
 * agent can and cannot do.
 *
 * @param transport The transport method and address of the agent. Supports http, stdio, or TPipe's internal
 * p2p registry system.
 *
 * @param requiresAuth Whether the agent requires authentication. The method of authentication will be internally
 * defined by the agent or program parsing the p2p request.
 *
 * @param usesConverse Whether the agent uses the converse protocol. If true TPipe's internal chat json schema
 * will be used to track user to agent interaction in a conversational user to assistant prompt manner.
 *
 * @param allowsAgentDuplication If true, certain requests may result in the pipeline being called to be duplicated
 * into a fresh copy. This allows for changes like adjusting the json input and output, and creating custom context
 * supply strings. Off by default.
 *
 * @param allowsCustomAgentJson If true, The requester can supply custom json schemas for input, output, and
 * additional context explanation on how to use any supplied external context. If true, the pipeline that is bound
 * to the agent will have to be temporarily duplicated to apply these changes.
 *
 * @param recordsInteractionContext Whether the agent records the interaction context. If true, the agent might
 * automatically store lorebook keys of context based on the interaction, memories about the conversation, the user
 * or any other interaction as context. This exists as a type hint but should not be treated as an absolute. This does
 * not enforce that the TPipe agent will or will not do this. It is treated as a courtesy so should still be taken
 * with caution for any untrusted sources or systems.
 *
 * @param recordsPromptContent Whether the agent records the prompt content. If true, the agent is notifying that it can
 * and will be recording every user prompt and interaction with the agent either as context, or as some other form of
 * internal storage. This exists as a type hint but should not be treated as an absolute. This does not enforce that the
 * TPipe agent will or will not do this. It is treated as a courtesy so should still be taken with caution for any
 * untrusted sources or systems.
 *
 * @param allowsExternalContext If true, the agent is notifying that it can accept an external TPipe ContextWindow object
 * as context it will pull into itself. If false, the agent either does not accept external TPipe context, or does not
 * guarantee compatibility or conformance.
 *
 * @param contextProtocol The context protocol the agent accepts. This acts as a type hint into weather non pcp
 * context systems will be honored or not.
 *
 * @param inputPromptSchema Optional schema definition of how a prompt must be formatted. The TPipe agent may or
 * may not test conformance for this formatting before accepting or rejecting a prompt.
 *
 * @param contextProtocolSchema The schema of the context protocol the agent accepts. Optional type hint to assist
 * the llm that's reading this descriptor. The TPipe agent may enforce that the prompt is formatting this correctly.
 *
 * @param contextWindowSize The size of the context window the agent accepts. Optional type hint to assist the
 * system on the other side engaging with this agent to ensure it does not supply a context that is too large.
 *
 * @param supportedContentTypes The content types the agent accepts.
 *
 * @param pcpDescriptor The pcp descriptor of the agent. Optional.
 *
 * @param allowedModels Map that denotes use case, and allowed models for said use case. Enables the requester to
 * request specific available llm models for a task or part of the task. Only true if duplication is allowed.
 *
 * @param distributionGridMetadata Optional typed metadata that marks this descriptor as a `DistributionGrid` node.
 */
@kotlinx.serialization.Serializable
data class P2PDescriptor(
    var agentName : String,
    var agentDescription: String,
    var transport: P2PTransport,
    var requiresAuth: Boolean,
    var usesConverse: Boolean,
    var allowsAgentDuplication: Boolean,
    var allowsCustomContext: Boolean,
    var allowsCustomAgentJson: Boolean,
    var recordsInteractionContext: Boolean,
    var recordsPromptContent: Boolean,
    var allowsExternalContext: Boolean,
    var contextProtocol: ContextProtocol,
    var inputPromptSchema: String = "", //Optional
    var contextProtocolSchema: String = "", //Optional
    var contextWindowSize: Int = 32000,
    var supportedContentTypes: MutableList<SupportedContentTypes> = mutableListOf(SupportedContentTypes.text),
    var agentSkills: MutableList<P2PSkills>? = null,
    var pcpDescriptor: PcpContext = PcpContext(), //Optional.
    var allowedModels: MutableMap<String, MutableList<String>>? = null, //Optional depends on duplication being allowed.
    var requestTemplate: P2PRequest? = null,
    var distributionGridMetadata: DistributionGridNodeMetadata? = null
    )
{
    
}


/**
 * LLM friendly list of available agents it can call. Strips out elements of P2PDescriptor that is not needed by it
 * or might confuse it. The llm will be shown this in its explanation of available agents instead of the full descriptor
 * which is not needed by the llm, but is needed by the human programmer.
 */
@kotlinx.serialization.Serializable
data class AgentDescriptor(
    var agentName: String = "",
    var description: String = "",
    var skills: P2PSkills = P2PSkills("", ""),
    var inputMethod: InputSchema = InputSchema.plainText,
    var inputSchema: String = "",
    var tools: PcpContext? = null
)
{
    companion object {

        /**
         * Build a less complex agent descriptor for the llm using the full descriptor object.
         */
        fun buildFromDescriptor(descriptor: P2PDescriptor) : AgentDescriptor
        {

            if(descriptor.agentSkills == null)
            {
                descriptor.agentSkills = mutableListOf()
            }

            val agentDescriptor = AgentDescriptor(
                agentName = descriptor.agentName,
                description = descriptor.agentDescription,
                inputSchema = descriptor.inputPromptSchema,
                skills = P2PSkills("", ""),
                tools = descriptor.pcpDescriptor

            )

            if(descriptor.inputPromptSchema.isNotEmpty())
            {
                agentDescriptor.inputMethod = InputSchema.json
            }

            return agentDescriptor
        }
    }
}
