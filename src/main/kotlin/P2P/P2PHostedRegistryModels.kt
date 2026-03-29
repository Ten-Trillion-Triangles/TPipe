package com.TTT.P2P

import com.TTT.Pipeline.DistributionGridNodeAdvertisement
import com.TTT.Pipeline.DistributionGridProtocolVersion
import com.TTT.Pipeline.DistributionGridRegistryAdvertisement
import com.TTT.PipeContextProtocol.Transport
import kotlinx.serialization.Serializable

/**
 * Distinguishes the public listing shapes supported by the hosted registry.
 */
@Serializable
enum class P2PHostedListingKind
{
    AGENT,
    GRID_NODE,
    GRID_REGISTRY
}

/**
 * Visibility state for one hosted registry listing.
 */
@Serializable
enum class P2PHostedListingVisibility
{
    PUBLIC,
    PRIVATE
}

/**
 * Lightweight moderation state for one hosted registry listing.
 */
@Serializable
enum class P2PHostedModerationState
{
    ACTIVE,
    PENDING,
    HIDDEN
}

/**
 * Supported sort modes for hosted-registry search.
 */
@Serializable
enum class P2PHostedRegistrySortMode
{
    RELEVANCE,
    UPDATED_AT,
    CREATED_AT,
    TITLE
}

/**
 * Versioned hosted-registry RPC operations carried over the normal P2P request path.
 */
@Serializable
enum class P2PHostedRegistryRpcType
{
    REGISTRY_INFO,
    SEARCH_LISTINGS,
    GET_LISTING,
    PUBLISH_LISTING,
    UPDATE_LISTING,
    RENEW_LISTING,
    REMOVE_LISTING,
    MODERATE_LISTING,
    LIST_AUDIT_LOG
}

/**
 * Auditable hosted-registry actions.
 */
@Serializable
enum class P2PHostedRegistryAuditAction
{
    PUBLISH,
    UPDATE,
    RENEW,
    REMOVE,
    MODERATE,
    EXPIRE
}

/**
 * Lease issued for one hosted public listing.
 */
@Serializable
data class P2PHostedListingLease(
    var listingId: String = "",
    var leaseId: String = "",
    var ownerRef: String = "",
    var expiresAtEpochMillis: Long = 0L,
    var renewalRequired: Boolean = true
)

/**
 * Human-facing metadata and search hints for one listing.
 */
@Serializable
data class P2PHostedListingMetadata(
    var title: String = "",
    var summary: String = "",
    var categories: MutableList<String> = mutableListOf(),
    var tags: MutableList<String> = mutableListOf(),
    var visibility: P2PHostedListingVisibility = P2PHostedListingVisibility.PUBLIC,
    var searchableText: String = "",
    var createdAtEpochMillis: Long = 0L,
    var updatedAtEpochMillis: Long = 0L
)

/**
 * One public hosted-registry record. Exactly one payload family should be populated based on [kind].
 */
@Serializable
data class P2PHostedRegistryListing(
    var listingId: String = "",
    var kind: P2PHostedListingKind = P2PHostedListingKind.AGENT,
    var metadata: P2PHostedListingMetadata = P2PHostedListingMetadata(),
    var publicDescriptor: P2PDescriptor? = null,
    var gridNodeAdvertisement: DistributionGridNodeAdvertisement? = null,
    var gridRegistryAdvertisement: DistributionGridRegistryAdvertisement? = null,
    var lease: P2PHostedListingLease? = null,
    var moderationState: P2PHostedModerationState = P2PHostedModerationState.ACTIVE,
    var attestationRef: String = ""
)

/**
 * Structured query used by human callers, PCP tools, and DistributionGrid bootstrap pulls.
 */
@Serializable
data class P2PHostedRegistryQuery(
    var textQuery: String = "",
    var listingKinds: MutableList<P2PHostedListingKind> = mutableListOf(),
    var categories: MutableList<String> = mutableListOf(),
    var tags: MutableList<String> = mutableListOf(),
    var transportMethods: MutableList<Transport> = mutableListOf(),
    var requiresAuth: Boolean? = null,
    var contextProtocols: MutableList<ContextProtocol> = mutableListOf(),
    var supportedContentTypes: MutableList<SupportedContentTypes> = mutableListOf(),
    var skillOrCapabilityFilters: MutableList<String> = mutableListOf(),
    var trustDomainIds: MutableList<String> = mutableListOf(),
    var registryIds: MutableList<String> = mutableListOf(),
    var actsAsRegistry: Boolean? = null,
    var verifiedOnly: Boolean = false,
    var healthyOnly: Boolean = false,
    var offset: Int = 0,
    var limit: Int = 25,
    var sortMode: P2PHostedRegistrySortMode = P2PHostedRegistrySortMode.RELEVANCE
)

/**
 * Search result produced by the hosted registry service.
 */
@Serializable
data class P2PHostedRegistryQueryResult(
    var accepted: Boolean = true,
    var rejectionReason: String = "",
    var totalCount: Int = 0,
    var results: MutableList<P2PHostedRegistryListing> = mutableListOf()
)

/**
 * Create-one-listing request.
 */
@Serializable
data class P2PHostedRegistryPublishRequest(
    var listing: P2PHostedRegistryListing = P2PHostedRegistryListing(),
    var requestedLeaseSeconds: Int = 3600,
    var replaceExisting: Boolean = false
)

/**
 * Update-one-listing request.
 */
@Serializable
data class P2PHostedRegistryUpdateRequest(
    var listingId: String = "",
    var leaseId: String = "",
    var listing: P2PHostedRegistryListing = P2PHostedRegistryListing(),
    var requestedLeaseSeconds: Int = 3600
)

/**
 * Renew-one-listing request.
 */
@Serializable
data class P2PHostedRegistryRenewRequest(
    var listingId: String = "",
    var leaseId: String = "",
    var requestedLeaseSeconds: Int = 3600
)

/**
 * Remove-one-listing request.
 */
@Serializable
data class P2PHostedRegistryRemoveRequest(
    var listingId: String = "",
    var leaseId: String = ""
)

/**
 * Lookup-one-listing request.
 */
@Serializable
data class P2PHostedRegistryGetRequest(
    var listingId: String = ""
)

/**
 * Generic mutation result for publish/update/renew/delete operations.
 */
@Serializable
data class P2PHostedRegistryMutationResult(
    var accepted: Boolean = true,
    var rejectionReason: String = "",
    var listing: P2PHostedRegistryListing? = null,
    var lease: P2PHostedListingLease? = null
)

/**
 * Operator moderation request for one listing.
 */
@Serializable
data class P2PHostedRegistryModerateRequest(
    var listingId: String = "",
    var moderationState: P2PHostedModerationState = P2PHostedModerationState.ACTIVE,
    var reason: String = ""
)

/**
 * Audit record for one hosted-registry lifecycle action.
 */
@Serializable
data class P2PHostedRegistryAuditRecord(
    var recordId: String = "",
    var listingId: String = "",
    var listingKind: P2PHostedListingKind = P2PHostedListingKind.AGENT,
    var action: P2PHostedRegistryAuditAction = P2PHostedRegistryAuditAction.PUBLISH,
    var principalRef: String = "",
    var reason: String = "",
    var moderationState: P2PHostedModerationState? = null,
    var occurredAtEpochMillis: Long = 0L
)

/**
 * Query one hosted-registry audit trail.
 */
@Serializable
data class P2PHostedRegistryAuditQuery(
    var listingId: String = "",
    var offset: Int = 0,
    var limit: Int = 100
)

/**
 * Audit query response.
 */
@Serializable
data class P2PHostedRegistryAuditQueryResult(
    var accepted: Boolean = true,
    var rejectionReason: String = "",
    var totalCount: Int = 0,
    var results: MutableList<P2PHostedRegistryAuditRecord> = mutableListOf()
)

/**
 * Exposed service metadata so clients can introspect listing kinds, policy posture, and protocol support.
 */
@Serializable
data class P2PHostedRegistryInfo(
    var registryName: String = "",
    var protocolVersion: DistributionGridProtocolVersion = DistributionGridProtocolVersion(),
    var supportedListingKinds: MutableList<P2PHostedListingKind> = mutableListOf(
        P2PHostedListingKind.AGENT,
        P2PHostedListingKind.GRID_NODE,
        P2PHostedListingKind.GRID_REGISTRY
    ),
    var requireAuthForRead: Boolean = false,
    var requireAuthForWrite: Boolean = true,
    var allowAnonymousPublish: Boolean = false,
    var listingCount: Int = 0,
    var activeListingCount: Int = 0,
    var durableStoreKind: String = "memory",
    var auditEnabled: Boolean = true,
    var operatorManaged: Boolean = false
)

/**
 * Hosted-registry RPC envelope serialized into normal P2P prompt text.
 */
@Serializable
data class P2PHostedRegistryRpcMessage(
    var protocolVersion: DistributionGridProtocolVersion = DistributionGridProtocolVersion(),
    var messageType: P2PHostedRegistryRpcType = P2PHostedRegistryRpcType.REGISTRY_INFO,
    var payloadType: String = "",
    var payloadJson: String = ""
)
