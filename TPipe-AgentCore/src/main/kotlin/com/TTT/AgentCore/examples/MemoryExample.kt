package com.TTT.AgentCore.examples

import com.TTT.AgentCore.AgentCoreClients
import com.TTT.AgentCore.memory.AgentCoreMemoryBackend
import com.TTT.AgentCore.memory.AgentCoreMemoryConfig

/**
 * Create the exact ContextBank backend while keeping semantic memory separate.
 *
 * @param clients AgentCore data and control clients.
 * @param config Memory backend configuration.
 * @return A backend for exact TPipe context and todo values.
 */
fun exactMemoryExample(clients: AgentCoreClients, config: AgentCoreMemoryConfig): AgentCoreMemoryBackend =
    AgentCoreMemoryBackend(clients, config)
