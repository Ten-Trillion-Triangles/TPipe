package com.TTT.AgentCore.registry

import aws.sdk.kotlin.services.agentregistrycontrol.model.AgentSkillsAdditionalData
import aws.sdk.kotlin.services.agentregistrycontrol.model.AgentSkillsDefinitionDescriptor
import aws.sdk.kotlin.services.agentregistrycontrol.model.CustomDescriptor
import aws.sdk.kotlin.services.agentregistrycontrol.model.DescriptorSource
import aws.sdk.kotlin.services.agentregistrycontrol.model.Descriptors
import aws.sdk.kotlin.services.agentregistrycontrol.model.McpServerAdditionalData
import aws.sdk.kotlin.services.agentregistrycontrol.model.McpServerDescriptor

/** Build the MCP variant of the typed descriptor union. */
fun mcpDescriptor(
    data: String? = null,
    dataSchemaVersion: String? = null,
    source: DescriptorSource? = null,
    additionalData: McpServerAdditionalData? = null
): Descriptors = Descriptors {
    mcp(data, dataSchemaVersion, source, additionalData)
}

/** Build the agent-skills variant of the typed descriptor union. */
fun skillDescriptor(
    data: String? = null,
    dataSchemaVersion: String? = null,
    additionalData: AgentSkillsAdditionalData? = null
): Descriptors = Descriptors {
    skill(data, dataSchemaVersion, additionalData)
}

/** Build the custom variant of the typed descriptor union. */
fun customDescriptor(data: String): Descriptors = Descriptors {
    custom(data)
}

/** Populate the MCP variant on a descriptor builder. */
fun Descriptors.Builder.mcp(
    data: String? = null,
    dataSchemaVersion: String? = null,
    source: DescriptorSource? = null,
    additionalData: McpServerAdditionalData? = null
)
{
    mcpServer {
        this.data = data
        this.dataSchemaVersion = dataSchemaVersion
        this.source = source
        this.additionalData = additionalData
    }
}

/** Populate the agent-skills variant on a descriptor builder. */
fun Descriptors.Builder.skill(
    data: String? = null,
    dataSchemaVersion: String? = null,
    additionalData: AgentSkillsAdditionalData? = null
)
{
    agentSkillsDefinition {
        this.data = data
        this.dataSchemaVersion = dataSchemaVersion
        this.additionalData = additionalData
    }
}

/** Populate the custom variant on a descriptor builder. */
fun Descriptors.Builder.custom(data: String)
{
    custom {
        this.data = data
    }
}

/** Build an MCP descriptor model without wrapping it in the union. */
fun mcpServerDescriptor(
    data: String? = null,
    dataSchemaVersion: String? = null,
    source: DescriptorSource? = null,
    additionalData: McpServerAdditionalData? = null
): McpServerDescriptor = McpServerDescriptor {
    this.data = data
    this.dataSchemaVersion = dataSchemaVersion
    this.source = source
    this.additionalData = additionalData
}

/** Build an agent-skills descriptor model without wrapping it in the union. */
fun agentSkillsDefinitionDescriptor(
    data: String? = null,
    dataSchemaVersion: String? = null,
    additionalData: AgentSkillsAdditionalData? = null
): AgentSkillsDefinitionDescriptor = AgentSkillsDefinitionDescriptor {
    this.data = data
    this.dataSchemaVersion = dataSchemaVersion
    this.additionalData = additionalData
}

/** Build a custom descriptor model without wrapping it in the union. */
fun customDescriptorModel(data: String): CustomDescriptor = CustomDescriptor {
    this.data = data
}
