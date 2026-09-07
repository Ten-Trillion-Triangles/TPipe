package com.TTT.AgentCore.registry

import aws.sdk.kotlin.services.agentregistry.model.RegistryRecordStatus as DiscoveryRegistryRecordStatus
import aws.sdk.kotlin.services.agentregistrycontrol.model.RegistryRecordStatus as ControlRegistryRecordStatus

/** Whether the discovery-plane status is terminal for the record lifecycle. */
val DiscoveryRegistryRecordStatus.isTerminal: Boolean
    get() = this is DiscoveryRegistryRecordStatus.Deprecated

/** Whether the control-plane status is terminal for the record lifecycle. */
val ControlRegistryRecordStatus.isTerminal: Boolean
    get() = this is ControlRegistryRecordStatus.Deprecated

/** Whether the discovery-plane status is visible through discovery APIs. */
val DiscoveryRegistryRecordStatus.isDiscoverable: Boolean
    get() = this is DiscoveryRegistryRecordStatus.Approved

/** Whether the control-plane status represents a discoverable approved record. */
val ControlRegistryRecordStatus.isDiscoverable: Boolean
    get() = this is ControlRegistryRecordStatus.Approved
