package com.TTT.AgentCore.registry

import aws.sdk.kotlin.services.agentregistry.AgentRegistryClient
import aws.sdk.kotlin.services.agentregistrycontrol.AgentRegistryControlClient
import com.TTT.AgentCore.AgentCoreConfig

/**
 * Owns the AWS Agent Registry discovery and control-plane clients.
 *
 * This bundle is deliberately separate from [com.TTT.AgentCore.AgentCoreClients].
 * Agent Registry has its own AWS service namespaces and lifecycle, so closing
 * this bundle affects only clients created by this bundle.
 *
 * @param config Shared region and credential configuration.
 * @param agentRegistryClient Optional injected discovery client.
 * @param agentRegistryControlClient Optional injected control-plane client.
 */
class AgentRegistryClients(
    config: AgentCoreConfig,
    agentRegistryClient: AgentRegistryClient? = null,
    agentRegistryControlClient: AgentRegistryControlClient? = null
) : AutoCloseable
{
    private val ownsAgentRegistryClient = agentRegistryClient == null
    private val ownsAgentRegistryControlClient = agentRegistryControlClient == null

    /** Discovery data-plane client for approved registry records. */
    val agentRegistry: AgentRegistryClient = agentRegistryClient ?: AgentRegistryClient {
        region = config.region
        config.credentialsProvider?.let { credentialsProvider = it }
    }

    /** Control-plane client for registries and registry records. */
    val agentRegistryControl: AgentRegistryControlClient =
        agentRegistryControlClient ?: AgentRegistryControlClient {
            region = config.region
            config.credentialsProvider?.let { credentialsProvider = it }
        }

    /** Compatibility name for the discovery client. */
    val registry: AgentRegistryClient
        get() = agentRegistry

    /** Compatibility name for the control-plane client. */
    val control: AgentRegistryControlClient
        get() = agentRegistryControl

    /** Compatibility spelling matching the AWS service module name. */
    val agentregistry: AgentRegistryClient
        get() = agentRegistry

    /** Compatibility spelling matching the AWS control service module name. */
    val agentregistrycontrol: AgentRegistryControlClient
        get() = agentRegistryControl

    /** Close only the clients created by this bundle. */
    override fun close()
    {
        if(ownsAgentRegistryClient)
        {
            agentRegistry.close()
        }

        if(ownsAgentRegistryControlClient)
        {
            agentRegistryControl.close()
        }
    }
}
