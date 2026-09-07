package com.TTT.AgentCore.registry

/** Compatibility name for discoverable Agent Registry data-plane operations. */
typealias AgentCoreRegistryClient = AgentRegistryDiscovery

/** Build the discoverable Agent Registry data-plane facade from owned clients. */
fun AgentRegistryClients.agentCoreRegistryClient(): AgentCoreRegistryClient =
    AgentCoreRegistryClient(agentRegistry)
