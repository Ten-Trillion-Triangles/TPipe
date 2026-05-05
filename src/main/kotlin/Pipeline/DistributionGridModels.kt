package com.TTT.Pipeline

import com.TTT.P2P.P2PTransport
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.Transport
import kotlinx.serialization.Serializable

/**
 * Declares how a `DistributionGrid` router may discover downstream peers.
 */
@Serializable
enum class DistributionGridPeerDiscoveryMode
{
    EXPLICIT_ONLY,
    REGISTRY_ONLY,
    HYBRID
}

/**
 * Declares the action a router decided to take for the current envelope.
 */
@Serializable
enum class DistributionGridDirectiveKind
{
    RUN_LOCAL_WORKER,
    HAND_OFF_TO_PEER,
    RETURN_TO_SENDER,
    RETURN_TO_ORIGIN,
    RETURN_TO_TRANSPORT,
    RETRY_SAME_PEER,
    TRY_ALTERNATE_PEER,
    REJECT,
    TERMINATE
}

/**
 * Declares the terminal status of a `DistributionGrid` outcome.
 */
@Serializable
enum class DistributionGridOutcomeStatus
{
    SUCCESS,
    FAILURE,
    REJECTED
}

/**
 * Declares the failure category recorded for grid routing, execution, or policy decisions.
 */
@Serializable
enum class DistributionGridFailureKind
{
    HANDSHAKE_REJECTED,
    SESSION_REJECTED,
    TRUST_REJECTED,
    POLICY_REJECTED,
    ROUTING_FAILURE,
    WORKER_FAILURE,
    TRANSPORT_FAILURE,
    VALIDATION_FAILURE,
    DURABILITY_FAILURE,
    UNKNOWN
}

/**
 * Declares how a task should return when local work or downstream execution completes.
 */
@Serializable
enum class DistributionGridReturnMode
{
    RETURN_TO_SENDER,
    RETURN_TO_ORIGIN,
    RETURN_TO_TRANSPORT,
    RETURN_AFTER_LOCAL_WORK,
    RETURN_FAILURE_UPSTREAM
}

/**
 * Main envelope carried through the `DistributionGrid` runtime.
 *
 * @param taskId Stable identifier for the task instance.
 * @param originNodeId Grid node that originated the task.
 * @param originTransport Transport used to contact the origin node.
 * @param senderNodeId Immediate sender of the current hop.
 * @param senderTransport Transport used by the immediate sender.
 * @param currentNodeId Node currently evaluating the envelope.
 * @param currentTransport Transport identity for the current node.
 * @param content Current multimodal content payload for the task.
 * @param taskIntent Stable task intent or objective.
 * @param currentObjective Immediate objective for the current node.
 * @param routingPolicy Completion and failure routing policy.
 * @param tracePolicy Requester-supplied tracing and privacy policy.
 * @param credentialPolicy Credential propagation policy for downstream hops.
 * @param executionNotes Compact human-readable execution notes.
 * @param hopHistory Ordered hop audit history for the task.
 * @param completed Whether the task has already reached terminal completion.
 * @param latestOutcome Latest terminal or near-terminal outcome record if one exists.
 * @param latestFailure Latest failure or rejection record if one exists.
 * @param durableStateKey Optional durable-storage key for save and resume flows.
 * @param sessionRef Optional negotiated session reference currently associated with the envelope.
 * @param attributes Extra string metadata reserved for future contract expansion.
 */
@Serializable
data class DistributionGridEnvelope(
    var taskId: String = "",
    var originNodeId: String = "",
    var originTransport: P2PTransport = P2PTransport(),
    var senderNodeId: String = "",
    var senderTransport: P2PTransport = P2PTransport(),
    var currentNodeId: String = "",
    var currentTransport: P2PTransport = P2PTransport(),
    var content: MultimodalContent = MultimodalContent(),
    var taskIntent: String = "",
    var currentObjective: String = "",
    var routingPolicy: DistributionGridRoutingPolicy = DistributionGridRoutingPolicy(),
    var tracePolicy: DistributionGridTracePolicy = DistributionGridTracePolicy(),
    var credentialPolicy: DistributionGridCredentialPolicy = DistributionGridCredentialPolicy(),
    var executionNotes: MutableList<String> = mutableListOf(),
    var hopHistory: MutableList<DistributionGridHopRecord> = mutableListOf(),
    var completed: Boolean = false,
    var latestOutcome: DistributionGridOutcome? = null,
    var latestFailure: DistributionGridFailure? = null,
    var durableStateKey: String = "",
    var sessionRef: DistributionGridSessionRef? = null,
    var attributes: MutableMap<String, String> = mutableMapOf()
)

/**
 * Structured router decision emitted after inspecting the current envelope.
 *
 * @param kind Action the router wants to take next.
 * @param targetNodeId Optional downstream node identity for handoff or return.
 * @param targetPeerId Optional configured peer identity for handoff selection.
 * @param targetTransport Optional explicit transport target for return or handoff.
 * @param notes Human-readable router notes explaining the decision.
 * @param alternatePeerIds Ordered alternates the router may try after a primary failure.
 * @param rejectReason Optional rejection reason when the directive terminates the task.
 */
@Serializable
data class DistributionGridDirective(
    var kind: DistributionGridDirectiveKind = DistributionGridDirectiveKind.RUN_LOCAL_WORKER,
    var targetNodeId: String = "",
    var targetPeerId: String = "",
    var targetTransport: P2PTransport? = null,
    var notes: String = "",
    var alternatePeerIds: MutableList<String> = mutableListOf(),
    var rejectReason: String = ""
)

/**
 * Structured terminal outcome produced by the grid harness.
 *
 * @param status Terminal status for the task.
 * @param returnMode Return mode that resolved the task.
 * @param taskId Stable task identifier.
 * @param finalContent Final multimodal content returned to the caller or upstream node.
 * @param completionNotes Human-readable completion summary.
 * @param hopCount Total number of hops recorded for the task.
 * @param finalNodeId Node that produced the terminal outcome.
 * @param terminalFailure Optional terminal failure details when the outcome is not successful.
 */
@Serializable
data class DistributionGridOutcome(
    var status: DistributionGridOutcomeStatus = DistributionGridOutcomeStatus.SUCCESS,
    var returnMode: DistributionGridReturnMode = DistributionGridReturnMode.RETURN_TO_SENDER,
    var taskId: String = "",
    var finalContent: MultimodalContent = MultimodalContent(),
    var completionNotes: String = "",
    var hopCount: Int = 0,
    var finalNodeId: String = "",
    var terminalFailure: DistributionGridFailure? = null
)

/**
 * Structured error or rejection recorded by the grid harness.
 *
 * @param kind Failure category.
 * @param sourceNodeId Node that produced the failure.
 * @param targetNodeId Optional downstream node associated with the failure.
 * @param transportMethod Transport used when the failure occurred.
 * @param transportAddress Transport address used when the failure occurred.
 * @param reason Human-readable failure reason.
 * @param policyCause Optional policy-specific cause when the failure is policy-driven.
 * @param retryable Whether a caller may attempt the same action again.
 */
@Serializable
data class DistributionGridFailure(
    var kind: DistributionGridFailureKind = DistributionGridFailureKind.UNKNOWN,
    var sourceNodeId: String = "",
    var targetNodeId: String = "",
    var transportMethod: Transport = Transport.Tpipe,
    var transportAddress: String = "",
    var reason: String = "",
    var policyCause: String = "",
    var retryable: Boolean = false
)

/**
 * Compact audit record describing one hop in the grid.
 *
 * @param sourceNodeId Source node or sender identity.
 * @param destinationNodeId Destination node identity.
 * @param transportMethod Transport used for the hop.
 * @param transportAddress Transport address used for the hop.
 * @param routerAction Router action taken at this hop.
 * @param privacyDecision Summary of the privacy or redaction stance applied to the hop.
 * @param resultSummary Short result summary for the hop.
 * @param startedAtEpochMillis Start timestamp for the hop.
 * @param completedAtEpochMillis Completion timestamp for the hop.
 */
@Serializable
data class DistributionGridHopRecord(
    var sourceNodeId: String = "",
    var destinationNodeId: String = "",
    var transportMethod: Transport = Transport.Tpipe,
    var transportAddress: String = "",
    var routerAction: DistributionGridDirectiveKind = DistributionGridDirectiveKind.RUN_LOCAL_WORKER,
    var privacyDecision: String = "",
    var resultSummary: String = "",
    var startedAtEpochMillis: Long = 0L,
    var completedAtEpochMillis: Long = 0L
)

/**
 * Policy that controls how the grid returns results and handles retries or alternate peers.
 *
 * @param completionReturnMode Default return mode for successful completion.
 * @param failureReturnMode Default return mode for failures.
 * @param explicitReturnTransport Explicit transport target used when the return mode requires one.
 * @param returnAfterFirstLocalWork Whether control should return to the sender after one local worker step.
 * @param allowRetrySamePeer Whether the router may retry the same peer after a retryable failure.
 * @param maxRetryCount Maximum number of same-peer retries.
 * @param allowAlternatePeers Whether the router may fall back to alternate peers.
 * @param maxHopCount Maximum number of hops allowed for the task.
 * @param allowRemotePcpForwarding Whether PCP tooling may flow through to remote peers on non-stdio transports.
 */
@Serializable
data class DistributionGridRoutingPolicy(
    var completionReturnMode: DistributionGridReturnMode = DistributionGridReturnMode.RETURN_TO_SENDER,
    var failureReturnMode: DistributionGridReturnMode = DistributionGridReturnMode.RETURN_FAILURE_UPSTREAM,
    var explicitReturnTransport: P2PTransport? = null,
    var returnAfterFirstLocalWork: Boolean = false,
    var allowRetrySamePeer: Boolean = false,
    var maxRetryCount: Int = 0,
    var allowAlternatePeers: Boolean = true,
    var maxHopCount: Int = 16,
    var allowRemotePcpForwarding: Boolean = false
)

/**
 * Requester-controlled tracing and privacy policy.
 *
 * @param allowTracing Whether tracing is allowed at all for this task.
 * @param allowTracePersistence Whether trace output may be persisted or exported.
 * @param requireRedaction Whether outbound trace content must be redacted.
 * @param rejectNonCompliantNodes Whether nodes that cannot honor the policy should be rejected.
 * @param allowedStorageClasses Named storage classes explicitly allowed for trace or task persistence.
 * @param disallowedStorageClasses Named storage classes explicitly disallowed for trace or task persistence.
 */
@Serializable
data class DistributionGridTracePolicy(
    var allowTracing: Boolean = true,
    var allowTracePersistence: Boolean = true,
    var requireRedaction: Boolean = false,
    var rejectNonCompliantNodes: Boolean = true,
    var allowedStorageClasses: MutableList<String> = mutableListOf(),
    var disallowedStorageClasses: MutableList<String> = mutableListOf()
)

/**
 * Policy controlling how credentials may be referenced or forwarded between nodes.
 *
 * @param forwardSecrets Whether raw secrets may be forwarded directly.
 * @param credentialRefs Stable credential references allowed for the task.
 * @param perHopCredentialRefs Per-node credential reference overrides.
 * @param allowCredentialTransforms Whether policy-based credential transforms are allowed.
 * @param denySecretPropagation Whether raw secret propagation is denied by default.
 */
@Serializable
data class DistributionGridCredentialPolicy(
    var forwardSecrets: Boolean = false,
    var credentialRefs: MutableList<String> = mutableListOf(),
    var perHopCredentialRefs: MutableMap<String, String> = mutableMapOf(),
    var allowCredentialTransforms: Boolean = false,
    var denySecretPropagation: Boolean = true
)
