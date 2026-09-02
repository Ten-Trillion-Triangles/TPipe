package com.TTT.AgentCore.harness

import aws.sdk.kotlin.services.bedrockagentcore.model.HarnessContentBlock
import aws.sdk.kotlin.services.bedrockagentcore.model.HarnessConversationRole
import aws.sdk.kotlin.services.bedrockagentcore.model.HarnessMessage
import aws.sdk.kotlin.services.bedrockagentcore.model.InvokeHarnessRequest
import com.TTT.Pipe.MultimodalContent
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse

/**
 * P2P adapter for an external AgentCore Harness worker. Harness remains an
 * external worker boundary and does not add a transport enum.
 */
class AgentCoreHarnessAgent(
    private val worker: suspend (P2PRequest) -> P2PResponse
) : P2PInterface {
    /**
     * Build a real Harness-backed P2P agent while retaining the lambda
     * constructor used by existing callers and tests.
     *
     * @param client Pinned AgentCore Harness data-plane client.
     * @param harnessArn Harness resource to invoke.
     * @param runtimeSessionId Optional session correlation supplied per request.
     */
    constructor(
        client: AgentCoreHarnessClient,
        harnessArn: String,
        runtimeSessionId: (P2PRequest) -> String? = { null }
    ) : this(worker = { request ->
        val outputs = client.invoke(
            InvokeHarnessRequest {
                this.harnessArn = harnessArn
                this.runtimeSessionId = runtimeSessionId(request)
                messages = listOf(
                    HarnessMessage {
                        role = HarnessConversationRole.User
                        content = listOf(HarnessContentBlock.Text(request.prompt.text))
                    }
                )
            }
        )
        val text = outputs.mapNotNull { output ->
            output.asContentBlockDeltaOrNull()?.delta?.asTextOrNull()
        }.joinToString(separator = "")
        P2PResponse(output = MultimodalContent(text = text))
    })

    override var killSwitch: com.TTT.P2P.KillSwitch? = null

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse = worker(request)
}
