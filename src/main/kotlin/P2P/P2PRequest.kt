package com.TTT.P2P

import com.TTT.Context.ContextWindow
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.Util.deserialize
import com.TTT.Util.examplePromptFor

/**
 * Defines a custom json schema that can be requested to be used by an agent instead of its default one.
 */
@kotlinx.serialization.Serializable
data class CustomJsonSchema(
    var schemaContainer: MutableMap<String, Pair<String, String>> = mutableMapOf()
)
{
    
    fun add(pipeName: String, description: String, jsonObject: Any)
    {
        val schemaExample = examplePromptFor(jsonObject::class)
        val schemaPair = Pair(description, schemaExample)
        schemaContainer[pipeName] = schemaPair
    }

    companion object {

        /**
         * Helper function to construct this object by supplying a valid data class and generating the
         * schema for it instead of providing the string manually.
         *
         * @param description Description to explain how to use the json schema to the llm.
         * @param jsonObject Any valid object that can be serialized and is compatible with kotlin reflection.
         * A json schema that can be used by an llm will be generated if possible.
         */
        fun newSchema(pipeName: String, description: String, jsonObject: Any) : CustomJsonSchema?
        {
            try{
                val schemaExample = examplePromptFor(jsonObject::class)
                val schemaPair = Pair(description, schemaExample)
                val customJsonSchemaObject = CustomJsonSchema(mutableMapOf(pipeName to schemaPair))
                return customJsonSchemaObject
            }

            catch (e: Exception)
            {
                return null
            }

            return null
        }
    }
}

/**
 * Request class to send an P2P request to a TPipe powered system. Includes all the required information to route
 * the data to, and invoke the connection to the agent.
 *
 * @param transport Outgoing connection path to the TPipe agent.
 * @param returnAddress Return path for the result to be sent back to.
 * @param prompt  Prompt being sent to the agent. Uses the standard TPipe MultiModalContent object. The text variable
 * of the content object will act as the text prompt the agent sees.
 * @param authBody Required form of authentication. Intended to carry various forms of auth as strings or base64 strings.
 * If required by the other endpoint, it will internally handle this data and reject the connection if it does not
 * conform.
 * @param contextExplanationMessage Explains to the llm how to interpret the context and what to do with it. If not
 * empty and the context value is not empty a duplication request to make a copied and modified pipeline will occur
 * internally. This is only allowed if the agent descriptor and internal requirements allow for it.
 * @param context Optional TPipe context window object to supply. If supported, this will be auto-injected.
 * @param customContextDescriptions Optional map of custom context descriptions to supply to the llm. If supported,
 * this will be auto-injected. The key is the name of the pipe in the pipeline the agent will be running. The
 * value is the description that is normally auto injected. This will affect any pipeline that the agent calls so
 * exercise caution and proper knowledge of pipe names when engaging with container, or multi-container agentic systems.
 * @param pcpRequest PCP request body that can be optionally sent. If sent, and if allowed depending on internal policy,
 * the requested tools will be used.
 * @param inputSchema Optional input json schema to override the default json schema that the agent expects as the
 * user prompt either standalone, or held inside the multimodal content object. Only allowed if the agent enables
 * pipeline duplication.
 * @param outputSchema Optional output schema to instruct the llm to return instead of it's default. Only allowed
 * if policy, and pipeline duplication is enabled by the agent.
 */
@kotlinx.serialization.Serializable
data class P2PRequest(
    var transport: P2PTransport = P2PTransport(),
    var returnAddress: P2PTransport = P2PTransport(),
    var prompt: MultimodalContent = MultimodalContent(),
    var authBody: String = "",
    var contextExplanationMessage: String = "",
    var context: ContextWindow? = null,
    var customContextDescriptions: MutableMap<String, String>? = null,
    var pcpRequest: PcPRequest? = null,
    var inputSchema: CustomJsonSchema? = null,
    var outputSchema: CustomJsonSchema? = null
)


/**
 * Simplified llm friendly request object that can be used for an llm to request a call to another agent.
 */
@kotlinx.serialization.Serializable
data class AgentRequest(
    var agentName: String = "",
    var promptSchema: InputSchema = InputSchema.plainText,
    var prompt: String = "",
    var content: String = "",
    var pcpRequest: PcPRequest = PcPRequest())
{
    //todo: Adjust this to have content, and then the prompt and merge the two together.

    /**
     * Given this simplified request object an llm can understand build a full P2P request from it. An optional template
     * can be supplied to merge with the request body of this object. If not provided an incomplete P2P request object
     * will be returned which the programmer must fill out the rest of the way. It's strongly recommended to store
     * P2P requests as templates and merge them with the llm's simplified request.
     */
    fun buildP2PRequest(template: P2PRequest? = null) : P2PRequest
    {
        val request = template ?: P2PRequest()
        request.prompt.addText(prompt)

        /**
         * Assign prompt data to the correct location of the input based on the output the agent is producing.
         */
        when(promptSchema)
        {
            InputSchema.plainText -> request.prompt.addText(content)
            InputSchema.json -> request.prompt.addText(content)
            InputSchema.xml -> request.prompt.addText(content)
            InputSchema.html -> request.prompt.addText(content)
            InputSchema.csv -> request.prompt.addText(content)
            InputSchema.tsv -> request.prompt.addText(content)
            InputSchema.yaml -> request.prompt.addText(content)
            InputSchema.markdown -> request.prompt.addText(content)
            InputSchema.bytes -> request.prompt.addBinary(content.toByteArray(), "application/octet-stream")
            InputSchema.other -> request.prompt.addText(content)
            InputSchema.none -> {request.prompt.addText(content)}
        }

        request.transport.transportAddress = agentName

        try{
            //This line makes no sense!!! Even when it was a string it makes no sense!!!
            request.pcpRequest = template?.pcpRequest ?: PcPRequest()
        }
        catch (e: Exception)
        {
            request.pcpRequest = null
        }

        template?.let {
            if (request.authBody.isEmpty()) request.authBody = it.authBody
            if (request.contextExplanationMessage.isEmpty()) request.contextExplanationMessage = it.contextExplanationMessage
            if (request.context == null) request.context = it.context
            if (request.inputSchema == null) request.inputSchema = it.inputSchema
            if (request.outputSchema == null) request.outputSchema = it.outputSchema
            if (request.returnAddress.transportAddress.isEmpty()) request.returnAddress = it.returnAddress
        }

        return request
    }

    /**
     * Helper function to build a P2P request from a registry template. A blank template will be created if the
     * request was not found in the registry.
     * @see buildP2PRequest
     */
    fun buildRequestFromRegistry(templateRef: Any)  : P2PRequest
    {
        val template = P2PRegistry.requestTemplates[templateRef] ?: P2PRequest()
        return buildP2PRequest(template)
    }
}
