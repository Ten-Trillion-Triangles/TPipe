package com.TTT.Pipeline

import com.TTT.Debug.TraceEventType
import com.TTT.Config.AuthRegistry
import com.TTT.P2P.ContextProtocol
import com.TTT.Pipe.BinaryContent
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.PipeContextProtocol.StdioContextOptions
import com.TTT.PipeContextProtocol.StdioExecutionMode
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.deepCopy
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Focused Phase 7 coverage for outbound memory shaping, durability, PCP mediation, retry routing, and trace export.
 */
class DistributionGridHardeningTest
{
    private companion object
    {
        private const val GRID_RPC_FRAME_PREFIX = "TPipe-DistributionGrid-RPC::1"
        private const val MEMORY_ENVELOPE_METADATA_KEY = "distributionGridMemoryEnvelope"
        private const val PAUSED_METADATA_KEY = "distributionGridPaused"
        private const val PAUSED_TASK_ID_METADATA_KEY = "distributionGridPausedTaskId"
    }

    /**
     * Verifies that remote handoff builds a bounded outbound memory envelope and strips local grid metadata before
     * the remote peer sees the payload.
     */
    @Test
    fun remoteHandoffBuildsOutboundMemoryEnvelope()
    {
        runBlocking {
            val remotePeer = RecordingGridRpcPeer(nodeId = "memory-remote-node", transportAddress = "memory-remote-address")
            P2PRegistry.register(remotePeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "memory-sender-node",
                    transportAddress = "memory-sender-address",
                    router = ExecutionInterface("memory-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = "Tpipe::memory-remote-address"
                        )
                        content
                    },
                    worker = ExecutionInterface("memory-worker") { it }
                )
                senderGrid
                    .setMemoryPolicy(
                        DistributionGridMemoryPolicy(
                            outboundTokenBudget = 256,
                            safetyReserveTokens = 32,
                            minimumCriticalBudget = 64,
                            minimumRecentBudget = 32,
                            summaryBudget = 64,
                            maxExecutionNotes = 2,
                            maxHopRecords = 2,
                            enableSummarization = true,
                            summarizer = { text -> "SUMMARY:$text" }
                        )
                    )
                    .setBeforeRouteHook { envelope ->
                        envelope.tracePolicy.requireRedaction = true
                        envelope.executionNotes.addAll(
                            mutableListOf(
                                "note-one",
                                "note-two",
                                "note-three"
                            )
                        )
                        envelope
                    }

                senderGrid.addPeerDescriptor(remotePeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(
                    MultimodalContent(
                        text = buildString {
                            repeat(30) {
                                append("Long content chunk to force memory compaction. ")
                            }
                        },
                        binaryContent = mutableListOf(
                            BinaryContent.Bytes(
                                data = byteArrayOf(0x01, 0x02, 0x03),
                                mimeType = "application/octet-stream",
                                filename = "payload.bin"
                            )
                        ),
                        tools = PcPRequest(
                            callParams = mapOf("tool" to "should-redact")
                        )
                    )
                )
                val summaryEnvelopeMethod = DistributionGrid::class.java.getDeclaredMethod(
                    "buildOutboundMemoryEnvelope",
                    DistributionGridEnvelope::class.java,
                    P2PDescriptor::class.java
                )
                summaryEnvelopeMethod.isAccessible = true
                val shapedMemoryEnvelope = summaryEnvelopeMethod.invoke(
                    senderGrid,
                    DistributionGridEnvelope(
                        taskId = "memory-task",
                        content = MultimodalContent(
                            text = buildString {
                                repeat(12) {
                                    append("Summary budget must be reserved before compacting the remaining memory. ")
                                }
                            }
                        ),
                        executionNotes = mutableListOf("older-note-1", "older-note-2", "older-note-3")
                    ),
                    remotePeer.getP2pDescription()!!.deepCopy()
                ) as DistributionGridMemoryEnvelope
                val blankSummaryEnvelope = summaryEnvelopeMethod.invoke(
                    senderGrid,
                    DistributionGridEnvelope(
                        taskId = "memory-task-blank",
                        content = MultimodalContent(text = "")
                    ),
                    remotePeer.getP2pDescription()!!.deepCopy()
                ) as DistributionGridMemoryEnvelope

                val capturedEnvelope = remotePeer.lastTaskEnvelope
                assertTrue(result.passPipeline)
                assertNotNull(capturedEnvelope)
                assertEquals("[REDACTED]", capturedEnvelope.content.text)
                assertTrue(capturedEnvelope.content.context.contextElements.isNotEmpty())
                assertTrue(capturedEnvelope.content.miniBankContext.contextMap.isNotEmpty())
                assertTrue(capturedEnvelope.content.binaryContent.isEmpty())
                assertEquals(PcPRequest(), capturedEnvelope.content.tools)
                assertNull(capturedEnvelope.content.metadata["distributionGridDirective"])
                assertNull(capturedEnvelope.content.metadata["distributionGridEnvelope"])
                assertTrue(capturedEnvelope.executionNotes.size <= 2)
                assertEquals(64, shapedMemoryEnvelope.summaryBudget)
                assertEquals(0, blankSummaryEnvelope.summaryBudget)
                assertTrue(blankSummaryEnvelope.sections.none { it.name == "summary" })
            }

            finally
            {
                P2PRegistry.remove(remotePeer)
            }
        }
    }

    /**
     * Verifies that peers with very small advertised context windows are compacted to fit instead of being rejected.
     */
    @Test
    fun remoteHandoffCompactsToSmallAdvertisedWindow()
    {
        runBlocking {
            val remotePeer = RecordingGridRpcPeer(
                nodeId = "small-window-remote-node",
                transportAddress = "small-window-remote-address"
            )
            P2PRegistry.register(remotePeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "small-window-sender-node",
                    transportAddress = "small-window-sender-address",
                    router = ExecutionInterface("small-window-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = "Tpipe::small-window-remote-address"
                        )
                        content
                    },
                    worker = ExecutionInterface("small-window-worker") { it }
                )
                senderGrid.setMemoryPolicy(
                    DistributionGridMemoryPolicy(
                        outboundTokenBudget = 256,
                        safetyReserveTokens = 32,
                        minimumCriticalBudget = 64,
                        minimumRecentBudget = 32,
                        summaryBudget = 64
                    )
                )
                senderGrid.addPeerDescriptor(
                    buildGridDescriptor(
                        nodeId = "small-window-remote-node",
                        transportAddress = "small-window-remote-address"
                    ).apply {
                        contextWindowSize = 96
                    }
                )
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "small-window-request"))

                assertTrue(result.passPipeline, "failure=${result.metadata["distributionGridFailure"]}")
                assertEquals(1, remotePeer.taskHandoffCount)
                assertEquals("small-window-request", remotePeer.lastTaskEnvelope?.content?.text)
            }
            finally
            {
                P2PRegistry.remove(remotePeer)
            }
        }
    }

    /**
     * Verifies that stdio session options survive the outbound policy gate and are not treated as PCP forwarding.
     */
    @Test
    fun remoteHandoffAllowsStdioSessionOptions()
    {
        runBlocking {
            val senderGrid = initializedGrid(
                nodeId = "pcp-sender-node",
                transportAddress = "pcp-sender-address",
                router = ExecutionInterface("pcp-router") { content ->
                    content.metadata["distributionGridDirective"] = DistributionGridDirective(
                        kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                        targetPeerId = "Stdio::pcp-remote-command"
                    )
                    content
                },
                worker = ExecutionInterface("pcp-worker") { it }
            )

            senderGrid.addPeerDescriptor(
                buildGridDescriptor(
                    nodeId = "pcp-remote-node",
                    transportAddress = "pcp-remote-command",
                    transportMethod = Transport.Stdio
                ).apply {
                    requestTemplate = P2PRequest(
                        pcpRequest = PcPRequest(
                            stdioContextOptions = StdioContextOptions().apply {
                                command = "echo"
                                executionMode = StdioExecutionMode.ONE_SHOT
                            }
                        )
                    )
                }
            )
            senderGrid.init()

            val result = senderGrid.execute(MultimodalContent(text = "pcp-request"))
            val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

            assertTrue(result.terminatePipeline)
            assertNotNull(failure)
            assertTrue(failure.kind != DistributionGridFailureKind.POLICY_REJECTED)
        }
    }

    /**
     * Verifies that a default PCP template object does not block non-stdio remote handoffs when no real PCP payload is
     * being forwarded.
     */
    @Test
    fun remoteHandoffAllowsDefaultTemplatePcpObjectForNonStdioPeers()
    {
        runBlocking {
            val remotePeer = RecordingGridRpcPeer(
                nodeId = "template-remote-node",
                transportAddress = "template-remote-address"
            )
            P2PRegistry.register(remotePeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "template-sender-node",
                    transportAddress = "template-sender-address",
                    router = ExecutionInterface("template-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = "Tpipe::template-remote-address"
                        )
                        content
                    },
                    worker = ExecutionInterface("template-worker") { it }
                )
                senderGrid.addPeerDescriptor(
                    buildGridDescriptor(
                        nodeId = "template-remote-node",
                        transportAddress = "template-remote-address"
                    ).apply {
                        contextWindowSize = 1024
                        requestTemplate = P2PRequest()
                    }
                )
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "template-request"))

                assertTrue(result.passPipeline, "failure=${result.metadata["distributionGridFailure"]}")
                assertEquals(1, remotePeer.taskHandoffCount)
                assertTrue(result.text.contains("remote-success"))
            }
            finally
            {
                P2PRegistry.remove(remotePeer)
            }
        }
    }

    /**
     * Verifies that real PCP payloads survive non-stdio handoff when remote forwarding is explicitly allowed.
     */
    @Test
    fun remoteHandoffPreservesPcpPayloadWhenForwardingIsAllowed()
    {
        runBlocking {
            val remotePeer = RecordingGridRpcPeer(
                nodeId = "pcp-forward-remote-node",
                transportAddress = "pcp-forward-remote-address"
            )
            P2PRegistry.register(remotePeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "pcp-forward-sender-node",
                    transportAddress = "pcp-forward-sender-address",
                    router = ExecutionInterface("pcp-forward-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = "Tpipe::pcp-forward-remote-address"
                        )
                        content
                    },
                    worker = ExecutionInterface("pcp-forward-worker") { it }
                )
                senderGrid
                    .setBeforePeerDispatchHook { envelope ->
                        envelope.attributes["distributionGridAllowRemotePcpForwarding"] = "true"
                        envelope
                    }
                    .addPeerDescriptor(
                        buildGridDescriptor(
                            nodeId = "pcp-forward-remote-node",
                            transportAddress = "pcp-forward-remote-address"
                        ).apply {
                            requestTemplate = P2PRequest(
                                pcpRequest = PcPRequest(
                                    callParams = mapOf("tool" to "allowed-remote-tool")
                                )
                            )
                        }
                    )
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "pcp-forward-request"))

                assertTrue(result.passPipeline, "failure=${result.metadata["distributionGridFailure"]}")
                assertEquals(1, remotePeer.taskHandoffCount)
                assertEquals("allowed-remote-tool", remotePeer.lastRequestPcpRequest?.callParams?.get("tool"))
            }
            finally
            {
                P2PRegistry.remove(remotePeer)
            }
        }
    }

    /**
     * Verifies that a safe pause checkpoint persists durable state and that `resumeTask(...)` can continue from it.
     */
    @Test
    fun pauseCheckpointCanBeResumedThroughDurableStore()
    {
        runBlocking {
            val store = InMemoryDurableStore()
            lateinit var grid: DistributionGrid
            var pauseIssued = false
            grid = initializedGrid(
                nodeId = "resume-node",
                transportAddress = "resume-address",
                router = ExecutionInterface("resume-router") { content ->
                    content.addText("router")
                    content
                },
                worker = ExecutionInterface("resume-worker") { content ->
                    if(!pauseIssued)
                    {
                        grid.pause()
                        pauseIssued = true
                    }
                    content.addText("worker")
                    content
                }
            )
            grid.setDurableStore(store)
            grid.init()

            val pausedResult = grid.execute(MultimodalContent(text = "resume-me"))
            val pausedTaskId = pausedResult.metadata[PAUSED_TASK_ID_METADATA_KEY] as? String

            assertEquals(true, pausedResult.metadata[PAUSED_METADATA_KEY])
            assertNotNull(pausedTaskId)
            assertNotNull(store.resumeState(pausedTaskId))

            val resumedResult = grid.resumeTask(pausedTaskId)
            val resumedOutcome = resumedResult.metadata["distributionGridOutcome"] as? DistributionGridOutcome

            assertTrue(resumedResult.text.contains("worker"))
            assertNotNull(resumedResult.metadata["distributionGridEnvelope"])
            assertFalse(resumedResult.metadata.containsKey(PAUSED_METADATA_KEY))
            assertNotNull(resumedOutcome)
            assertTrue(resumedOutcome!!.hopCount >= 1)
            assertNull(resumedResult.metadata["distributionGridFailure"])
        }
    }

    /**
     * Verifies that a paused after-peer-response checkpoint remains resumable instead of being treated as terminal.
     */
    @Test
    fun pausedPeerResponseCheckpointCanBeResumedThroughDurableStore()
    {
        runBlocking {
            val remotePeer = RecordingGridRpcPeer(
                nodeId = "resume-response-remote-node",
                transportAddress = "resume-response-remote-address",
                successReturnMode = DistributionGridReturnMode.RETURN_TO_ORIGIN
            )
            P2PRegistry.register(remotePeer)
            val store = InMemoryDurableStore()
            var pauseIssued = false

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "resume-response-sender-node",
                    transportAddress = "resume-response-sender-address",
                    router = ExecutionInterface("resume-response-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = "Tpipe::resume-response-remote-address"
                        )
                        content
                    },
                    worker = ExecutionInterface("resume-response-worker") { it }
                )
                senderGrid.setAfterPeerResponseHook { envelope ->
                    if(!pauseIssued)
                    {
                        senderGrid.pause()
                        pauseIssued = true
                    }
                    envelope
                }
                senderGrid.setDurableStore(store)
                senderGrid.addPeerDescriptor(remotePeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val pausedResult = senderGrid.execute(MultimodalContent(text = "resume-after-response"))
                val pausedTaskId = pausedResult.metadata[PAUSED_TASK_ID_METADATA_KEY] as? String

                assertNotNull(pausedTaskId)
                assertEquals(true, pausedResult.metadata[PAUSED_METADATA_KEY])
                assertNotNull(store.resumeState(pausedTaskId))
                assertFalse(store.resumeState(pausedTaskId)!!.completed)

                val resumedResult = senderGrid.resumeTask(pausedTaskId)
                val resumedEnvelope = resumedResult.metadata["distributionGridEnvelope"] as? DistributionGridEnvelope
                val resumedOutcome = resumedResult.metadata["distributionGridOutcome"] as? DistributionGridOutcome

                assertTrue(resumedResult.passPipeline)
                assertFalse(resumedResult.metadata.containsKey(PAUSED_METADATA_KEY))
                assertNotNull(resumedEnvelope)
                assertNotNull(resumedOutcome)
                assertEquals(DistributionGridReturnMode.RETURN_TO_ORIGIN, resumedOutcome!!.returnMode)
                assertEquals(1, remotePeer.taskHandoffCount)
                assertTrue(resumedResult.text.contains("remote-success"))
            }
            finally
            {
                P2PRegistry.remove(remotePeer)
            }
        }
    }

    /**
     * Verifies that a worker failure is not converted into a paused success checkpoint.
     */
    @Test
    fun pausedWorkerFailureRemainsFailure()
    {
        runBlocking {
            lateinit var grid: DistributionGrid
            grid = initializedGrid(
                nodeId = "pause-failure-node",
                transportAddress = "pause-failure-address",
                router = ExecutionInterface("pause-failure-router") { content -> content },
                worker = ExecutionInterface("pause-failure-worker") { _ ->
                    grid.pause()
                    throw IllegalStateException("worker boom")
                }
            )
            grid.init()

            val result = grid.execute(MultimodalContent(text = "fail-me"))
            val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

            assertNotNull(failure)
            assertEquals(DistributionGridFailureKind.WORKER_FAILURE, failure.kind)
            assertFalse(result.metadata.containsKey(PAUSED_METADATA_KEY))
            assertFalse(grid.isPaused())
        }
    }

    /**
     * Verifies that a pause request does not survive a terminal router return path.
     */
    @Test
    fun pauseRequestClearsAfterTerminalRouterReturn()
    {
        runBlocking {
            lateinit var grid: DistributionGrid
            var pauseIssued = false
            grid = initializedGrid(
                nodeId = "pause-return-node",
                transportAddress = "pause-return-address",
                router = ExecutionInterface("pause-return-router") { content ->
                    if(!pauseIssued)
                    {
                        grid.pause()
                        pauseIssued = true
                    }
                    content.metadata["distributionGridDirective"] = DistributionGridDirective(
                        kind = DistributionGridDirectiveKind.RETURN_TO_SENDER
                    )
                    content.addText("router")
                    content
                },
                worker = ExecutionInterface("pause-return-worker") { content ->
                    content.addText("worker")
                    content
                }
            )
            grid.init()

            val result = grid.execute(MultimodalContent(text = "pause-return"))

            assertTrue(result.passPipeline)
            assertFalse(grid.isPaused())
            assertFalse(result.metadata.containsKey(PAUSED_METADATA_KEY))
        }
    }

    /**
     * Verifies that a pause request does not survive a terminal router rejection path.
     */
    @Test
    fun pauseRequestClearsAfterTerminalRouterReject()
    {
        runBlocking {
            lateinit var grid: DistributionGrid
            var pauseIssued = false
            grid = initializedGrid(
                nodeId = "pause-reject-node",
                transportAddress = "pause-reject-address",
                router = ExecutionInterface("pause-reject-router") { content ->
                    if(!pauseIssued)
                    {
                        grid.pause()
                        pauseIssued = true
                    }
                    content.metadata["distributionGridDirective"] = DistributionGridDirective(
                        kind = DistributionGridDirectiveKind.REJECT,
                        rejectReason = "pause-reject"
                    )
                    content
                },
                worker = ExecutionInterface("pause-reject-worker") { content ->
                    content.addText("worker")
                    content
                }
            )
            grid.init()

            val result = grid.execute(MultimodalContent(text = "pause-reject"))
            val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

            assertTrue(result.terminatePipeline)
            assertNotNull(failure)
            assertEquals(DistributionGridFailureKind.POLICY_REJECTED, failure!!.kind)
            assertFalse(grid.isPaused())
            assertFalse(result.metadata.containsKey(PAUSED_METADATA_KEY))
        }
    }

    /**
     * Verifies that a paused remote handoff resumes with the local sender identity restored.
     */
    @Test
    fun pausedPeerDispatchRestoresLocalSenderIdentity()
    {
        runBlocking {
            val remotePeer = RecordingGridRpcPeer(
                nodeId = "resume-remote-node",
                transportAddress = "resume-remote-address"
            )
            P2PRegistry.register(remotePeer)
            var pauseIssued = false
            val store = InMemoryDurableStore()

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "resume-sender-node",
                    transportAddress = "resume-sender-address",
                    router = ExecutionInterface("resume-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = "Tpipe::resume-remote-address"
                        )
                        content
                    },
                    worker = ExecutionInterface("resume-worker") { it }
                )
                senderGrid.setMemoryPolicy(
                    DistributionGridMemoryPolicy(
                        outboundTokenBudget = 256,
                        safetyReserveTokens = 32,
                        minimumCriticalBudget = 64,
                        minimumRecentBudget = 32,
                        summaryBudget = 64,
                        redactContentSections = true
                    )
                )
                senderGrid.setBeforePeerDispatchHook { envelope ->
                    if(!pauseIssued)
                    {
                        senderGrid.pause()
                        pauseIssued = true
                    }
                    envelope
                }
                senderGrid.setDurableStore(store)
                senderGrid.addPeerDescriptor(remotePeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val pausedResult = senderGrid.execute(MultimodalContent(text = "resume-peer"))
                val pausedTaskId = pausedResult.metadata[PAUSED_TASK_ID_METADATA_KEY] as? String

                assertNotNull(pausedTaskId)
                assertEquals(true, pausedResult.metadata[PAUSED_METADATA_KEY])
                val pausedState = store.resumeState(pausedTaskId)
                assertNotNull(pausedState)
                assertEquals("[REDACTED]", pausedState!!.envelope.content.text)

                val resumedResult = senderGrid.resumeTask(pausedTaskId)
                val resumedEnvelope = resumedResult.metadata["distributionGridEnvelope"] as? DistributionGridEnvelope

                assertNotNull(resumedEnvelope)
                assertEquals("resume-sender-node", remotePeer.lastTaskEnvelope?.senderNodeId)
                assertEquals(1, remotePeer.taskHandoffCount)
                assertEquals("resume-sender-node", resumedEnvelope!!.senderNodeId)
            }
            finally
            {
                P2PRegistry.remove(remotePeer)
            }
        }
    }

    /**
     * Verifies that transport-injected auth can satisfy per-hop credential routing.
     */
    @Test
    fun remoteHandoffAllowsTransportInjectedAuth()
    {
        runBlocking {
            AuthRegistry.registerToken("echo", "Bearer injected-token")
            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "auth-sender-node",
                    transportAddress = "auth-sender-address",
                    router = ExecutionInterface("auth-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = "Stdio::echo"
                        )
                        content
                    },
                    worker = ExecutionInterface("auth-worker") { it }
                )
                senderGrid
                    .setBeforePeerDispatchHook { envelope ->
                        envelope.credentialPolicy.perHopCredentialRefs["Stdio::echo"] = "credential-ref"
                        envelope
                    }
                    .addPeerDescriptor(
                        buildGridDescriptor(
                            nodeId = "auth-remote-node",
                            transportAddress = "echo",
                            transportMethod = Transport.Stdio
                        ).apply {
                            requestTemplate = P2PRequest()
                        }
                    )
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "auth-request"))
                val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure

                assertNotNull(failure)
                assertTrue(failure.kind != DistributionGridFailureKind.POLICY_REJECTED)
            }
            finally
            {
                AuthRegistry.removeToken("echo")
            }
        }
    }

    /**
     * Verifies that per-hop credential refs are resolved through the peer's node identity and same-identity auth.
     */
    @Test
    fun remoteHandoffAllowsAgentNameInjectedAuth()
    {
        runBlocking {
            AuthRegistry.registerToken("auth-agent-node", "Bearer agent-token")
            val remotePeer = RecordingGridRpcPeer(
                nodeId = "auth-agent-node",
                transportAddress = "auth-agent-address"
            )
            P2PRegistry.register(remotePeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "auth-agent-sender-node",
                    transportAddress = "auth-agent-sender-address",
                    router = ExecutionInterface("auth-agent-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = "Tpipe::auth-agent-address"
                        )
                        content
                    },
                    worker = ExecutionInterface("auth-agent-worker") { it }
                )
                senderGrid.setMemoryPolicy(
                    DistributionGridMemoryPolicy(
                        outboundTokenBudget = 512,
                        safetyReserveTokens = 32,
                        minimumCriticalBudget = 64,
                        minimumRecentBudget = 32,
                        summaryBudget = 64
                    )
                )
                senderGrid
                    .setBeforePeerDispatchHook { envelope ->
                        envelope.credentialPolicy.perHopCredentialRefs["auth-agent-node"] = "credential-ref"
                        envelope
                    }
                    .addPeerDescriptor(
                        buildGridDescriptor(
                            nodeId = "auth-agent-node",
                            transportAddress = "auth-agent-address"
                        )
                    )
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "auth-agent-request"))

                assertTrue(result.passPipeline, "failure=${result.metadata["distributionGridFailure"]}")
                assertEquals(1, remotePeer.taskHandoffCount)
                assertEquals("Bearer agent-token", remotePeer.lastRequestAuthBody)
                assertTrue(result.text.contains("remote-success"))
            }
            finally
            {
                AuthRegistry.removeToken("auth-agent-node")
                P2PRegistry.remove(remotePeer)
            }
        }
    }

    /**
     * Verifies that TPipe peers can satisfy per-hop credentials from transport-address keyed auth and that the
     * resulting auth body is attached to the outbound request.
     */
    @Test
    fun remoteHandoffAttachesTransportAddressAuthForTpipePeer()
    {
        runBlocking {
            AuthRegistry.registerToken("auth-transport-address", "Bearer transport-token")
            val remotePeer = RecordingGridRpcPeer(
                nodeId = "auth-transport-node",
                transportAddress = "auth-transport-address"
            )
            P2PRegistry.register(remotePeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "auth-transport-sender-node",
                    transportAddress = "auth-transport-sender-address",
                    router = ExecutionInterface("auth-transport-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = "Tpipe::auth-transport-address"
                        )
                        content
                    },
                    worker = ExecutionInterface("auth-transport-worker") { it }
                )
                senderGrid
                    .setBeforePeerDispatchHook { envelope ->
                        envelope.credentialPolicy.perHopCredentialRefs["auth-transport-address"] = "credential-ref"
                        envelope
                    }
                    .addPeerDescriptor(
                        buildGridDescriptor(
                            nodeId = "auth-transport-node",
                            transportAddress = "auth-transport-address"
                        )
                    )
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "auth-transport-request"))

                assertTrue(result.passPipeline, "failure=${result.metadata["distributionGridFailure"]}")
                assertEquals(1, remotePeer.taskHandoffCount)
                assertEquals("Bearer transport-token", remotePeer.lastRequestAuthBody)
            }
            finally
            {
                AuthRegistry.removeToken("auth-transport-address")
                AuthRegistry.removeToken("auth-transport-node")
                P2PRegistry.remove(remotePeer)
            }
        }
    }

    /**
     * Verifies that a failed after-peer-response pause checkpoint still leaves the cancellation note on the live
     * envelope that finalization returns.
     */
    @Test
    fun pausedAfterPeerResponseCheckpointKeepsCancellationNoteWhenSaveFails()
    {
        runBlocking {
            val remotePeer = RecordingGridRpcPeer(
                nodeId = "pause-note-remote-node",
                transportAddress = "pause-note-remote-address",
                successReturnMode = DistributionGridReturnMode.RETURN_TO_ORIGIN
            )
            P2PRegistry.register(remotePeer)
            val store = FailingSaveDurableStore()
            var pauseIssued = false

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "pause-note-sender-node",
                    transportAddress = "pause-note-sender-address",
                    router = ExecutionInterface("pause-note-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = "Tpipe::pause-note-remote-address"
                        )
                        content
                    },
                    worker = ExecutionInterface("pause-note-worker") { it }
                )
                senderGrid.setAfterPeerResponseHook { envelope ->
                    if(!pauseIssued)
                    {
                        senderGrid.pause()
                        pauseIssued = true
                    }
                    envelope
                }
                senderGrid.setDurableStore(store)
                senderGrid.addPeerDescriptor(remotePeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "pause-note"))
                val envelope = result.metadata["distributionGridEnvelope"] as? DistributionGridEnvelope

                assertTrue(result.passPipeline)
                assertNotNull(envelope)
                assertTrue(
                    envelope!!.executionNotes.any {
                        it.contains("Pause request was canceled because checkpoint 'after-peer-response' could not be saved.")
                    }
                )
                assertFalse(result.metadata.containsKey(PAUSED_METADATA_KEY))
            }
            finally
            {
                P2PRegistry.remove(remotePeer)
            }
        }
    }

    /**
     * Verifies that a retryable peer failure can trigger one same-peer retry through the post-response hook.
     */
    @Test
    fun samePeerRetryDirectiveRetriesThePeer()
    {
        runBlocking {
            val remotePeer = RecordingGridRpcPeer(
                nodeId = "retry-remote-node",
                transportAddress = "retry-remote-address",
                failFirstTask = true
            )
            P2PRegistry.register(remotePeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "retry-sender-node",
                    transportAddress = "retry-sender-address",
                    router = ExecutionInterface("retry-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = "Tpipe::retry-remote-address"
                        )
                        content
                    },
                    worker = ExecutionInterface("retry-worker") { it }
                )
                senderGrid
                    .setRoutingPolicy(
                        DistributionGridRoutingPolicy(
                            allowRetrySamePeer = true,
                            maxRetryCount = 1
                        )
                    )
                    .setAfterPeerResponseHook { envelope ->
                        val latestFailure = envelope.latestFailure
                        if(latestFailure != null && latestFailure.retryable)
                        {
                            envelope.content.metadata["distributionGridDirective"] = DistributionGridDirective(
                                kind = DistributionGridDirectiveKind.RETRY_SAME_PEER,
                                targetPeerId = "Tpipe::retry-remote-address"
                            )
                        }
                        envelope
                    }

                senderGrid.addPeerDescriptor(remotePeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "retry-me"))

                assertTrue(result.passPipeline)
                assertEquals(2, remotePeer.taskHandoffCount)
                assertTrue(result.text.contains("remote-success"))
                assertNull(remotePeer.lastTaskEnvelope?.latestFailure)
                assertEquals("retry-me", remotePeer.lastTaskEnvelope?.content?.text)
                assertFalse(remotePeer.lastTaskEnvelope?.content?.terminatePipeline ?: true)
                assertEquals(1, remotePeer.lastTaskEnvelope?.hopHistory?.size)
                assertTrue(
                    remotePeer.lastTaskEnvelope?.executionNotes?.any { it.contains("Explicit remote handoff failed") } == true
                )
                assertEquals("retry-sender-node", remotePeer.lastTaskEnvelope?.hopHistory?.lastOrNull()?.sourceNodeId)
            }

            finally
            {
                P2PRegistry.remove(remotePeer)
            }
        }
    }

    /**
     * Verifies that a pause request is honored before a retryable peer handoff is retried.
     */
    @Test
    fun pauseRequestIsHonoredBeforeRetryingPeerFailure()
    {
        runBlocking {
            val remotePeer = RecordingGridRpcPeer(
                nodeId = "retry-pause-remote-node",
                transportAddress = "retry-pause-remote-address",
                failFirstTask = true
            )
            P2PRegistry.register(remotePeer)
            val store = InMemoryDurableStore()
            var pauseIssued = false

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "retry-pause-sender-node",
                    transportAddress = "retry-pause-sender-address",
                    router = ExecutionInterface("retry-pause-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = "Tpipe::retry-pause-remote-address"
                        )
                        content
                    },
                    worker = ExecutionInterface("retry-pause-worker") { it }
                )
                senderGrid
                    .setRoutingPolicy(
                        DistributionGridRoutingPolicy(
                            allowRetrySamePeer = true,
                            maxRetryCount = 1
                        )
                    )
                    .setAfterPeerResponseHook { envelope ->
                        if(!pauseIssued && envelope.latestFailure?.retryable == true)
                        {
                            senderGrid.pause()
                            pauseIssued = true
                            envelope.content.metadata["distributionGridDirective"] = DistributionGridDirective(
                                kind = DistributionGridDirectiveKind.RETRY_SAME_PEER,
                                targetPeerId = "Tpipe::retry-pause-remote-address"
                            )
                        }
                        envelope
                    }
                senderGrid.setDurableStore(store)
                senderGrid.addPeerDescriptor(remotePeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val pausedResult = senderGrid.execute(MultimodalContent(text = "retry-pause-me"))
                val pausedTaskId = pausedResult.metadata[PAUSED_TASK_ID_METADATA_KEY] as? String

                assertTrue(pausedResult.metadata[PAUSED_METADATA_KEY] == true)
                assertNotNull(pausedTaskId)
                val pausedState = store.resumeState(pausedTaskId)
                assertNotNull(pausedState)
                assertTrue(
                    pausedState!!.envelope.executionNotes.any { it.contains("Explicit remote handoff failed") }
                )
                assertEquals("retry-pause-sender-node", pausedState.envelope.hopHistory.lastOrNull()?.sourceNodeId)
                assertEquals(1, remotePeer.taskHandoffCount)

                val resumedResult = senderGrid.resumeTask(pausedTaskId)

                assertTrue(resumedResult.passPipeline)
                assertEquals(2, remotePeer.taskHandoffCount)
                assertTrue(resumedResult.text.contains("remote-success"))
            }
            finally
            {
                P2PRegistry.remove(remotePeer)
            }
        }
    }

    /**
     * Verifies that alternate-peer routing can continue to the first explicit alternate after a retryable failure.
     */
    @Test
    fun alternatePeerDirectiveUsesExplicitAlternatesInOrder()
    {
        runBlocking {
            val failingPeer = RecordingGridRpcPeer(
                nodeId = "alternate-failing-node",
                transportAddress = "alternate-failing-address",
                alwaysFailTask = true
            )
            val successPeer = RecordingGridRpcPeer(
                nodeId = "alternate-success-node",
                transportAddress = "alternate-success-address"
            )
            P2PRegistry.register(failingPeer)
            P2PRegistry.register(successPeer)

            try
            {
                val senderGrid = initializedGrid(
                    nodeId = "alternate-sender-node",
                    transportAddress = "alternate-sender-address",
                    router = ExecutionInterface("alternate-router") { content ->
                        content.metadata["distributionGridDirective"] = DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = "Tpipe::alternate-failing-address"
                        )
                        content
                    },
                    worker = ExecutionInterface("alternate-worker") { it }
                )
                senderGrid
                    .setRoutingPolicy(
                        DistributionGridRoutingPolicy(
                            allowAlternatePeers = true
                        )
                    )
                    .setAfterPeerResponseHook { envelope ->
                        if(envelope.latestFailure?.retryable == true)
                        {
                            envelope.content.metadata["distributionGridDirective"] = DistributionGridDirective(
                                kind = DistributionGridDirectiveKind.TRY_ALTERNATE_PEER,
                                targetPeerId = "Tpipe::alternate-failing-address",
                                alternatePeerIds = mutableListOf("Tpipe::alternate-success-address")
                            )
                        }
                        envelope
                    }

                senderGrid.addPeerDescriptor(failingPeer.getP2pDescription()!!.deepCopy())
                senderGrid.addPeerDescriptor(successPeer.getP2pDescription()!!.deepCopy())
                senderGrid.init()

                val result = senderGrid.execute(MultimodalContent(text = "alternate-me"))

                assertTrue(result.passPipeline)
                assertEquals(1, failingPeer.taskHandoffCount)
                assertEquals(1, successPeer.taskHandoffCount)
                assertTrue(result.text.contains("remote-success"))
                assertNull(successPeer.lastTaskEnvelope?.latestFailure)
                assertEquals("alternate-me", successPeer.lastTaskEnvelope?.content?.text)
                assertFalse(successPeer.lastTaskEnvelope?.content?.terminatePipeline ?: true)
                assertEquals(1, successPeer.lastTaskEnvelope?.hopHistory?.size)
                assertTrue(
                    successPeer.lastTaskEnvelope?.executionNotes?.any { it.contains("Explicit remote handoff failed") } == true
                )
                assertEquals("alternate-sender-node", successPeer.lastTaskEnvelope?.hopHistory?.lastOrNull()?.sourceNodeId)
            }

            finally
            {
                P2PRegistry.remove(failingPeer)
                P2PRegistry.remove(successPeer)
            }
        }
    }

    /**
     * Verifies that the grid now exposes normal trace export and failure analysis wrappers.
     */
    @Test
    fun traceReportAndFailureAnalysisUseTheSharedTracer()
    {
        runBlocking {
            val grid = initializedGrid(
                nodeId = "trace-node",
                transportAddress = "trace-address",
                router = ExecutionInterface("trace-router") { content ->
                    content.metadata["distributionGridDirective"] = DistributionGridDirective(
                        kind = DistributionGridDirectiveKind.REJECT,
                        rejectReason = "trace-failure"
                    )
                    content
                },
                worker = ExecutionInterface("trace-worker") { it }
            )
            grid.enableTracing()
            grid.init()

            grid.execute(MultimodalContent(text = "trace"))

            val traceReport = grid.getTraceReport()
            val failureAnalysis = grid.getFailureAnalysis()

            assertTrue(traceReport.isNotBlank())
            assertNotNull(failureAnalysis)
            assertTrue(
                failureAnalysis.executionPath.any { it.contains(TraceEventType.DISTRIBUTION_GRID_FAILURE.name) }
            )
        }
    }

    /**
     * Build one initialized grid with stable outward metadata for the hardening tests.
     */
    private suspend fun initializedGrid(
        nodeId: String,
        transportAddress: String,
        router: P2PInterface,
        worker: P2PInterface
    ): DistributionGrid
    {
        val grid = DistributionGrid()
        grid.setP2pTransport(
            P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = transportAddress
            )
        )
        grid.setP2pDescription(buildGridDescriptor(nodeId, transportAddress))
        grid.setP2pRequirements(
            P2PRequirements(
                allowExternalConnections = true,
                acceptedContent = mutableListOf(SupportedContentTypes.text),
                maxTokens = 1024
            )
        )
        router.setContainerObject(grid)
        worker.setContainerObject(grid)
        grid.setRouter(router)
        grid.setWorker(worker)
        grid.init()
        return grid
    }

    /**
     * Build one outward grid descriptor for the tests.
     */
    private fun buildGridDescriptor(
        nodeId: String,
        transportAddress: String,
        transportMethod: Transport = Transport.Tpipe
    ): P2PDescriptor
    {
        return P2PDescriptor(
            agentName = nodeId,
            agentDescription = "DistributionGrid hardening test node",
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
            supportedContentTypes = mutableListOf(SupportedContentTypes.text),
            contextWindowSize = 256,
            distributionGridMetadata = DistributionGridNodeMetadata(
                nodeId = nodeId,
                supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
                roleCapabilities = mutableListOf("Router", "Worker"),
                supportedTransports = mutableListOf(transportMethod),
                requiresHandshake = true,
                defaultTracePolicy = DistributionGridTracePolicy(),
                defaultRoutingPolicy = DistributionGridRoutingPolicy(),
                actsAsRegistry = false
            )
        )
    }

    /**
     * Simple in-memory durable store used to exercise checkpoint and resume behavior.
     */
    private class InMemoryDurableStore : DistributionGridDurableStore
    {
        private val statesById = linkedMapOf<String, DistributionGridDurableState>()

        override suspend fun saveState(state: DistributionGridDurableState)
        {
            statesById[state.taskId] = state.deepCopy()
        }

        override suspend fun loadState(taskId: String): DistributionGridDurableState?
        {
            return statesById[taskId]?.deepCopy()
        }

        override suspend fun resumeState(taskId: String): DistributionGridDurableState?
        {
            return statesById[taskId]?.deepCopy()
        }

        override suspend fun clearState(taskId: String): Boolean
        {
            return statesById.remove(taskId) != null
        }

        override suspend fun archiveState(taskId: String): Boolean
        {
            val state = statesById[taskId] ?: return false
            statesById[taskId] = state.copy(archived = true)
            return true
        }
    }

    /**
     * Durable store variant that fails every save attempt so pause-cancel diagnostics can be asserted.
     */
    private class FailingSaveDurableStore : DistributionGridDurableStore
    {
        private val statesById = linkedMapOf<String, DistributionGridDurableState>()

        override suspend fun saveState(state: DistributionGridDurableState)
        {
            throw IllegalStateException("durable save failed")
        }

        override suspend fun loadState(taskId: String): DistributionGridDurableState?
        {
            return statesById[taskId]?.deepCopy()
        }

        override suspend fun resumeState(taskId: String): DistributionGridDurableState?
        {
            return statesById[taskId]?.deepCopy()
        }

        override suspend fun clearState(taskId: String): Boolean
        {
            return statesById.remove(taskId) != null
        }

        override suspend fun archiveState(taskId: String): Boolean
        {
            val state = statesById[taskId] ?: return false
            statesById[taskId] = state.copy(archived = true)
            return true
        }
    }

    /**
     * Minimal execution stub used for local router and worker bindings inside the tests.
     */
    private class ExecutionInterface(
        private val name: String,
        private val executor: suspend (MultimodalContent) -> MultimodalContent
    ) : P2PInterface
    {
        private var descriptor: P2PDescriptor = buildStaticGridDescriptor(name, "$name-address")
        private var transport: P2PTransport = descriptor.transport.deepCopy()
        private var requirements: P2PRequirements = P2PRequirements(
            allowExternalConnections = true,
            acceptedContent = mutableListOf(SupportedContentTypes.text)
        )
        private var containerRef: Any? = null
        override var killSwitch: KillSwitch? = null

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
            return executor(content)
        }

        private fun buildStaticGridDescriptor(
            nodeId: String,
            transportAddress: String
        ): P2PDescriptor
        {
            return P2PDescriptor(
                agentName = nodeId,
                agentDescription = "ExecutionInterface test node",
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
    }

    /**
     * Lightweight remote peer that speaks just enough grid RPC to exercise retry and memory-envelope behavior.
     */
    private class RecordingGridRpcPeer(
        private val nodeId: String,
        private val transportAddress: String,
        private val failFirstTask: Boolean = false,
        private val alwaysFailTask: Boolean = false,
        private val successReturnMode: DistributionGridReturnMode? = null
    ) : P2PInterface
    {
        private var descriptor = P2PDescriptor(
            agentName = nodeId,
            agentDescription = "RecordingGridRpcPeer",
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
                defaultRoutingPolicy = DistributionGridRoutingPolicy()
            )
        )
        private var transport = descriptor.transport.deepCopy()
        private var requirements = P2PRequirements(
            allowExternalConnections = true,
            acceptedContent = mutableListOf(SupportedContentTypes.text)
        )
        override var killSwitch: com.TTT.P2P.KillSwitch? = null
        private var activeSession: DistributionGridSessionRecord? = null

        var taskHandoffCount = 0
            private set
        var lastRequestAuthBody: String = ""
            private set
        var lastRequestPcpRequest: PcPRequest? = null
            private set
        var lastTaskEnvelope: DistributionGridEnvelope? = null
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
            lastRequestAuthBody = request.authBody
            lastRequestPcpRequest = request.pcpRequest?.deepCopy()
            val rpcMessage = parseRpc(request.prompt.text) ?: return P2PResponse()
            return when(rpcMessage.messageType)
            {
                DistributionGridRpcMessageType.HANDSHAKE_INIT ->
                {
                    val handshakeRequest = deserialize<DistributionGridHandshakeRequest>(
                        rpcMessage.payloadJson,
                        useRepair = false
                    )!!
                    val sessionRecord = DistributionGridSessionRecord(
                        sessionId = UUID.randomUUID().toString(),
                        requesterNodeId = handshakeRequest.requesterNodeId.ifBlank {
                            handshakeRequest.requesterMetadata.nodeId
                        },
                        responderNodeId = nodeId,
                        registryId = handshakeRequest.registryId,
                        negotiatedProtocolVersion = rpcMessage.protocolVersion.deepCopy(),
                        negotiatedPolicy = DistributionGridNegotiatedPolicy(
                            protocolVersion = rpcMessage.protocolVersion.deepCopy(),
                            tracePolicy = handshakeRequest.tracePolicy.deepCopy(),
                            credentialPolicy = handshakeRequest.credentialPolicy.deepCopy(),
                            routingPolicy = handshakeRequest.acceptedRoutingPolicy.deepCopy(),
                            storageClasses = handshakeRequest.tracePolicy.allowedStorageClasses.deepCopy()
                        ),
                        expiresAtEpochMillis = System.currentTimeMillis() + 60_000L
                    )
                    activeSession = sessionRecord

                    P2PResponse(
                        output = MultimodalContent(
                            text = buildRpcText(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.HANDSHAKE_ACK,
                                    senderNodeId = nodeId,
                                    targetId = sessionRecord.requesterNodeId,
                                    protocolVersion = rpcMessage.protocolVersion.deepCopy(),
                                    sessionRef = sessionRecord.toSessionRef(),
                                    payloadType = "DistributionGridHandshakeResponse",
                                    payloadJson = serialize(
                                        DistributionGridHandshakeResponse(
                                            accepted = true,
                                            negotiatedProtocolVersion = rpcMessage.protocolVersion.deepCopy(),
                                            negotiatedPolicy = sessionRecord.negotiatedPolicy.deepCopy(),
                                            sessionRecord = sessionRecord.deepCopy()
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
                    val outboundEnvelope = deserialize<DistributionGridEnvelope>(
                        rpcMessage.payloadJson,
                        useRepair = false
                    )!!
                    lastTaskEnvelope = outboundEnvelope.deepCopy()
                    val sessionRecord = activeSession!!

                    if(alwaysFailTask || (failFirstTask && taskHandoffCount == 1))
                    {
                        val failedEnvelope = outboundEnvelope.deepCopy().apply {
                            currentNodeId = nodeId
                            currentTransport = descriptor.transport.deepCopy()
                            latestFailure = DistributionGridFailure(
                                kind = DistributionGridFailureKind.ROUTING_FAILURE,
                                sourceNodeId = nodeId,
                                transportMethod = descriptor.transport.transportMethod,
                                transportAddress = descriptor.transport.transportAddress,
                                reason = "retryable remote failure",
                                retryable = true
                            )
                            content = content.deepCopy().apply {
                                terminatePipeline = true
                                passPipeline = false
                            }
                        }

                        return P2PResponse(
                            output = MultimodalContent(
                                text = buildRpcText(
                                    DistributionGridRpcMessage(
                                        messageType = DistributionGridRpcMessageType.TASK_FAILURE,
                                        senderNodeId = nodeId,
                                        targetId = outboundEnvelope.senderNodeId,
                                        protocolVersion = rpcMessage.protocolVersion.deepCopy(),
                                        sessionRef = sessionRecord.toSessionRef(),
                                        payloadType = "DistributionGridEnvelope",
                                        payloadJson = serialize(failedEnvelope)
                                    )
                                )
                            )
                        )
                    }

                    val successEnvelope = outboundEnvelope.deepCopy().apply {
                        currentNodeId = nodeId
                        currentTransport = descriptor.transport.deepCopy()
                        content = content.deepCopy().apply {
                            addText("remote-success")
                        }
                        completed = true
                        if(successReturnMode != null)
                        {
                            latestOutcome = DistributionGridOutcome(
                                status = DistributionGridOutcomeStatus.SUCCESS,
                                returnMode = successReturnMode,
                                taskId = taskId,
                                finalContent = content.deepCopy(),
                                completionNotes = "remote-success",
                                hopCount = hopHistory.size,
                                finalNodeId = nodeId,
                                terminalFailure = null
                            )
                        }
                    }

                    P2PResponse(
                        output = MultimodalContent(
                            text = buildRpcText(
                                DistributionGridRpcMessage(
                                    messageType = DistributionGridRpcMessageType.TASK_RETURN,
                                    senderNodeId = nodeId,
                                    targetId = outboundEnvelope.senderNodeId,
                                    protocolVersion = rpcMessage.protocolVersion.deepCopy(),
                                    sessionRef = sessionRecord.toSessionRef(),
                                    payloadType = "DistributionGridEnvelope",
                                    payloadJson = serialize(successEnvelope)
                                )
                            )
                        )
                    )
                }

                else -> P2PResponse()
            }
        }

        private fun parseRpc(text: String): DistributionGridRpcMessage?
        {
            val trimmed = text.trim()
            if(!trimmed.startsWith(GRID_RPC_FRAME_PREFIX))
            {
                return null
            }

            return deserialize<DistributionGridRpcMessage>(
                trimmed.removePrefix(GRID_RPC_FRAME_PREFIX).trim(),
                useRepair = false
            )
        }

        private fun buildRpcText(message: DistributionGridRpcMessage): String
        {
            return "$GRID_RPC_FRAME_PREFIX\n${serialize(message)}"
        }

        private fun DistributionGridSessionRecord.toSessionRef(): DistributionGridSessionRef
        {
            return DistributionGridSessionRef(
                sessionId = sessionId,
                requesterNodeId = requesterNodeId,
                responderNodeId = responderNodeId,
                registryId = registryId,
                expiresAtEpochMillis = expiresAtEpochMillis
            )
        }
    }
}
