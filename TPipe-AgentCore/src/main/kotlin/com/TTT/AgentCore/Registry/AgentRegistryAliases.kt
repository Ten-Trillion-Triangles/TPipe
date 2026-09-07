package com.TTT.AgentCore.Registry

import aws.sdk.kotlin.services.agentregistrycontrol.model.AgentSkillsAdditionalData
import aws.sdk.kotlin.services.agentregistrycontrol.model.AgentSkillsDefinitionDescriptor
import aws.sdk.kotlin.services.agentregistrycontrol.model.CustomDescriptor
import aws.sdk.kotlin.services.agentregistrycontrol.model.DescriptorSource
import aws.sdk.kotlin.services.agentregistrycontrol.model.Descriptors
import aws.sdk.kotlin.services.agentregistrycontrol.model.McpServerAdditionalData
import aws.sdk.kotlin.services.agentregistrycontrol.model.McpServerDescriptor
import aws.sdk.kotlin.services.agentregistry.model.RegistryRecordStatus as DiscoveryRegistryRecordStatus
import aws.sdk.kotlin.services.agentregistrycontrol.model.RegistryRecordStatus as ControlRegistryRecordStatus
import com.TTT.AgentCore.AgentCoreClients
import com.TTT.AgentCore.registry.custom as customInternal
import com.TTT.AgentCore.registry.mcp as mcpInternal
import com.TTT.AgentCore.registry.registryAdmin as registryAdminInternal
import com.TTT.AgentCore.registry.registryDiscovery as registryDiscoveryInternal
import com.TTT.AgentCore.registry.skill as skillInternal

/** Compatibility aliases for the capitalized registry package layout. */
typealias AgentRegistryClients = com.TTT.AgentCore.registry.AgentRegistryClients
typealias AgentRegistryAdmin = com.TTT.AgentCore.registry.AgentRegistryAdmin
typealias RegistryAdmin = com.TTT.AgentCore.registry.RegistryAdmin
typealias Registry = com.TTT.AgentCore.registry.Registry
typealias RegistryRecord = com.TTT.AgentCore.registry.RegistryRecord
typealias AgentRegistryDiscovery = com.TTT.AgentCore.registry.AgentRegistryDiscovery
typealias AgentRegistryBatchGetResult = com.TTT.AgentCore.registry.AgentRegistryBatchGetResult

/** Build an MCP descriptor in the capitalized compatibility package. */
fun mcpDescriptor(
    data: String? = null,
    dataSchemaVersion: String? = null,
    source: DescriptorSource? = null,
    additionalData: McpServerAdditionalData? = null
): Descriptors = com.TTT.AgentCore.registry.mcpDescriptor(data, dataSchemaVersion, source, additionalData)

/** Build an agent-skills descriptor in the capitalized compatibility package. */
fun skillDescriptor(
    data: String? = null,
    dataSchemaVersion: String? = null,
    additionalData: AgentSkillsAdditionalData? = null
): Descriptors = com.TTT.AgentCore.registry.skillDescriptor(data, dataSchemaVersion, additionalData)

/** Build a custom descriptor in the capitalized compatibility package. */
fun customDescriptor(data: String): Descriptors = com.TTT.AgentCore.registry.customDescriptor(data)

/** Build an MCP descriptor model in the capitalized compatibility package. */
fun mcpServerDescriptor(
    data: String? = null,
    dataSchemaVersion: String? = null,
    source: DescriptorSource? = null,
    additionalData: McpServerAdditionalData? = null
): McpServerDescriptor =
    com.TTT.AgentCore.registry.mcpServerDescriptor(data, dataSchemaVersion, source, additionalData)

/** Build an agent-skills descriptor model in the capitalized compatibility package. */
fun agentSkillsDefinitionDescriptor(
    data: String? = null,
    dataSchemaVersion: String? = null,
    additionalData: AgentSkillsAdditionalData? = null
): AgentSkillsDefinitionDescriptor =
    com.TTT.AgentCore.registry.agentSkillsDefinitionDescriptor(data, dataSchemaVersion, additionalData)

/** Build a custom descriptor model in the capitalized compatibility package. */
fun customDescriptorModel(data: String): CustomDescriptor =
    com.TTT.AgentCore.registry.customDescriptorModel(data)

/** Populate an MCP descriptor builder in the capitalized compatibility package. */
fun Descriptors.Builder.mcp(
    data: String? = null,
    dataSchemaVersion: String? = null,
    source: DescriptorSource? = null,
    additionalData: McpServerAdditionalData? = null
)
{
    this.mcpInternal(data, dataSchemaVersion, source, additionalData)
}

/** Populate an agent-skills descriptor builder in the capitalized compatibility package. */
fun Descriptors.Builder.skill(
    data: String? = null,
    dataSchemaVersion: String? = null,
    additionalData: AgentSkillsAdditionalData? = null
)
{
    this.skillInternal(data, dataSchemaVersion, additionalData)
}

/** Populate a custom descriptor builder in the capitalized compatibility package. */
fun Descriptors.Builder.custom(data: String)
{
    this.customInternal(data)
}

/** Build registry administration from the separate registry client bundle. */
fun AgentRegistryClients.registryAdmin(): AgentRegistryAdmin =
    this.registryAdminInternal()

/** Build registry discovery from the separate registry client bundle. */
fun AgentRegistryClients.registryDiscovery(): AgentRegistryDiscovery =
    this.registryDiscoveryInternal()

/** Whether a discovery status is terminal. */
val DiscoveryRegistryRecordStatus.isTerminal: Boolean
    get() = this is DiscoveryRegistryRecordStatus.Deprecated

/** Whether a control status is terminal. */
val ControlRegistryRecordStatus.isTerminal: Boolean
    get() = this is ControlRegistryRecordStatus.Deprecated

/** Whether a discovery status is discoverable. */
val DiscoveryRegistryRecordStatus.isDiscoverable: Boolean
    get() = this is DiscoveryRegistryRecordStatus.Approved

/** Whether a control status is discoverable. */
val ControlRegistryRecordStatus.isDiscoverable: Boolean
    get() = this is ControlRegistryRecordStatus.Approved
