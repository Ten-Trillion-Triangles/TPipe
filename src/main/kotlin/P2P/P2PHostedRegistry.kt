package com.TTT.P2P

import com.TTT.Config.AuthRegistry
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.DistributionGridNodeAdvertisement
import com.TTT.Pipeline.DistributionGridProtocolVersion
import com.TTT.Pipeline.DistributionGridRegistryAdvertisement
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.deepCopy
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val HOSTED_REGISTRY_INFO_PAYLOAD = "P2PHostedRegistryInfo"
private const val HOSTED_REGISTRY_STATUS_PAYLOAD = "P2PHostedRegistryStatus"
private const val HOSTED_REGISTRY_QUERY_PAYLOAD = "P2PHostedRegistryQuery"
private const val HOSTED_REGISTRY_QUERY_RESULT_PAYLOAD = "P2PHostedRegistryQueryResult"
private const val HOSTED_REGISTRY_FACET_PAYLOAD = "P2PHostedRegistryFacetRequest"
private const val HOSTED_REGISTRY_FACET_RESULT_PAYLOAD = "P2PHostedRegistryFacetResult"
private const val HOSTED_REGISTRY_GET_PAYLOAD = "P2PHostedRegistryGetRequest"
private const val HOSTED_REGISTRY_LISTING_PAYLOAD = "P2PHostedRegistryListing"
private const val HOSTED_REGISTRY_PUBLISH_PAYLOAD = "P2PHostedRegistryPublishRequest"
private const val HOSTED_REGISTRY_UPDATE_PAYLOAD = "P2PHostedRegistryUpdateRequest"
private const val HOSTED_REGISTRY_RENEW_PAYLOAD = "P2PHostedRegistryRenewRequest"
private const val HOSTED_REGISTRY_REMOVE_PAYLOAD = "P2PHostedRegistryRemoveRequest"
private const val HOSTED_REGISTRY_MODERATE_PAYLOAD = "P2PHostedRegistryModerateRequest"
private const val HOSTED_REGISTRY_AUDIT_QUERY_PAYLOAD = "P2PHostedRegistryAuditQuery"
private const val HOSTED_REGISTRY_AUDIT_RESULT_PAYLOAD = "P2PHostedRegistryAuditQueryResult"
private const val HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD = "P2PHostedRegistryMutationResult"

/**
 * Request context provided to hosted-registry policy hooks.
 */
data class P2PHostedRegistryAccessContext(
    val request: P2PRequest,
    val principalRef: String,
    val authBody: String,
    val transportAuthBody: String
)

/**
 * Operator-controlled hosted-registry policy surface.
 */
interface P2PHostedRegistryPolicy
{
    suspend fun canRead(
        context: P2PHostedRegistryAccessContext,
        query: P2PHostedRegistryQuery? = null
    ): Boolean

    suspend fun canPublish(
        context: P2PHostedRegistryAccessContext,
        listing: P2PHostedRegistryListing,
        existingListing: P2PHostedRegistryListing?
    ): Boolean

    suspend fun canMutate(
        context: P2PHostedRegistryAccessContext,
        listing: P2PHostedRegistryListing
    ): Boolean

    suspend fun canModerate(
        context: P2PHostedRegistryAccessContext,
        listing: P2PHostedRegistryListing
    ): Boolean

    suspend fun canReadAudit(
        context: P2PHostedRegistryAccessContext,
        listingId: String
    ): Boolean

    fun resolveOwnerRef(context: P2PHostedRegistryAccessContext): String

    fun sanitizeListing(listing: P2PHostedRegistryListing): P2PHostedRegistryListing

    fun info(registryName: String): P2PHostedRegistryInfo
}

/**
 * Simple configurable default policy for the hosted registry.
 */
data class P2PHostedRegistryPolicySettings(
    var requireAuthForRead: Boolean = false,
    var requireAuthForWrite: Boolean = true,
    var allowAnonymousPublish: Boolean = false,
    var operatorRefs: MutableSet<String> = mutableSetOf(),
    var allowedKinds: MutableSet<P2PHostedListingKind> = mutableSetOf(
        P2PHostedListingKind.AGENT,
        P2PHostedListingKind.GRID_NODE,
        P2PHostedListingKind.GRID_REGISTRY
    )
)

/**
 * Default hosted-registry policy used when callers do not install their own host rule set.
 */
class DefaultP2PHostedRegistryPolicy(
    private val settings: P2PHostedRegistryPolicySettings = P2PHostedRegistryPolicySettings()
) : P2PHostedRegistryPolicy
{
    private fun isOperator(principalRef: String): Boolean
    {
        return principalRef.isNotBlank() && principalRef in settings.operatorRefs
    }

    override suspend fun canRead(
        context: P2PHostedRegistryAccessContext,
        query: P2PHostedRegistryQuery?
    ): Boolean
    {
        if(!settings.requireAuthForRead)
        {
            return true
        }
        return context.principalRef.isNotBlank()
    }

    override suspend fun canPublish(
        context: P2PHostedRegistryAccessContext,
        listing: P2PHostedRegistryListing,
        existingListing: P2PHostedRegistryListing?
    ): Boolean
    {
        if(listing.kind !in settings.allowedKinds)
        {
            return false
        }

        if(settings.requireAuthForWrite && context.principalRef.isBlank())
        {
            return false
        }

        if(context.principalRef.isBlank() && !settings.allowAnonymousPublish)
        {
            return false
        }

        if(existingListing == null)
        {
            return true
        }

        return canMutate(context, existingListing)
    }

    override suspend fun canMutate(
        context: P2PHostedRegistryAccessContext,
        listing: P2PHostedRegistryListing
    ): Boolean
    {
        if(isOperator(context.principalRef))
        {
            return true
        }

        val ownerRef = listing.lease?.ownerRef.orEmpty()
        if(ownerRef.isNotBlank())
        {
            return ownerRef == context.principalRef
        }

        return !settings.requireAuthForWrite || context.principalRef.isNotBlank()
    }

    override suspend fun canModerate(
        context: P2PHostedRegistryAccessContext,
        listing: P2PHostedRegistryListing
    ): Boolean
    {
        return isOperator(context.principalRef)
    }

    override suspend fun canReadAudit(
        context: P2PHostedRegistryAccessContext,
        listingId: String
    ): Boolean
    {
        return isOperator(context.principalRef)
    }

    override fun resolveOwnerRef(context: P2PHostedRegistryAccessContext): String
    {
        return context.principalRef
    }

    override fun sanitizeListing(listing: P2PHostedRegistryListing): P2PHostedRegistryListing
    {
        val sanitized = listing.deepCopy<P2PHostedRegistryListing>()
        sanitized.publicDescriptor = sanitizeDescriptor(sanitized.publicDescriptor)
        sanitized.gridNodeAdvertisement = sanitizeGridNodeAdvertisement(sanitized.gridNodeAdvertisement)
        sanitized.gridRegistryAdvertisement = sanitizeGridRegistryAdvertisement(sanitized.gridRegistryAdvertisement)
        if(sanitized.attestationRef.isBlank())
        {
            sanitized.attestationRef = sanitized.gridNodeAdvertisement?.attestationRef
                ?: sanitized.gridRegistryAdvertisement?.attestationRef
                ?: ""
        }
        return sanitized
    }

    override fun info(registryName: String): P2PHostedRegistryInfo
    {
        return P2PHostedRegistryInfo(
            registryName = registryName,
            protocolVersion = DistributionGridProtocolVersion(1, 0, 0),
            requireAuthForRead = settings.requireAuthForRead,
            requireAuthForWrite = settings.requireAuthForWrite,
            allowAnonymousPublish = settings.allowAnonymousPublish,
            supportedListingKinds = settings.allowedKinds.toMutableList(),
            operatorManaged = settings.operatorRefs.isNotEmpty()
        )
    }

    private fun sanitizeDescriptor(descriptor: P2PDescriptor?): P2PDescriptor?
    {
        val sanitized = descriptor?.deepCopy<P2PDescriptor>() ?: return null
        sanitized.transport = sanitized.transport.copy(transportAuthBody = "")
        sanitized.requestTemplate = sanitizeRequestTemplate(sanitized.requestTemplate)
        return sanitized
    }

    private fun sanitizeGridNodeAdvertisement(
        advertisement: DistributionGridNodeAdvertisement?
    ): DistributionGridNodeAdvertisement?
    {
        val sanitized = advertisement?.deepCopy<DistributionGridNodeAdvertisement>() ?: return null
        sanitized.descriptor = sanitizeDescriptor(sanitized.descriptor)
        return sanitized
    }

    private fun sanitizeGridRegistryAdvertisement(
        advertisement: DistributionGridRegistryAdvertisement?
    ): DistributionGridRegistryAdvertisement?
    {
        val sanitized = advertisement?.deepCopy<DistributionGridRegistryAdvertisement>() ?: return null
        sanitized.transport = sanitized.transport.copy(transportAuthBody = "")
        return sanitized
    }

    private fun sanitizeRequestTemplate(template: P2PRequest?): P2PRequest?
    {
        val sanitized = template?.deepCopy<P2PRequest>() ?: return null
        sanitized.authBody = ""
        sanitized.transport = sanitized.transport.copy(transportAuthBody = "")
        return sanitized
    }
}

/**
 * Storage contract for hosted public listings.
 */
interface P2PHostedRegistryStore
{
    suspend fun upsert(listing: P2PHostedRegistryListing): P2PHostedRegistryListing
    suspend fun getListing(listingId: String): P2PHostedRegistryListing?
    suspend fun removeListing(listingId: String): Boolean
    suspend fun listListings(): List<P2PHostedRegistryListing>
    suspend fun appendAuditRecord(record: P2PHostedRegistryAuditRecord)
    suspend fun listAuditRecords(): List<P2PHostedRegistryAuditRecord>
    fun describeStoreKind(): String
}

/**
 * Simple in-memory store for hosted public listings.
 */
class InMemoryP2PHostedRegistryStore : P2PHostedRegistryStore
{
    private val mutex = Mutex()
    private val listingsById = linkedMapOf<String, P2PHostedRegistryListing>()
    private val auditRecords = mutableListOf<P2PHostedRegistryAuditRecord>()

    override suspend fun upsert(listing: P2PHostedRegistryListing): P2PHostedRegistryListing
    {
        return mutex.withLock {
            val stored = listing.deepCopy<P2PHostedRegistryListing>()
            listingsById[stored.listingId] = stored
            stored.deepCopy()
        }
    }

    override suspend fun getListing(listingId: String): P2PHostedRegistryListing?
    {
        return mutex.withLock {
            listingsById[listingId]?.deepCopy()
        }
    }

    override suspend fun removeListing(listingId: String): Boolean
    {
        return mutex.withLock {
            listingsById.remove(listingId) != null
        }
    }

    override suspend fun listListings(): List<P2PHostedRegistryListing>
    {
        return mutex.withLock {
            listingsById.values.map { it.deepCopy<P2PHostedRegistryListing>() }
        }
    }

    override suspend fun appendAuditRecord(record: P2PHostedRegistryAuditRecord)
    {
        mutex.withLock {
            auditRecords.add(record.deepCopy())
        }
    }

    override suspend fun listAuditRecords(): List<P2PHostedRegistryAuditRecord>
    {
        return mutex.withLock {
            auditRecords.map { it.deepCopy<P2PHostedRegistryAuditRecord>() }
        }
    }

    override fun describeStoreKind(): String = "memory"
}

@kotlinx.serialization.Serializable
private data class P2PHostedRegistryFileSnapshot(
    var listings: MutableList<P2PHostedRegistryListing> = mutableListOf(),
    var auditRecords: MutableList<P2PHostedRegistryAuditRecord> = mutableListOf()
)

/**
 * Simple durable file-backed hosted-registry store.
 */
class FileBackedP2PHostedRegistryStore(
    private val filePath: String
) : P2PHostedRegistryStore
{
    private val mutex = Mutex()

    override suspend fun upsert(listing: P2PHostedRegistryListing): P2PHostedRegistryListing
    {
        return mutex.withLock {
            val snapshot = readSnapshotLocked()
            val stored = listing.deepCopy<P2PHostedRegistryListing>()
            val existingIndex = snapshot.listings.indexOfFirst { it.listingId == stored.listingId }
            if(existingIndex >= 0)
            {
                snapshot.listings[existingIndex] = stored
            }
            else
            {
                snapshot.listings.add(stored)
            }
            writeSnapshotLocked(snapshot)
            stored.deepCopy()
        }
    }

    override suspend fun getListing(listingId: String): P2PHostedRegistryListing?
    {
        return mutex.withLock {
            readSnapshotLocked().listings.firstOrNull { it.listingId == listingId }?.deepCopy()
        }
    }

    override suspend fun removeListing(listingId: String): Boolean
    {
        return mutex.withLock {
            val snapshot = readSnapshotLocked()
            val removed = snapshot.listings.removeIf { it.listingId == listingId }
            if(removed)
            {
                writeSnapshotLocked(snapshot)
            }
            removed
        }
    }

    override suspend fun listListings(): List<P2PHostedRegistryListing>
    {
        return mutex.withLock {
            readSnapshotLocked().listings.map { it.deepCopy<P2PHostedRegistryListing>() }
        }
    }

    override suspend fun appendAuditRecord(record: P2PHostedRegistryAuditRecord)
    {
        mutex.withLock {
            val snapshot = readSnapshotLocked()
            snapshot.auditRecords.add(record.deepCopy())
            writeSnapshotLocked(snapshot)
        }
    }

    override suspend fun listAuditRecords(): List<P2PHostedRegistryAuditRecord>
    {
        return mutex.withLock {
            readSnapshotLocked().auditRecords.map { it.deepCopy<P2PHostedRegistryAuditRecord>() }
        }
    }

    override fun describeStoreKind(): String = "file-json"

    private fun readSnapshotLocked(): P2PHostedRegistryFileSnapshot
    {
        val path = java.nio.file.Paths.get(filePath)
        if(!java.nio.file.Files.exists(path))
        {
            return P2PHostedRegistryFileSnapshot()
        }

        val content = java.nio.file.Files.readString(path)
        if(content.isBlank())
        {
            return P2PHostedRegistryFileSnapshot()
        }

        return deserialize<P2PHostedRegistryFileSnapshot>(content, useRepair = false)
            ?: P2PHostedRegistryFileSnapshot()
    }

    private fun writeSnapshotLocked(snapshot: P2PHostedRegistryFileSnapshot)
    {
        val path = java.nio.file.Paths.get(filePath)
        java.nio.file.Files.createDirectories(path.parent ?: path.toAbsolutePath().parent)
        val tempPath = java.nio.file.Paths.get("$filePath.tmp")
        java.nio.file.Files.writeString(
            tempPath,
            serialize(snapshot),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
            java.nio.file.StandardOpenOption.WRITE
        )
        java.nio.file.Files.move(
            tempPath,
            path,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
    }
}

/**
 * Hosted registry service exposed over the existing P2P request/response path.
 */
class P2PHostedRegistry(
    private val registryName: String,
    transport: P2PTransport,
    private val store: P2PHostedRegistryStore = InMemoryP2PHostedRegistryStore(),
    private val policy: P2PHostedRegistryPolicy = DefaultP2PHostedRegistryPolicy()
) : P2PInterface
{
    private var lastExpirySweepEpochMillis: Long = 0L
    private var descriptor = P2PDescriptor(
        agentName = registryName,
        agentDescription = "Hosted P2P registry service for public agent and DistributionGrid listings.",
        transport = transport.copy(transportAuthBody = ""),
        requiresAuth = false,
        usesConverse = false,
        allowsAgentDuplication = false,
        allowsCustomContext = false,
        allowsCustomAgentJson = false,
        recordsInteractionContext = false,
        recordsPromptContent = false,
        allowsExternalContext = false,
        contextProtocol = ContextProtocol.none
    )
    private var requirements = P2PRequirements(
        allowExternalConnections = true,
        allowAgentDuplication = false,
        allowCustomContext = false,
        allowCustomJson = false
    )

    override fun setP2pDescription(description: P2PDescriptor)
    {
        descriptor = description.deepCopy()
    }

    override fun getP2pDescription(): P2PDescriptor
    {
        return descriptor.deepCopy()
    }

    override fun setP2pTransport(transport: P2PTransport)
    {
        descriptor = descriptor.deepCopy().apply {
            this.transport = transport.copy(transportAuthBody = "")
        }
    }

    override fun getP2pTransport(): P2PTransport
    {
        return descriptor.transport.copy()
    }

    override fun setP2pRequirements(requirements: P2PRequirements)
    {
        this.requirements = requirements.copy()
    }

    override fun getP2pRequirements(): P2PRequirements
    {
        return requirements.copy()
    }

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
    {
        val rpcMessage = deserialize<P2PHostedRegistryRpcMessage>(
            request.prompt.text,
            useRepair = false
        ) ?: return P2PResponse(
            rejection = P2PRejection(
                errorType = P2PError.json,
                reason = "Failed to deserialize hosted-registry RPC message."
            )
        )

        val context = P2PHostedRegistryAccessContext(
            request = request,
            principalRef = request.authBody
                .ifBlank { request.transport.transportAuthBody }
                .trim(),
            authBody = request.authBody,
            transportAuthBody = request.transport.transportAuthBody
        )

        return when(rpcMessage.messageType)
        {
            P2PHostedRegistryRpcType.REGISTRY_INFO -> {
                purgeExpiredListings()
                val listings = store.listListings()
                buildRpcResponse(
                    messageType = P2PHostedRegistryRpcType.REGISTRY_INFO,
                    payloadType = HOSTED_REGISTRY_INFO_PAYLOAD,
                    payload = policy.info(registryName).apply {
                        listingCount = listings.size
                        activeListingCount = listings.count { it.moderationState == P2PHostedModerationState.ACTIVE }
                        durableStoreKind = store.describeStoreKind()
                    }
                )
            }
            P2PHostedRegistryRpcType.REGISTRY_STATUS -> {
                purgeExpiredListings()
                buildRpcResponse(
                    messageType = P2PHostedRegistryRpcType.REGISTRY_STATUS,
                    payloadType = HOSTED_REGISTRY_STATUS_PAYLOAD,
                    payload = buildRegistryStatus()
                )
            }

            P2PHostedRegistryRpcType.SEARCH_LISTINGS -> handleSearch(request = request, context = context, rpcMessage = rpcMessage)
            P2PHostedRegistryRpcType.SEARCH_FACETS -> handleFacetSearch(context, rpcMessage)
            P2PHostedRegistryRpcType.GET_LISTING -> handleGet(context, rpcMessage)
            P2PHostedRegistryRpcType.PUBLISH_LISTING -> handlePublish(context, rpcMessage)
            P2PHostedRegistryRpcType.UPDATE_LISTING -> handleUpdate(context, rpcMessage)
            P2PHostedRegistryRpcType.RENEW_LISTING -> handleRenew(context, rpcMessage)
            P2PHostedRegistryRpcType.REMOVE_LISTING -> handleRemove(context, rpcMessage)
            P2PHostedRegistryRpcType.MODERATE_LISTING -> handleModerate(context, rpcMessage)
            P2PHostedRegistryRpcType.LIST_AUDIT_LOG -> handleListAudit(context, rpcMessage)
        }
    }

    private suspend fun handleSearch(
        request: P2PRequest,
        context: P2PHostedRegistryAccessContext,
        rpcMessage: P2PHostedRegistryRpcMessage
    ): P2PResponse
    {
        val query = deserialize<P2PHostedRegistryQuery>(
            rpcMessage.payloadJson,
            useRepair = false
        ) ?: return P2PResponse(
            rejection = P2PRejection(P2PError.json, "Failed to deserialize hosted-registry query.")
        )

        if(!policy.canRead(context, query))
        {
            return buildRpcResponse(
                messageType = P2PHostedRegistryRpcType.SEARCH_LISTINGS,
                payloadType = HOSTED_REGISTRY_QUERY_RESULT_PAYLOAD,
                payload = P2PHostedRegistryQueryResult(
                    accepted = false,
                    rejectionReason = "Hosted registry read access was denied.",
                    totalCount = 0,
                    results = mutableListOf()
                )
            )
        }

        purgeExpiredListings()
        val allListings = store.listListings()
            .filter { it.metadata.visibility == P2PHostedListingVisibility.PUBLIC }
        val matchedListings = allListings
            .filter { matchesQuery(it, query) }
            .sortedWith(buildComparator(query))

        val pagedListings = matchedListings
            .drop(query.offset.coerceAtLeast(0))
            .take(query.limit.coerceAtLeast(1))
            .map { it.deepCopy<P2PHostedRegistryListing>() }
            .toMutableList()

        return buildRpcResponse(
            messageType = P2PHostedRegistryRpcType.SEARCH_LISTINGS,
            payloadType = HOSTED_REGISTRY_QUERY_RESULT_PAYLOAD,
            payload = P2PHostedRegistryQueryResult(
                accepted = true,
                rejectionReason = "",
                totalCount = matchedListings.size,
                hasMore = query.offset.coerceAtLeast(0) + pagedListings.size < matchedListings.size,
                results = pagedListings
            )
        )
    }

    private suspend fun handleFacetSearch(
        context: P2PHostedRegistryAccessContext,
        rpcMessage: P2PHostedRegistryRpcMessage
    ): P2PResponse
    {
        val request = deserialize<P2PHostedRegistryFacetRequest>(
            rpcMessage.payloadJson,
            useRepair = false
        ) ?: return P2PResponse(
            rejection = P2PRejection(P2PError.json, "Failed to deserialize hosted-registry facet request.")
        )

        if(!policy.canRead(context, request.query))
        {
            return buildRpcResponse(
                messageType = P2PHostedRegistryRpcType.SEARCH_FACETS,
                payloadType = HOSTED_REGISTRY_FACET_RESULT_PAYLOAD,
                payload = P2PHostedRegistryFacetResult(
                    accepted = false,
                    rejectionReason = "Hosted registry read access was denied."
                )
            )
        }

        purgeExpiredListings()
        val matched = store.listListings()
            .filter { it.metadata.visibility == P2PHostedListingVisibility.PUBLIC }
            .filter {
                matchesQuery(
                    it,
                    request.query.deepCopy<P2PHostedRegistryQuery>().apply {
                        offset = 0
                        limit = Int.MAX_VALUE
                    }
                )
            }

        return buildRpcResponse(
            messageType = P2PHostedRegistryRpcType.SEARCH_FACETS,
            payloadType = HOSTED_REGISTRY_FACET_RESULT_PAYLOAD,
            payload = P2PHostedRegistryFacetResult(
                accepted = true,
                rejectionReason = "",
                listingKinds = facetBuckets(matched.map { it.kind.name }),
                categories = facetBuckets(matched.flatMap { it.metadata.categories }),
                tags = facetBuckets(matched.flatMap { it.metadata.tags }),
                transportMethods = facetBuckets(matched.flatMap { listing ->
                    when(listing.kind)
                    {
                        P2PHostedListingKind.AGENT -> listOfNotNull(listing.publicDescriptor?.transport?.transportMethod?.name)
                        P2PHostedListingKind.GRID_NODE -> listOfNotNull(listing.gridNodeAdvertisement?.descriptor?.transport?.transportMethod?.name)
                        P2PHostedListingKind.GRID_REGISTRY -> listOfNotNull(listing.gridRegistryAdvertisement?.transport?.transportMethod?.name)
                    }
                }),
                authRequirements = facetBuckets(matched.map { listing ->
                    when(listing.kind)
                    {
                        P2PHostedListingKind.AGENT -> (listing.publicDescriptor?.requiresAuth ?: false).toString()
                        P2PHostedListingKind.GRID_NODE -> (listing.gridNodeAdvertisement?.descriptor?.requiresAuth ?: false).toString()
                        P2PHostedListingKind.GRID_REGISTRY -> "false"
                    }
                }),
                trustDomains = facetBuckets(matched.mapNotNull { listing ->
                    when(listing.kind)
                    {
                        P2PHostedListingKind.AGENT -> null
                        P2PHostedListingKind.GRID_NODE -> listing.gridNodeAdvertisement?.metadata?.registryMemberships?.firstOrNull()
                        P2PHostedListingKind.GRID_REGISTRY -> listing.gridRegistryAdvertisement?.metadata?.trustDomainId
                    }?.takeIf { it.isNotBlank() }
                }),
                moderationStates = facetBuckets(matched.map { it.moderationState.name })
            )
        )
    }

    private suspend fun handleGet(
        context: P2PHostedRegistryAccessContext,
        rpcMessage: P2PHostedRegistryRpcMessage
    ): P2PResponse
    {
        val request = deserialize<P2PHostedRegistryGetRequest>(
            rpcMessage.payloadJson,
            useRepair = false
        ) ?: return P2PResponse(
            rejection = P2PRejection(P2PError.json, "Failed to deserialize hosted-registry get request.")
        )

        if(!policy.canRead(context))
        {
            return buildRpcResponse(
                messageType = P2PHostedRegistryRpcType.GET_LISTING,
                payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
                payload = P2PHostedRegistryMutationResult(
                    accepted = false,
                    rejectionReason = "Hosted registry read access was denied."
                )
            )
        }

        purgeExpiredListings()
        val listing = store.getListing(request.listingId)
        return buildRpcResponse(
            messageType = P2PHostedRegistryRpcType.GET_LISTING,
            payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
            payload = P2PHostedRegistryMutationResult(
                accepted = listing != null,
                rejectionReason = if(listing == null) "Hosted registry listing was not found." else "",
                listing = listing?.deepCopy()
            )
        )
    }

    private suspend fun handlePublish(
        context: P2PHostedRegistryAccessContext,
        rpcMessage: P2PHostedRegistryRpcMessage
    ): P2PResponse
    {
        val request = deserialize<P2PHostedRegistryPublishRequest>(
            rpcMessage.payloadJson,
            useRepair = false
        ) ?: return P2PResponse(
            rejection = P2PRejection(P2PError.json, "Failed to deserialize hosted-registry publish request.")
        )

        val candidate = normalizePublishedListing(
            listing = request.listing,
            existingListing = resolveExistingListingForPublish(request),
            requestedLeaseSeconds = request.requestedLeaseSeconds,
            ownerRef = policy.resolveOwnerRef(context)
        )

        val existingListing = store.getListing(candidate.listingId)
        if(!policy.canPublish(context, candidate, existingListing))
        {
            return buildRpcResponse(
                messageType = P2PHostedRegistryRpcType.PUBLISH_LISTING,
                payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
                payload = P2PHostedRegistryMutationResult(
                    accepted = false,
                    rejectionReason = "Hosted registry publish access was denied."
                )
            )
        }

        val stored = store.upsert(policy.sanitizeListing(candidate))
        appendAuditRecord(
            listing = stored,
            action = P2PHostedRegistryAuditAction.PUBLISH,
            principalRef = context.principalRef,
            reason = ""
        )
        return buildRpcResponse(
            messageType = P2PHostedRegistryRpcType.PUBLISH_LISTING,
            payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
            payload = P2PHostedRegistryMutationResult(
                accepted = true,
                rejectionReason = "",
                listing = stored.deepCopy(),
                lease = stored.lease?.deepCopy()
            )
        )
    }

    private suspend fun handleUpdate(
        context: P2PHostedRegistryAccessContext,
        rpcMessage: P2PHostedRegistryRpcMessage
    ): P2PResponse
    {
        val request = deserialize<P2PHostedRegistryUpdateRequest>(
            rpcMessage.payloadJson,
            useRepair = false
        ) ?: return P2PResponse(
            rejection = P2PRejection(P2PError.json, "Failed to deserialize hosted-registry update request.")
        )

        val existing = store.getListing(request.listingId)
        if(existing == null)
        {
            return buildRpcResponse(
                messageType = P2PHostedRegistryRpcType.UPDATE_LISTING,
                payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
                payload = P2PHostedRegistryMutationResult(
                    accepted = false,
                    rejectionReason = "Hosted registry listing was not found."
                )
            )
        }

        if(existing.lease?.leaseId.orEmpty() != request.leaseId)
        {
            return buildRpcResponse(
                messageType = P2PHostedRegistryRpcType.UPDATE_LISTING,
                payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
                payload = P2PHostedRegistryMutationResult(
                    accepted = false,
                    rejectionReason = "Hosted registry update was denied because the lease id did not match."
                )
            )
        }

        if(!policy.canMutate(context, existing))
        {
            return buildRpcResponse(
                messageType = P2PHostedRegistryRpcType.UPDATE_LISTING,
                payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
                payload = P2PHostedRegistryMutationResult(
                    accepted = false,
                    rejectionReason = "Hosted registry update access was denied."
                )
            )
        }

        val candidate = normalizeUpdatedListing(
            listingId = request.listingId,
            leaseId = request.leaseId,
            listing = request.listing,
            existingListing = existing,
            requestedLeaseSeconds = request.requestedLeaseSeconds
        )
        val stored = store.upsert(policy.sanitizeListing(candidate))
        appendAuditRecord(
            listing = stored,
            action = P2PHostedRegistryAuditAction.UPDATE,
            principalRef = context.principalRef,
            reason = ""
        )
        return buildRpcResponse(
            messageType = P2PHostedRegistryRpcType.UPDATE_LISTING,
            payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
            payload = P2PHostedRegistryMutationResult(
                accepted = true,
                listing = stored.deepCopy(),
                lease = stored.lease?.deepCopy()
            )
        )
    }

    private suspend fun handleRenew(
        context: P2PHostedRegistryAccessContext,
        rpcMessage: P2PHostedRegistryRpcMessage
    ): P2PResponse
    {
        val request = deserialize<P2PHostedRegistryRenewRequest>(
            rpcMessage.payloadJson,
            useRepair = false
        ) ?: return P2PResponse(
            rejection = P2PRejection(P2PError.json, "Failed to deserialize hosted-registry renew request.")
        )

        val existing = store.getListing(request.listingId)
        if(existing == null)
        {
            return buildRpcResponse(
                messageType = P2PHostedRegistryRpcType.RENEW_LISTING,
                payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
                payload = P2PHostedRegistryMutationResult(
                    accepted = false,
                    rejectionReason = "Hosted registry listing was not found."
                )
            )
        }

        if(existing.lease?.leaseId.orEmpty() != request.leaseId || !policy.canMutate(context, existing))
        {
            return buildRpcResponse(
                messageType = P2PHostedRegistryRpcType.RENEW_LISTING,
                payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
                payload = P2PHostedRegistryMutationResult(
                    accepted = false,
                    rejectionReason = "Hosted registry renewal access was denied."
                )
            )
        }

        val renewed = existing.deepCopy<P2PHostedRegistryListing>().apply {
            lease = lease?.deepCopy<P2PHostedListingLease>()?.apply {
                val grantedSeconds = request.requestedLeaseSeconds.coerceAtLeast(1)
                expiresAtEpochMillis = System.currentTimeMillis() + grantedSeconds * 1000L
            }
            metadata.updatedAtEpochMillis = System.currentTimeMillis()
        }
        val stored = store.upsert(renewed)
        appendAuditRecord(
            listing = stored,
            action = P2PHostedRegistryAuditAction.RENEW,
            principalRef = context.principalRef,
            reason = ""
        )
        return buildRpcResponse(
            messageType = P2PHostedRegistryRpcType.RENEW_LISTING,
            payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
            payload = P2PHostedRegistryMutationResult(
                accepted = true,
                listing = stored.deepCopy(),
                lease = stored.lease?.deepCopy()
            )
        )
    }

    private suspend fun handleRemove(
        context: P2PHostedRegistryAccessContext,
        rpcMessage: P2PHostedRegistryRpcMessage
    ): P2PResponse
    {
        val request = deserialize<P2PHostedRegistryRemoveRequest>(
            rpcMessage.payloadJson,
            useRepair = false
        ) ?: return P2PResponse(
            rejection = P2PRejection(P2PError.json, "Failed to deserialize hosted-registry remove request.")
        )

        val existing = store.getListing(request.listingId)
        if(existing == null)
        {
            return buildRpcResponse(
                messageType = P2PHostedRegistryRpcType.REMOVE_LISTING,
                payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
                payload = P2PHostedRegistryMutationResult(
                    accepted = false,
                    rejectionReason = "Hosted registry listing was not found."
                )
            )
        }

        if(existing.lease?.leaseId.orEmpty() != request.leaseId || !policy.canMutate(context, existing))
        {
            return buildRpcResponse(
                messageType = P2PHostedRegistryRpcType.REMOVE_LISTING,
                payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
                payload = P2PHostedRegistryMutationResult(
                    accepted = false,
                    rejectionReason = "Hosted registry delete access was denied."
                )
            )
        }

        store.removeListing(request.listingId)
        appendAuditRecord(
            listing = existing,
            action = P2PHostedRegistryAuditAction.REMOVE,
            principalRef = context.principalRef,
            reason = ""
        )
        return buildRpcResponse(
            messageType = P2PHostedRegistryRpcType.REMOVE_LISTING,
            payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
            payload = P2PHostedRegistryMutationResult(
                accepted = true,
                listing = existing.deepCopy(),
                lease = existing.lease?.deepCopy()
            )
        )
    }

    private suspend fun handleModerate(
        context: P2PHostedRegistryAccessContext,
        rpcMessage: P2PHostedRegistryRpcMessage
    ): P2PResponse
    {
        val request = deserialize<P2PHostedRegistryModerateRequest>(
            rpcMessage.payloadJson,
            useRepair = false
        ) ?: return P2PResponse(
            rejection = P2PRejection(P2PError.json, "Failed to deserialize hosted-registry moderate request.")
        )

        val existing = store.getListing(request.listingId)
        if(existing == null)
        {
            return buildRpcResponse(
                messageType = P2PHostedRegistryRpcType.MODERATE_LISTING,
                payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
                payload = P2PHostedRegistryMutationResult(
                    accepted = false,
                    rejectionReason = "Hosted registry listing was not found."
                )
            )
        }

        if(!policy.canModerate(context, existing))
        {
            return buildRpcResponse(
                messageType = P2PHostedRegistryRpcType.MODERATE_LISTING,
                payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
                payload = P2PHostedRegistryMutationResult(
                    accepted = false,
                    rejectionReason = "Hosted registry moderation access was denied."
                )
            )
        }

        val moderated = existing.deepCopy<P2PHostedRegistryListing>().apply {
            moderationState = request.moderationState
            metadata.updatedAtEpochMillis = System.currentTimeMillis()
        }
        val stored = store.upsert(moderated)
        appendAuditRecord(
            listing = stored,
            action = P2PHostedRegistryAuditAction.MODERATE,
            principalRef = context.principalRef,
            reason = request.reason,
            moderationState = request.moderationState
        )
        return buildRpcResponse(
            messageType = P2PHostedRegistryRpcType.MODERATE_LISTING,
            payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
            payload = P2PHostedRegistryMutationResult(
                accepted = true,
                listing = stored.deepCopy(),
                lease = stored.lease?.deepCopy()
            )
        )
    }

    private suspend fun handleListAudit(
        context: P2PHostedRegistryAccessContext,
        rpcMessage: P2PHostedRegistryRpcMessage
    ): P2PResponse
    {
        val request = deserialize<P2PHostedRegistryAuditQuery>(
            rpcMessage.payloadJson,
            useRepair = false
        ) ?: return P2PResponse(
            rejection = P2PRejection(P2PError.json, "Failed to deserialize hosted-registry audit query.")
        )

        if(!policy.canReadAudit(context, request.listingId))
        {
            return buildRpcResponse(
                messageType = P2PHostedRegistryRpcType.LIST_AUDIT_LOG,
                payloadType = HOSTED_REGISTRY_AUDIT_RESULT_PAYLOAD,
                payload = P2PHostedRegistryAuditQueryResult(
                    accepted = false,
                    rejectionReason = "Hosted registry audit access was denied."
                )
            )
        }

        val filtered = store.listAuditRecords()
            .filter { request.listingId.isBlank() || it.listingId == request.listingId }
            .filter { request.actions.isEmpty() || it.action in request.actions }
            .filter { request.principalRef.isBlank() || it.principalRef == request.principalRef }
            .filter { request.listingKinds.isEmpty() || it.listingKind in request.listingKinds }
            .filter {
                request.occurredAfterEpochMillis <= 0L || it.occurredAtEpochMillis >= request.occurredAfterEpochMillis
            }
            .filter {
                request.occurredBeforeEpochMillis <= 0L || it.occurredAtEpochMillis <= request.occurredBeforeEpochMillis
            }
            .sortedByDescending { it.occurredAtEpochMillis }
        val paged = filtered
            .drop(request.offset.coerceAtLeast(0))
            .take(request.limit.coerceAtLeast(1))
            .map { it.deepCopy<P2PHostedRegistryAuditRecord>() }
            .toMutableList()

        return buildRpcResponse(
            messageType = P2PHostedRegistryRpcType.LIST_AUDIT_LOG,
            payloadType = HOSTED_REGISTRY_AUDIT_RESULT_PAYLOAD,
            payload = P2PHostedRegistryAuditQueryResult(
                accepted = true,
                rejectionReason = "",
                totalCount = filtered.size,
                results = paged
            )
        )
    }

    private suspend fun purgeExpiredListings()
    {
        val now = System.currentTimeMillis()
        lastExpirySweepEpochMillis = now
        store.listListings().forEach { listing ->
            val expiresAt = listing.lease?.expiresAtEpochMillis ?: Long.MAX_VALUE
            if(expiresAt in 1 until now)
            {
                store.removeListing(listing.listingId)
                appendAuditRecord(
                    listing = listing,
                    action = P2PHostedRegistryAuditAction.EXPIRE,
                    principalRef = "",
                    reason = "Lease expired."
                )
            }
        }
    }

    private suspend fun buildRegistryStatus(): P2PHostedRegistryStatus
    {
        val listings = store.listListings()
        return P2PHostedRegistryStatus(
            registryName = registryName,
            durableStoreKind = store.describeStoreKind(),
            stats = P2PHostedRegistryListingStats(
                totalCount = listings.size,
                activeCount = listings.count { it.moderationState == P2PHostedModerationState.ACTIVE },
                pendingCount = listings.count { it.moderationState == P2PHostedModerationState.PENDING },
                hiddenCount = listings.count { it.moderationState == P2PHostedModerationState.HIDDEN },
                agentCount = listings.count { it.kind == P2PHostedListingKind.AGENT },
                gridNodeCount = listings.count { it.kind == P2PHostedListingKind.GRID_NODE },
                gridRegistryCount = listings.count { it.kind == P2PHostedListingKind.GRID_REGISTRY }
            ),
            auditEnabled = true,
            operatorManaged = policy.info(registryName).operatorManaged,
            lastExpirySweepEpochMillis = lastExpirySweepEpochMillis
        )
    }

    private fun facetBuckets(values: List<String>): MutableList<P2PHostedRegistryFacetBucket>
    {
        return values
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { P2PHostedRegistryFacetBucket(value = it.key, count = it.value) }
            .toMutableList()
    }

    private suspend fun resolveExistingListingForPublish(
        request: P2PHostedRegistryPublishRequest
    ): P2PHostedRegistryListing?
    {
        val listingId = request.listing.listingId
        if(listingId.isNotBlank())
        {
            return store.getListing(listingId)
        }

        if(!request.replaceExisting)
        {
            return null
        }

        val desiredTitle = request.listing.metadata.title
        return store.listListings().firstOrNull { existing ->
            existing.kind == request.listing.kind &&
                existing.metadata.title == desiredTitle &&
                desiredTitle.isNotBlank()
        }
    }

    private fun normalizePublishedListing(
        listing: P2PHostedRegistryListing,
        existingListing: P2PHostedRegistryListing?,
        requestedLeaseSeconds: Int,
        ownerRef: String
    ): P2PHostedRegistryListing
    {
        val now = System.currentTimeMillis()
        val listingId = existingListing?.listingId
            ?: listing.listingId.ifBlank { UUID.randomUUID().toString() }
        val leaseId = existingListing?.lease?.leaseId ?: UUID.randomUUID().toString()
        val title = listing.metadata.title.ifBlank {
            listing.publicDescriptor?.agentName
                ?: listing.gridNodeAdvertisement?.metadata?.nodeId
                ?: listing.gridRegistryAdvertisement?.metadata?.registryId
                ?: listingId
        }

        return listing.deepCopy<P2PHostedRegistryListing>().apply {
            this.listingId = listingId
            metadata.title = title
            if(metadata.createdAtEpochMillis <= 0L)
            {
                metadata.createdAtEpochMillis = existingListing?.metadata?.createdAtEpochMillis ?: now
            }
            metadata.updatedAtEpochMillis = now
            metadata.searchableText = buildSearchableText(this)
            lease = P2PHostedListingLease(
                listingId = listingId,
                leaseId = leaseId,
                ownerRef = ownerRef.ifBlank { existingListing?.lease?.ownerRef.orEmpty() },
                expiresAtEpochMillis = now + requestedLeaseSeconds.coerceAtLeast(1) * 1000L,
                renewalRequired = true
            )
        }
    }

    private fun normalizeUpdatedListing(
        listingId: String,
        leaseId: String,
        listing: P2PHostedRegistryListing,
        existingListing: P2PHostedRegistryListing,
        requestedLeaseSeconds: Int
    ): P2PHostedRegistryListing
    {
        val now = System.currentTimeMillis()
        return listing.deepCopy<P2PHostedRegistryListing>().apply {
            this.listingId = listingId
            val existingLease = existingListing.lease?.deepCopy<P2PHostedListingLease>()
            lease = existingLease?.apply {
                this.leaseId = leaseId
                expiresAtEpochMillis = now + requestedLeaseSeconds.coerceAtLeast(1) * 1000L
            }
            if(metadata.createdAtEpochMillis <= 0L)
            {
                metadata.createdAtEpochMillis = existingListing.metadata.createdAtEpochMillis
            }
            metadata.updatedAtEpochMillis = now
            if(metadata.title.isBlank())
            {
                metadata.title = existingListing.metadata.title
            }
            metadata.searchableText = buildSearchableText(this)
        }
    }

    private fun matchesQuery(
        listing: P2PHostedRegistryListing,
        query: P2PHostedRegistryQuery
    ): Boolean
    {
        if(query.listingKinds.isNotEmpty() && listing.kind !in query.listingKinds)
        {
            return false
        }

        if(query.categories.isNotEmpty() &&
            query.categories.none { candidate -> candidate in listing.metadata.categories }
        )
        {
            return false
        }

        if(query.tags.isNotEmpty() &&
            query.tags.none { candidate -> candidate in listing.metadata.tags }
        )
        {
            return false
        }

        if(query.textQuery.isNotBlank())
        {
            val haystack = if(listing.metadata.searchableText.isNotBlank())
            {
                listing.metadata.searchableText
            }
            else
            {
                buildSearchableText(listing)
            }
            if(!haystack.contains(query.textQuery, ignoreCase = true))
            {
                return false
            }
        }

        if(query.exactTitle.isNotBlank() &&
            !listing.metadata.title.equals(query.exactTitle, ignoreCase = true)
        )
        {
            return false
        }

        if(query.titlePrefix.isNotBlank() &&
            !listing.metadata.title.startsWith(query.titlePrefix, ignoreCase = true)
        )
        {
            return false
        }

        query.requiresAuth?.let { required ->
            val actual = when(listing.kind)
            {
                P2PHostedListingKind.AGENT -> listing.publicDescriptor?.requiresAuth ?: false
                P2PHostedListingKind.GRID_NODE -> listing.gridNodeAdvertisement?.descriptor?.requiresAuth ?: false
                P2PHostedListingKind.GRID_REGISTRY -> false
            }
            if(actual != required)
            {
                return false
            }
        }

        if(query.transportMethods.isNotEmpty())
        {
            val transports = when(listing.kind)
            {
                P2PHostedListingKind.AGENT -> listOfNotNull(listing.publicDescriptor?.transport?.transportMethod)
                P2PHostedListingKind.GRID_NODE -> listOfNotNull(listing.gridNodeAdvertisement?.descriptor?.transport?.transportMethod)
                P2PHostedListingKind.GRID_REGISTRY -> listOf(listing.gridRegistryAdvertisement?.transport?.transportMethod ?: Transport.Tpipe)
            }
            if(transports.none { it in query.transportMethods })
            {
                return false
            }
        }

        if(query.contextProtocols.isNotEmpty())
        {
            val contextProtocol = when(listing.kind)
            {
                P2PHostedListingKind.AGENT -> listing.publicDescriptor?.contextProtocol
                P2PHostedListingKind.GRID_NODE -> listing.gridNodeAdvertisement?.descriptor?.contextProtocol
                P2PHostedListingKind.GRID_REGISTRY -> null
            }
            if(contextProtocol !in query.contextProtocols)
            {
                return false
            }
        }

        if(query.supportedContentTypes.isNotEmpty())
        {
            val contentTypes = when(listing.kind)
            {
                P2PHostedListingKind.AGENT -> listing.publicDescriptor?.supportedContentTypes.orEmpty()
                P2PHostedListingKind.GRID_NODE -> listing.gridNodeAdvertisement?.descriptor?.supportedContentTypes.orEmpty()
                P2PHostedListingKind.GRID_REGISTRY -> emptyList()
            }
            if(contentTypes.none { it in query.supportedContentTypes })
            {
                return false
            }
        }

        if(query.skillOrCapabilityFilters.isNotEmpty())
        {
            val normalizedFilters = query.skillOrCapabilityFilters.map { it.lowercase() }
            val searchTerms = when(listing.kind)
            {
                P2PHostedListingKind.AGENT -> listing.publicDescriptor?.agentSkills
                    ?.map { "${it.skillName} ${it.skillDescription}".lowercase() }
                    .orEmpty()
                P2PHostedListingKind.GRID_NODE -> listing.gridNodeAdvertisement?.metadata?.roleCapabilities
                    ?.map { it.lowercase() }
                    .orEmpty()
                P2PHostedListingKind.GRID_REGISTRY -> mutableListOf("registry")
            }
            if(normalizedFilters.none { filter -> searchTerms.any { filter in it } })
            {
                return false
            }
        }

        if(query.registryIds.isNotEmpty())
        {
            val registryId = when(listing.kind)
            {
                P2PHostedListingKind.AGENT -> ""
                P2PHostedListingKind.GRID_NODE -> listing.gridNodeAdvertisement?.registryId.orEmpty()
                P2PHostedListingKind.GRID_REGISTRY -> listing.gridRegistryAdvertisement?.metadata?.registryId.orEmpty()
            }
            if(registryId !in query.registryIds)
            {
                return false
            }
        }

        if(query.trustDomainIds.isNotEmpty())
        {
            val trustDomainId = when(listing.kind)
            {
                P2PHostedListingKind.AGENT -> ""
                P2PHostedListingKind.GRID_NODE -> listing.gridNodeAdvertisement?.metadata?.registryMemberships?.firstOrNull().orEmpty()
                P2PHostedListingKind.GRID_REGISTRY -> listing.gridRegistryAdvertisement?.metadata?.trustDomainId.orEmpty()
            }
            if(trustDomainId !in query.trustDomainIds)
            {
                return false
            }
        }

        query.actsAsRegistry?.let { expected ->
            val actual = when(listing.kind)
            {
                P2PHostedListingKind.AGENT -> false
                P2PHostedListingKind.GRID_NODE -> listing.gridNodeAdvertisement?.metadata?.actsAsRegistry ?: false
                P2PHostedListingKind.GRID_REGISTRY -> true
            }
            if(actual != expected)
            {
                return false
            }
        }

        if(query.moderationStates.isEmpty())
        {
            if(listing.moderationState != P2PHostedModerationState.ACTIVE)
            {
                return false
            }
        }
        else if(listing.moderationState !in query.moderationStates)
        {
            return false
        }

        if(query.createdAfterEpochMillis > 0L && listing.metadata.createdAtEpochMillis < query.createdAfterEpochMillis)
        {
            return false
        }
        if(query.createdBeforeEpochMillis > 0L && listing.metadata.createdAtEpochMillis > query.createdBeforeEpochMillis)
        {
            return false
        }
        if(query.updatedAfterEpochMillis > 0L && listing.metadata.updatedAtEpochMillis < query.updatedAfterEpochMillis)
        {
            return false
        }
        if(query.updatedBeforeEpochMillis > 0L && listing.metadata.updatedAtEpochMillis > query.updatedBeforeEpochMillis)
        {
            return false
        }

        if(query.verifiedOnly && !listingHasVerificationEvidence(listing))
        {
            return false
        }

        if(query.healthyOnly && !listingIsHealthy(listing))
        {
            return false
        }

        return true
    }

    private fun buildComparator(query: P2PHostedRegistryQuery): Comparator<P2PHostedRegistryListing>
    {
        return when(query.sortMode)
        {
            P2PHostedRegistrySortMode.TITLE -> compareBy<P2PHostedRegistryListing> { it.metadata.title.lowercase() }
                .thenBy { it.listingId }
            P2PHostedRegistrySortMode.UPDATED_AT -> compareByDescending<P2PHostedRegistryListing> { it.metadata.updatedAtEpochMillis }
                .thenBy { it.metadata.title.lowercase() }
                .thenBy { it.listingId }
            P2PHostedRegistrySortMode.CREATED_AT -> compareByDescending<P2PHostedRegistryListing> { it.metadata.createdAtEpochMillis }
                .thenByDescending { it.metadata.updatedAtEpochMillis }
                .thenBy { it.listingId }
            P2PHostedRegistrySortMode.RELEVANCE -> compareByDescending<P2PHostedRegistryListing> {
                relevanceScore(it, query.textQuery)
            }.thenByDescending { it.metadata.updatedAtEpochMillis }
                .thenBy { it.listingId }
        }
    }

    private fun relevanceScore(listing: P2PHostedRegistryListing, textQuery: String): Int
    {
        if(textQuery.isBlank())
        {
            return 0
        }
        val normalizedQuery = textQuery.lowercase()
        var score = 0
        val title = listing.metadata.title.lowercase()
        val summary = listing.metadata.summary.lowercase()
        val searchable = buildSearchableText(listing).lowercase()
        if(normalizedQuery == title) score += 50
        if(normalizedQuery in title) score += 30
        if(normalizedQuery in summary) score += 20
        if(normalizedQuery in searchable) score += 10
        return score
    }

    private fun listingHasVerificationEvidence(listing: P2PHostedRegistryListing): Boolean
    {
        return listing.attestationRef.isNotBlank() ||
            listing.gridNodeAdvertisement?.attestationRef?.isNotBlank() == true ||
            listing.gridRegistryAdvertisement?.attestationRef?.isNotBlank() == true
    }

    private fun listingIsHealthy(listing: P2PHostedRegistryListing): Boolean
    {
        val now = System.currentTimeMillis()
        return when(listing.kind)
        {
            P2PHostedListingKind.AGENT -> true
            P2PHostedListingKind.GRID_NODE -> {
                val leaseExpiry = listing.gridNodeAdvertisement?.lease?.expiresAtEpochMillis
                    ?: listing.lease?.expiresAtEpochMillis
                    ?: Long.MAX_VALUE
                leaseExpiry > now
            }
            P2PHostedListingKind.GRID_REGISTRY -> listing.lease?.expiresAtEpochMillis?.let { it > now } ?: true
        }
    }

    private fun buildSearchableText(listing: P2PHostedRegistryListing): String
    {
        val parts = mutableListOf<String>()
        parts += listing.metadata.title
        parts += listing.metadata.summary
        parts += listing.metadata.categories
        parts += listing.metadata.tags
        listing.publicDescriptor?.let { descriptor ->
            parts += descriptor.agentName
            parts += descriptor.agentDescription
            descriptor.agentSkills?.forEach { skill ->
                parts += skill.skillName
                parts += skill.skillDescription
            }
        }
        listing.gridNodeAdvertisement?.let { advertisement ->
            parts += advertisement.metadata.nodeId
            parts += advertisement.metadata.roleCapabilities
            advertisement.descriptor?.let { descriptor ->
                parts += descriptor.agentName
                parts += descriptor.agentDescription
            }
        }
        listing.gridRegistryAdvertisement?.let { advertisement ->
            parts += advertisement.metadata.registryId
            parts += advertisement.metadata.trustDomainId
        }
        return parts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
    }

    private inline fun <reified T> buildRpcResponse(
        messageType: P2PHostedRegistryRpcType,
        payloadType: String,
        payload: T
    ): P2PResponse
    {
        return P2PResponse(
            output = MultimodalContent(
                text = serialize(
                    P2PHostedRegistryRpcMessage(
                        protocolVersion = DistributionGridProtocolVersion(1, 0, 0),
                        messageType = messageType,
                        payloadType = payloadType,
                        payloadJson = serialize(payload)
                    )
                )
            )
        )
    }

    private suspend fun appendAuditRecord(
        listing: P2PHostedRegistryListing,
        action: P2PHostedRegistryAuditAction,
        principalRef: String,
        reason: String,
        moderationState: P2PHostedModerationState? = null
    )
    {
        store.appendAuditRecord(
            P2PHostedRegistryAuditRecord(
                recordId = UUID.randomUUID().toString(),
                listingId = listing.listingId,
                listingKind = listing.kind,
                action = action,
                principalRef = principalRef,
                reason = reason,
                moderationState = moderationState,
                occurredAtEpochMillis = System.currentTimeMillis()
            )
        )
    }
}

/**
 * Client helper for hosted-registry search and mutation flows.
 */
object P2PHostedRegistryClient
{
    suspend fun getRegistryInfo(
        transport: P2PTransport,
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryInfo?
    {
        val response = send(
            transport = transport,
            authBody = authBody,
            transportAuthBody = transportAuthBody,
            messageType = P2PHostedRegistryRpcType.REGISTRY_INFO,
            payloadType = HOSTED_REGISTRY_INFO_PAYLOAD,
            payloadJson = ""
        ) ?: return null

        if(response.payloadType != HOSTED_REGISTRY_INFO_PAYLOAD)
        {
            return null
        }
        return deserialize<P2PHostedRegistryInfo>(response.payloadJson, useRepair = false)
    }

    suspend fun searchListings(
        transport: P2PTransport,
        query: P2PHostedRegistryQuery,
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryQueryResult
    {
        val response = send(
            transport = transport,
            authBody = authBody,
            transportAuthBody = transportAuthBody,
            messageType = P2PHostedRegistryRpcType.SEARCH_LISTINGS,
            payloadType = HOSTED_REGISTRY_QUERY_PAYLOAD,
            payloadJson = serialize(query)
        ) ?: return P2PHostedRegistryQueryResult(
            accepted = false,
            rejectionReason = "Hosted registry search failed before a response was received."
        )

        if(response.payloadType != HOSTED_REGISTRY_QUERY_RESULT_PAYLOAD)
        {
            return P2PHostedRegistryQueryResult(
                accepted = false,
                rejectionReason = "Hosted registry search returned an unexpected payload type '${response.payloadType}'."
            )
        }
        return deserialize<P2PHostedRegistryQueryResult>(response.payloadJson, useRepair = false)
            ?: P2PHostedRegistryQueryResult(
                accepted = false,
                rejectionReason = "Hosted registry search returned an unreadable payload."
            )
    }

    suspend fun getRegistryStatus(
        transport: P2PTransport,
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryStatus?
    {
        val response = send(
            transport = transport,
            authBody = authBody,
            transportAuthBody = transportAuthBody,
            messageType = P2PHostedRegistryRpcType.REGISTRY_STATUS,
            payloadType = HOSTED_REGISTRY_STATUS_PAYLOAD,
            payloadJson = ""
        ) ?: return null

        if(response.payloadType != HOSTED_REGISTRY_STATUS_PAYLOAD)
        {
            return null
        }
        return deserialize<P2PHostedRegistryStatus>(response.payloadJson, useRepair = false)
    }

    suspend fun getSearchFacets(
        transport: P2PTransport,
        query: P2PHostedRegistryQuery = P2PHostedRegistryQuery(),
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryFacetResult
    {
        val response = send(
            transport = transport,
            authBody = authBody,
            transportAuthBody = transportAuthBody,
            messageType = P2PHostedRegistryRpcType.SEARCH_FACETS,
            payloadType = HOSTED_REGISTRY_FACET_PAYLOAD,
            payloadJson = serialize(P2PHostedRegistryFacetRequest(query = query))
        ) ?: return P2PHostedRegistryFacetResult(
            accepted = false,
            rejectionReason = "Hosted registry facet query failed before a response was received."
        )

        if(response.payloadType != HOSTED_REGISTRY_FACET_RESULT_PAYLOAD)
        {
            return P2PHostedRegistryFacetResult(
                accepted = false,
                rejectionReason = "Hosted registry facet query returned an unexpected payload type '${response.payloadType}'."
            )
        }

        return deserialize<P2PHostedRegistryFacetResult>(response.payloadJson, useRepair = false)
            ?: P2PHostedRegistryFacetResult(
                accepted = false,
                rejectionReason = "Hosted registry facet query returned an unreadable payload."
            )
    }

    suspend fun searchAgentListings(
        transport: P2PTransport,
        query: P2PHostedRegistryQuery = P2PHostedRegistryQuery(),
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryQueryResult
    {
        return searchListings(
            transport = transport,
            query = query.deepCopy<P2PHostedRegistryQuery>().apply {
                listingKinds = mutableListOf(P2PHostedListingKind.AGENT)
            },
            authBody = authBody,
            transportAuthBody = transportAuthBody
        )
    }

    suspend fun searchGridNodeListings(
        transport: P2PTransport,
        query: P2PHostedRegistryQuery = P2PHostedRegistryQuery(),
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryQueryResult
    {
        return searchListings(
            transport = transport,
            query = query.deepCopy<P2PHostedRegistryQuery>().apply {
                listingKinds = mutableListOf(P2PHostedListingKind.GRID_NODE)
            },
            authBody = authBody,
            transportAuthBody = transportAuthBody
        )
    }

    suspend fun searchGridRegistryListings(
        transport: P2PTransport,
        query: P2PHostedRegistryQuery = P2PHostedRegistryQuery(),
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryQueryResult
    {
        return searchListings(
            transport = transport,
            query = query.deepCopy<P2PHostedRegistryQuery>().apply {
                listingKinds = mutableListOf(P2PHostedListingKind.GRID_REGISTRY)
            },
            authBody = authBody,
            transportAuthBody = transportAuthBody
        )
    }

    suspend fun getListing(
        transport: P2PTransport,
        listingId: String,
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        val response = send(
            transport = transport,
            authBody = authBody,
            transportAuthBody = transportAuthBody,
            messageType = P2PHostedRegistryRpcType.GET_LISTING,
            payloadType = HOSTED_REGISTRY_GET_PAYLOAD,
            payloadJson = serialize(P2PHostedRegistryGetRequest(listingId = listingId))
        ) ?: return failedMutation("Hosted registry get failed before a response was received.")

        if(response.payloadType != HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD)
        {
            return failedMutation("Hosted registry get returned an unexpected payload type '${response.payloadType}'.")
        }
        return deserialize<P2PHostedRegistryMutationResult>(response.payloadJson, useRepair = false)
            ?: failedMutation("Hosted registry get returned an unreadable payload.")
    }

    suspend fun publishListing(
        transport: P2PTransport,
        request: P2PHostedRegistryPublishRequest,
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        val response = send(
            transport = transport,
            authBody = authBody,
            transportAuthBody = transportAuthBody,
            messageType = P2PHostedRegistryRpcType.PUBLISH_LISTING,
            payloadType = HOSTED_REGISTRY_PUBLISH_PAYLOAD,
            payloadJson = serialize(request)
        ) ?: return failedMutation("Hosted registry publish failed before a response was received.")

        if(response.payloadType != HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD)
        {
            return failedMutation("Hosted registry publish returned an unexpected payload type '${response.payloadType}'.")
        }
        return deserialize<P2PHostedRegistryMutationResult>(response.payloadJson, useRepair = false)
            ?: failedMutation("Hosted registry publish returned an unreadable payload.")
    }

    suspend fun updateListing(
        transport: P2PTransport,
        request: P2PHostedRegistryUpdateRequest,
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        val response = send(
            transport = transport,
            authBody = authBody,
            transportAuthBody = transportAuthBody,
            messageType = P2PHostedRegistryRpcType.UPDATE_LISTING,
            payloadType = HOSTED_REGISTRY_UPDATE_PAYLOAD,
            payloadJson = serialize(request)
        ) ?: return failedMutation("Hosted registry update failed before a response was received.")

        if(response.payloadType != HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD)
        {
            return failedMutation("Hosted registry update returned an unexpected payload type '${response.payloadType}'.")
        }
        return deserialize<P2PHostedRegistryMutationResult>(response.payloadJson, useRepair = false)
            ?: failedMutation("Hosted registry update returned an unreadable payload.")
    }

    suspend fun renewListing(
        transport: P2PTransport,
        request: P2PHostedRegistryRenewRequest,
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        val response = send(
            transport = transport,
            authBody = authBody,
            transportAuthBody = transportAuthBody,
            messageType = P2PHostedRegistryRpcType.RENEW_LISTING,
            payloadType = HOSTED_REGISTRY_RENEW_PAYLOAD,
            payloadJson = serialize(request)
        ) ?: return failedMutation("Hosted registry renewal failed before a response was received.")

        if(response.payloadType != HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD)
        {
            return failedMutation("Hosted registry renewal returned an unexpected payload type '${response.payloadType}'.")
        }
        return deserialize<P2PHostedRegistryMutationResult>(response.payloadJson, useRepair = false)
            ?: failedMutation("Hosted registry renewal returned an unreadable payload.")
    }

    suspend fun removeListing(
        transport: P2PTransport,
        request: P2PHostedRegistryRemoveRequest,
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        val response = send(
            transport = transport,
            authBody = authBody,
            transportAuthBody = transportAuthBody,
            messageType = P2PHostedRegistryRpcType.REMOVE_LISTING,
            payloadType = HOSTED_REGISTRY_REMOVE_PAYLOAD,
            payloadJson = serialize(request)
        ) ?: return failedMutation("Hosted registry delete failed before a response was received.")

        if(response.payloadType != HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD)
        {
            return failedMutation("Hosted registry delete returned an unexpected payload type '${response.payloadType}'.")
        }
        return deserialize<P2PHostedRegistryMutationResult>(response.payloadJson, useRepair = false)
            ?: failedMutation("Hosted registry delete returned an unreadable payload.")
    }

    suspend fun moderateListing(
        transport: P2PTransport,
        request: P2PHostedRegistryModerateRequest,
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        val response = send(
            transport = transport,
            authBody = authBody,
            transportAuthBody = transportAuthBody,
            messageType = P2PHostedRegistryRpcType.MODERATE_LISTING,
            payloadType = HOSTED_REGISTRY_MODERATE_PAYLOAD,
            payloadJson = serialize(request)
        ) ?: return failedMutation("Hosted registry moderation failed before a response was received.")

        if(response.payloadType != HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD)
        {
            return failedMutation("Hosted registry moderation returned an unexpected payload type '${response.payloadType}'.")
        }
        return deserialize<P2PHostedRegistryMutationResult>(response.payloadJson, useRepair = false)
            ?: failedMutation("Hosted registry moderation returned an unreadable payload.")
    }

    suspend fun listAuditRecords(
        transport: P2PTransport,
        query: P2PHostedRegistryAuditQuery = P2PHostedRegistryAuditQuery(),
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryAuditQueryResult
    {
        val response = send(
            transport = transport,
            authBody = authBody,
            transportAuthBody = transportAuthBody,
            messageType = P2PHostedRegistryRpcType.LIST_AUDIT_LOG,
            payloadType = HOSTED_REGISTRY_AUDIT_QUERY_PAYLOAD,
            payloadJson = serialize(query)
        ) ?: return P2PHostedRegistryAuditQueryResult(
            accepted = false,
            rejectionReason = "Hosted registry audit query failed before a response was received."
        )

        if(response.payloadType != HOSTED_REGISTRY_AUDIT_RESULT_PAYLOAD)
        {
            return P2PHostedRegistryAuditQueryResult(
                accepted = false,
                rejectionReason = "Hosted registry audit query returned an unexpected payload type '${response.payloadType}'."
            )
        }
        return deserialize<P2PHostedRegistryAuditQueryResult>(response.payloadJson, useRepair = false)
            ?: P2PHostedRegistryAuditQueryResult(
                accepted = false,
                rejectionReason = "Hosted registry audit query returned an unreadable payload."
            )
    }

    suspend fun pullListingsToLocalRegistry(
        transport: P2PTransport,
        query: P2PHostedRegistryQuery,
        authBody: String = "",
        transportAuthBody: String = "",
        replaceExisting: Boolean = true
    ): P2PHostedRegistryQueryResult
    {
        val result = searchListings(
            transport = transport,
            query = query,
            authBody = authBody,
            transportAuthBody = transportAuthBody
        )
        if(result.accepted)
        {
            P2PRegistry.loadHostedAgentListings(result.results, replaceExisting)
        }
        return result
    }

    private suspend fun send(
        transport: P2PTransport,
        authBody: String,
        transportAuthBody: String,
        messageType: P2PHostedRegistryRpcType,
        payloadType: String,
        payloadJson: String
    ): P2PHostedRegistryRpcMessage?
    {
        val resolvedTransportAuth = transportAuthBody.ifBlank {
            AuthRegistry.getToken(transport.transportAddress)
        }
        val resolvedTransport = transport.copy(
            transportAddress = resolveHostedRegistryTransportAddress(transport),
            transportAuthBody = resolvedTransportAuth
        )
        val response = P2PRegistry.externalP2PCall(
            P2PRequest(
                transport = resolvedTransport,
                prompt = MultimodalContent(
                    text = serialize(
                        P2PHostedRegistryRpcMessage(
                            protocolVersion = DistributionGridProtocolVersion(1, 0, 0),
                            messageType = messageType,
                            payloadType = payloadType,
                            payloadJson = payloadJson
                        )
                    )
                ),
                authBody = authBody
            )
        )

        if(response.rejection != null)
        {
            return P2PHostedRegistryRpcMessage(
                protocolVersion = DistributionGridProtocolVersion(1, 0, 0),
                messageType = messageType,
                payloadType = HOSTED_REGISTRY_MUTATION_RESULT_PAYLOAD,
                payloadJson = serialize(
                    failedMutation(response.rejection!!.reason)
                )
            )
        }

        val rpcMessage = deserialize<P2PHostedRegistryRpcMessage>(
            response.output?.text.orEmpty(),
            useRepair = false
        ) ?: return null

        return rpcMessage
    }

    private fun failedMutation(reason: String): P2PHostedRegistryMutationResult
    {
        return P2PHostedRegistryMutationResult(
            accepted = false,
            rejectionReason = reason
        )
    }

    private fun resolveHostedRegistryTransportAddress(transport: P2PTransport): String
    {
        if(transport.transportMethod != Transport.Http)
        {
            return transport.transportAddress
        }

        val trimmed = transport.transportAddress.trimEnd('/')
        if(trimmed.endsWith("/p2p/registry"))
        {
            return trimmed
        }
        if(trimmed.endsWith("/p2p"))
        {
            return "$trimmed/registry"
        }
        return "$trimmed/p2p/registry"
    }
}
