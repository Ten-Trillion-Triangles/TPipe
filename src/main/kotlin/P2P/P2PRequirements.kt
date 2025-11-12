package com.TTT.P2P

import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipe.TruncationSettings

/**
 * Data class denoting requirements for a TPipe agent to accept a request and connection from another TPipe agent or
 * external support. When attempting to make a call from one agent to another, this will be checked against the agent's
 * registry integrally, and only if the requirements conform in full will it allow the agent to be activated.
 *
 * @param requireMultiModal If true, content must be passed as a valid MultimodalContent object and must be able to be
 * correctly and fully deserialized back into one upon arrival.
 *
 * @param requireConverseInput If true, the user prompt must be formatted in TPipes Converse json schema for user to
 * agent roles and conversation context storage.
 *
 * @param allowAgentDuplication If true, the pipeline controlled by this agent can be duplicated when invoked allowing
 * for remapping of the json inputs, outputs, custom context, and hints regarding how to use the custom context.
 *
 * @param allowCustomContext If true, the request can have a custom context object supplied, as well as a descriptor
 * for TPipe context management systems. If false, any request containing this context will be rejected. Requires
 * allowAgentDuplication to be true to enable this.
 *
 * @param allowCustomJson If true, the request can supply a new schema for json input and output.
 *
 * @param allowExternalConnections If true, this Agent will be allowed to handle requests made from pipelines, agents,
 * and external systems that exist outside its parent container class. Otherwise, only requests from its parent container
 * class or containers inside of it's space will be allowed.
 *
 * @param acceptedContent List of what content the Agent accepts. If content is provided that is not accepted the
 * connection will be refused.
 *
 * @param maxTokens Maximum number of tokens that can be passed in any form of text including base64.
 * Any content that exceeds this amount will be rejected.
 *
 * @param maxBinarySize Maximum size of binary data that can be passed in any form of binary data including base64.
 * Any content that exceeds this amount will be rejected.
 *
 * @param authMechanism A lambda function that takes in a string and returns a boolean. If the lambda returns true, the
 * connection will be accepted. If it returns false, the connection will be refused. This is useful for implementing
 * authentication mechanisms such as JWTs or other forms of authentication.
 */
data class P2PRequirements(
    var requireConverseInput: Boolean = false,
    var allowAgentDuplication: Boolean = false,
    var allowCustomContext: Boolean = false,
    var allowCustomJson: Boolean = false, //Depends on allowAgentDuplication being true.
    var allowExternalConnections: Boolean = false,
    var acceptedContent: MutableList<SupportedContentTypes>? = null,
    var maxTokens: Int = 30000,
    var tokenCountingSettings: TruncationSettings? = null,
    var multiPageBudgetSettings: TokenBudgetSettings? = null,
    var allowMultiPageContext: Boolean = true,
    var maxBinarySize: Int = 20 * 1024, //20MB default.
    var authMechanism: (suspend (authBody: String) -> Boolean)? = null
)
