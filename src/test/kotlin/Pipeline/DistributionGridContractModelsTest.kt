package com.TTT.Pipeline

import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract-level regression coverage for the Phase 1 `DistributionGrid` model layer.
 */
class DistributionGridContractModelsTest
{
    /**
     * Verifies that the core envelope and directive models round-trip through serialization with nested content and
     * policy objects intact.
     */
    @Test
    fun coreEnvelopeAndDirectiveRoundTrip()
    {
        val failure = DistributionGridFailure(
            kind = DistributionGridFailureKind.POLICY_REJECTED,
            sourceNodeId = "router-a",
            targetNodeId = "worker-b",
            transportMethod = Transport.Http,
            transportAddress = "https://grid.example/worker-b",
            reason = "Node rejected the storage policy.",
            policyCause = "trace-storage-blocked",
            retryable = false
        )
        val outcome = DistributionGridOutcome(
            status = DistributionGridOutcomeStatus.REJECTED,
            returnMode = DistributionGridReturnMode.RETURN_FAILURE_UPSTREAM,
            taskId = "task-001",
            finalContent = MultimodalContent(text = "Rejected."),
            completionNotes = "Stopped during peer selection.",
            hopCount = 2,
            finalNodeId = "router-a",
            terminalFailure = failure
        )
        val envelope = DistributionGridEnvelope(
            taskId = "task-001",
            originNodeId = "origin-node",
            originTransport = P2PTransport(transportMethod = Transport.Tpipe, transportAddress = "origin-node"),
            senderNodeId = "sender-node",
            senderTransport = P2PTransport(transportMethod = Transport.Http, transportAddress = "https://grid.example/sender"),
            currentNodeId = "router-a",
            currentTransport = P2PTransport(transportMethod = Transport.Stdio, transportAddress = "/usr/local/bin/router"),
            content = MultimodalContent(text = "Book the flight and hotel."),
            taskIntent = "Travel booking workflow",
            currentObjective = "Choose the next booking node",
            routingPolicy = DistributionGridRoutingPolicy(
                completionReturnMode = DistributionGridReturnMode.RETURN_TO_ORIGIN,
                failureReturnMode = DistributionGridReturnMode.RETURN_FAILURE_UPSTREAM,
                returnAfterFirstLocalWork = true,
                allowRetrySamePeer = true,
                maxRetryCount = 2,
                allowAlternatePeers = true,
                maxHopCount = 12
            ),
            tracePolicy = DistributionGridTracePolicy(
                allowTracing = true,
                allowTracePersistence = false,
                requireRedaction = true,
                rejectNonCompliantNodes = true,
                allowedStorageClasses = mutableListOf("memory"),
                disallowedStorageClasses = mutableListOf("disk")
            ),
            credentialPolicy = DistributionGridCredentialPolicy(
                forwardSecrets = false,
                credentialRefs = mutableListOf("travel-api"),
                perHopCredentialRefs = mutableMapOf("hotel-node" to "hotel-api"),
                allowCredentialTransforms = true,
                denySecretPropagation = true
            ),
            executionNotes = mutableListOf("Origin requested minimal retention."),
            hopHistory = mutableListOf(
                DistributionGridHopRecord(
                    sourceNodeId = "origin-node",
                    destinationNodeId = "router-a",
                    transportMethod = Transport.Tpipe,
                    transportAddress = "router-a",
                    routerAction = DistributionGridDirectiveKind.RUN_LOCAL_WORKER,
                    privacyDecision = "redacted",
                    resultSummary = "Router inspected the task.",
                    startedAtEpochMillis = 10L,
                    completedAtEpochMillis = 12L
                )
            ),
            completed = false,
            latestOutcome = outcome,
            latestFailure = failure,
            durableStateKey = "grid/task-001",
            sessionRef = DistributionGridSessionRef(
                sessionId = "session-1",
                requesterNodeId = "origin-node",
                responderNodeId = "router-a",
                registryId = "travel-registry",
                expiresAtEpochMillis = 1000L
            ),
            attributes = mutableMapOf("priority" to "high")
        )
        val directive = DistributionGridDirective(
            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
            targetNodeId = "hotel-node",
            targetPeerId = "hotel-peer",
            targetTransport = P2PTransport(transportMethod = Transport.Http, transportAddress = "https://grid.example/hotel"),
            notes = "Route to hotel inventory node.",
            alternatePeerIds = mutableListOf("hotel-peer-backup"),
            rejectReason = ""
        )

        val serializedEnvelope = serialize(envelope)
        val serializedDirective = serialize(directive)
        val restoredEnvelope = deserialize<DistributionGridEnvelope>(serializedEnvelope)
        val restoredDirective = deserialize<DistributionGridDirective>(serializedDirective)

        assertNotNull(restoredEnvelope)
        assertNotNull(restoredDirective)
        assertEquals("task-001", restoredEnvelope.taskId)
        assertEquals("Travel booking workflow", restoredEnvelope.taskIntent)
        assertEquals("Choose the next booking node", restoredEnvelope.currentObjective)
        assertEquals("travel-registry", restoredEnvelope.sessionRef?.registryId)
        assertEquals("origin-node", restoredEnvelope.originNodeId)
        assertEquals(1, restoredEnvelope.hopHistory.size)
        assertEquals(DistributionGridFailureKind.POLICY_REJECTED, restoredEnvelope.latestFailure?.kind)
        assertEquals(DistributionGridOutcomeStatus.REJECTED, restoredEnvelope.latestOutcome?.status)
        assertEquals("hotel-node", restoredDirective.targetNodeId)
        assertEquals(DistributionGridDirectiveKind.HAND_OFF_TO_PEER, restoredDirective.kind)
        assertEquals("hotel-peer-backup", restoredDirective.alternatePeerIds.first())
    }

    /**
     * Verifies that memory-policy callback fields remain transient and memory models round-trip safely.
     */
    @Test
    fun memoryModelsRoundTripAndSkipTransientSummarizer()
    {
        val policy = DistributionGridMemoryPolicy(
            outboundTokenBudget = 4096,
            safetyReserveTokens = 128,
            minimumCriticalBudget = 512,
            minimumRecentBudget = 320,
            maxExecutionNotes = 6,
            maxHopRecords = 10,
            enableSummarization = true,
            summaryBudget = 768,
            maxSummaryCharacters = 2048,
            redactContentSections = true,
            redactTraceMetadata = true,
            summarizer = { text -> "summary:$text" }
        )
        val envelope = DistributionGridMemoryEnvelope(
            targetNodeId = "hotel-node",
            resolvedBudget = 3800,
            availableBudget = 3600,
            safetyReserveTokens = 128,
            criticalBudget = 1200,
            recentBudget = 1000,
            summaryBudget = 768,
            summarizationUsed = true,
            compacted = true,
            failureReason = "",
            sections = mutableListOf(
                DistributionGridMemorySection(
                    name = "task-intent",
                    tokenBudget = 250,
                    text = "Travel booking workflow.",
                    truncated = false,
                    tokenCount = 8
                )
            )
        )

        val serializedPolicy = serialize(policy)
        val serializedEnvelope = serialize(envelope)
        val restoredPolicy = deserialize<DistributionGridMemoryPolicy>(serializedPolicy)
        val restoredEnvelope = deserialize<DistributionGridMemoryEnvelope>(serializedEnvelope)

        assertNotNull(restoredPolicy)
        assertNotNull(restoredEnvelope)
        assertTrue(serializedPolicy.isNotEmpty())
        assertFalse(serializedPolicy.contains("summarizer"))
        assertNull(restoredPolicy.summarizer)
        assertTrue(restoredEnvelope.compacted)
        assertEquals("task-intent", restoredEnvelope.sections.first().name)
        assertEquals(250, restoredEnvelope.sections.first().tokenBudget)
    }

    /**
     * Verifies that protocol metadata, advertisements, handshake records, and RPC messages round-trip cleanly.
     */
    @Test
    fun protocolModelsRoundTripAndNegotiateVersion()
    {
        val descriptor = buildDescriptor("hotel-node", Transport.Http, "https://grid.example/hotel")
        val nodeMetadata = DistributionGridNodeMetadata(
            nodeId = "hotel-node",
            supportedProtocolVersions = mutableListOf(
                DistributionGridProtocolVersion(1, 0, 0),
                DistributionGridProtocolVersion(1, 2, 0)
            ),
            roleCapabilities = mutableListOf("router", "hotel-search"),
            registryMemberships = mutableListOf("travel-registry"),
            supportedTransports = mutableListOf(Transport.Http, Transport.Tpipe),
            requiresHandshake = true,
            defaultTracePolicy = DistributionGridTracePolicy(
                allowTracing = true,
                allowTracePersistence = false,
                requireRedaction = true
            ),
            defaultRoutingPolicy = DistributionGridRoutingPolicy(
                completionReturnMode = DistributionGridReturnMode.RETURN_TO_SENDER,
                failureReturnMode = DistributionGridReturnMode.RETURN_FAILURE_UPSTREAM,
                maxHopCount = 12
            ),
            actsAsRegistry = false
        )
        val lease = DistributionGridRegistrationLease(
            leaseId = "lease-1",
            nodeId = "hotel-node",
            registryId = "travel-registry",
            grantedLeaseSeconds = 600,
            expiresAtEpochMillis = 60000L,
            renewalRequired = true
        )
        val nodeAdvertisement = DistributionGridNodeAdvertisement(
            descriptor = descriptor,
            metadata = nodeMetadata,
            registryId = "travel-registry",
            lease = lease,
            attestationRef = "sig://travel-registry/hotel-node",
            discoveredAtEpochMillis = 10L,
            expiresAtEpochMillis = 60000L
        )
        val negotiatedPolicy = DistributionGridNegotiatedPolicy(
            protocolVersion = DistributionGridProtocolVersion(1, 2, 0),
            tracePolicy = DistributionGridTracePolicy(allowTracing = true, allowTracePersistence = false),
            credentialPolicy = DistributionGridCredentialPolicy(
                credentialRefs = mutableListOf("hotel-api"),
                perHopCredentialRefs = mutableMapOf("hotel-node" to "hotel-api")
            ),
            routingPolicy = DistributionGridRoutingPolicy(maxHopCount = 8),
            storageClasses = mutableListOf("memory")
        )
        val sessionRecord = DistributionGridSessionRecord(
            sessionId = "session-1",
            requesterNodeId = "origin-node",
            responderNodeId = "hotel-node",
            registryId = "travel-registry",
            negotiatedProtocolVersion = DistributionGridProtocolVersion(1, 2, 0),
            negotiatedPolicy = negotiatedPolicy,
            expiresAtEpochMillis = 100000L,
            invalidated = false,
            invalidationReason = ""
        )
        val handshakeResponse = DistributionGridHandshakeResponse(
            accepted = true,
            negotiatedProtocolVersion = DistributionGridProtocolVersion(1, 2, 0),
            negotiatedPolicy = negotiatedPolicy,
            rejectionReason = "",
            sessionRecord = sessionRecord
        )
        val rpcMessage = DistributionGridRpcMessage(
            messageType = DistributionGridRpcMessageType.HANDSHAKE_ACK,
            senderNodeId = "hotel-node",
            targetId = "origin-node",
            protocolVersion = DistributionGridProtocolVersion(1, 2, 0),
            sessionRef = DistributionGridSessionRef(
                sessionId = "session-1",
                requesterNodeId = "origin-node",
                responderNodeId = "hotel-node",
                registryId = "travel-registry",
                expiresAtEpochMillis = 100000L
            ),
            payloadType = "DistributionGridHandshakeResponse",
            payloadJson = serialize(handshakeResponse)
        )

        val serializedAdvertisement = serialize(nodeAdvertisement)
        val serializedRpc = serialize(rpcMessage)
        val restoredAdvertisement = deserialize<DistributionGridNodeAdvertisement>(serializedAdvertisement)
        val restoredRpc = deserialize<DistributionGridRpcMessage>(serializedRpc)
        val negotiatedVersion = DistributionGridProtocolVersion.negotiateHighestShared(
            localVersions = listOf(
                DistributionGridProtocolVersion(1, 0, 0),
                DistributionGridProtocolVersion(1, 2, 0)
            ),
            remoteVersions = listOf(
                DistributionGridProtocolVersion(1, 1, 0),
                DistributionGridProtocolVersion(1, 2, 0),
                DistributionGridProtocolVersion(2, 0, 0)
            )
        )

        assertNotNull(restoredAdvertisement)
        assertNotNull(restoredRpc)
        assertEquals("hotel-node", restoredAdvertisement.metadata.nodeId)
        assertEquals("travel-registry", restoredAdvertisement.registryId)
        assertEquals(DistributionGridRpcMessageType.HANDSHAKE_ACK, restoredRpc.messageType)
        assertEquals("DistributionGridHandshakeResponse", restoredRpc.payloadType)
        assertEquals("travel-registry", restoredRpc.sessionRef?.registryId)
        assertNotNull(negotiatedVersion)
        assertEquals(1, negotiatedVersion.major)
        assertEquals(2, negotiatedVersion.minor)
        assertTrue(DistributionGridProtocolVersion(1, 2, 0).isCompatibleWith(DistributionGridProtocolVersion(1, 0, 0)))
        assertFalse(DistributionGridProtocolVersion(1, 2, 0).isCompatibleWith(DistributionGridProtocolVersion(2, 0, 0)))
    }

    /**
     * Verifies that the durable-store contract can be implemented and used without touching runtime behavior.
     */
    @Test
    fun durableStoreContractSupportsSimpleImplementations()
    {
        runBlocking {
            val store = RecordingDurableStore()
            val state = DistributionGridDurableState(
                taskId = "task-001",
                stateVersion = 1,
                envelope = DistributionGridEnvelope(
                    taskId = "task-001",
                    currentNodeId = "router-a",
                    content = MultimodalContent(text = "Checkpoint")
                ),
                sessionRef = DistributionGridSessionRef(
                    sessionId = "session-1",
                    requesterNodeId = "origin-node",
                    responderNodeId = "router-a",
                    registryId = "travel-registry",
                    expiresAtEpochMillis = 100000L
                ),
                checkpointReason = "after-router-decision",
                completed = false,
                updatedAtEpochMillis = 50L,
                archived = false
            )

            store.saveState(state)
            val loaded = store.loadState("task-001")
            val resumed = store.resumeState("task-001")
            val archived = store.archiveState("task-001")
            val cleared = store.clearState("task-001")

            assertNotNull(loaded)
            assertNotNull(resumed)
            assertEquals("Checkpoint", loaded.envelope.content.text)
            assertTrue(archived)
            assertTrue(cleared)
            assertNull(store.loadState("task-001"))
        }
    }

    /**
     * Helper used to build a minimal serializable descriptor for protocol-model tests.
     *
     * @param name Agent name carried by the descriptor.
     * @param transportMethod Transport method used by the descriptor.
     * @param transportAddress Transport address used by the descriptor.
     * @return Descriptor configured with safe local defaults for contract tests.
     */
    private fun buildDescriptor(name: String, transportMethod: Transport, transportAddress: String): P2PDescriptor
    {
        return P2PDescriptor(
            agentName = name,
            agentDescription = "Contract-test descriptor for $name",
            transport = P2PTransport(
                transportMethod = transportMethod,
                transportAddress = transportAddress
            ),
            requiresAuth = false,
            usesConverse = false,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none,
            supportedContentTypes = mutableListOf(SupportedContentTypes.text)
        )
    }

    /**
     * Simple in-memory durable-store fake used to verify the public store contract compiles and behaves predictably.
     */
    private class RecordingDurableStore : DistributionGridDurableStore
    {
        private val stateByTaskId = mutableMapOf<String, DistributionGridDurableState>()

        override suspend fun saveState(state: DistributionGridDurableState)
        {
            stateByTaskId[state.taskId] = state
        }

        override suspend fun loadState(taskId: String): DistributionGridDurableState?
        {
            return stateByTaskId[taskId]
        }

        override suspend fun resumeState(taskId: String): DistributionGridDurableState?
        {
            return stateByTaskId[taskId]
        }

        override suspend fun clearState(taskId: String): Boolean
        {
            return stateByTaskId.remove(taskId) != null
        }

        override suspend fun archiveState(taskId: String): Boolean
        {
            val state = stateByTaskId[taskId] ?: return false
            stateByTaskId[taskId] = state.copy(archived = true)
            return true
        }
    }
}
