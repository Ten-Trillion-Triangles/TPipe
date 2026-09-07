package com.TTT.AgentCore.registry

/**
 * Compatibility name for the Agent Registry control-plane facade.
 *
 * The implementation keeps the AWS service's registry and registry-record
 * lifecycles in [AgentRegistryAdmin]; this name matches the AgentCore public
 * API convention without introducing a second implementation.
 */
typealias AgentCoreRegistryAdmin = AgentRegistryAdmin

/** Build the Agent Registry control-plane facade from its owned clients. */
fun AgentRegistryClients.agentCoreRegistryAdmin(): AgentCoreRegistryAdmin =
    AgentCoreRegistryAdmin(agentRegistryControl)
