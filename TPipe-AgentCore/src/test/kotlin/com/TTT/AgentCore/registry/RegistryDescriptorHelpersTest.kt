package com.TTT.AgentCore.registry

import aws.sdk.kotlin.services.agentregistrycontrol.model.Descriptors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegistryDescriptorHelpersTest
{
    @Test
    fun createsMcpSkillAndCustomDescriptorUnions()
    {
        val mcp = mcpDescriptor(
            data = "{\"name\":\"weather\"}",
            dataSchemaVersion = "2025-12-11"
        )
        val skill = skillDescriptor(
            data = "# Weather skill",
            dataSchemaVersion = "1.0"
        )
        val custom = customDescriptor("{\"kind\":\"custom\"}")

        assertEquals("{\"name\":\"weather\"}", mcp.mcpServer?.data)
        assertEquals("2025-12-11", mcp.mcpServer?.dataSchemaVersion)
        assertNull(mcp.custom)
        assertEquals("# Weather skill", skill.agentSkillsDefinition?.data)
        assertEquals("1.0", skill.agentSkillsDefinition?.dataSchemaVersion)
        assertEquals("{\"kind\":\"custom\"}", custom.custom?.data)
    }

    @Test
    fun supportsDescriptorBuilderHelpersWithoutPopulatingOtherVariants()
    {
        val descriptors = Descriptors {
            mcp(data = "mcp")
        }

        assertEquals("mcp", descriptors.mcpServer?.data)
        assertNull(descriptors.agentSkillsDefinition)
        assertNull(descriptors.custom)
    }
}
