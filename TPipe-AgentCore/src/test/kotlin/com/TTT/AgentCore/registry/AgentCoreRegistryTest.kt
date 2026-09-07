package com.TTT.AgentCore.registry

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AgentCoreRegistryTest
{
    @Test
    fun exposesAgentCoreCompatibilityNamesWithoutASecondImplementation()
    {
        assertEquals(AgentRegistryAdmin::class, AgentCoreRegistryAdmin::class)
        assertEquals(AgentRegistryDiscovery::class, AgentCoreRegistryClient::class)
    }
}
