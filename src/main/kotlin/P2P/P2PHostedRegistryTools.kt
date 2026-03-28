package com.TTT.P2P

import com.TTT.PipeContextProtocol.ContextOptionParameter
import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.ParamType
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.TPipeContextOptions
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Pipeline.DistributionGridRegistryAdvertisement
import com.TTT.Util.deepCopy
import com.TTT.Util.deserialize

/**
 * PCP-callable tools for querying and mutating hosted P2P registries.
 *
 * These tools intentionally reuse [P2PHostedRegistryClient] so human-facing code and agent-facing PCP
 * calls follow the same transport, payload validation, and sanitization paths.
 */
object P2PHostedRegistryTools
{
    suspend fun searchP2pRegistryListings(
        transportAddress: String,
        transportMethod: Transport = Transport.Tpipe,
        queryJson: String = "",
        authBody: String = ""
    ): P2PHostedRegistryQueryResult
    {
        val query = deserialize<P2PHostedRegistryQuery>(queryJson, useRepair = false)
            ?: P2PHostedRegistryQuery()
        return P2PHostedRegistryClient.searchListings(
            transport = P2PTransport(transportMethod = transportMethod, transportAddress = transportAddress),
            query = query,
            authBody = authBody
        )
    }

    suspend fun getP2pRegistryListing(
        transportAddress: String,
        listingId: String,
        transportMethod: Transport = Transport.Tpipe,
        authBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        return P2PHostedRegistryClient.getListing(
            transport = P2PTransport(transportMethod = transportMethod, transportAddress = transportAddress),
            listingId = listingId,
            authBody = authBody
        )
    }

    suspend fun listTrustedGridRegistries(
        transportAddress: String,
        transportMethod: Transport = Transport.Tpipe,
        queryJson: String = "",
        authBody: String = ""
    ): List<DistributionGridRegistryAdvertisement>
    {
        val baseQuery = deserialize<P2PHostedRegistryQuery>(queryJson, useRepair = false)
            ?: P2PHostedRegistryQuery()
        val result = P2PHostedRegistryClient.searchListings(
            transport = P2PTransport(transportMethod = transportMethod, transportAddress = transportAddress),
            query = baseQuery.apply {
                if(listingKinds.none { it == P2PHostedListingKind.GRID_REGISTRY })
                {
                    listingKinds.add(P2PHostedListingKind.GRID_REGISTRY)
                }
                verifiedOnly = true
            },
            authBody = authBody
        )
        if(!result.accepted)
        {
            return emptyList()
        }
        return result.results.mapNotNull { listing ->
            listing.gridRegistryAdvertisement?.deepCopy<DistributionGridRegistryAdvertisement>()
        }
    }

    suspend fun publishP2pRegistryListing(
        transportAddress: String,
        listingJson: String,
        requestedLeaseSeconds: Long = 3600L,
        replaceExisting: Boolean = false,
        transportMethod: Transport = Transport.Tpipe,
        authBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        val listing = deserialize<P2PHostedRegistryListing>(listingJson, useRepair = false)
            ?: return P2PHostedRegistryMutationResult(
                accepted = false,
                rejectionReason = "Could not deserialize hosted registry listing payload."
            )
        return P2PHostedRegistryClient.publishListing(
            transport = P2PTransport(transportMethod = transportMethod, transportAddress = transportAddress),
            request = P2PHostedRegistryPublishRequest(
                listing = listing,
                requestedLeaseSeconds = requestedLeaseSeconds.toInt(),
                replaceExisting = replaceExisting
            ),
            authBody = authBody
        )
    }

    suspend fun renewP2pRegistryListing(
        transportAddress: String,
        listingId: String,
        leaseId: String,
        requestedLeaseSeconds: Long = 3600L,
        transportMethod: Transport = Transport.Tpipe,
        authBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        return P2PHostedRegistryClient.renewListing(
            transport = P2PTransport(transportMethod = transportMethod, transportAddress = transportAddress),
            request = P2PHostedRegistryRenewRequest(
                listingId = listingId,
                leaseId = leaseId,
                requestedLeaseSeconds = requestedLeaseSeconds.toInt()
            ),
            authBody = authBody
        )
    }

    suspend fun removeP2pRegistryListing(
        transportAddress: String,
        listingId: String,
        leaseId: String = "",
        transportMethod: Transport = Transport.Tpipe,
        authBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        return P2PHostedRegistryClient.removeListing(
            transport = P2PTransport(transportMethod = transportMethod, transportAddress = transportAddress),
            request = P2PHostedRegistryRemoveRequest(
                listingId = listingId,
                leaseId = leaseId
            ),
            authBody = authBody
        )
    }

    /**
     * Registers read-only hosted registry tools by default and opt-in mutation tools when requested.
     */
    fun registerAndEnable(
        context: PcpContext,
        allowWriteTools: Boolean = false
    )
    {
        FunctionRegistry.registerFunction("search_p2p_registry_listings", ::searchP2pRegistryListings)
        FunctionRegistry.registerFunction("get_p2p_registry_listing", ::getP2pRegistryListing)
        FunctionRegistry.registerFunction("list_trusted_grid_registries", ::listTrustedGridRegistries)

        if(allowWriteTools)
        {
            FunctionRegistry.registerFunction("publish_p2p_registry_listing", ::publishP2pRegistryListing)
            FunctionRegistry.registerFunction("renew_p2p_registry_listing", ::renewP2pRegistryListing)
            FunctionRegistry.registerFunction("remove_p2p_registry_listing", ::removeP2pRegistryListing)
        }

        fun addIfMissing(option: TPipeContextOptions)
        {
            if(context.tpipeOptions.none { it.functionName == option.functionName })
            {
                context.addTPipeOption(option)
            }
        }

        addIfMissing(TPipeContextOptions().apply {
            functionName = "search_p2p_registry_listings"
            description = "Search a hosted public P2P registry for agent, grid node, or grid registry listings."
            params["transportAddress"] = ContextOptionParameter(ParamType.String, "Hosted registry transport address.", emptyList())
            params["transportMethod"] = ContextOptionParameter(ParamType.String, "Transport method name such as Tpipe, Http, or Stdio.", emptyList())
            params["queryJson"] = ContextOptionParameter(ParamType.String, "Serialized P2PHostedRegistryQuery JSON.", emptyList(), isRequired = false)
            params["authBody"] = ContextOptionParameter(ParamType.String, "Optional auth token for registry hosts that gate reads.", emptyList(), isRequired = false)
        })

        addIfMissing(TPipeContextOptions().apply {
            functionName = "get_p2p_registry_listing"
            description = "Fetch one hosted P2P registry listing by id."
            params["transportAddress"] = ContextOptionParameter(ParamType.String, "Hosted registry transport address.", emptyList())
            params["listingId"] = ContextOptionParameter(ParamType.String, "Listing id to fetch.", emptyList())
            params["transportMethod"] = ContextOptionParameter(ParamType.String, "Transport method name such as Tpipe, Http, or Stdio.", emptyList(), isRequired = false)
            params["authBody"] = ContextOptionParameter(ParamType.String, "Optional auth token for registry hosts that gate reads.", emptyList(), isRequired = false)
        })

        addIfMissing(TPipeContextOptions().apply {
            functionName = "list_trusted_grid_registries"
            description = "Search hosted registry listings and return only verified grid-registry advertisements."
            params["transportAddress"] = ContextOptionParameter(ParamType.String, "Hosted registry transport address.", emptyList())
            params["transportMethod"] = ContextOptionParameter(ParamType.String, "Transport method name such as Tpipe, Http, or Stdio.", emptyList(), isRequired = false)
            params["queryJson"] = ContextOptionParameter(ParamType.String, "Serialized P2PHostedRegistryQuery JSON.", emptyList(), isRequired = false)
            params["authBody"] = ContextOptionParameter(ParamType.String, "Optional auth token for registry hosts that gate reads.", emptyList(), isRequired = false)
        })

        if(allowWriteTools)
        {
            addIfMissing(TPipeContextOptions().apply {
                functionName = "publish_p2p_registry_listing"
                description = "Publish a listing into a hosted public P2P registry."
                params["transportAddress"] = ContextOptionParameter(ParamType.String, "Hosted registry transport address.", emptyList())
                params["listingJson"] = ContextOptionParameter(ParamType.String, "Serialized P2PHostedRegistryListing JSON.", emptyList())
                params["requestedLeaseSeconds"] = ContextOptionParameter(ParamType.Int, "Requested listing lease duration in seconds.", emptyList(), isRequired = false)
                params["replaceExisting"] = ContextOptionParameter(ParamType.Bool, "Replace an existing listing with the same listingId.", emptyList(), isRequired = false)
                params["transportMethod"] = ContextOptionParameter(ParamType.String, "Transport method name such as Tpipe, Http, or Stdio.", emptyList(), isRequired = false)
                params["authBody"] = ContextOptionParameter(ParamType.String, "Registry write auth token.", emptyList(), isRequired = false)
            })

            addIfMissing(TPipeContextOptions().apply {
                functionName = "renew_p2p_registry_listing"
                description = "Renew an existing hosted registry listing lease."
                params["transportAddress"] = ContextOptionParameter(ParamType.String, "Hosted registry transport address.", emptyList())
                params["listingId"] = ContextOptionParameter(ParamType.String, "Listing id to renew.", emptyList())
                params["leaseId"] = ContextOptionParameter(ParamType.String, "Lease id proving listing ownership.", emptyList())
                params["requestedLeaseSeconds"] = ContextOptionParameter(ParamType.Int, "Requested renewed lease duration in seconds.", emptyList(), isRequired = false)
                params["transportMethod"] = ContextOptionParameter(ParamType.String, "Transport method name such as Tpipe, Http, or Stdio.", emptyList(), isRequired = false)
                params["authBody"] = ContextOptionParameter(ParamType.String, "Registry write auth token.", emptyList(), isRequired = false)
            })

            addIfMissing(TPipeContextOptions().apply {
                functionName = "remove_p2p_registry_listing"
                description = "Remove a hosted registry listing."
                params["transportAddress"] = ContextOptionParameter(ParamType.String, "Hosted registry transport address.", emptyList())
                params["listingId"] = ContextOptionParameter(ParamType.String, "Listing id to remove.", emptyList())
                params["leaseId"] = ContextOptionParameter(ParamType.String, "Optional lease id proving listing ownership.", emptyList(), isRequired = false)
                params["transportMethod"] = ContextOptionParameter(ParamType.String, "Transport method name such as Tpipe, Http, or Stdio.", emptyList(), isRequired = false)
                params["authBody"] = ContextOptionParameter(ParamType.String, "Registry write auth token.", emptyList(), isRequired = false)
            })
        }
    }
}
