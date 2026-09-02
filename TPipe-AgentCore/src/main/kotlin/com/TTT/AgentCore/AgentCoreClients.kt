package com.TTT.AgentCore

import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient

/**
 * Owns the Bedrock AgentCore data-plane and control-plane clients.
 *
 * Clients may be injected for tests. When omitted, the AWS SDK default
 * credential chain is used, with the optional [AgentCoreConfig.credentialsProvider]
 * taking precedence.
 *
 * @param config Shared region and credential configuration.
 * @param dataClient Optional injected data-plane client.
 * @param controlClient Optional injected control-plane client.
 */
class AgentCoreClients(
    config: AgentCoreConfig,
    dataClient: BedrockAgentCoreClient? = null,
    controlClient: BedrockAgentCoreControlClient? = null
) : AutoCloseable
{
    private val ownsDataClient = dataClient == null
    private val ownsControlClient = controlClient == null

    /** Data-plane client for memory, runtime, tools, and evaluations. */
    val data: BedrockAgentCoreClient = dataClient ?: BedrockAgentCoreClient {
        region = config.region
        config.credentialsProvider?.let { credentialsProvider = it }
    }

    /** Control-plane client for runtimes, gateways, identity, and policy. */
    val control: BedrockAgentCoreControlClient = controlClient ?: BedrockAgentCoreControlClient {
        region = config.region
        config.credentialsProvider?.let { credentialsProvider = it }
    }

    /** Close only clients created by this bundle. */
    override fun close()
    {
        if(ownsDataClient)
        {
            data.close()
        }

        if(ownsControlClient)
        {
            control.close()
        }
    }
}
