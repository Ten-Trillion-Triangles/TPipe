package com.TTT.Pipeline

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TracePhase
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PRejection
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.PipeError
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.PipeContextProtocol.StdioContextOptions
import com.TTT.PipeContextProtocol.StdioExecutionMode
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.deserialize
import com.TTT.Util.deepCopy
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the Phase 5 explicit remote peer handoff slice.
 */
class DistributionGridRemoteHandoffTest
{
    companion object {
        private const val GRID_RPC_FRAME_PREFIX = "TPipe-DistributionGrid-RPC::1"

        /**
         * Build a public grid descriptor for static test doubles outside the test instance.
         *
         * @param nodeId Stable grid node id.
         * @param transportAddress Outward transport address.
         * @return Public grid descriptor.
         */
        private fun buildStaticGridDescriptor(
            nodeId: String,
            transportAddress: String
        ): P2PDescriptor
        {
            return P2PDescriptor(
                agentName = nodeId,
                agentDescription = "DistributionGrid static test node",
                transport = P2PTransport(
                    transportMethod = Transport.Tpipe,
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
                supportedContentTypes = mutableListOf(SupportedContentTypes.text),
                distributionGridMetadata = DistributionGridNodeMetadata(
                    nodeId = nodeId,
                    supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
                    roleCapabilities = mutableListOf("Router", "Worker"),
                    supportedTransports = mutableListOf(Transport.Tpipe),
                    requiresHandshake = true,
                    defaultTracePolicy = DistributionGridTracePolicy(),
                    defaultRoutingPolicy = DistributionGridRoutingPolicy(),
                    actsAsRegistry = false
                )
            )
        }

        /**
         * Build framed prompt text for static test doubles.
         *
         * @param rpcMessage Grid RPC message to serialize.
         * @return Framed RPC prompt text.
         */
        private fun buildStaticGridRpcPrompt(rpcMessage: DistributionGridRpcMessage): String
        {
            return "$GRID_RPC_FRAME_PREFIX\n${serialize(rpcMessage)}"
        }
    }

    /**
     * Verifies that a sender can handshake with and hand off to an explicit remote peer, then finalize locally.
     */
    @Test
    fun explicitPeerHandoffExecutesRemotelyAndFinalizesLocally()
    {
        runBlocking {
            val remoteGrid = remoteGrid(
                nodeId = "remote-grid",
                transportAddress = "remote-grid-address",
                router = ExecutionInterface("remote-router") { content ->
                    content.addText("remote-router")
                    content
                },
                worker = ExecutionInterface("remote-worker") { content ->
                    content.addText("remote-worker")
                    content
                }
            )
                .setBeforeRouteHook { envelope ->
                    envelope.attributes["remote-result-attribute"] = "remote-success"
                    envelope
                }
                .setBeforeLocalWorkerHook { envelope ->
                    envelope.attributes["remote-worker-attribute"] = "remote-worker"
                    envelope
                }

            try
            {
                val senderRouter = ExecutionInterface("sender-router") { content ->
                    content.addText("sender-router")
                    content.metadata["distributionGridDirective"] = DistributionGridDirective(
                        kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                        targetPeerId = remoteGrid.getP2pTransport()!!.let {
                            "${it.transportMethod}::${it.transportAddress}"
                        }
                    )
                    content
                }
                val senderWorker = ExecutionInterface("sender-worker") { content ->
                    content.addText("sender-worker")
                    content
                }

                val senderGrid = initializedGrid(
                    nodeId = "sender-grid",
                    transportAddress = "sender-grid-address",
                    router = senderRouter,
                    worker = senderWorker
                )
                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val envelope = result.metadata["distributionGridEnvelope"] as? DistributionGridEnvelope

                assertTrue(result.passPipeline)
                assertFalse(result.terminatePipeline)
                assertTrue(result.text.contains("remote-router"))
                assertTrue(result.text.contains("remote-worker"))
                assertEquals(1, senderRouter.localExecutionCount)
                assertEquals(0, senderWorker.localExecutionCount)
                assertNotNull(envelope)
                assertEquals("remote-success", envelope.attributes["remote-result-attribute"])
                assertEquals("remote-worker", envelope.attributes["remote-worker-attribute"])
                assertEquals(2, envelope.hopHistory.size)
                assertEquals(
                    remoteGrid.getP2pDescription()!!.distributionGridMetadata!!.nodeId,
                    envelope.currentNodeId
                )
                assertEquals(
                    remoteGrid.getP2pDescription()!!.distributionGridMetadata!!.nodeId,
                    (result.metadata["distributionGridOutcome"] as DistributionGridOutcome).finalNodeId
                )
                assertEquals(
                    envelope.hopHistory.size,
                    (result.metadata["distributionGridOutcome"] as DistributionGridOutcome).hopCount
                )
                assertEquals(
                    DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                    envelope.hopHistory.first().routerAction
                )
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that inbound remote handoff records the caller's return address as the sender transport.
     */
    @Test
    fun explicitPeerHandoffRecordsCallerReturnAddressAsSenderTransport()
    {
        runBlocking {
            var capturedSenderTransportAddress = ""
            val remoteGrid = DistributionGrid()
            remoteGrid.setP2pTransport(
                P2PTransport(
                    transportMethod = Transport.Tpipe,
                    transportAddress = "sender-transport-remote-address"
                )
            )
            remoteGrid.setP2pDescription(
                buildGridDescriptor(
                    nodeId = "sender-transport-remote-grid",
                    transportAddress = "sender-transport-remote-address"
                )
            )
            remoteGrid.setP2pRequirements(
                P2PRequirements(
                    allowExternalConnections = true,
                    acceptedContent = mutableListOf(SupportedContentTypes.text)
                )
            )
            remoteGrid
                .setRouter(ExecutionInterface("sender-transport-router") { it })
                .setWorker(ExecutionInterface("sender-transport-worker") { content ->
                    content.addText("sender-transport-worker")
                    content
                })
                .setBeforeRouteHook { envelope ->
                    capturedSenderTransportAddress = envelope.senderTransport.transportAddress
                    envelope
                }
            remoteGrid.init()
            P2PRegistry.register(remoteGrid)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "sender-transport-sender-grid",
                    transportAddress = "sender-transport-sender-address",
                    router = ExecutionInterface("sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remoteGrid.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("sender-worker") { it }
                )

                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))

                assertTrue(result.passPipeline)
                assertEquals(
                    senderGrid.getP2pTransport()!!.transportAddress,
                    capturedSenderTransportAddress
                )
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that a valid explicit-peer session is reused on repeated calls.
     */
    @Test
    fun repeatedExplicitPeerCallsReuseTheNegotiatedSession()
    {
        runBlocking {
            val remoteGrid = remoteGrid(
                nodeId = "session-remote-grid",
                transportAddress = "session-remote-grid-address",
                router = ExecutionInterface("remote-router") { it },
                worker = ExecutionInterface("remote-worker") { content ->
                    content.addText("remote-worker")
                    content
                }
            )

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "session-sender-grid",
                    transportAddress = "session-sender-grid-address",
                    router = ExecutionInterface("sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remoteGrid.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("sender-worker") { it }
                )
                    .enableTracing()

                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())

                senderGrid.init()
                senderGrid.execute(MultimodalContent(text = "first"))
                senderGrid.execute(MultimodalContent(text = "second"))

                val handshakeEvents = PipeTracer.getTrace(readGridId(senderGrid))
                    .count { it.eventType == TraceEventType.DISTRIBUTION_GRID_SESSION_HANDSHAKE }

                assertEquals(1, handshakeEvents)
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that a remote session rejection invalidates the cached session so the next call re-handshakes.
     */
    @Test
    fun sessionRejectInvalidatesCachedSessionAndAllowsFreshHandshake()
    {
        runBlocking {
            val remoteGrid = remoteGrid(
                nodeId = "stale-session-remote-grid",
                transportAddress = "stale-session-remote-address",
                router = ExecutionInterface("remote-router") { it },
                worker = ExecutionInterface("remote-worker") { content ->
                    content.addText("remote-worker")
                    content
                }
            )

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "stale-session-sender-grid",
                    transportAddress = "stale-session-sender-address",
                    router = ExecutionInterface("sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remoteGrid.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("sender-worker") { it }
                )
                    .enableTracing()

                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val firstResult = senderGrid.execute(MultimodalContent(text = "first"))
                assertTrue(firstResult.passPipeline)

                remoteGrid.clearRuntimeState()
                remoteGrid.init()

                val secondResult = senderGrid.execute(MultimodalContent(text = "second"))
                val secondFailure = secondResult.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(secondResult.terminatePipeline)
                assertNotNull(secondFailure)
                assertEquals(DistributionGridFailureKind.SESSION_REJECTED, secondFailure.kind)
                assertEquals(0, readSessionRecordCount(senderGrid))

                val thirdResult = senderGrid.execute(MultimodalContent(text = "third"))
                assertTrue(thirdResult.passPipeline)
                assertFalse(thirdResult.terminatePipeline)
                assertEquals(1, readSessionRecordCount(senderGrid))

                val handshakeEvents = PipeTracer.getTrace(readGridId(senderGrid))
                    .count { it.eventType == TraceEventType.DISTRIBUTION_GRID_SESSION_HANDSHAKE }

                assertEquals(2, handshakeEvents)
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that tightening task policy for a repeated peer call forces a fresh handshake instead of reusing a broader cached session.
     */
    @Test
    fun tighterPerTaskPolicyForcesFreshHandshake()
    {
        runBlocking {
            val observedPolicies = mutableListOf<Pair<Boolean, Int>>()
            val remoteGrid = DistributionGrid()
            remoteGrid.setP2pTransport(
                P2PTransport(
                    transportMethod = Transport.Tpipe,
                    transportAddress = "policy-refresh-remote-address"
                )
            )
            remoteGrid.setP2pDescription(
                buildGridDescriptor(
                    nodeId = "policy-refresh-remote-grid",
                    transportAddress = "policy-refresh-remote-address",
                    defaultTracePolicy = DistributionGridTracePolicy(
                        allowTracing = true,
                        allowTracePersistence = true,
                        requireRedaction = false,
                        rejectNonCompliantNodes = true,
                        allowedStorageClasses = mutableListOf("shared-storage", "wide-storage")
                    ),
                    defaultRoutingPolicy = DistributionGridRoutingPolicy(maxHopCount = 12)
                )
            )
            remoteGrid.setP2pRequirements(
                P2PRequirements(
                    allowExternalConnections = true,
                    acceptedContent = mutableListOf(SupportedContentTypes.text)
                )
            )
            remoteGrid
                .setRouter(ExecutionInterface("policy-refresh-router") { it })
                .setWorker(ExecutionInterface("policy-refresh-worker") { content ->
                    content.addText("policy-refresh-worker")
                    content
                })
                .setBeforeRouteHook { envelope ->
                    observedPolicies.add(
                        Pair(
                            envelope.tracePolicy.allowTracePersistence,
                            envelope.routingPolicy.maxHopCount
                        )
                    )
                    envelope
                }
            remoteGrid.init()
            P2PRegistry.register(remoteGrid)

            try
            {
                var taskCount = 0
                val senderGrid = initializedGrid(
                    nodeId = "policy-refresh-sender-grid",
                    transportAddress = "policy-refresh-sender-address",
                    router = ExecutionInterface("policy-refresh-sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remoteGrid.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("policy-refresh-sender-worker") { it }
                )
                    .enableTracing()
                    .setBeforeRouteHook { envelope ->
                        taskCount += 1
                        if(taskCount == 1)
                        {
                            envelope.tracePolicy.allowTracePersistence = true
                            envelope.tracePolicy.allowedStorageClasses = mutableListOf("shared-storage", "wide-storage")
                            envelope.routingPolicy.maxHopCount = 12
                        }

                        else
                        {
                            envelope.tracePolicy.allowTracePersistence = false
                            envelope.tracePolicy.allowedStorageClasses = mutableListOf("shared-storage")
                            envelope.routingPolicy.maxHopCount = 3
                        }

                        envelope
                    }

                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val firstResult = senderGrid.execute(MultimodalContent(text = "first"))
                val secondResult = senderGrid.execute(MultimodalContent(text = "second"))

                assertTrue(firstResult.passPipeline)
                assertTrue(secondResult.passPipeline)
                assertEquals(
                    listOf(Pair(true, 12), Pair(false, 3)),
                    observedPolicies
                )

                val handshakeEvents = PipeTracer.getTrace(readGridId(senderGrid))
                    .count { it.eventType == TraceEventType.DISTRIBUTION_GRID_SESSION_HANDSHAKE }

                assertEquals(2, handshakeEvents)
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that the inbound remote execution path uses the negotiated session policy instead of the serialized envelope policy.
     */
    @Test
    fun inboundRemoteHandoffUsesNegotiatedSessionPolicy()
    {
        runBlocking {
            var capturedTracePersistence = true
            var capturedRequireRedaction = false
            var capturedAllowedStorageClasses = mutableListOf<String>()
            var capturedMaxHopCount = 0
            var capturedForwardSecrets = true

            val remoteGrid = DistributionGrid()
            remoteGrid.setP2pTransport(
                P2PTransport(
                    transportMethod = Transport.Tpipe,
                    transportAddress = "policy-remote-grid-address"
                )
            )
            remoteGrid.setP2pDescription(
                buildGridDescriptor(
                    nodeId = "policy-remote-grid",
                    transportAddress = "policy-remote-grid-address",
                    defaultTracePolicy = DistributionGridTracePolicy(
                        allowTracing = true,
                        allowTracePersistence = false,
                        requireRedaction = true,
                        rejectNonCompliantNodes = true,
                        allowedStorageClasses = mutableListOf("shared-storage")
                    ),
                    defaultRoutingPolicy = DistributionGridRoutingPolicy(maxHopCount = 3)
                )
            )
            remoteGrid.setP2pRequirements(
                P2PRequirements(
                    allowExternalConnections = true,
                    acceptedContent = mutableListOf(SupportedContentTypes.text)
                )
            )
            remoteGrid
                .setRouter(ExecutionInterface("remote-router") { it })
                .setWorker(ExecutionInterface("remote-worker") { content ->
                    content.addText("remote-worker")
                    content
                })
                .setBeforeRouteHook { envelope ->
                    capturedTracePersistence = envelope.tracePolicy.allowTracePersistence
                    capturedRequireRedaction = envelope.tracePolicy.requireRedaction
                    capturedAllowedStorageClasses = envelope.tracePolicy.allowedStorageClasses.deepCopy()
                    capturedMaxHopCount = envelope.routingPolicy.maxHopCount
                    capturedForwardSecrets = envelope.credentialPolicy.forwardSecrets
                    envelope
                }
            remoteGrid.init()
            P2PRegistry.register(remoteGrid)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "policy-sender-grid",
                    transportAddress = "policy-sender-grid-address",
                    router = ExecutionInterface("sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remoteGrid.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("sender-worker") { it }
                )
                    .setRoutingPolicy(DistributionGridRoutingPolicy(maxHopCount = 12))
                    .setBeforeRouteHook { envelope ->
                        envelope.tracePolicy = DistributionGridTracePolicy(
                            allowTracing = true,
                            allowTracePersistence = true,
                            requireRedaction = false,
                            rejectNonCompliantNodes = true,
                            allowedStorageClasses = mutableListOf("shared-storage", "sender-only")
                        )
                        envelope.routingPolicy.maxHopCount = 12
                        envelope.credentialPolicy.forwardSecrets = true
                        envelope
                    }

                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))

                assertTrue(result.passPipeline)
                assertFalse(capturedTracePersistence)
                assertTrue(capturedRequireRedaction)
                assertEquals(mutableListOf("shared-storage"), capturedAllowedStorageClasses)
                assertEquals(3, capturedMaxHopCount)
                assertFalse(capturedForwardSecrets)
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that inbound route hooks cannot widen the negotiated policy after the grid has clamped it.
     */
    @Test
    fun inboundRouteHooksCannotWidenNegotiatedPolicyAfterClamp()
    {
        runBlocking {
            var capturedTracePersistence = true
            var capturedRequireRedaction = false
            var capturedAllowedStorageClasses = mutableListOf<String>()
            var capturedMaxHopCount = 0
            var capturedForwardSecrets = true

            val remoteGrid = DistributionGrid()
            remoteGrid.setP2pTransport(
                P2PTransport(
                    transportMethod = Transport.Tpipe,
                    transportAddress = "policy-clamp-remote-grid-address"
                )
            )
            remoteGrid.setP2pDescription(
                buildGridDescriptor(
                    nodeId = "policy-clamp-remote-grid",
                    transportAddress = "policy-clamp-remote-grid-address",
                    defaultTracePolicy = DistributionGridTracePolicy(
                        allowTracing = true,
                        allowTracePersistence = false,
                        requireRedaction = true,
                        rejectNonCompliantNodes = true,
                        allowedStorageClasses = mutableListOf("shared-storage")
                    ),
                    defaultRoutingPolicy = DistributionGridRoutingPolicy(maxHopCount = 3)
                )
            )
            remoteGrid.setP2pRequirements(
                P2PRequirements(
                    allowExternalConnections = true,
                    acceptedContent = mutableListOf(SupportedContentTypes.text)
                )
            )
            remoteGrid
                .setRouter(ExecutionInterface("policy-clamp-router") { it })
                .setWorker(ExecutionInterface("policy-clamp-worker") { content ->
                    content.addText("policy-clamp-worker")
                    content
                })
                .setBeforeRouteHook { envelope ->
                    envelope.tracePolicy.allowTracePersistence = true
                    envelope.tracePolicy.allowedStorageClasses = mutableListOf("shared-storage", "hook-only")
                    envelope.routingPolicy.maxHopCount = 12
                    envelope.credentialPolicy.forwardSecrets = true
                    envelope
                }
                .setBeforeLocalWorkerHook { envelope ->
                    capturedTracePersistence = envelope.tracePolicy.allowTracePersistence
                    capturedRequireRedaction = envelope.tracePolicy.requireRedaction
                    capturedAllowedStorageClasses = envelope.tracePolicy.allowedStorageClasses.deepCopy()
                    capturedMaxHopCount = envelope.routingPolicy.maxHopCount
                    capturedForwardSecrets = envelope.credentialPolicy.forwardSecrets
                    envelope
                }
            remoteGrid.init()
            P2PRegistry.register(remoteGrid)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "policy-clamp-sender-grid",
                    transportAddress = "policy-clamp-sender-grid-address",
                    router = ExecutionInterface("sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remoteGrid.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("sender-worker") { it }
                )
                    .setRoutingPolicy(DistributionGridRoutingPolicy(maxHopCount = 12))
                    .setBeforeRouteHook { envelope ->
                        envelope.tracePolicy = DistributionGridTracePolicy(
                            allowTracing = true,
                            allowTracePersistence = true,
                            requireRedaction = false,
                            rejectNonCompliantNodes = true,
                            allowedStorageClasses = mutableListOf("shared-storage", "sender-only")
                        )
                        envelope.routingPolicy.maxHopCount = 12
                        envelope.credentialPolicy.forwardSecrets = true
                        envelope
                    }

                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))

                assertTrue(result.passPipeline)
                assertFalse(capturedTracePersistence)
                assertTrue(capturedRequireRedaction)
                assertEquals(mutableListOf("shared-storage"), capturedAllowedStorageClasses)
                assertEquals(3, capturedMaxHopCount)
                assertFalse(capturedForwardSecrets)
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that explicit peers without grid metadata are rejected before handoff.
     */
    @Test
    fun explicitPeerWithoutGridMetadataIsRejected()
    {
        runBlocking {
            val senderGrid = initializedGrid(
                nodeId = "metadata-sender-grid",
                transportAddress = "metadata-sender-grid-address",
                router = ExecutionInterface("sender-router") { content ->
                    content.metadata["distributionGridDirective"] = DistributionGridDirective(
                        kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                        targetPeerId = "Tpipe::missing-grid-metadata"
                    )
                    content
                },
                worker = ExecutionInterface("sender-worker") { it }
            )
            senderGrid.addPeerDescriptor(
                P2PDescriptor(
                    agentName = "missing-grid-peer",
                    agentDescription = "Remote peer missing grid metadata",
                    transport = P2PTransport(
                        transportMethod = Transport.Tpipe,
                        transportAddress = "missing-grid-metadata"
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
            )
            senderGrid.init()

            val result = senderGrid.execute(MultimodalContent(text = "start"))
            val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

            assertTrue(result.terminatePipeline)
            assertNotNull(failure)
            assertEquals(DistributionGridFailureKind.TRUST_REJECTED, failure.kind)
        }
    }

    /**
     * Verifies that inbound remote task execution stays in single-node mode and rejects nested remote handoff.
     */
    @Test
    fun inboundRemoteTaskRejectsNestedRemoteHandoff()
    {
        runBlocking {
            val remoteGrid = remoteGrid(
                nodeId = "nested-remote-grid",
                transportAddress = "nested-remote-grid-address",
                router = ExecutionInterface("nested-remote-router") { content ->
                    content.metadata["distributionGridDirective"] = DistributionGridDirective(
                        kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                        targetPeerId = "another-peer"
                    )
                    content
                },
                worker = ExecutionInterface("nested-remote-worker") { content ->
                    content.addText("should-not-run")
                    content
                }
            )
                .setBeforeRouteHook { envelope ->
                    envelope.attributes.clear()
                    envelope
                }

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "nested-sender-grid",
                    transportAddress = "nested-sender-grid-address",
                    router = ExecutionInterface("sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remoteGrid.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("sender-worker") { it }
                )
                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(result.terminatePipeline)
                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.ROUTING_FAILURE, failure.kind)
                assertTrue(failure.reason.contains("Nested remote handoff"))
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that peer-dispatch and peer-response hooks run during explicit remote handoff.
     */
    @Test
    fun invokesRemotePeerHooksAroundExplicitHandoff()
    {
        runBlocking {
            val remoteGrid = remoteGrid(
                nodeId = "hook-remote-grid",
                transportAddress = "hook-remote-grid-address",
                router = ExecutionInterface("remote-router") { it },
                worker = ExecutionInterface("remote-worker") { content ->
                    content.addText("remote-worker")
                    content
                }
            )

            try
            {
                val calls = mutableListOf<String>()
                val senderGrid = initializedGrid(
                    nodeId = "hook-sender-grid",
                    transportAddress = "hook-sender-grid-address",
                    router = ExecutionInterface("sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remoteGrid.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("sender-worker") { it }
                )
                    .setBeforePeerDispatchHook { envelope ->
                        calls.add("beforePeerDispatch")
                        envelope.executionNotes.add("beforePeerDispatch")
                        envelope
                    }
                    .setAfterPeerResponseHook { envelope ->
                        calls.add("afterPeerResponse")
                        envelope.executionNotes.add("afterPeerResponse")
                        envelope
                    }
                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val envelope = result.metadata["distributionGridEnvelope"] as? DistributionGridEnvelope

                assertEquals(listOf("beforePeerDispatch", "afterPeerResponse"), calls)
                assertNotNull(envelope)
                assertTrue(envelope.executionNotes.contains("beforePeerDispatch"))
                assertTrue(envelope.executionNotes.contains("afterPeerResponse"))
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that remote success serializes the final post-hook content and notes instead of a stale snapshot.
     */
    @Test
    fun remoteOutcomeHookMutationsAreSerializedInTaskReturn()
    {
        runBlocking {
            val remoteGrid = remoteGrid(
                nodeId = "hook-outcome-remote-grid",
                transportAddress = "hook-outcome-remote-grid-address",
                router = ExecutionInterface("hook-outcome-remote-router") { content ->
                    content.addText("remote-router")
                    content
                },
                worker = ExecutionInterface("hook-outcome-remote-worker") { content ->
                    content.addText("remote-worker")
                    content
                }
            )
                .setOutcomeTransformationHook { content, envelope ->
                    envelope.executionNotes.add("remote outcome hook")
                    content.addText("remote-outcome-hook")
                    content.metadata["remoteOutcomeHook"] = "applied"
                    content
                }

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "hook-outcome-sender-grid",
                    transportAddress = "hook-outcome-sender-grid-address",
                    router = ExecutionInterface("hook-outcome-sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remoteGrid.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("hook-outcome-sender-worker") { it }
                )

                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val envelope = result.metadata["distributionGridEnvelope"] as? DistributionGridEnvelope
                val outcome = result.metadata["distributionGridOutcome"] as? DistributionGridOutcome

                assertTrue(result.passPipeline)
                assertFalse(result.terminatePipeline)
                assertTrue(result.text.contains("remote-outcome-hook"))
                assertNotNull(envelope)
                assertTrue(envelope.executionNotes.contains("remote outcome hook"))
                assertNotNull(outcome)
                assertTrue(outcome!!.completionNotes.contains("remote outcome hook"))
                assertTrue(outcome.finalContent.text.contains("remote-outcome-hook"))
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that remote failure serializes the final post-hook content and notes instead of a stale snapshot.
     */
    @Test
    fun remoteFailureHookMutationsAreSerializedInTaskFailure()
    {
        runBlocking {
            val remoteGrid = remoteGrid(
                nodeId = "hook-failure-remote-grid",
                transportAddress = "hook-failure-remote-grid-address",
                router = ExecutionInterface("hook-failure-remote-router") { content ->
                    content.addText("remote-router")
                    content
                },
                worker = ExecutionInterface("hook-failure-remote-worker") { content ->
                    content.addText("remote-worker")
                    content.terminatePipeline = true
                    content.pipeError = PipeError(
                        exception = IllegalStateException("remote worker failure"),
                        eventType = TraceEventType.DISTRIBUTION_GRID_FAILURE,
                        phase = TracePhase.CLEANUP,
                        pipeName = "remote-worker",
                        pipeId = "hook-failure-remote-worker"
                    )
                    content
                }
            )
                .setBeforeRouteHook { envelope ->
                    envelope.attributes["remote-failure-attribute"] = "remote-failure"
                    envelope
                }
                .setFailureHook { envelope ->
                    envelope.executionNotes.add("remote failure hook")
                    envelope.content.addText("remote-failure-hook")
                    envelope
                }

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "hook-failure-sender-grid",
                    transportAddress = "hook-failure-sender-grid-address",
                    router = ExecutionInterface("hook-failure-sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remoteGrid.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("hook-failure-sender-worker") { it }
                )

                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val envelope = result.metadata["distributionGridEnvelope"] as? DistributionGridEnvelope
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure
                val outcome = result.metadata["distributionGridOutcome"] as? DistributionGridOutcome

                assertTrue(result.terminatePipeline)
                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.WORKER_FAILURE, failure.kind)
                assertTrue(result.text.contains("remote-failure-hook"))
                assertNotNull(envelope)
                assertEquals("remote-failure", envelope.attributes["remote-failure-attribute"])
                assertTrue(envelope.executionNotes.contains("remote failure hook"))
                assertNotNull(outcome)
                assertTrue(outcome!!.completionNotes.contains("remote failure hook"))
                assertTrue(outcome.finalContent.text.contains("remote-failure-hook"))
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that attached local peers are not treated as Phase 5 remote targets.
     */
    @Test
    fun attachedLocalPeersAreNotValidRemoteTargets()
    {
        runBlocking {
            val localPeer = ExecutionInterface("local-peer") { content ->
                content.addText("local-peer")
                content
            }
            val senderGrid = initializedGrid(
                nodeId = "local-peer-sender-grid",
                transportAddress = "local-peer-sender-grid-address",
                router = ExecutionInterface("sender-router") { content ->
                    content.metadata["distributionGridDirective"] = DistributionGridDirective(
                        kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                        targetPeerId = senderGridPeerKey(localPeer)
                    )
                    content
                },
                worker = ExecutionInterface("sender-worker") { it }
            )
            senderGrid.addPeer(localPeer)
            senderGrid.init()

            val result = senderGrid.execute(MultimodalContent(text = "start"))
            val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

            assertTrue(result.terminatePipeline)
            assertNotNull(failure)
            assertEquals(DistributionGridFailureKind.ROUTING_FAILURE, failure.kind)
        }
    }

    /**
     * Verifies that an uninitialized remote grid rejects an inbound handshake at the P2P boundary.
     */
    @Test
    fun uninitializedRemoteGridRejectsHandshakeAtTheP2pBoundary()
    {
        runBlocking {
            val remoteGrid = DistributionGrid()
            remoteGrid.setP2pTransport(
                P2PTransport(
                    transportMethod = Transport.Tpipe,
                    transportAddress = "uninitialized-remote-grid-address"
                )
            )
            remoteGrid.setP2pDescription(
                buildGridDescriptor(
                    nodeId = "uninitialized-remote-grid",
                    transportAddress = "uninitialized-remote-grid-address"
                )
            )
            remoteGrid.setP2pRequirements(
                P2PRequirements(
                    allowExternalConnections = true,
                    acceptedContent = mutableListOf(SupportedContentTypes.text)
                )
            )

            P2PRegistry.register(remoteGrid)

            try
            {
                val request = P2PRequest(
                    transport = remoteGrid.getP2pTransport()!!.deepCopy(),
                    prompt = MultimodalContent(
                        text = buildGridRpcPrompt(
                            DistributionGridRpcMessage(
                                messageType = DistributionGridRpcMessageType.HANDSHAKE_INIT,
                                senderNodeId = "sender",
                                targetId = "uninitialized-remote-grid",
                                payloadType = "DistributionGridHandshakeRequest",
                                payloadJson = serialize(DistributionGridHandshakeRequest(requesterNodeId = "sender"))
                            )
                        )
                    )
                )

                val response = remoteGrid.executeP2PRequest(request)

                assertNotNull(response)
                assertNull(response.output)
                assertNotNull(response.rejection)
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that a widened handshake acknowledgment is rejected before remote task handoff begins.
     */
    @Test
    fun widenedHandshakeAckIsRejectedBeforeTaskHandoff()
    {
        runBlocking {
            val wideningPeer = WideningHandshakePeer(
                nodeId = "widening-peer",
                transportAddress = "widening-peer-address"
            )

            P2PRegistry.register(wideningPeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "widening-sender-grid",
                    transportAddress = "widening-sender-address",
                    router = ExecutionInterface("widening-sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = wideningPeer.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("widening-sender-worker") { it }
                )
                    .setBeforeRouteHook { envelope ->
                        envelope.tracePolicy.allowTracePersistence = false
                        envelope.routingPolicy.maxHopCount = 2
                        envelope.credentialPolicy.forwardSecrets = false
                        envelope
                    }

                senderGrid.addPeerDescriptor(wideningPeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(result.terminatePipeline)
                assertNotNull(failure)
                assertEquals(1, wideningPeer.handshakeCount)
                assertEquals(0, wideningPeer.taskHandoffCount)
            }

            finally
            {
                P2PRegistry.remove(wideningPeer)
            }
        }
    }

    /**
     * Verifies that a handshake acknowledgment with a mismatched session reference is rejected before caching.
     */
    @Test
    fun handshakeAckWithMismatchedSessionReferenceIsRejected()
    {
        runBlocking {
            val mismatchedPeer = MismatchedSessionHandshakePeer(
                nodeId = "mismatched-peer",
                transportAddress = "mismatched-peer-address"
            )

            P2PRegistry.register(mismatchedPeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "mismatched-sender-grid",
                    transportAddress = "mismatched-sender-address",
                    router = ExecutionInterface("mismatched-sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = mismatchedPeer.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("mismatched-sender-worker") { it }
                )

                senderGrid.addPeerDescriptor(mismatchedPeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(result.terminatePipeline)
                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.HANDSHAKE_REJECTED, failure.kind)
                assertEquals(1, mismatchedPeer.handshakeCount)
                assertEquals(0, mismatchedPeer.taskHandoffCount)
            }

            finally
            {
                P2PRegistry.remove(mismatchedPeer)
            }
        }
    }

    /**
     * Verifies that a malformed remote task reply cannot be merged into the current session.
     */
    @Test
    fun mismatchedTaskReplySessionReferenceIsRejectedBeforeMerge()
    {
        runBlocking {
            val replyPeer = MismatchedTaskReplyPeer(
                nodeId = "reply-peer",
                transportAddress = "reply-peer-address"
            )

            P2PRegistry.register(replyPeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "reply-sender-grid",
                    transportAddress = "reply-sender-address",
                    router = ExecutionInterface("reply-sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = replyPeer.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("reply-sender-worker") { it }
                )

                senderGrid.addPeerDescriptor(replyPeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(result.terminatePipeline)
                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.SESSION_REJECTED, failure.kind)
                assertEquals(1, replyPeer.handshakeCount)
                assertEquals(1, replyPeer.taskHandoffCount)
            }

            finally
            {
                P2PRegistry.remove(replyPeer)
            }
        }
    }

    /**
     * Verifies that inbound task handoff rejects a tampered wrapper even when the session id still looks valid.
     */
    @Test
    fun inboundTaskHandoffRejectsTamperedSessionWrapper()
    {
        runBlocking {
            val delegateGrid = initializedGrid(
                nodeId = "tampered-wrapper-remote-grid",
                transportAddress = "tampered-wrapper-remote-address",
                router = ExecutionInterface("tampered-wrapper-remote-router") { it },
                worker = ExecutionInterface("tampered-wrapper-remote-worker") { content ->
                    content.addText("tampered-wrapper-remote-worker")
                    content
                }
            )
            val tamperingPeer = InboundWrapperTamperingPeer(delegateGrid)

            P2PRegistry.register(tamperingPeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "tampered-wrapper-sender-grid",
                    transportAddress = "tampered-wrapper-sender-address",
                    router = ExecutionInterface("tampered-wrapper-sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = tamperingPeer.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("tampered-wrapper-sender-worker") { it }
                )

                senderGrid.addPeerDescriptor(tamperingPeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(result.terminatePipeline)
                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.SESSION_REJECTED, failure.kind)
                assertEquals(1, tamperingPeer.handshakeCount)
                assertEquals(1, tamperingPeer.taskHandoffCount)
            }

            finally
            {
                P2PRegistry.remove(tamperingPeer)
            }
        }
    }

    /**
     * Verifies that handshake acknowledgments cannot introduce credential refs the caller did not request.
     */
    @Test
    fun handshakeRejectsUnrequestedCredentialRefs()
    {
        runBlocking {
            val wideningPeer = CredentialRefsWideningHandshakePeer(
                nodeId = "credential-refs-peer",
                transportAddress = "credential-refs-peer-address"
            )

            P2PRegistry.register(wideningPeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "credential-refs-sender-grid",
                    transportAddress = "credential-refs-sender-address",
                    router = ExecutionInterface("credential-refs-sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = wideningPeer.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("credential-refs-sender-worker") { it }
                )

                senderGrid.addPeerDescriptor(wideningPeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(result.terminatePipeline)
                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.HANDSHAKE_REJECTED, failure.kind)
                assertEquals(1, wideningPeer.handshakeCount)
                assertEquals(0, wideningPeer.taskHandoffCount)
            }

            finally
            {
                P2PRegistry.remove(wideningPeer)
            }
        }
    }

    /**
     * Verifies that a peer-provided handshake rejection reason is preserved instead of being flattened.
     */
    @Test
    fun handshakeSessionRejectPreservesPeerFailureDetails()
    {
        runBlocking {
            val rejectingPeer = RejectingHandshakePeer(
                nodeId = "rejecting-peer",
                transportAddress = "rejecting-peer-address",
                failureReason = "peer-policy-mismatch",
                failureKind = DistributionGridFailureKind.POLICY_REJECTED
            )

            P2PRegistry.register(rejectingPeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "rejecting-sender-grid",
                    transportAddress = "rejecting-sender-address",
                    router = ExecutionInterface("rejecting-sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = rejectingPeer.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("rejecting-sender-worker") { it }
                )

                senderGrid.addPeerDescriptor(rejectingPeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(result.terminatePipeline)
                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.POLICY_REJECTED, failure.kind)
                assertEquals("peer-policy-mismatch", failure.reason)
                assertFalse(failure.retryable)
            }

            finally
            {
                P2PRegistry.remove(rejectingPeer)
            }
        }
    }

    /**
     * Verifies that semantically equivalent storage-class sets can differ in ordering and still negotiate.
     */
    @Test
    fun handshakeAcceptsEquivalentStorageClassOrdering()
    {
        runBlocking {
            val orderingPeer = StorageClassOrderingHandshakePeer(
                nodeId = "ordering-peer",
                transportAddress = "ordering-peer-address"
            )

            P2PRegistry.register(orderingPeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "ordering-sender-grid",
                    transportAddress = "ordering-sender-address",
                    router = ExecutionInterface("ordering-sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = orderingPeer.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("ordering-sender-worker") { it }
                )
                    .setBeforeRouteHook { envelope ->
                        envelope.tracePolicy.allowedStorageClasses = mutableListOf("alpha", "beta")
                        envelope
                    }

                senderGrid.addPeerDescriptor(orderingPeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))

                assertTrue(result.passPipeline)
                assertFalse(result.terminatePipeline)
                assertEquals(1, orderingPeer.handshakeCount)
                assertEquals(1, orderingPeer.taskHandoffCount)
            }

            finally
            {
                P2PRegistry.remove(orderingPeer)
            }
        }
    }

    /**
     * Verifies that short handshake lifetimes are preserved exactly instead of being widened to the old 60-second floor.
     */
    @Test
    fun handshakePreservesExactRequestedSessionLifetime()
    {
        runBlocking {
            val remoteGrid = remoteGrid(
                nodeId = "short-session-peer",
                transportAddress = "short-session-peer-address",
                router = ExecutionInterface("short-session-router") { it },
                worker = ExecutionInterface("short-session-worker") { it },
                registryMemberships = mutableListOf("short-session-registry")
            )

            try
            {
                val startedAt = System.currentTimeMillis()
                val handshakeRequest = DistributionGridHandshakeRequest(
                    requesterNodeId = "short-session-requester",
                    requesterMetadata = DistributionGridNodeMetadata(
                        nodeId = "short-session-requester",
                        supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
                        roleCapabilities = mutableListOf("Router"),
                        supportedTransports = mutableListOf(Transport.Tpipe),
                        requiresHandshake = true,
                        defaultTracePolicy = DistributionGridTracePolicy(),
                        defaultRoutingPolicy = DistributionGridRoutingPolicy(),
                        actsAsRegistry = false
                    ),
                    supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
                    acceptedRoutingPolicy = DistributionGridRoutingPolicy(),
                    tracePolicy = DistributionGridTracePolicy(),
                    credentialPolicy = DistributionGridCredentialPolicy(),
                    acceptedTransports = mutableListOf(Transport.Tpipe),
                    requestedSessionDurationSeconds = 5
                )

                val response = remoteGrid.executeP2PRequest(
                    P2PRequest(
                        transport = remoteGrid.getP2pTransport()!!.deepCopy(),
                        returnAddress = P2PTransport(
                            transportMethod = Transport.Tpipe,
                            transportAddress = "short-session-sender-address"
                        ),
                        prompt = MultimodalContent(
                            text = buildGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.HANDSHAKE_INIT,
                                    senderNodeId = "short-session-sender",
                                    targetId = remoteGrid.getP2pDescription()!!.distributionGridMetadata!!.nodeId,
                                    payloadType = "DistributionGridHandshakeRequest",
                                    payloadJson = serialize(handshakeRequest)
                                )
                            )
                        )
                    )
                )

                val rpcMessage = deserialize<DistributionGridRpcMessage>(
                    response!!.output!!.text.substringAfter('\n'),
                    useRepair = false
                )
                assertNotNull(rpcMessage)
                assertEquals(DistributionGridRpcMessageType.HANDSHAKE_ACK, rpcMessage.messageType)
                val handshakeResponse = deserialize<DistributionGridHandshakeResponse>(
                    rpcMessage.payloadJson,
                    useRepair = false
                )
                assertNotNull(handshakeResponse)
                val sessionRecord = handshakeResponse.sessionRecord
                assertNotNull(sessionRecord)
                assertTrue(sessionRecord.registryId.isBlank())
                assertTrue(sessionRecord.expiresAtEpochMillis <= startedAt + 6_000L)
                assertTrue(sessionRecord.expiresAtEpochMillis >= startedAt + 4_000L)
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that explicit-peer sessions are scoped to the current registry context instead of being reused across trust domains.
     */
    @Test
    fun explicitPeerSessionsAreScopedToTheCurrentRegistryContext()
    {
        runBlocking {
            val remoteGrid = remoteGrid(
                nodeId = "registry-scoped-peer",
                transportAddress = "registry-scoped-peer-address",
                router = ExecutionInterface("registry-scoped-router") { it },
                worker = ExecutionInterface("registry-scoped-worker") { it },
                registryMemberships = mutableListOf("registry-a")
            )

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "registry-scoped-sender",
                    transportAddress = "registry-scoped-sender-address",
                    router = ExecutionInterface("registry-scoped-sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remoteGrid.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("registry-scoped-sender-worker") { it },
                    registryMemberships = mutableListOf("registry-a")
                )

                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                assertTrue(result.passPipeline)

                val peerTransport = remoteGrid.getP2pTransport()!!
                val peerKey = "${peerTransport.transportMethod}::${peerTransport.transportAddress}"
                val matchingEnvelope = DistributionGridEnvelope(
                    tracePolicy = DistributionGridTracePolicy(),
                    routingPolicy = DistributionGridRoutingPolicy(),
                    credentialPolicy = DistributionGridCredentialPolicy()
                )
                val cachedInRegistryA = invokeResolveValidCachedSession(
                    senderGrid,
                    peerKey,
                    "registry-a",
                    remoteGrid.getP2pDescription()!!.deepCopy(),
                    matchingEnvelope.deepCopy()
                )
                val cachedInRegistryB = invokeResolveValidCachedSession(
                    senderGrid,
                    peerKey,
                    "registry-b",
                    remoteGrid.getP2pDescription()!!.deepCopy(),
                    matchingEnvelope.deepCopy()
                )

                assertNotNull(cachedInRegistryA)
                assertEquals(serialize(listOf("registry-a")), cachedInRegistryA!!.registryId)
                assertNull(cachedInRegistryB)
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that a blank requester registry scope is cached as blank instead of being rebound to the responder's scope.
     */
    @Test
    fun explicitPeerSessionsWithBlankRequesterRegistryScopeStayBlank()
    {
        runBlocking {
            val remoteGrid = remoteGrid(
                nodeId = "blank-registry-peer",
                transportAddress = "blank-registry-peer-address",
                router = ExecutionInterface("blank-registry-router") { it },
                worker = ExecutionInterface("blank-registry-worker") { it },
                registryMemberships = mutableListOf("registry-a")
            )

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "blank-registry-sender",
                    transportAddress = "blank-registry-sender-address",
                    router = ExecutionInterface("blank-registry-sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remoteGrid.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("blank-registry-sender-worker") { it }
                )

                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                assertTrue(result.passPipeline)

                val peerTransport = remoteGrid.getP2pTransport()!!
                val peerKey = "${peerTransport.transportMethod}::${peerTransport.transportAddress}"
                val matchingEnvelope = DistributionGridEnvelope(
                    tracePolicy = DistributionGridTracePolicy(),
                    routingPolicy = DistributionGridRoutingPolicy(),
                    credentialPolicy = DistributionGridCredentialPolicy()
                )
                val cachedWithBlankScope = invokeResolveValidCachedSession(
                    senderGrid,
                    peerKey,
                    "",
                    remoteGrid.getP2pDescription()!!.deepCopy(),
                    matchingEnvelope.deepCopy()
                )
                val cachedWithRemoteScope = invokeResolveValidCachedSession(
                    senderGrid,
                    peerKey,
                    "registry-a",
                    remoteGrid.getP2pDescription()!!.deepCopy(),
                    matchingEnvelope.deepCopy()
                )

                assertNotNull(cachedWithBlankScope)
                assertTrue(cachedWithBlankScope!!.registryId.isBlank())
                assertNull(cachedWithRemoteScope)
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that handshake acknowledgements with a mismatched registry scope are rejected before they can poison the cache.
     */
    @Test
    fun handshakeAckWithMismatchedRegistryScopeIsRejectedBeforeCaching()
    {
        runBlocking {
            val peerTransport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = "mismatched-registry-peer-address"
            )
            val peerDescriptor = buildStaticGridDescriptor(
                nodeId = "mismatched-registry-peer",
                transportAddress = peerTransport.transportAddress
            )
            val peerRequirements = P2PRequirements(
                allowExternalConnections = true,
                acceptedContent = mutableListOf(SupportedContentTypes.text)
            )

            val fakePeer = object : P2PInterface
            {
                override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
                {
                    val rpcMessage = deserialize<DistributionGridRpcMessage>(
                        request.prompt.text.substringAfter('\n'),
                        useRepair = false
                    ) ?: return P2PResponse().apply {
                        rejection = P2PRejection(
                            P2PError.transport,
                            "Failed to deserialize grid RPC request."
                        )
                    }

                    if(rpcMessage.messageType != DistributionGridRpcMessageType.HANDSHAKE_INIT)
                    {
                        return P2PResponse().apply {
                            rejection = P2PRejection(
                                P2PError.configuration,
                                "Unsupported RPC '${rpcMessage.messageType.name}'."
                            )
                        }
                    }

                    val handshakeRequest = deserialize<DistributionGridHandshakeRequest>(
                        rpcMessage.payloadJson,
                        useRepair = false
                    ) ?: return P2PResponse().apply {
                        rejection = P2PRejection(
                            P2PError.transport,
                            "Failed to deserialize handshake request."
                        )
                    }

                    val sessionRecord = DistributionGridSessionRecord(
                        sessionId = "mismatched-registry-session",
                        requesterNodeId = handshakeRequest.requesterNodeId,
                        responderNodeId = peerDescriptor.distributionGridMetadata!!.nodeId,
                        registryId = "registry-b",
                        negotiatedProtocolVersion = handshakeRequest.supportedProtocolVersions.first().deepCopy(),
                        negotiatedPolicy = DistributionGridNegotiatedPolicy(
                            tracePolicy = handshakeRequest.tracePolicy.deepCopy().apply {
                                allowedStorageClasses = handshakeRequest.tracePolicy.allowedStorageClasses.deepCopy()
                            },
                            routingPolicy = handshakeRequest.acceptedRoutingPolicy.deepCopy(),
                            credentialPolicy = handshakeRequest.credentialPolicy.deepCopy(),
                            storageClasses = handshakeRequest.tracePolicy.allowedStorageClasses.deepCopy()
                        ),
                        expiresAtEpochMillis = System.currentTimeMillis() + 60_000L
                    )
                    val responsePayload = DistributionGridHandshakeResponse(
                        accepted = true,
                        negotiatedProtocolVersion = sessionRecord.negotiatedProtocolVersion.deepCopy(),
                        negotiatedPolicy = sessionRecord.negotiatedPolicy.deepCopy(),
                        rejectionReason = "",
                        sessionRecord = sessionRecord.deepCopy()
                    )

                    return P2PResponse(
                        output = MultimodalContent(
                            text = buildStaticGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.HANDSHAKE_ACK,
                                    senderNodeId = peerDescriptor.distributionGridMetadata!!.nodeId,
                                    targetId = handshakeRequest.requesterNodeId,
                                    protocolVersion = sessionRecord.negotiatedProtocolVersion.deepCopy(),
                                    sessionRef = DistributionGridSessionRef(
                                        sessionId = sessionRecord.sessionId,
                                        requesterNodeId = sessionRecord.requesterNodeId,
                                        responderNodeId = sessionRecord.responderNodeId,
                                        registryId = sessionRecord.registryId,
                                        expiresAtEpochMillis = sessionRecord.expiresAtEpochMillis
                                    ),
                                    payloadType = "DistributionGridHandshakeResponse",
                                    payloadJson = serialize(responsePayload)
                                )
                            )
                        )
                    )
                }
            }

            P2PRegistry.register(fakePeer, peerTransport, peerDescriptor, peerRequirements)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "mismatched-registry-sender",
                    transportAddress = "mismatched-registry-sender-address",
                    router = ExecutionInterface("mismatched-registry-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = "${peerTransport.transportMethod}::${peerTransport.transportAddress}"
                        )
                        content
                    },
                    worker = ExecutionInterface("mismatched-registry-worker") { it },
                    registryMemberships = mutableListOf("registry-a")
                )

                senderGrid.addPeerDescriptor(peerDescriptor.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))

                assertTrue(result.terminatePipeline)
                assertEquals(0, readSessionRecordCount(senderGrid))
            }

            finally
            {
                P2PRegistry.remove(peerTransport)
            }
        }
    }

    /**
     * Verifies that peer-session cache keys remain distinct even when peer and registry strings contain separator characters.
     */
    @Test
    fun peerSessionCacheKeysDoNotAliasWhenValuesContainSeparators()
    {
        val grid = DistributionGrid()
        val sessionA = DistributionGridSessionRecord(
            sessionId = "collision-session-a",
            requesterNodeId = "collision-requester",
            responderNodeId = "collision-responder",
            registryId = "baz",
            negotiatedProtocolVersion = DistributionGridProtocolVersion(),
            negotiatedPolicy = DistributionGridNegotiatedPolicy(),
            expiresAtEpochMillis = System.currentTimeMillis() + 60_000L
        )
        val sessionB = DistributionGridSessionRecord(
            sessionId = "collision-session-b",
            requesterNodeId = "collision-requester",
            responderNodeId = "collision-responder",
            registryId = "bar::baz",
            negotiatedProtocolVersion = DistributionGridProtocolVersion(),
            negotiatedPolicy = DistributionGridNegotiatedPolicy(),
            expiresAtEpochMillis = System.currentTimeMillis() + 60_000L
        )

        invokeCacheSession(grid, sessionA, "foo::bar")
        invokeCacheSession(grid, sessionB, "foo")

        assertEquals(2, readPeerSessionRecordCount(grid))
    }

    /**
     * Verifies that remote failure RPCs use the negotiated protocol version instead of the node's first supported version.
     */
    @Test
    fun remoteFailureRpcUsesTheNegotiatedProtocolVersion()
    {
        runBlocking {
            val supportedVersions = mutableListOf(
                DistributionGridProtocolVersion(1, 0, 0),
                DistributionGridProtocolVersion(2, 0, 0)
            )
            val remoteGrid = remoteGrid(
                nodeId = "versioned-failure-peer",
                transportAddress = "versioned-failure-peer-address",
                router = ExecutionInterface("versioned-failure-router") { content -> content },
                worker = ExecutionInterface("versioned-failure-worker") { content ->
                    content.terminatePipeline = true
                    content.pipeError = PipeError(
                        exception = IllegalStateException("remote worker failure"),
                        eventType = TraceEventType.DISTRIBUTION_GRID_FAILURE,
                        phase = TracePhase.CLEANUP,
                        pipeName = "versioned-failure-worker",
                        pipeId = "versioned-failure-peer"
                    )
                    content
                },
                supportedProtocolVersions = supportedVersions
            )

            try
            {
                val handshakeRequest = DistributionGridHandshakeRequest(
                    requesterNodeId = "versioned-failure-requester",
                    requesterMetadata = DistributionGridNodeMetadata(
                        nodeId = "versioned-failure-requester",
                        supportedProtocolVersions = supportedVersions.deepCopy(),
                        roleCapabilities = mutableListOf("Router"),
                        supportedTransports = mutableListOf(Transport.Tpipe),
                        requiresHandshake = true,
                        defaultTracePolicy = DistributionGridTracePolicy(),
                        defaultRoutingPolicy = DistributionGridRoutingPolicy(),
                        actsAsRegistry = false
                    ),
                    supportedProtocolVersions = supportedVersions.deepCopy(),
                    acceptedRoutingPolicy = DistributionGridRoutingPolicy(),
                    tracePolicy = DistributionGridTracePolicy(),
                    credentialPolicy = DistributionGridCredentialPolicy(),
                    acceptedTransports = mutableListOf(Transport.Tpipe),
                    requestedSessionDurationSeconds = 600
                )

                val handshakeResponse = remoteGrid.executeP2PRequest(
                    P2PRequest(
                        transport = remoteGrid.getP2pTransport()!!.deepCopy(),
                        returnAddress = P2PTransport(
                            transportMethod = Transport.Tpipe,
                            transportAddress = "versioned-failure-requester-address"
                        ),
                        prompt = MultimodalContent(
                            text = buildGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.HANDSHAKE_INIT,
                                    senderNodeId = "versioned-failure-sender",
                                    targetId = remoteGrid.getP2pDescription()!!.distributionGridMetadata!!.nodeId,
                                    protocolVersion = supportedVersions.first(),
                                    payloadType = "DistributionGridHandshakeRequest",
                                    payloadJson = serialize(handshakeRequest)
                                )
                            )
                        )
                    )
                )

                val handshakeRpc = deserialize<DistributionGridRpcMessage>(
                    handshakeResponse!!.output!!.text.substringAfter('\n'),
                    useRepair = false
                )
                assertNotNull(handshakeRpc)
                val handshakePayload = deserialize<DistributionGridHandshakeResponse>(
                    handshakeRpc.payloadJson,
                    useRepair = false
                )
                assertNotNull(handshakePayload)
                val sessionRecord = handshakePayload.sessionRecord
                assertNotNull(sessionRecord)
                assertEquals(supportedVersions[1], sessionRecord.negotiatedProtocolVersion)

                val taskEnvelope = DistributionGridEnvelope(
                    taskId = "versioned-failure-task",
                    originNodeId = "versioned-failure-requester",
                    originTransport = P2PTransport(
                        transportMethod = Transport.Tpipe,
                        transportAddress = "versioned-failure-requester-address"
                    ),
                    senderNodeId = "versioned-failure-requester",
                    senderTransport = P2PTransport(
                        transportMethod = Transport.Tpipe,
                        transportAddress = "versioned-failure-requester-address"
                    ),
                    currentNodeId = "versioned-failure-requester",
                    currentTransport = P2PTransport(
                        transportMethod = Transport.Tpipe,
                        transportAddress = "versioned-failure-requester-address"
                    ),
                    content = MultimodalContent(text = "task"),
                    sessionRef = DistributionGridSessionRef(
                        sessionId = sessionRecord.sessionId,
                        requesterNodeId = sessionRecord.requesterNodeId,
                        responderNodeId = sessionRecord.responderNodeId,
                        registryId = sessionRecord.registryId,
                        expiresAtEpochMillis = sessionRecord.expiresAtEpochMillis
                    )
                )

                val failureResponse = remoteGrid.executeP2PRequest(
                    P2PRequest(
                        transport = remoteGrid.getP2pTransport()!!.deepCopy(),
                        returnAddress = P2PTransport(
                            transportMethod = Transport.Tpipe,
                            transportAddress = "versioned-failure-requester-address"
                        ),
                        prompt = MultimodalContent(
                            text = buildGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.TASK_HANDOFF,
                                    senderNodeId = "versioned-failure-requester",
                                    targetId = remoteGrid.getP2pDescription()!!.distributionGridMetadata!!.nodeId,
                                    protocolVersion = sessionRecord.negotiatedProtocolVersion.deepCopy(),
                                    sessionRef = DistributionGridSessionRef(
                                        sessionId = sessionRecord.sessionId,
                                        requesterNodeId = sessionRecord.requesterNodeId,
                                        responderNodeId = sessionRecord.responderNodeId,
                                        registryId = sessionRecord.registryId,
                                        expiresAtEpochMillis = sessionRecord.expiresAtEpochMillis
                                    ),
                                    payloadType = "DistributionGridEnvelope",
                                    payloadJson = serialize(taskEnvelope)
                                )
                            )
                        )
                    )
                )

                val failureRpc = deserialize<DistributionGridRpcMessage>(
                    failureResponse!!.output!!.text.substringAfter('\n'),
                    useRepair = false
                )
                assertNotNull(failureRpc)
                assertEquals(DistributionGridRpcMessageType.TASK_FAILURE, failureRpc.messageType)
                assertEquals(sessionRecord.negotiatedProtocolVersion, failureRpc.protocolVersion)
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that the private handshake-window helper uses the exact requested duration instead of a widened floor.
     */
    @Test
    fun handshakeWindowValidationUsesTheExactRequestedDuration()
    {
        val grid = DistributionGrid()
        val handshakeRequest = DistributionGridHandshakeRequest(
            requesterNodeId = "exact-duration-requester",
            requestedSessionDurationSeconds = 5
        )
        val now = System.currentTimeMillis()
        val sessionRecord = DistributionGridSessionRecord(
            sessionId = "exact-duration-session",
            requesterNodeId = "exact-duration-requester",
            responderNodeId = "exact-duration-responder",
            registryId = "",
            negotiatedProtocolVersion = DistributionGridProtocolVersion(),
            negotiatedPolicy = DistributionGridNegotiatedPolicy(),
            expiresAtEpochMillis = now + 5_000L,
            invalidated = false,
            invalidationReason = ""
        )
        val withinWindow = invokeHandshakeWindowValidation(grid, sessionRecord, handshakeRequest)
        val outsideWindow = invokeHandshakeWindowValidation(
            grid,
            sessionRecord.copy(expiresAtEpochMillis = now + 6_000L),
            handshakeRequest
        )

        assertTrue(withinWindow)
        assertFalse(outsideWindow)
    }

    /**
     * Verifies that peer-dispatch hooks run before privacy compatibility checks so they can influence peer selection.
     */
    @Test
    fun beforePeerDispatchHookCanInfluencePeerCompatibility()
    {
        runBlocking {
            val remoteGrid = remoteGrid(
                nodeId = "hook-compatibility-peer",
                transportAddress = "hook-compatibility-peer-address",
                router = ExecutionInterface("hook-compatibility-router") { it },
                worker = ExecutionInterface("hook-compatibility-worker") { it },
                recordsPromptContent = true,
                registryMemberships = mutableListOf("registry-a")
            )

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "hook-compatibility-sender",
                    transportAddress = "hook-compatibility-sender-address",
                    router = ExecutionInterface("hook-compatibility-sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remoteGrid.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("hook-compatibility-sender-worker") { it }
                )
                    .setBeforePeerDispatchHook { envelope ->
                        envelope.tracePolicy.rejectNonCompliantNodes = true
                        envelope.tracePolicy.allowTracing = false
                        envelope
                    }

                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(result.terminatePipeline)
                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.POLICY_REJECTED, failure.kind)
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that stdio-targeted outbound RPC requests keep their PCP session mode instead of being stripped.
     */
    @Test
    fun stdioOutboundRpcPreservesPcpRequestSessionMode()
    {
        runBlocking {
            val grid = DistributionGrid()
            grid.setP2pTransport(
                P2PTransport(
                    transportMethod = Transport.Stdio,
                    transportAddress = "stdio-rpc-cmd"
                )
            )
            grid.setP2pDescription(
                buildGridDescriptor(
                    nodeId = "stdio-grid",
                    transportAddress = "stdio-rpc-cmd"
                ).apply {
                    requestTemplate = P2PRequest(
                        pcpRequest = PcPRequest(
                            stdioContextOptions = StdioContextOptions().apply {
                                executionMode = StdioExecutionMode.INTERACTIVE
                                sessionId = "stdio-session-id"
                                command = "stdio-rpc-cmd"
                            }
                        )
                    )
                }
            )
            grid.setP2pRequirements(
                P2PRequirements(
                    allowExternalConnections = true,
                    acceptedContent = mutableListOf(SupportedContentTypes.text)
                )
            )
            grid.setRouter(ExecutionInterface("stdio-router") { it })
            grid.setWorker(ExecutionInterface("stdio-worker") { it })
            grid.init()

            val request = invokeBuildGridRpcRequest(
                grid = grid,
                descriptor = grid.getP2pDescription()!!,
                rpcMessage = DistributionGridRpcMessage(
                    messageType = DistributionGridRpcMessageType.TASK_HANDOFF,
                    senderNodeId = "stdio-sender",
                    targetId = "stdio-grid",
                    payloadType = "DistributionGridEnvelope",
                    payloadJson = serialize(DistributionGridEnvelope())
                )
            )

            assertNotNull(request.pcpRequest)
            assertEquals(StdioExecutionMode.INTERACTIVE, request.pcpRequest!!.stdioContextOptions.executionMode)
            assertEquals("stdio-session-id", request.pcpRequest!!.stdioContextOptions.sessionId)
        }
    }

    /**
     * Verifies that inbound task-handoff protocol failures preserve the active session reference in the failure RPC.
     */
    @Test
    fun taskHandoffProtocolFailuresPreserveSessionReference()
    {
        runBlocking {
            val remoteGrid = remoteGrid(
                nodeId = "failure-rpc-remote-grid",
                transportAddress = "failure-rpc-remote-address",
                router = ExecutionInterface("failure-rpc-router") { it },
                worker = ExecutionInterface("failure-rpc-worker") { it }
            )

            try
            {
                val handshakeRequest = DistributionGridHandshakeRequest(
                    requesterNodeId = "failure-rpc-sender",
                    requesterMetadata = DistributionGridNodeMetadata(
                        nodeId = "failure-rpc-sender",
                        supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
                        roleCapabilities = mutableListOf("Router"),
                        supportedTransports = mutableListOf(Transport.Tpipe),
                        requiresHandshake = true,
                        defaultTracePolicy = DistributionGridTracePolicy(),
                        defaultRoutingPolicy = DistributionGridRoutingPolicy(),
                        actsAsRegistry = false
                    ),
                    supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
                    acceptedRoutingPolicy = DistributionGridRoutingPolicy(),
                    tracePolicy = DistributionGridTracePolicy(),
                    credentialPolicy = DistributionGridCredentialPolicy(),
                    acceptedTransports = mutableListOf(Transport.Tpipe)
                )

                val handshakeResponse = remoteGrid.executeP2PRequest(
                    P2PRequest(
                        transport = remoteGrid.getP2pTransport()!!.deepCopy(),
                        returnAddress = P2PTransport(
                            transportMethod = Transport.Tpipe,
                            transportAddress = "failure-rpc-sender-address"
                        ),
                        prompt = MultimodalContent(
                            text = buildGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.HANDSHAKE_INIT,
                                    senderNodeId = "failure-rpc-sender",
                                    targetId = remoteGrid.getP2pDescription()!!.distributionGridMetadata!!.nodeId,
                                    payloadType = "DistributionGridHandshakeRequest",
                                    payloadJson = serialize(handshakeRequest)
                                )
                            )
                        )
                    )
                )

                val handshakeRpc = deserialize<DistributionGridRpcMessage>(
                    handshakeResponse!!.output!!.text.substringAfter('\n'),
                    useRepair = false
                )
                assertNotNull(handshakeRpc)
                val sessionRef = handshakeRpc.sessionRef
                assertNotNull(sessionRef)

                val failureResponse = remoteGrid.executeP2PRequest(
                    P2PRequest(
                        transport = remoteGrid.getP2pTransport()!!.deepCopy(),
                        returnAddress = P2PTransport(
                            transportMethod = Transport.Tpipe,
                            transportAddress = "failure-rpc-sender-address"
                        ),
                        prompt = MultimodalContent(
                            text = buildGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.TASK_HANDOFF,
                                    senderNodeId = "failure-rpc-sender",
                                    targetId = remoteGrid.getP2pDescription()!!.distributionGridMetadata!!.nodeId,
                                    protocolVersion = DistributionGridProtocolVersion(),
                                    sessionRef = sessionRef.deepCopy(),
                                    payloadType = "DistributionGridEnvelope",
                                    payloadJson = "{not-valid-envelope-json"
                                )
                            )
                        )
                    )
                )

                val failureRpc = deserialize<DistributionGridRpcMessage>(
                    failureResponse!!.output!!.text.substringAfter('\n'),
                    useRepair = false
                )

                assertNotNull(failureRpc)
                assertEquals(DistributionGridRpcMessageType.TASK_FAILURE, failureRpc.messageType)
                assertEquals(sessionRef, failureRpc.sessionRef)
                val failure = deserialize<DistributionGridFailure>(failureRpc.payloadJson, useRepair = false)
                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.ROUTING_FAILURE, failure.kind)
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that a remote P2P boundary rejection preserves configuration semantics instead of being flattened.
     */
    @Test
    fun taskHandoffP2pBoundaryConfigurationRejectMapsToPolicyFailure()
    {
        runBlocking {
            val rejectingPeer = BoundaryRejectingTaskPeer(
                nodeId = "boundary-rejecting-peer",
                transportAddress = "boundary-rejecting-peer-address",
                rejection = P2PRejection(
                    errorType = P2PError.configuration,
                    reason = "remote-peer-paused"
                )
            )

            P2PRegistry.register(rejectingPeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "boundary-rejecting-sender",
                    transportAddress = "boundary-rejecting-sender-address",
                    router = ExecutionInterface("boundary-rejecting-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = rejectingPeer.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("boundary-rejecting-worker") { it }
                )

                senderGrid.addPeerDescriptor(rejectingPeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(result.terminatePipeline)
                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.POLICY_REJECTED, failure.kind)
                assertEquals("remote-peer-paused", failure.reason)
                assertFalse(failure.retryable)
                assertEquals(rejectingPeer.getP2pTransport()!!.transportMethod, failure.transportMethod)
                assertEquals(rejectingPeer.getP2pTransport()!!.transportAddress, failure.transportAddress)
                assertEquals(0, readSessionRecordCount(senderGrid))
            }

            finally
            {
                P2PRegistry.remove(rejectingPeer)
            }
        }
    }

    /**
     * Verifies that a handshake boundary rejection preserves the peer's configuration semantics.
     */
    @Test
    fun explicitPeerHandshakeBoundaryConfigurationRejectMapsToPolicyFailure()
    {
        runBlocking {
            val rejectingPeer = BoundaryRejectingTaskPeer(
                nodeId = "boundary-handshake-rejecting-peer",
                transportAddress = "boundary-handshake-rejecting-peer-address",
                rejection = P2PRejection(
                    errorType = P2PError.configuration,
                    reason = "remote-peer-paused"
                )
            )

            P2PRegistry.register(rejectingPeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "boundary-handshake-rejecting-sender",
                    transportAddress = "boundary-handshake-rejecting-sender-address",
                    router = ExecutionInterface("boundary-handshake-rejecting-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = rejectingPeer.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("boundary-handshake-rejecting-worker") { it }
                )

                senderGrid.addPeerDescriptor(rejectingPeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(result.terminatePipeline)
                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.POLICY_REJECTED, failure.kind)
                assertEquals("remote-peer-paused", failure.reason)
                assertFalse(failure.retryable)
                assertEquals(rejectingPeer.getP2pTransport()!!.transportMethod, failure.transportMethod)
                assertEquals(rejectingPeer.getP2pTransport()!!.transportAddress, failure.transportAddress)
            }

            finally
            {
                P2PRegistry.remove(rejectingPeer)
            }
        }
    }

    /**
     * Verifies that return-after-local-work can be negotiated against a peer whose default routing policy leaves it disabled.
     */
    @Test
    fun returnAfterFirstLocalWorkCanNegotiateAgainstDefaultPeer()
    {
        runBlocking {
            var capturedReturnAfterFirstLocalWork = false
            val remoteGrid = remoteGrid(
                nodeId = "return-after-local-grid",
                transportAddress = "return-after-local-grid-address",
                router = ExecutionInterface("return-after-local-router") { it },
                worker = ExecutionInterface("return-after-local-worker") { content ->
                    content.addText("return-after-local-worker")
                    content
                }
            )
                .setBeforeRouteHook { envelope ->
                    capturedReturnAfterFirstLocalWork = envelope.routingPolicy.returnAfterFirstLocalWork
                    envelope
                }

            remoteGrid.init()

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "return-after-local-sender-grid",
                    transportAddress = "return-after-local-sender-address",
                    router = ExecutionInterface("return-after-local-sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remoteGrid.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("return-after-local-sender-worker") { it }
                )
                    .setRoutingPolicy(
                        DistributionGridRoutingPolicy(
                            returnAfterFirstLocalWork = true
                        )
                    )

                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))

                assertTrue(result.passPipeline)
                assertFalse(result.terminatePipeline)
                assertTrue(result.text.contains("return-after-local-worker"))
                assertTrue(capturedReturnAfterFirstLocalWork)
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * Verifies that a bare remote failure payload is still attributed to the remote peer.
     */
    @Test
    fun bareRemoteFailureIsAttributedToRemotePeer()
    {
        runBlocking {
            val failingPeer = BareFailureReplyPeer(
                nodeId = "bare-failure-peer",
                transportAddress = "bare-failure-peer-address"
            )

            P2PRegistry.register(failingPeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "bare-failure-sender-grid",
                    transportAddress = "bare-failure-sender-address",
                    router = ExecutionInterface("bare-failure-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = failingPeer.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("bare-failure-worker") { it }
                )

                senderGrid.addPeerDescriptor(failingPeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val envelope = result.metadata["distributionGridEnvelope"] as? DistributionGridEnvelope
                val outcome = result.metadata["distributionGridOutcome"] as? DistributionGridOutcome
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(result.terminatePipeline)
                assertNotNull(envelope)
                assertNotNull(outcome)
                assertNotNull(failure)
                assertEquals(failingPeer.getP2pDescription()!!.distributionGridMetadata!!.nodeId, envelope.currentNodeId)
                assertEquals(failingPeer.getP2pDescription()!!.distributionGridMetadata!!.nodeId, outcome.finalNodeId)
                assertEquals(failingPeer.getP2pDescription()!!.distributionGridMetadata!!.nodeId, failure.sourceNodeId)
            }

            finally
            {
                P2PRegistry.remove(failingPeer)
            }
        }
    }

    /**
     * Verifies that an explicit-peer task reply that is not framed as grid RPC invalidates the cached session.
     */
    @Test
    fun nonGridRemoteReplyInvalidatesCachedSession()
    {
        runBlocking {
            val peer = NonGridReplyPeer(
                nodeId = "non-grid-peer",
                transportAddress = "non-grid-peer-address"
            )

            P2PRegistry.register(peer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "non-grid-sender-grid",
                    transportAddress = "non-grid-sender-address",
                    router = ExecutionInterface("non-grid-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = peer.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("non-grid-worker") { it }
                )

                senderGrid.addPeerDescriptor(peer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertTrue(result.terminatePipeline)
                assertNotNull(failure)
                assertEquals(DistributionGridFailureKind.TRANSPORT_FAILURE, failure.kind)
                assertEquals(0, readSessionRecordCount(senderGrid))
                assertEquals(1, peer.taskHandoffCount)
            }

            finally
            {
                P2PRegistry.remove(peer)
            }
        }
    }

    /**
     * Verifies that successful remote merges keep the peer's execution notes.
     */
    @Test
    fun successfulRemoteMergePreservesPeerExecutionNotes()
    {
        runBlocking {
            val remoteGrid = remoteGrid(
                nodeId = "notes-remote-grid",
                transportAddress = "notes-remote-grid-address",
                router = ExecutionInterface("notes-remote-router") { content ->
                    content
                },
                worker = ExecutionInterface("notes-remote-worker") { content ->
                    content.addText("remote-worker")
                    content
                }
            )
                .setBeforeRouteHook { envelope ->
                    envelope.executionNotes.add("remote-router-note")
                    envelope
                }
                .setAfterLocalWorkerHook { envelope ->
                    envelope.executionNotes.add("remote-worker-note")
                    envelope
                }

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "notes-sender-grid",
                    transportAddress = "notes-sender-address",
                    router = ExecutionInterface("notes-sender-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remoteGrid.getP2pTransport()!!.let {
                                "${it.transportMethod}::${it.transportAddress}"
                            }
                        )
                        content
                    },
                    worker = ExecutionInterface("notes-sender-worker") { it }
                )

                senderGrid.addPeerDescriptor(remoteGrid.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "start"))
                val envelope = result.metadata["distributionGridEnvelope"] as? DistributionGridEnvelope

                assertTrue(result.passPipeline)
                assertNotNull(envelope)
                assertTrue(envelope.executionNotes.any { it.contains("remote-router-note") })
                assertTrue(envelope.executionNotes.any { it.contains("remote-worker-note") })
            }

            finally
            {
                P2PRegistry.remove(remoteGrid)
            }
        }
    }

    /**
     * P2P fake that returns a valid handshake acknowledgment but a task reply with a mismatched wrapper session id.
     *
     * @param nodeId Stable peer node id.
     * @param transportAddress Stable peer transport address.
     */
    private class MismatchedTaskReplyPeer(
        nodeId: String,
        transportAddress: String
    ) : P2PInterface
    {
        private var descriptor = buildStaticGridDescriptor(nodeId, transportAddress)
        private var transport = descriptor.transport.deepCopy()
        private var requirements = P2PRequirements(
            allowExternalConnections = true,
            acceptedContent = mutableListOf(SupportedContentTypes.text)
        )

        var handshakeCount: Int = 0
            private set

        var taskHandoffCount: Int = 0
            private set

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
        }

        override fun getP2pDescription(): P2PDescriptor
        {
            return descriptor
        }

        override fun setP2pTransport(transport: P2PTransport)
        {
            this.transport = transport
        }

        override fun getP2pTransport(): P2PTransport
        {
            return transport
        }

        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
        }

        override fun getP2pRequirements(): P2PRequirements
        {
            return requirements
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            val promptText = request.prompt.text
            if(!promptText.startsWith(GRID_RPC_FRAME_PREFIX))
            {
                return P2PResponse(
                    rejection = P2PRejection(
                        errorType = P2PError.configuration,
                        reason = "Expected framed DistributionGrid RPC traffic."
                    )
                )
            }

            val rpcMessage = deserialize<DistributionGridRpcMessage>(
                promptText.substringAfter('\n'),
                useRepair = false
            ) ?: return null

            return when(rpcMessage.messageType)
            {
                DistributionGridRpcMessageType.HANDSHAKE_INIT ->
                {
                    handshakeCount += 1

                    val handshakeRequest = deserialize<DistributionGridHandshakeRequest>(
                        rpcMessage.payloadJson,
                        useRepair = false
                    ) ?: return null

                    val negotiatedPolicy = DistributionGridNegotiatedPolicy(
                        protocolVersion = DistributionGridProtocolVersion(),
                        tracePolicy = handshakeRequest.tracePolicy.deepCopy(),
                        credentialPolicy = handshakeRequest.credentialPolicy.deepCopy(),
                        routingPolicy = handshakeRequest.acceptedRoutingPolicy.deepCopy(),
                        storageClasses = handshakeRequest.tracePolicy.allowedStorageClasses.deepCopy()
                    )

                    val sessionRecord = DistributionGridSessionRecord(
                        sessionId = "reply-session-id",
                        requesterNodeId = handshakeRequest.requesterNodeId,
                        responderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                        registryId = "",
                        negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                        negotiatedPolicy = negotiatedPolicy,
                        expiresAtEpochMillis = System.currentTimeMillis() + 60_000L,
                        invalidated = false,
                        invalidationReason = ""
                    )

                    P2PResponse(
                        output = MultimodalContent(
                            text = buildStaticGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.HANDSHAKE_ACK,
                                    senderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                    targetId = handshakeRequest.requesterNodeId,
                                    protocolVersion = DistributionGridProtocolVersion(),
                                    sessionRef = DistributionGridSessionRef(
                                        sessionId = sessionRecord.sessionId,
                                        requesterNodeId = sessionRecord.requesterNodeId,
                                        responderNodeId = sessionRecord.responderNodeId,
                                        expiresAtEpochMillis = sessionRecord.expiresAtEpochMillis
                                    ),
                                    payloadType = "DistributionGridHandshakeResponse",
                                    payloadJson = serialize(
                                        DistributionGridHandshakeResponse(
                                            accepted = true,
                                            negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                                            negotiatedPolicy = negotiatedPolicy,
                                            rejectionReason = "",
                                            sessionRecord = sessionRecord
                                        )
                                    )
                                )
                            )
                        )
                    )
                }

                DistributionGridRpcMessageType.TASK_HANDOFF ->
                {
                    taskHandoffCount += 1
                    P2PResponse(
                        output = MultimodalContent(
                            text = buildStaticGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.TASK_RETURN,
                                    senderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                    targetId = rpcMessage.senderNodeId,
                                    protocolVersion = DistributionGridProtocolVersion(),
                                    sessionRef = DistributionGridSessionRef(
                                        sessionId = "wrong-wrapper-session-id",
                                        requesterNodeId = rpcMessage.sessionRef?.requesterNodeId
                                            ?: rpcMessage.senderNodeId,
                                        responderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                        expiresAtEpochMillis = System.currentTimeMillis() + 60_000L
                                    ),
                                    payloadType = "DistributionGridEnvelope",
                                    payloadJson = serialize(
                                        DistributionGridEnvelope(
                                            taskId = "reply-task",
                                            originNodeId = rpcMessage.senderNodeId,
                                            originTransport = request.returnAddress.deepCopy(),
                                            senderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                            senderTransport = descriptor.transport.deepCopy(),
                                            currentNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                            currentTransport = descriptor.transport.deepCopy(),
                                            content = MultimodalContent(text = "reply"),
                                            taskIntent = "reply",
                                            currentObjective = "reply",
                                            routingPolicy = DistributionGridRoutingPolicy(),
                                            tracePolicy = DistributionGridTracePolicy(),
                                            credentialPolicy = DistributionGridCredentialPolicy(),
                                            executionNotes = mutableListOf("peer reply"),
                                            hopHistory = mutableListOf(),
                                            completed = true,
                                            latestOutcome = null,
                                            latestFailure = null,
                                            durableStateKey = "",
                                            sessionRef = rpcMessage.sessionRef?.deepCopy(),
                                            attributes = mutableMapOf()
                                        )
                                    )
                                )
                            )
                        )
                    )
                }

                else -> null
            }
        }
    }

    /**
     * P2P fake that delegates to a real DistributionGrid but tampers with inbound task-handoff wrappers before they are processed.
     *
     * @param delegateGrid Real initialized grid that performs the actual inbound execution.
     */
    private class InboundWrapperTamperingPeer(
        private val delegateGrid: DistributionGrid
    ) : P2PInterface
    {
        var handshakeCount: Int = 0
            private set

        var taskHandoffCount: Int = 0
            private set

        override fun setP2pDescription(description: P2PDescriptor)
        {
            delegateGrid.setP2pDescription(description)
        }

        override fun getP2pDescription(): P2PDescriptor
        {
            return delegateGrid.getP2pDescription()!!
        }

        override fun setP2pTransport(transport: P2PTransport)
        {
            delegateGrid.setP2pTransport(transport)
        }

        override fun getP2pTransport(): P2PTransport
        {
            return delegateGrid.getP2pTransport()!!
        }

        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            delegateGrid.setP2pRequirements(requirements)
        }

        override fun getP2pRequirements(): P2PRequirements
        {
            return delegateGrid.getP2pRequirements()!!
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            val promptText = request.prompt.text
            if(!promptText.startsWith(GRID_RPC_FRAME_PREFIX))
            {
                return delegateGrid.executeP2PRequest(request)
            }

            val rpcMessage = deserialize<DistributionGridRpcMessage>(
                promptText.substringAfter('\n'),
                useRepair = false
            ) ?: return delegateGrid.executeP2PRequest(request)

            return when(rpcMessage.messageType)
            {
                DistributionGridRpcMessageType.HANDSHAKE_INIT ->
                {
                    handshakeCount += 1
                    delegateGrid.executeP2PRequest(request)
                }

                DistributionGridRpcMessageType.TASK_HANDOFF ->
                {
                    taskHandoffCount += 1

                    val tamperedRpcMessage = rpcMessage.deepCopy().apply {
                        protocolVersion = DistributionGridProtocolVersion(
                            major = protocolVersion.major,
                            minor = protocolVersion.minor,
                            patch = protocolVersion.patch + 1
                        )
                        sessionRef = sessionRef?.deepCopy()?.apply {
                            responderNodeId = "tampered-responder-node"
                            expiresAtEpochMillis += 1
                        }
                    }

                    val tamperedRequest = request.deepCopy<P2PRequest>().apply {
                        prompt = MultimodalContent(
                            text = buildStaticGridRpcPrompt(tamperedRpcMessage)
                        )
                    }

                    delegateGrid.executeP2PRequest(tamperedRequest)
                }

                else ->
                {
                    delegateGrid.executeP2PRequest(request)
                }
            }
        }
    }

    /**
     * Verifies that ordinary JSON prompts are not misclassified as internal grid RPC traffic.
     */
    @Test
    fun ordinaryJsonPromptIsNotAutoDetectedAsGridRpc()
    {
        runBlocking {
            val grid = initializedGrid(
                nodeId = "json-grid",
                transportAddress = "json-grid-address",
                router = ExecutionInterface("json-router") { it },
                worker = ExecutionInterface("json-worker") { content ->
                    content.addText("json-worker")
                    content
                }
            )

            val request = P2PRequest(
                transport = P2PTransport(
                    transportMethod = Transport.Tpipe,
                    transportAddress = "ordinary-json-sender"
                ),
                prompt = MultimodalContent(
                    text = """{"messageType":"TASK_HANDOFF","payloadType":"DistributionGridEnvelope","prompt":"ordinary-user-json"}"""
                )
            )

            val response = grid.executeP2PRequest(request)

            assertNotNull(response)
            assertNull(response.rejection)
            assertNotNull(response.output)
            assertTrue(response.output!!.passPipeline)
            assertTrue(response.output!!.text.contains("ordinary-user-json"))
            assertTrue(response.output!!.text.contains("json-worker"))
        }
    }

    /**
     * Verifies that a framed RPC with a malformed payload is rejected as protocol traffic.
     */
    @Test
    fun malformedFramedRpcIsRejectedAsProtocolTraffic()
    {
        runBlocking {
            val grid = initializedGrid(
                nodeId = "malformed-rpc-grid",
                transportAddress = "malformed-rpc-grid-address",
                router = ExecutionInterface("malformed-rpc-router") { it },
                worker = ExecutionInterface("malformed-rpc-worker") { content ->
                    content.addText("malformed-rpc-worker")
                    content
                }
            )

            val request = P2PRequest(
                transport = P2PTransport(
                    transportMethod = Transport.Tpipe,
                    transportAddress = "malformed-rpc-sender"
                ),
                prompt = MultimodalContent(
                    text = "$GRID_RPC_FRAME_PREFIX\nnot-json"
                )
            )

            val response = grid.executeP2PRequest(request)

            assertNotNull(response)
            assertNull(response.output)
            assertNotNull(response.rejection)
            assertEquals(P2PError.json, response.rejection!!.errorType)
            assertTrue(response.rejection!!.reason.contains("malformed", ignoreCase = true))
        }
    }

    /**
     * Verifies that CRLF-framed RPC prompts are accepted by the inbound parser.
     */
    @Test
    fun crlfFramedRpcIsAccepted()
    {
        runBlocking {
            val grid = initializedGrid(
                nodeId = "crlf-rpc-grid",
                transportAddress = "crlf-rpc-grid-address",
                router = ExecutionInterface("crlf-rpc-router") { it },
                worker = ExecutionInterface("crlf-rpc-worker") { content ->
                    content.addText("crlf-rpc-worker")
                    content
                }
            )

            val handshakeRequest = DistributionGridHandshakeRequest(
                requesterNodeId = "crlf-sender",
                requesterMetadata = buildGridDescriptor(
                    nodeId = "crlf-sender",
                    transportAddress = "crlf-sender-address"
                ).distributionGridMetadata!!,
                supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
                acceptedRoutingPolicy = DistributionGridRoutingPolicy(),
                tracePolicy = DistributionGridTracePolicy(),
                credentialPolicy = DistributionGridCredentialPolicy(),
                acceptedTransports = mutableListOf(Transport.Tpipe)
            )

            val response = grid.executeP2PRequest(
                P2PRequest(
                    transport = P2PTransport(
                        transportMethod = Transport.Tpipe,
                        transportAddress = "crlf-rpc-sender"
                    ),
                    prompt = MultimodalContent(
                        text = "$GRID_RPC_FRAME_PREFIX\r\n${
                            serialize(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.HANDSHAKE_INIT,
                                    senderNodeId = "crlf-sender",
                                    targetId = "crlf-rpc-grid",
                                    payloadType = "DistributionGridHandshakeRequest",
                                    payloadJson = serialize(handshakeRequest)
                                )
                            )
                        }"
                    )
                )
            )

            assertNotNull(response)
            assertNull(response.rejection)
            assertNotNull(response.output)
            assertTrue(response.output!!.text.startsWith(GRID_RPC_FRAME_PREFIX))
        }
    }

    /**
     * Build a fully initialized grid node with explicit outward identity so remote tests can address it directly.
     *
     * @param nodeId Stable grid node id.
     * @param transportAddress Stable outward transport address.
     * @param router Router binding to install.
     * @param worker Worker binding to install.
     * @return Initialized grid node.
     */
    private suspend fun initializedGrid(
        nodeId: String,
        transportAddress: String,
        router: ExecutionInterface,
        worker: ExecutionInterface,
        supportedProtocolVersions: MutableList<DistributionGridProtocolVersion> = mutableListOf(DistributionGridProtocolVersion()),
        recordsPromptContent: Boolean = false,
        recordsInteractionContext: Boolean = false,
        registryMemberships: MutableList<String> = mutableListOf()
    ): DistributionGrid
    {
        val grid = DistributionGrid()
        grid.setP2pTransport(
            P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = transportAddress
            )
        )
        grid.setP2pDescription(
            buildGridDescriptor(
                nodeId = nodeId,
                transportAddress = transportAddress,
                supportedProtocolVersions = supportedProtocolVersions,
                recordsPromptContent = recordsPromptContent,
                recordsInteractionContext = recordsInteractionContext,
                registryMemberships = registryMemberships
            )
        )
        grid.setP2pRequirements(
            P2PRequirements(
                allowExternalConnections = true,
                acceptedContent = mutableListOf(SupportedContentTypes.text)
            )
        )
        grid.setRouter(router)
        grid.setWorker(worker)
        grid.init()
        return grid
    }

    /**
     * Build and register a remote grid node in the local P2P registry.
     *
     * @param nodeId Stable grid node id.
     * @param transportAddress Stable outward transport address.
     * @param router Router binding to install.
     * @param worker Worker binding to install.
     * @return Registered remote grid node.
     */
    private suspend fun remoteGrid(
        nodeId: String,
        transportAddress: String,
        router: ExecutionInterface,
        worker: ExecutionInterface,
        supportedProtocolVersions: MutableList<DistributionGridProtocolVersion> = mutableListOf(DistributionGridProtocolVersion()),
        recordsPromptContent: Boolean = false,
        recordsInteractionContext: Boolean = false,
        registryMemberships: MutableList<String> = mutableListOf()
    ): DistributionGrid
    {
        val grid = initializedGrid(
            nodeId,
            transportAddress,
            router,
            worker,
            supportedProtocolVersions,
            recordsPromptContent,
            recordsInteractionContext,
            registryMemberships
        )
        P2PRegistry.register(grid)
        return grid
    }

    /**
     * Build a public grid descriptor with explicit metadata for remote tests.
     *
     * @param nodeId Stable grid node id.
     * @param transportAddress Outward transport address.
     * @return Public grid descriptor.
     */
    private fun buildGridDescriptor(
        nodeId: String,
        transportAddress: String,
        defaultTracePolicy: DistributionGridTracePolicy = DistributionGridTracePolicy(),
        defaultRoutingPolicy: DistributionGridRoutingPolicy = DistributionGridRoutingPolicy(),
        supportedProtocolVersions: MutableList<DistributionGridProtocolVersion> = mutableListOf(DistributionGridProtocolVersion()),
        recordsPromptContent: Boolean = false,
        recordsInteractionContext: Boolean = false,
        registryMemberships: MutableList<String> = mutableListOf()
    ): P2PDescriptor
    {
        val transport = P2PTransport(
            transportMethod = Transport.Tpipe,
            transportAddress = transportAddress
        )

        return P2PDescriptor(
            agentName = nodeId,
            agentDescription = "DistributionGrid test node",
            transport = transport,
            requiresAuth = false,
            usesConverse = false,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none,
            supportedContentTypes = mutableListOf(SupportedContentTypes.text),
            recordsPromptContent = recordsPromptContent,
            recordsInteractionContext = recordsInteractionContext,
            distributionGridMetadata = DistributionGridNodeMetadata(
                nodeId = nodeId,
                supportedProtocolVersions = supportedProtocolVersions,
                roleCapabilities = mutableListOf("Router", "Worker"),
                registryMemberships = registryMemberships,
                supportedTransports = mutableListOf(Transport.Tpipe),
                requiresHandshake = true,
                defaultTracePolicy = defaultTracePolicy,
                defaultRoutingPolicy = defaultRoutingPolicy,
                actsAsRegistry = false
            )
        )
    }

    /**
     * Build the framed prompt text used by DistributionGrid RPC traffic.
     *
     * @param rpcMessage Grid RPC message to serialize.
     * @return Framed RPC prompt text.
     */
    private fun buildGridRpcPrompt(rpcMessage: DistributionGridRpcMessage): String
    {
        return "$GRID_RPC_FRAME_PREFIX\n${serialize(rpcMessage)}"
    }

    /**
     * Read the private grid id for trace assertions.
     *
     * @param grid Grid under test.
     * @return Private grid id.
     */
    private fun readGridId(grid: DistributionGrid): String
    {
        val field = grid.javaClass.getDeclaredField("gridId")
        field.isAccessible = true
        return field.get(grid) as String
    }

    /**
     * Read the current number of live cached sessions for one grid instance.
     *
     * @param grid Grid under test.
     * @return Live cached session count.
     */
    private fun readSessionRecordCount(grid: DistributionGrid): Int
    {
        val field = grid.javaClass.getDeclaredField("sessionRecordsById")
        field.isAccessible = true
        val sessionRecords = field.get(grid) as MutableMap<*, *>
        return sessionRecords.size
    }

    /**
     * Read the current number of cached explicit-peer sessions for one grid instance.
     *
     * @param grid Grid under test.
     * @return Cached explicit-peer session count.
     */
    private fun readPeerSessionRecordCount(grid: DistributionGrid): Int
    {
        val field = grid.javaClass.getDeclaredField("sessionRecordsByPeerKey")
        field.isAccessible = true
        val sessionRecords = field.get(grid) as MutableMap<*, *>
        return sessionRecords.size
    }

    /**
     * Invoke the private outbound grid RPC builder for direct transport-shaping assertions.
     *
     * @param grid Grid under test.
     * @param descriptor Explicit peer descriptor.
     * @param rpcMessage RPC message to encode.
     * @return Built outbound P2P request.
     */
    private fun invokeBuildGridRpcRequest(
        grid: DistributionGrid,
        descriptor: P2PDescriptor,
        rpcMessage: DistributionGridRpcMessage
    ): P2PRequest
    {
        val method = grid.javaClass.getDeclaredMethod(
            "buildGridRpcRequest",
            P2PDescriptor::class.java,
            DistributionGridRpcMessage::class.java
        )
        method.isAccessible = true
        return method.invoke(grid, descriptor, rpcMessage) as P2PRequest
    }

    /**
     * Invoke the private handshake-window validator for exact-duration regression coverage.
     *
     * @param grid Grid under test.
     * @param sessionRecord Negotiated session record to validate.
     * @param handshakeRequest Original handshake request.
     * @return `true` when the session expiry stays within the requested window.
     */
    private fun invokeHandshakeWindowValidation(
        grid: DistributionGrid,
        sessionRecord: DistributionGridSessionRecord,
        handshakeRequest: DistributionGridHandshakeRequest
    ): Boolean
    {
        val method = grid.javaClass.getDeclaredMethod(
            "isHandshakeSessionDurationWithinRequestedWindow",
            DistributionGridSessionRecord::class.java,
            DistributionGridHandshakeRequest::class.java
        )
        method.isAccessible = true
        return method.invoke(grid, sessionRecord, handshakeRequest) as Boolean
    }

    /**
     * Invoke the private cached-session resolver for registry-scoped cache coverage.
     *
     * @param grid Grid under test.
     * @param peerKey Canonical explicit-peer key.
     * @param registryId Registry context token to test.
     * @param descriptor Explicit peer descriptor.
     * @param envelope Current envelope used for policy compatibility.
     * @return Cached session record when one is reusable for the supplied registry context.
     */
    private fun invokeResolveValidCachedSession(
        grid: DistributionGrid,
        peerKey: String,
        registryId: String,
        descriptor: P2PDescriptor,
        envelope: DistributionGridEnvelope
    ): DistributionGridSessionRecord?
    {
        val method = grid.javaClass.getDeclaredMethod(
            "resolveValidCachedSession",
            String::class.java,
            String::class.java,
            P2PDescriptor::class.java,
            DistributionGridEnvelope::class.java
        )
        method.isAccessible = true
        return method.invoke(grid, peerKey, registryId, descriptor, envelope) as DistributionGridSessionRecord?
    }

    /**
     * Invoke the private session cache helper for collision-regression setup.
     *
     * @param grid Grid under test.
     * @param sessionRecord Session record to cache.
     * @param peerKey Explicit peer key to associate with the record.
     */
    private fun invokeCacheSession(
        grid: DistributionGrid,
        sessionRecord: DistributionGridSessionRecord,
        peerKey: String
    )
    {
        val method = grid.javaClass.getDeclaredMethod(
            "cacheSession",
            DistributionGridSessionRecord::class.java,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(grid, sessionRecord, peerKey)
    }

    /**
     * Derive the Phase 2 canonical local-peer key for a synthesized Tpipe peer binding.
     *
     * @param component Local peer test double.
     * @return Canonical local-peer key.
     */
    private fun senderGridPeerKey(component: ExecutionInterface): String
    {
        val transport = component.getP2pTransport() ?: error("Local peer transport was not synthesized.")
        return "${transport.transportMethod}::${transport.transportAddress}"
    }

    /**
     * Simple execution double used for router and worker bindings in Phase 5 tests.
     *
     * @param name Stable test identity.
     * @param behavior Local execution behavior to apply.
     */
    private class ExecutionInterface(
        private val name: String,
        private val behavior: suspend (MultimodalContent) -> MultimodalContent
    ) : P2PInterface
    {
        private var descriptor: P2PDescriptor? = null
        private var requirements: P2PRequirements? = null
        private var transport: P2PTransport? = null
        private var containerRef: Any? = null

        var localExecutionCount: Int = 0
            private set

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
        }

        override fun getP2pDescription(): P2PDescriptor?
        {
            return descriptor
        }

        override fun setP2pTransport(transport: P2PTransport)
        {
            this.transport = transport
        }

        override fun getP2pTransport(): P2PTransport?
        {
            return transport
        }

        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
        }

        override fun getP2pRequirements(): P2PRequirements?
        {
            return requirements
        }

        override fun getContainerObject(): Any?
        {
            return containerRef
        }

        override fun setContainerObject(container: Any)
        {
            containerRef = container
        }

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            localExecutionCount += 1
            return behavior(content)
        }
    }

    /**
     * P2P fake that returns a deliberately widened handshake acknowledgment and tracks whether task handoff occurs.
     *
     * @param nodeId Stable peer node id.
     * @param transportAddress Stable peer transport address.
     */
    private class WideningHandshakePeer(
        nodeId: String,
        transportAddress: String
    ) : P2PInterface
    {
        private var descriptor = buildStaticGridDescriptor(nodeId, transportAddress)
        private var transport = descriptor.transport.deepCopy()
        private var requirements = P2PRequirements(
            allowExternalConnections = true,
            acceptedContent = mutableListOf(SupportedContentTypes.text)
        )

        var handshakeCount: Int = 0
            private set

        var taskHandoffCount: Int = 0
            private set

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
        }

        override fun getP2pDescription(): P2PDescriptor
        {
            return descriptor
        }

        override fun setP2pTransport(transport: P2PTransport)
        {
            this.transport = transport
        }

        override fun getP2pTransport(): P2PTransport
        {
            return transport
        }

        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
        }

        override fun getP2pRequirements(): P2PRequirements
        {
            return requirements
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            val promptText = request.prompt.text
            if(!promptText.startsWith(GRID_RPC_FRAME_PREFIX))
            {
                return P2PResponse(
                    rejection = com.TTT.P2P.P2PRejection(
                        errorType = com.TTT.P2P.P2PError.configuration,
                        reason = "Expected framed DistributionGrid RPC traffic."
                    )
                )
            }

            val rpcMessage = deserialize<DistributionGridRpcMessage>(
                promptText.substringAfter('\n'),
                useRepair = false
            ) ?: return null

            return when(rpcMessage.messageType)
            {
                DistributionGridRpcMessageType.HANDSHAKE_INIT ->
                {
                    handshakeCount += 1
                    val handshakeRequest = deserialize<DistributionGridHandshakeRequest>(
                        rpcMessage.payloadJson,
                        useRepair = false
                    ) ?: return null

                    val widenedPolicy = DistributionGridNegotiatedPolicy(
                        protocolVersion = DistributionGridProtocolVersion(),
                        tracePolicy = handshakeRequest.tracePolicy.deepCopy().apply {
                            allowTracePersistence = true
                        },
                        credentialPolicy = handshakeRequest.credentialPolicy.deepCopy().apply {
                            forwardSecrets = true
                            credentialRefs = mutableListOf("widened-credential-ref")
                        },
                        routingPolicy = handshakeRequest.acceptedRoutingPolicy.deepCopy().apply {
                            maxHopCount = handshakeRequest.acceptedRoutingPolicy.maxHopCount + 4
                        },
                        storageClasses = handshakeRequest.tracePolicy.allowedStorageClasses.deepCopy()
                    )

                    val sessionRecord = DistributionGridSessionRecord(
                        sessionId = "widened-session",
                        requesterNodeId = handshakeRequest.requesterNodeId,
                        responderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                        registryId = "",
                        negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                        negotiatedPolicy = widenedPolicy,
                        expiresAtEpochMillis = System.currentTimeMillis() + 60_000L,
                        invalidated = false,
                        invalidationReason = ""
                    )

                    P2PResponse(
                        output = MultimodalContent(
                            text = buildStaticGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.HANDSHAKE_ACK,
                                    senderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                    targetId = handshakeRequest.requesterNodeId,
                                    protocolVersion = DistributionGridProtocolVersion(),
                                    sessionRef = DistributionGridSessionRef(
                                        sessionId = sessionRecord.sessionId,
                                        requesterNodeId = sessionRecord.requesterNodeId,
                                        responderNodeId = sessionRecord.responderNodeId,
                                        expiresAtEpochMillis = sessionRecord.expiresAtEpochMillis
                                    ),
                                    payloadType = "DistributionGridHandshakeResponse",
                                    payloadJson = serialize(
                                        DistributionGridHandshakeResponse(
                                            accepted = true,
                                            negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                                            negotiatedPolicy = widenedPolicy,
                                            rejectionReason = "",
                                            sessionRecord = sessionRecord
                                        )
                                    )
                                )
                            )
                        )
                    )
                }

                DistributionGridRpcMessageType.TASK_HANDOFF ->
                {
                    taskHandoffCount += 1
                    P2PResponse(
                        output = MultimodalContent(
                            text = buildStaticGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.TASK_FAILURE,
                                    senderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                    targetId = rpcMessage.senderNodeId,
                                    protocolVersion = DistributionGridProtocolVersion(),
                                    sessionRef = rpcMessage.sessionRef,
                                    payloadType = "DistributionGridFailure",
                                    payloadJson = serialize(
                                        DistributionGridFailure(
                                            kind = DistributionGridFailureKind.ROUTING_FAILURE,
                                            sourceNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                            reason = "Task handoff should not occur after widened handshake ack."
                                        )
                                    )
                                )
                            )
                        )
                    )
                }

                else -> null
            }
        }
    }

    /**
     * P2P fake that returns a handshake acknowledgment with credential refs the caller did not request.
     *
     * @param nodeId Stable peer node id.
     * @param transportAddress Stable peer transport address.
     */
    private class CredentialRefsWideningHandshakePeer(
        nodeId: String,
        transportAddress: String
    ) : P2PInterface
    {
        private var descriptor = buildStaticGridDescriptor(nodeId, transportAddress)
        private var transport = descriptor.transport.deepCopy()
        private var requirements = P2PRequirements(
            allowExternalConnections = true,
            acceptedContent = mutableListOf(SupportedContentTypes.text)
        )

        var handshakeCount: Int = 0
            private set

        var taskHandoffCount: Int = 0
            private set

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
        }

        override fun getP2pDescription(): P2PDescriptor
        {
            return descriptor
        }

        override fun setP2pTransport(transport: P2PTransport)
        {
            this.transport = transport
        }

        override fun getP2pTransport(): P2PTransport
        {
            return transport
        }

        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
        }

        override fun getP2pRequirements(): P2PRequirements
        {
            return requirements
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            val promptText = request.prompt.text
            if(!promptText.startsWith(GRID_RPC_FRAME_PREFIX))
            {
                return P2PResponse(
                    rejection = com.TTT.P2P.P2PRejection(
                        errorType = com.TTT.P2P.P2PError.configuration,
                        reason = "Expected framed DistributionGrid RPC traffic."
                    )
                )
            }

            val rpcMessage = deserialize<DistributionGridRpcMessage>(
                promptText.substringAfter('\n'),
                useRepair = false
            ) ?: return null

            return when(rpcMessage.messageType)
            {
                DistributionGridRpcMessageType.HANDSHAKE_INIT ->
                {
                    handshakeCount += 1

                    val handshakeRequest = deserialize<DistributionGridHandshakeRequest>(
                        rpcMessage.payloadJson,
                        useRepair = false
                    ) ?: return null

                    val negotiatedPolicy = DistributionGridNegotiatedPolicy(
                        protocolVersion = DistributionGridProtocolVersion(),
                        tracePolicy = handshakeRequest.tracePolicy.deepCopy(),
                        credentialPolicy = handshakeRequest.credentialPolicy.deepCopy().apply {
                            credentialRefs = mutableListOf("peer-secret-ref")
                        },
                        routingPolicy = handshakeRequest.acceptedRoutingPolicy.deepCopy(),
                        storageClasses = handshakeRequest.tracePolicy.allowedStorageClasses.deepCopy()
                    )

                    val sessionRecord = DistributionGridSessionRecord(
                        sessionId = "credential-refs-session",
                        requesterNodeId = handshakeRequest.requesterNodeId,
                        responderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                        registryId = "",
                        negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                        negotiatedPolicy = negotiatedPolicy,
                        expiresAtEpochMillis = System.currentTimeMillis() + 60_000L,
                        invalidated = false,
                        invalidationReason = ""
                    )

                    P2PResponse(
                        output = MultimodalContent(
                            text = buildStaticGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.HANDSHAKE_ACK,
                                    senderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                    targetId = handshakeRequest.requesterNodeId,
                                    protocolVersion = DistributionGridProtocolVersion(),
                                    sessionRef = DistributionGridSessionRef(
                                        sessionId = sessionRecord.sessionId,
                                        requesterNodeId = sessionRecord.requesterNodeId,
                                        responderNodeId = sessionRecord.responderNodeId,
                                        expiresAtEpochMillis = sessionRecord.expiresAtEpochMillis
                                    ),
                                    payloadType = "DistributionGridHandshakeResponse",
                                    payloadJson = serialize(
                                        DistributionGridHandshakeResponse(
                                            accepted = true,
                                            negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                                            negotiatedPolicy = negotiatedPolicy,
                                            rejectionReason = "",
                                            sessionRecord = sessionRecord
                                        )
                                    )
                                )
                            )
                        )
                    )
                }

                DistributionGridRpcMessageType.TASK_HANDOFF ->
                {
                    taskHandoffCount += 1
                    null
                }

                else -> null
            }
        }
    }

    /**
     * P2P fake that returns the same storage-class set in a different order to verify canonical comparison.
     *
     * @param nodeId Stable peer node id.
     * @param transportAddress Stable peer transport address.
     */
    private class StorageClassOrderingHandshakePeer(
        nodeId: String,
        transportAddress: String
    ) : P2PInterface
    {
        private var descriptor = buildStaticGridDescriptor(nodeId, transportAddress)
        private var transport = descriptor.transport.deepCopy()
        private var requirements = P2PRequirements(
            allowExternalConnections = true,
            acceptedContent = mutableListOf(SupportedContentTypes.text)
        )

        var handshakeCount: Int = 0
            private set

        var taskHandoffCount: Int = 0
            private set

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
        }

        override fun getP2pDescription(): P2PDescriptor
        {
            return descriptor
        }

        override fun setP2pTransport(transport: P2PTransport)
        {
            this.transport = transport
        }

        override fun getP2pTransport(): P2PTransport
        {
            return transport
        }

        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
        }

        override fun getP2pRequirements(): P2PRequirements
        {
            return requirements
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            val promptText = request.prompt.text
            if(!promptText.startsWith(GRID_RPC_FRAME_PREFIX))
            {
                return P2PResponse(
                    rejection = P2PRejection(
                        errorType = P2PError.configuration,
                        reason = "Expected framed DistributionGrid RPC traffic."
                    )
                )
            }

            val rpcMessage = deserialize<DistributionGridRpcMessage>(
                promptText.substringAfter('\n'),
                useRepair = false
            ) ?: return null

            return when(rpcMessage.messageType)
            {
                DistributionGridRpcMessageType.HANDSHAKE_INIT ->
                {
                    handshakeCount += 1

                    val handshakeRequest = deserialize<DistributionGridHandshakeRequest>(
                        rpcMessage.payloadJson,
                        useRepair = false
                    ) ?: return null

                    val storageClasses = handshakeRequest.tracePolicy.allowedStorageClasses
                        .asReversed()
                        .toMutableList()

                    val negotiatedPolicy = DistributionGridNegotiatedPolicy(
                        protocolVersion = DistributionGridProtocolVersion(),
                        tracePolicy = handshakeRequest.tracePolicy.deepCopy(),
                        credentialPolicy = handshakeRequest.credentialPolicy.deepCopy(),
                        routingPolicy = handshakeRequest.acceptedRoutingPolicy.deepCopy(),
                        storageClasses = storageClasses
                    )

                    val sessionRecord = DistributionGridSessionRecord(
                        sessionId = "ordering-session",
                        requesterNodeId = handshakeRequest.requesterNodeId,
                        responderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                        registryId = "",
                        negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                        negotiatedPolicy = negotiatedPolicy,
                        expiresAtEpochMillis = System.currentTimeMillis() + 60_000L,
                        invalidated = false,
                        invalidationReason = ""
                    )

                    P2PResponse(
                        output = MultimodalContent(
                            text = buildStaticGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.HANDSHAKE_ACK,
                                    senderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                    targetId = handshakeRequest.requesterNodeId,
                                    protocolVersion = DistributionGridProtocolVersion(),
                                    sessionRef = DistributionGridSessionRef(
                                        sessionId = sessionRecord.sessionId,
                                        requesterNodeId = sessionRecord.requesterNodeId,
                                        responderNodeId = sessionRecord.responderNodeId,
                                        expiresAtEpochMillis = sessionRecord.expiresAtEpochMillis
                                    ),
                                    payloadType = "DistributionGridHandshakeResponse",
                                    payloadJson = serialize(
                                        DistributionGridHandshakeResponse(
                                            accepted = true,
                                            negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                                            negotiatedPolicy = negotiatedPolicy,
                                            rejectionReason = "",
                                            sessionRecord = sessionRecord
                                        )
                                    )
                                )
                            )
                        )
                    )
                }

                DistributionGridRpcMessageType.TASK_HANDOFF ->
                {
                    taskHandoffCount += 1
                    P2PResponse(
                        output = MultimodalContent(
                            text = buildStaticGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.TASK_RETURN,
                                    senderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                    targetId = rpcMessage.senderNodeId,
                                    protocolVersion = DistributionGridProtocolVersion(),
                                    sessionRef = rpcMessage.sessionRef,
                                    payloadType = "DistributionGridEnvelope",
                                    payloadJson = serialize(
                                        DistributionGridEnvelope(
                                            taskId = "ordering-task",
                                            originNodeId = rpcMessage.senderNodeId,
                                            originTransport = request.returnAddress.deepCopy(),
                                            senderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                            senderTransport = descriptor.transport.deepCopy(),
                                            currentNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                            currentTransport = descriptor.transport.deepCopy(),
                                            content = MultimodalContent(text = "ordering-success"),
                                            taskIntent = "ordering",
                                            currentObjective = "ordering",
                                            routingPolicy = DistributionGridRoutingPolicy(),
                                            tracePolicy = DistributionGridTracePolicy(),
                                            credentialPolicy = DistributionGridCredentialPolicy(),
                                            executionNotes = mutableListOf(),
                                            hopHistory = mutableListOf(),
                                            completed = true,
                                            latestOutcome = null,
                                            latestFailure = null,
                                            durableStateKey = "",
                                            sessionRef = rpcMessage.sessionRef?.deepCopy(),
                                            attributes = mutableMapOf()
                                        )
                                    )
                                )
                            )
                        )
                    )
                }

                else -> null
            }
        }
    }

    /**
     * P2P fake that returns a peer-authored session rejection and tracks whether task handoff occurs.
     *
     * @param nodeId Stable peer node id.
     * @param transportAddress Stable peer transport address.
     * @param failureReason Failure reason to return in the handshake rejection.
     * @param failureKind Failure kind to return in the handshake rejection.
     */
    private class RejectingHandshakePeer(
        nodeId: String,
        transportAddress: String,
        private val failureReason: String,
        private val failureKind: DistributionGridFailureKind
    ) : P2PInterface
    {
        private var descriptor = buildStaticGridDescriptor(nodeId, transportAddress)
        private var transport = descriptor.transport.deepCopy()
        private var requirements = P2PRequirements(
            allowExternalConnections = true,
            acceptedContent = mutableListOf(SupportedContentTypes.text)
        )

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
        }

        override fun getP2pDescription(): P2PDescriptor
        {
            return descriptor
        }

        override fun setP2pTransport(transport: P2PTransport)
        {
            this.transport = transport
        }

        override fun getP2pTransport(): P2PTransport
        {
            return transport
        }

        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
        }

        override fun getP2pRequirements(): P2PRequirements
        {
            return requirements
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            val promptText = request.prompt.text
            if(!promptText.startsWith(GRID_RPC_FRAME_PREFIX))
            {
                return P2PResponse(
                    rejection = com.TTT.P2P.P2PRejection(
                        errorType = com.TTT.P2P.P2PError.configuration,
                        reason = "Expected framed DistributionGrid RPC traffic."
                    )
                )
            }

            val rpcMessage = deserialize<DistributionGridRpcMessage>(
                promptText.substringAfter('\n'),
                useRepair = false
            ) ?: return null

            return when(rpcMessage.messageType)
            {
                DistributionGridRpcMessageType.HANDSHAKE_INIT ->
                {
                    val handshakeRequest = deserialize<DistributionGridHandshakeRequest>(
                        rpcMessage.payloadJson,
                        useRepair = false
                    ) ?: return null

                    val failure = DistributionGridFailure(
                        kind = failureKind,
                        sourceNodeId = descriptor.distributionGridMetadata!!.nodeId,
                        reason = failureReason,
                        retryable = false
                    )

                    P2PResponse(
                        output = MultimodalContent(
                            text = buildStaticGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.SESSION_REJECT,
                                    senderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                    targetId = handshakeRequest.requesterNodeId,
                                    protocolVersion = DistributionGridProtocolVersion(),
                                    sessionRef = null,
                                    payloadType = "DistributionGridFailure",
                                    payloadJson = serialize(failure)
                                )
                            )
                        )
                    )
                }

                else -> null
            }
        }
    }

    /**
     * P2P fake that handshakes normally and then rejects task handoff at the P2P boundary.
     *
     * @param nodeId Stable peer node id.
     * @param transportAddress Stable peer transport address.
     * @param rejection Boundary rejection to return on task handoff.
     */
    private class BoundaryRejectingTaskPeer(
        nodeId: String,
        transportAddress: String,
        private val rejection: P2PRejection
    ) : P2PInterface
    {
        private var descriptor = buildStaticGridDescriptor(nodeId, transportAddress)
        private var transport = descriptor.transport.deepCopy()
        private var requirements = P2PRequirements(
            allowExternalConnections = true,
            acceptedContent = mutableListOf(SupportedContentTypes.text)
        )

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
        }

        override fun getP2pDescription(): P2PDescriptor
        {
            return descriptor
        }

        override fun setP2pTransport(transport: P2PTransport)
        {
            this.transport = transport
        }

        override fun getP2pTransport(): P2PTransport
        {
            return transport
        }

        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
        }

        override fun getP2pRequirements(): P2PRequirements
        {
            return requirements
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            val promptText = request.prompt.text
            if(!promptText.startsWith(GRID_RPC_FRAME_PREFIX))
            {
                return P2PResponse(
                    rejection = P2PRejection(
                        errorType = P2PError.configuration,
                        reason = "Expected framed DistributionGrid RPC traffic."
                    )
                )
            }

            val rpcMessage = deserialize<DistributionGridRpcMessage>(
                promptText.substringAfter('\n'),
                useRepair = false
            ) ?: return null

            return when(rpcMessage.messageType)
            {
                DistributionGridRpcMessageType.HANDSHAKE_INIT ->
                {
                    val handshakeRequest = deserialize<DistributionGridHandshakeRequest>(
                        rpcMessage.payloadJson,
                        useRepair = false
                    ) ?: return null

                    val negotiatedPolicy = DistributionGridNegotiatedPolicy(
                        protocolVersion = DistributionGridProtocolVersion(),
                        tracePolicy = handshakeRequest.tracePolicy.deepCopy(),
                        credentialPolicy = handshakeRequest.credentialPolicy.deepCopy(),
                        routingPolicy = handshakeRequest.acceptedRoutingPolicy.deepCopy(),
                        storageClasses = handshakeRequest.tracePolicy.allowedStorageClasses.deepCopy()
                    )

                    val sessionRecord = DistributionGridSessionRecord(
                        sessionId = "boundary-rejecting-session",
                        requesterNodeId = handshakeRequest.requesterNodeId,
                        responderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                        registryId = "",
                        negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                        negotiatedPolicy = negotiatedPolicy,
                        expiresAtEpochMillis = System.currentTimeMillis() + 60_000L,
                        invalidated = false,
                        invalidationReason = ""
                    )

                    P2PResponse(
                        output = MultimodalContent(
                            text = buildStaticGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.HANDSHAKE_ACK,
                                    senderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                    targetId = handshakeRequest.requesterNodeId,
                                    protocolVersion = DistributionGridProtocolVersion(),
                                    sessionRef = DistributionGridSessionRef(
                                        sessionId = sessionRecord.sessionId,
                                        requesterNodeId = sessionRecord.requesterNodeId,
                                        responderNodeId = sessionRecord.responderNodeId,
                                        expiresAtEpochMillis = sessionRecord.expiresAtEpochMillis
                                    ),
                                    payloadType = "DistributionGridHandshakeResponse",
                                    payloadJson = serialize(
                                        DistributionGridHandshakeResponse(
                                            accepted = true,
                                            negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                                            negotiatedPolicy = negotiatedPolicy,
                                            rejectionReason = "",
                                            sessionRecord = sessionRecord
                                        )
                                    )
                                )
                            )
                        )
                    )
                }

                DistributionGridRpcMessageType.TASK_HANDOFF ->
                {
                    P2PResponse(rejection = rejection.deepCopy())
                }

                else -> null
            }
        }
    }

    /**
     * P2P fake that handshakes normally and then returns a bare failure payload on task handoff.
     *
     * @param nodeId Stable peer node id.
     * @param transportAddress Stable peer transport address.
     */
    private class BareFailureReplyPeer(
        nodeId: String,
        transportAddress: String
    ) : P2PInterface
    {
        private var descriptor = buildStaticGridDescriptor(nodeId, transportAddress)
        private var transport = descriptor.transport.deepCopy()
        private var requirements = P2PRequirements(
            allowExternalConnections = true,
            acceptedContent = mutableListOf(SupportedContentTypes.text)
        )

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
        }

        override fun getP2pDescription(): P2PDescriptor
        {
            return descriptor
        }

        override fun setP2pTransport(transport: P2PTransport)
        {
            this.transport = transport
        }

        override fun getP2pTransport(): P2PTransport
        {
            return transport
        }

        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
        }

        override fun getP2pRequirements(): P2PRequirements
        {
            return requirements
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            val promptText = request.prompt.text
            if(!promptText.startsWith(GRID_RPC_FRAME_PREFIX))
            {
                return P2PResponse(
                    rejection = P2PRejection(
                        errorType = P2PError.configuration,
                        reason = "Expected framed DistributionGrid RPC traffic."
                    )
                )
            }

            val rpcMessage = deserialize<DistributionGridRpcMessage>(
                promptText.substringAfter('\n'),
                useRepair = false
            ) ?: return null

            return when(rpcMessage.messageType)
            {
                DistributionGridRpcMessageType.HANDSHAKE_INIT ->
                {
                    val handshakeRequest = deserialize<DistributionGridHandshakeRequest>(
                        rpcMessage.payloadJson,
                        useRepair = false
                    ) ?: return null

                    val negotiatedPolicy = DistributionGridNegotiatedPolicy(
                        protocolVersion = DistributionGridProtocolVersion(),
                        tracePolicy = handshakeRequest.tracePolicy.deepCopy(),
                        credentialPolicy = handshakeRequest.credentialPolicy.deepCopy(),
                        routingPolicy = handshakeRequest.acceptedRoutingPolicy.deepCopy(),
                        storageClasses = handshakeRequest.tracePolicy.allowedStorageClasses.deepCopy()
                    )

                    val sessionRecord = DistributionGridSessionRecord(
                        sessionId = "bare-failure-session",
                        requesterNodeId = handshakeRequest.requesterNodeId,
                        responderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                        registryId = "",
                        negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                        negotiatedPolicy = negotiatedPolicy,
                        expiresAtEpochMillis = System.currentTimeMillis() + 60_000L,
                        invalidated = false,
                        invalidationReason = ""
                    )

                    P2PResponse(
                        output = MultimodalContent(
                            text = buildStaticGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.HANDSHAKE_ACK,
                                    senderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                    targetId = handshakeRequest.requesterNodeId,
                                    protocolVersion = DistributionGridProtocolVersion(),
                                    sessionRef = DistributionGridSessionRef(
                                        sessionId = sessionRecord.sessionId,
                                        requesterNodeId = sessionRecord.requesterNodeId,
                                        responderNodeId = sessionRecord.responderNodeId,
                                        expiresAtEpochMillis = sessionRecord.expiresAtEpochMillis
                                    ),
                                    payloadType = "DistributionGridHandshakeResponse",
                                    payloadJson = serialize(
                                        DistributionGridHandshakeResponse(
                                            accepted = true,
                                            negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                                            negotiatedPolicy = negotiatedPolicy,
                                            rejectionReason = "",
                                            sessionRecord = sessionRecord
                                        )
                                    )
                                )
                            )
                        )
                    )
                }

                DistributionGridRpcMessageType.TASK_HANDOFF ->
                {
                    val failure = DistributionGridFailure(
                        kind = DistributionGridFailureKind.ROUTING_FAILURE,
                        sourceNodeId = descriptor.distributionGridMetadata!!.nodeId,
                        targetNodeId = rpcMessage.senderNodeId,
                        transportMethod = descriptor.transport.transportMethod,
                        transportAddress = descriptor.transport.transportAddress,
                        reason = "bare failure reply",
                        retryable = false
                    )

                    P2PResponse(
                        output = MultimodalContent(
                            text = buildStaticGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.TASK_FAILURE,
                                    senderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                    targetId = rpcMessage.senderNodeId,
                                    protocolVersion = DistributionGridProtocolVersion(),
                                    sessionRef = rpcMessage.sessionRef?.deepCopy(),
                                    payloadType = "DistributionGridFailure",
                                    payloadJson = serialize(failure)
                                )
                            )
                        )
                    )
                }

                else -> null
            }
        }
    }

    /**
     * P2P fake that handshakes normally and then returns a non-grid task reply body.
     *
     * @param nodeId Stable peer node id.
     * @param transportAddress Stable peer transport address.
     */
    private class NonGridReplyPeer(
        nodeId: String,
        transportAddress: String
    ) : P2PInterface
    {
        private var descriptor = buildStaticGridDescriptor(nodeId, transportAddress)
        private var transport = descriptor.transport.deepCopy()
        private var requirements = P2PRequirements(
            allowExternalConnections = true,
            acceptedContent = mutableListOf(SupportedContentTypes.text)
        )

        var handshakeCount: Int = 0
            private set

        var taskHandoffCount: Int = 0
            private set

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
        }

        override fun getP2pDescription(): P2PDescriptor
        {
            return descriptor
        }

        override fun setP2pTransport(transport: P2PTransport)
        {
            this.transport = transport
        }

        override fun getP2pTransport(): P2PTransport
        {
            return transport
        }

        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
        }

        override fun getP2pRequirements(): P2PRequirements
        {
            return requirements
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            val promptText = request.prompt.text
            if(!promptText.startsWith(GRID_RPC_FRAME_PREFIX))
            {
                return P2PResponse(
                    rejection = P2PRejection(
                        errorType = P2PError.configuration,
                        reason = "Expected framed DistributionGrid RPC traffic."
                    )
                )
            }

            val rpcMessage = deserialize<DistributionGridRpcMessage>(
                promptText.substringAfter('\n'),
                useRepair = false
            ) ?: return null

            return when(rpcMessage.messageType)
            {
                DistributionGridRpcMessageType.HANDSHAKE_INIT ->
                {
                    handshakeCount += 1

                    val handshakeRequest = deserialize<DistributionGridHandshakeRequest>(
                        rpcMessage.payloadJson,
                        useRepair = false
                    ) ?: return null

                    val negotiatedPolicy = DistributionGridNegotiatedPolicy(
                        protocolVersion = DistributionGridProtocolVersion(),
                        tracePolicy = handshakeRequest.tracePolicy.deepCopy(),
                        credentialPolicy = handshakeRequest.credentialPolicy.deepCopy(),
                        routingPolicy = handshakeRequest.acceptedRoutingPolicy.deepCopy(),
                        storageClasses = handshakeRequest.tracePolicy.allowedStorageClasses.deepCopy()
                    )

                    val sessionRecord = DistributionGridSessionRecord(
                        sessionId = "non-grid-session-id",
                        requesterNodeId = handshakeRequest.requesterNodeId,
                        responderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                        registryId = "",
                        negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                        negotiatedPolicy = negotiatedPolicy,
                        expiresAtEpochMillis = System.currentTimeMillis() + 60_000L,
                        invalidated = false,
                        invalidationReason = ""
                    )

                    P2PResponse(
                        output = MultimodalContent(
                            text = buildStaticGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.HANDSHAKE_ACK,
                                    senderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                    targetId = handshakeRequest.requesterNodeId,
                                    protocolVersion = DistributionGridProtocolVersion(),
                                    sessionRef = DistributionGridSessionRef(
                                        sessionId = sessionRecord.sessionId,
                                        requesterNodeId = sessionRecord.requesterNodeId,
                                        responderNodeId = sessionRecord.responderNodeId,
                                        expiresAtEpochMillis = sessionRecord.expiresAtEpochMillis
                                    ),
                                    payloadType = "DistributionGridHandshakeResponse",
                                    payloadJson = serialize(
                                        DistributionGridHandshakeResponse(
                                            accepted = true,
                                            negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                                            negotiatedPolicy = negotiatedPolicy,
                                            rejectionReason = "",
                                            sessionRecord = sessionRecord
                                        )
                                    )
                                )
                            )
                        )
                    )
                }

                DistributionGridRpcMessageType.TASK_HANDOFF ->
                {
                    taskHandoffCount += 1
                    P2PResponse(
                        output = MultimodalContent(text = "plain-text-task-reply")
                    )
                }

                else -> null
            }
        }
    }

    /**
     * P2P fake that returns a handshake acknowledgment with a session identity mismatch.
     *
     * @param nodeId Stable peer node id.
     * @param transportAddress Stable peer transport address.
     */
    private class MismatchedSessionHandshakePeer(
        nodeId: String,
        transportAddress: String
    ) : P2PInterface
    {
        private var descriptor = buildStaticGridDescriptor(nodeId, transportAddress)
        private var transport = descriptor.transport.deepCopy()
        private var requirements = P2PRequirements(
            allowExternalConnections = true,
            acceptedContent = mutableListOf(SupportedContentTypes.text)
        )

        var handshakeCount = 0
            private set
        var taskHandoffCount = 0
            private set

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
        }

        override fun getP2pDescription(): P2PDescriptor
        {
            return descriptor
        }

        override fun setP2pTransport(transport: P2PTransport)
        {
            this.transport = transport
        }

        override fun getP2pTransport(): P2PTransport
        {
            return transport
        }

        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
        }

        override fun getP2pRequirements(): P2PRequirements
        {
            return requirements
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            val promptText = request.prompt.text
            if(!promptText.startsWith(GRID_RPC_FRAME_PREFIX))
            {
                return P2PResponse(
                    rejection = P2PRejection(
                        errorType = P2PError.configuration,
                        reason = "Expected framed DistributionGrid RPC traffic."
                    )
                )
            }

            val rpcMessage = deserialize<DistributionGridRpcMessage>(
                promptText.substringAfter('\n'),
                useRepair = false
            ) ?: return null

            return when(rpcMessage.messageType)
            {
                DistributionGridRpcMessageType.HANDSHAKE_INIT ->
                {
                    handshakeCount += 1

                    val handshakeRequest = deserialize<DistributionGridHandshakeRequest>(
                        rpcMessage.payloadJson,
                        useRepair = false
                    ) ?: return null

                    val negotiatedPolicy = DistributionGridNegotiatedPolicy(
                        protocolVersion = DistributionGridProtocolVersion(),
                        tracePolicy = handshakeRequest.tracePolicy.deepCopy(),
                        credentialPolicy = handshakeRequest.credentialPolicy.deepCopy(),
                        routingPolicy = handshakeRequest.acceptedRoutingPolicy.deepCopy(),
                        storageClasses = handshakeRequest.tracePolicy.allowedStorageClasses.deepCopy()
                    )

                    val sessionRecord = DistributionGridSessionRecord(
                        sessionId = "embedded-session-id",
                        requesterNodeId = handshakeRequest.requesterNodeId,
                        responderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                        registryId = "",
                        negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                        negotiatedPolicy = negotiatedPolicy,
                        expiresAtEpochMillis = System.currentTimeMillis() + 60_000L,
                        invalidated = false,
                        invalidationReason = ""
                    )

                    P2PResponse(
                        output = MultimodalContent(
                            text = buildStaticGridRpcPrompt(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.HANDSHAKE_ACK,
                                    senderNodeId = descriptor.distributionGridMetadata!!.nodeId,
                                    targetId = handshakeRequest.requesterNodeId,
                                    protocolVersion = DistributionGridProtocolVersion(),
                                    sessionRef = DistributionGridSessionRef(
                                        sessionId = "wrapper-session-id",
                                        requesterNodeId = sessionRecord.requesterNodeId,
                                        responderNodeId = sessionRecord.responderNodeId,
                                        expiresAtEpochMillis = sessionRecord.expiresAtEpochMillis
                                    ),
                                    payloadType = "DistributionGridHandshakeResponse",
                                    payloadJson = serialize(
                                        DistributionGridHandshakeResponse(
                                            accepted = true,
                                            negotiatedProtocolVersion = DistributionGridProtocolVersion(),
                                            negotiatedPolicy = negotiatedPolicy,
                                            rejectionReason = "",
                                            sessionRecord = sessionRecord
                                        )
                                    )
                                )
                            )
                        )
                    )
                }

                DistributionGridRpcMessageType.TASK_HANDOFF ->
                {
                    taskHandoffCount += 1
                    null
                }

                else -> null
            }
        }
    }

}
