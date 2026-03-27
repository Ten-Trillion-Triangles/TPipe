package com.TTT.Pipeline

import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PTransport
import com.TTT.PipeContextProtocol.Transport
import kotlinx.serialization.Serializable

/**
 * Declares how a registry is deployed.
 */
@Serializable
enum class DistributionGridRegistryMode
{
    DEDICATED,
    MIXED_ROLE
}

/**
 * Declares the supported grid RPC message types.
 */
@Serializable
enum class DistributionGridRpcMessageType
{
    REGISTER_NODE,
    RENEW_LEASE,
    QUERY_REGISTRY,
    PROBE_REGISTRY,
    HANDSHAKE_INIT,
    HANDSHAKE_ACK,
    TASK_HANDOFF,
    TASK_RETURN,
    TASK_FAILURE,
    SESSION_CLOSE,
    SESSION_REJECT
}

/**
 * Semantic protocol version used by the grid handshake and session layer.
 *
 * @param major Major version that must match for compatibility.
 * @param minor Minor version negotiated to the highest mutually supported value.
 * @param patch Optional patch version for diagnostics and finer-grained compatibility tracking.
 */
@Serializable
data class DistributionGridProtocolVersion(
    var major: Int = 1,
    var minor: Int = 0,
    var patch: Int = 0
)
{
    /**
     * Check whether another version is major-version compatible with this one.
     *
     * @param other Version supplied by a peer or registry.
     * @return `true` when the versions share the same major version.
     */
    fun isCompatibleWith(other: DistributionGridProtocolVersion): Boolean
    {
        return major == other.major
    }

    companion object {
        /**
         * Negotiate the highest mutually compatible version shared by two peers.
         *
         * @param localVersions Versions supported by the local node.
         * @param remoteVersions Versions supported by the remote node.
         * @return Highest mutually supported compatible version or `null` when none exists.
         */
        fun negotiateHighestShared(
            localVersions: List<DistributionGridProtocolVersion>,
            remoteVersions: List<DistributionGridProtocolVersion>
        ): DistributionGridProtocolVersion?
        {
            val compatiblePairs = localVersions.flatMap { localVersion ->
                remoteVersions.mapNotNull { remoteVersion ->
                    if(localVersion.major == remoteVersion.major)
                    {
                        Pair(localVersion, remoteVersion)
                    }

                    else
                    {
                        null
                    }
                }
            }

            val sharedMinorPair = compatiblePairs.maxWithOrNull(
                compareBy<Pair<DistributionGridProtocolVersion, DistributionGridProtocolVersion>> { it.first.major }
                    .thenBy { minOf(it.first.minor, it.second.minor) }
                    .thenBy { minOf(it.first.patch, it.second.patch) }
            ) ?: return null

            return DistributionGridProtocolVersion(
                major = sharedMinorPair.first.major,
                minor = minOf(sharedMinorPair.first.minor, sharedMinorPair.second.minor),
                patch = minOf(sharedMinorPair.first.patch, sharedMinorPair.second.patch)
            )
        }
    }
}

/**
 * Explicit metadata block that marks a P2P descriptor as a grid node.
 *
 * @param nodeId Stable node identifier.
 * @param supportedProtocolVersions Versions the node can negotiate.
 * @param roleCapabilities Human-readable router and worker capability labels.
 * @param registryMemberships Registry identifiers this node is registered with.
 * @param supportedTransports Transports the node accepts for grid traffic.
 * @param requiresHandshake Whether first contact requires a handshake.
 * @param defaultTracePolicy Default trace and privacy stance for the node.
 * @param defaultRoutingPolicy Default routing policy range the node can honor.
 * @param actsAsRegistry Whether the node also exposes registry behavior.
 */
@Serializable
data class DistributionGridNodeMetadata(
    var nodeId: String = "",
    var supportedProtocolVersions: MutableList<DistributionGridProtocolVersion> = mutableListOf(DistributionGridProtocolVersion()),
    var roleCapabilities: MutableList<String> = mutableListOf(),
    var registryMemberships: MutableList<String> = mutableListOf(),
    var supportedTransports: MutableList<Transport> = mutableListOf(Transport.Tpipe),
    var requiresHandshake: Boolean = true,
    var defaultTracePolicy: DistributionGridTracePolicy = DistributionGridTracePolicy(),
    var defaultRoutingPolicy: DistributionGridRoutingPolicy = DistributionGridRoutingPolicy(),
    var actsAsRegistry: Boolean = false
)

/**
 * Explicit metadata block for a registry endpoint.
 *
 * @param registryId Stable registry identifier.
 * @param trustDomainId Trust domain that governs the registry.
 * @param bootstrapTrusted Whether this registry is itself a configured trust anchor.
 * @param leaseRequired Whether node membership is lease-based.
 * @param defaultLeaseSeconds Default lease duration granted by the registry.
 * @param supportedProtocolVersions Versions supported by the registry.
 * @param mode Deployment mode for the registry endpoint.
 */
@Serializable
data class DistributionGridRegistryMetadata(
    var registryId: String = "",
    var trustDomainId: String = "",
    var bootstrapTrusted: Boolean = false,
    var leaseRequired: Boolean = true,
    var defaultLeaseSeconds: Int = 3600,
    var supportedProtocolVersions: MutableList<DistributionGridProtocolVersion> = mutableListOf(DistributionGridProtocolVersion()),
    var mode: DistributionGridRegistryMode = DistributionGridRegistryMode.DEDICATED
)

/**
 * Registry-returned advertisement describing one candidate grid node.
 *
 * @param descriptor Node descriptor returned by the registry.
 * @param metadata Explicit grid metadata for the node.
 * @param registryId Registry that surfaced the node.
 * @param lease Lease information currently associated with the node.
 * @param attestationRef Optional attestation or signature reference.
 * @param discoveredAtEpochMillis Timestamp when the advertisement was obtained.
 * @param expiresAtEpochMillis Timestamp when the advertisement should be treated as stale.
 */
@Serializable
data class DistributionGridNodeAdvertisement(
    var descriptor: P2PDescriptor? = null,
    var metadata: DistributionGridNodeMetadata = DistributionGridNodeMetadata(),
    var registryId: String = "",
    var lease: DistributionGridRegistrationLease? = null,
    var attestationRef: String = "",
    var discoveredAtEpochMillis: Long = 0L,
    var expiresAtEpochMillis: Long = 0L
)

/**
 * Advertisement describing one registry learned through a trusted discovery chain.
 *
 * @param transport Access path for the registry.
 * @param metadata Explicit registry metadata.
 * @param attestationRef Optional attestation or trust-chain reference.
 * @param discoveredAtEpochMillis Timestamp when the advertisement was obtained.
 * @param expiresAtEpochMillis Timestamp when the advertisement should be treated as stale.
 */
@Serializable
data class DistributionGridRegistryAdvertisement(
    var transport: P2PTransport = P2PTransport(),
    var metadata: DistributionGridRegistryMetadata = DistributionGridRegistryMetadata(),
    var attestationRef: String = "",
    var discoveredAtEpochMillis: Long = 0L,
    var expiresAtEpochMillis: Long = 0L
)

/**
 * Node-to-registry registration payload.
 *
 * @param leaseId Existing lease identifier when the request is renewing an active membership.
 * @param descriptor Public descriptor the node wants the registry to advertise.
 * @param metadata Explicit grid metadata for the node.
 * @param requestedLeaseSeconds Requested lease duration.
 * @param healthSummary Optional health or readiness summary.
 * @param restateCapabilities Whether the registration refreshes capabilities as well as presence.
 */
@Serializable
data class DistributionGridRegistrationRequest(
    var leaseId: String = "",
    var descriptor: P2PDescriptor? = null,
    var metadata: DistributionGridNodeMetadata = DistributionGridNodeMetadata(),
    var requestedLeaseSeconds: Int = 3600,
    var healthSummary: String = "",
    var restateCapabilities: Boolean = true
)

/**
 * Lease record returned by a registry for an active node registration.
 *
 * @param leaseId Stable lease identifier.
 * @param nodeId Registered node identifier.
 * @param registryId Registry identifier that granted the lease.
 * @param grantedLeaseSeconds Granted lease duration.
 * @param expiresAtEpochMillis Lease expiry timestamp.
 * @param renewalRequired Whether the node must actively renew before expiry.
 */
@Serializable
data class DistributionGridRegistrationLease(
    var leaseId: String = "",
    var nodeId: String = "",
    var registryId: String = "",
    var grantedLeaseSeconds: Int = 0,
    var expiresAtEpochMillis: Long = 0L,
    var renewalRequired: Boolean = true
)

/**
 * Structured router query used to ask a registry for eligible downstream nodes.
 *
 * @param requiredCapabilities Capabilities that candidate nodes must expose.
 * @param acceptedTransports Transports the router is willing to use.
 * @param registryIds Optional registry filter.
 * @param trustDomainIds Optional trust-domain filter.
 * @param requireHealthy Whether only healthy nodes should be returned.
 * @param freshnessWindowSeconds Freshness window for candidate advertisements.
 * @param policyCompatibilityNotes Optional free-form notes about policy constraints.
 */
@Serializable
data class DistributionGridRegistryQuery(
    var requiredCapabilities: MutableList<String> = mutableListOf(),
    var acceptedTransports: MutableList<Transport> = mutableListOf(),
    var registryIds: MutableList<String> = mutableListOf(),
    var trustDomainIds: MutableList<String> = mutableListOf(),
    var requireHealthy: Boolean = true,
    var freshnessWindowSeconds: Int = 0,
    var policyCompatibilityNotes: MutableList<String> = mutableListOf()
)

/**
 * Registry response containing matched node advertisements.
 *
 * @param registryId Registry that evaluated the query.
 * @param accepted Whether the query was accepted.
 * @param rejectionReason Optional rejection reason when the query is denied.
 * @param candidates Candidate node advertisements that matched the query.
 */
@Serializable
data class DistributionGridRegistryQueryResult(
    var registryId: String = "",
    var accepted: Boolean = true,
    var rejectionReason: String = "",
    var candidates: MutableList<DistributionGridNodeAdvertisement> = mutableListOf()
)

/**
 * Verifies registry and node advertisements before they are admitted to discovery state.
 */
interface DistributionGridTrustVerifier
{
    /**
     * Validate one registry advertisement against the currently trusted parent set.
     *
     * @param candidate Registry advertisement under consideration.
     * @param trustedParents Registry advertisements already trusted as parents or roots.
     * @return Rejection failure, or `null` when the candidate is trusted.
     */
    suspend fun verifyRegistryAdvertisement(
        candidate: DistributionGridRegistryAdvertisement,
        trustedParents: List<DistributionGridRegistryAdvertisement>
    ): DistributionGridFailure?

    /**
     * Validate one node advertisement against the trusted registry that surfaced it.
     *
     * @param registryAdvertisement Trusted registry advertisement that returned the node.
     * @param candidate Node advertisement under consideration.
     * @return Rejection failure, or `null` when the candidate is trusted.
     */
    suspend fun verifyNodeAdvertisement(
        registryAdvertisement: DistributionGridRegistryAdvertisement,
        candidate: DistributionGridNodeAdvertisement
    ): DistributionGridFailure?
}

/**
 * Mandatory first-contact node handshake payload.
 *
 * @param requesterNodeId Requesting node identifier.
 * @param requesterMetadata Explicit grid metadata for the requester.
 * @param registryId Registry relationship being used for the session.
 * @param supportedProtocolVersions Protocol versions the requester can negotiate.
 * @param acceptedTransports Transports the requester is willing to use.
 * @param tracePolicy Requested trace and privacy constraints.
 * @param credentialPolicy Requested credential-routing constraints.
 * @param acceptedRoutingPolicy Requested routing-policy range.
 * @param requestedSessionDurationSeconds Requested session lifetime.
 */
@Serializable
data class DistributionGridHandshakeRequest(
    var requesterNodeId: String = "",
    var requesterMetadata: DistributionGridNodeMetadata = DistributionGridNodeMetadata(),
    var registryId: String = "",
    var supportedProtocolVersions: MutableList<DistributionGridProtocolVersion> = mutableListOf(DistributionGridProtocolVersion()),
    var acceptedTransports: MutableList<Transport> = mutableListOf(Transport.Tpipe),
    var tracePolicy: DistributionGridTracePolicy = DistributionGridTracePolicy(),
    var credentialPolicy: DistributionGridCredentialPolicy = DistributionGridCredentialPolicy(),
    var acceptedRoutingPolicy: DistributionGridRoutingPolicy = DistributionGridRoutingPolicy(),
    var requestedSessionDurationSeconds: Int = 3600
)

/**
 * Handshake response returned by a grid peer.
 *
 * @param accepted Whether the handshake was accepted.
 * @param negotiatedProtocolVersion Negotiated protocol version when accepted.
 * @param negotiatedPolicy Negotiated policy intersection when accepted.
 * @param rejectionReason Optional rejection reason when denied.
 * @param sessionRecord Session record created by the successful handshake.
 */
@Serializable
data class DistributionGridHandshakeResponse(
    var accepted: Boolean = false,
    var negotiatedProtocolVersion: DistributionGridProtocolVersion? = null,
    var negotiatedPolicy: DistributionGridNegotiatedPolicy? = null,
    var rejectionReason: String = "",
    var sessionRecord: DistributionGridSessionRecord? = null
)

/**
 * Negotiated policy intersection produced by a successful handshake.
 *
 * @param protocolVersion Negotiated protocol version for the session.
 * @param tracePolicy Effective trace and privacy policy for the session.
 * @param credentialPolicy Effective credential-routing policy for the session.
 * @param routingPolicy Effective routing-policy range for the session.
 * @param storageClasses Allowed storage classes after negotiation.
 */
@Serializable
data class DistributionGridNegotiatedPolicy(
    var protocolVersion: DistributionGridProtocolVersion = DistributionGridProtocolVersion(),
    var tracePolicy: DistributionGridTracePolicy = DistributionGridTracePolicy(),
    var credentialPolicy: DistributionGridCredentialPolicy = DistributionGridCredentialPolicy(),
    var routingPolicy: DistributionGridRoutingPolicy = DistributionGridRoutingPolicy(),
    var storageClasses: MutableList<String> = mutableListOf()
)

/**
 * Cached trust and compatibility record created for a successful node-to-node session.
 *
 * @param sessionId Stable session identifier.
 * @param requesterNodeId Requesting node identifier.
 * @param responderNodeId Responding node identifier.
 * @param registryId Registry relationship used for the session.
 * @param negotiatedProtocolVersion Negotiated protocol version.
 * @param negotiatedPolicy Negotiated policy intersection.
 * @param expiresAtEpochMillis Session expiry timestamp.
 * @param invalidated Whether the session has been invalidated.
 * @param invalidationReason Optional invalidation reason.
 */
@Serializable
data class DistributionGridSessionRecord(
    var sessionId: String = "",
    var requesterNodeId: String = "",
    var responderNodeId: String = "",
    var registryId: String = "",
    var negotiatedProtocolVersion: DistributionGridProtocolVersion = DistributionGridProtocolVersion(),
    var negotiatedPolicy: DistributionGridNegotiatedPolicy = DistributionGridNegotiatedPolicy(),
    var expiresAtEpochMillis: Long = 0L,
    var invalidated: Boolean = false,
    var invalidationReason: String = ""
)

/**
 * Lightweight session reference reused in later grid RPC messages.
 *
 * @param sessionId Stable session identifier.
 * @param requesterNodeId Requesting node identifier.
 * @param responderNodeId Responding node identifier.
 * @param expiresAtEpochMillis Session expiry timestamp.
 */
@Serializable
data class DistributionGridSessionRef(
    var sessionId: String = "",
    var requesterNodeId: String = "",
    var responderNodeId: String = "",
    var registryId: String = "",
    var expiresAtEpochMillis: Long = 0L
)

/**
 * Versioned grid RPC message carried through normal P2P request and response flows.
 *
 * @param messageType RPC message type.
 * @param senderNodeId Sending node identifier.
 * @param targetId Target node or registry identifier.
 * @param protocolVersion Protocol version for the message.
 * @param sessionRef Optional existing session reference.
 * @param payloadType Human-readable payload type label.
 * @param payloadJson Serialized payload body.
 */
@Serializable
data class DistributionGridRpcMessage(
    var messageType: DistributionGridRpcMessageType = DistributionGridRpcMessageType.HANDSHAKE_INIT,
    var senderNodeId: String = "",
    var targetId: String = "",
    var protocolVersion: DistributionGridProtocolVersion = DistributionGridProtocolVersion(),
    var sessionRef: DistributionGridSessionRef? = null,
    var payloadType: String = "",
    var payloadJson: String = ""
)
