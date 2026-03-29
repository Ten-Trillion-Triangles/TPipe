package Defaults

import com.TTT.P2P.DefaultP2PHostedRegistryPolicy
import com.TTT.P2P.FileBackedP2PHostedRegistryStore
import com.TTT.P2P.InMemoryP2PHostedRegistryStore
import com.TTT.P2P.P2PHostedListingKind
import com.TTT.P2P.P2PHostedRegistry
import com.TTT.P2P.P2PHostedRegistryPolicySettings
import com.TTT.P2P.P2PHostedRegistryStore
import com.TTT.P2P.P2PHostedRegistryQuery
import com.TTT.P2P.P2PTrustedRegistryAdmissionPolicy
import com.TTT.P2P.P2PTrustedRegistrySource
import com.TTT.Pipeline.DistributionGridBootstrapCatalogSource
import com.TTT.Pipeline.DistributionGridPublicListingOptions
import com.TTT.Util.deepCopy

/**
 * Thin hosted-registry defaults and builder helpers for `TPipe-Defaults`.
 *
 * These helpers intentionally return the existing runtime objects directly instead of introducing another hosted
 * registry runtime layer.
 */
object HostedRegistryDefaults
{
    /**
     * Build a hosted-registry host from explicit configuration.
     */
    fun buildRegistryHost(configuration: HostedRegistryConfiguration): P2PHostedRegistry
    {
        require(configuration.validate()) { "Invalid hosted registry configuration: $configuration" }
        return P2PHostedRegistry(
            registryName = configuration.registryName,
            transport = configuration.transport.deepCopy(),
            store = buildStore(configuration),
            policy = DefaultP2PHostedRegistryPolicy(
                configuration.policySettings.copy(
                    operatorRefs = configuration.policySettings.operatorRefs.toMutableSet(),
                    allowedKinds = configuration.policySettings.allowedKinds.toMutableSet()
                )
            )
        )
    }

    /**
     * Build a public hosted-registry host preset: public read, authenticated write by default.
     */
    fun buildPublicRegistryHost(configuration: HostedRegistryConfiguration): P2PHostedRegistry
    {
        return buildRegistryHost(
            configuration.copy(
                policySettings = configuration.policySettings.copy(
                    requireAuthForRead = false,
                    requireAuthForWrite = true,
                    allowAnonymousPublish = false,
                    operatorRefs = configuration.policySettings.operatorRefs.toMutableSet(),
                    allowedKinds = configuration.policySettings.allowedKinds.toMutableSet()
                )
            )
        )
    }

    /**
     * Build a private/team hosted-registry host preset: authenticated read and write.
     */
    fun buildPrivateRegistryHost(configuration: HostedRegistryConfiguration): P2PHostedRegistry
    {
        return buildRegistryHost(
            configuration.copy(
                policySettings = configuration.policySettings.copy(
                    requireAuthForRead = true,
                    requireAuthForWrite = true,
                    allowAnonymousPublish = false,
                    operatorRefs = configuration.policySettings.operatorRefs.toMutableSet(),
                    allowedKinds = configuration.policySettings.allowedKinds.toMutableSet()
                )
            )
        )
    }

    /**
     * Build a trusted hosted-registry source for plain `P2PRegistry` imports.
     */
    fun trustedPublicAgentSource(configuration: TrustedRegistrySourceConfiguration): P2PTrustedRegistrySource
    {
        require(configuration.validate()) { "Invalid trusted registry source configuration: $configuration" }
        val seededPolicy = configuration.admissionPolicy.copy(
            requireVerificationEvidence = configuration.requireVerificationEvidence
                ?: configuration.admissionPolicy.requireVerificationEvidence,
            minimumRemainingLeaseMillis = configuration.minimumRemainingLeaseMillis
                ?: configuration.admissionPolicy.minimumRemainingLeaseMillis,
            requireActiveModerationState = configuration.admissionPolicy.requireActiveModerationState,
            sourceLabel = configuration.admissionPolicy.sourceLabel
        )

        return P2PTrustedRegistrySource(
            sourceId = configuration.sourceId,
            transport = configuration.transport.deepCopy(),
            query = P2PHostedRegistryQuery(
                textQuery = configuration.textQuery,
                listingKinds = mutableListOf(P2PHostedListingKind.AGENT),
                categories = configuration.categories.toMutableList(),
                tags = configuration.tags.toMutableList()
            ),
            autoPullOnRegister = configuration.autoPullOnRegister,
            includeInAutoRefresh = configuration.includeInAutoRefresh,
            authBody = configuration.authBody,
            transportAuthBody = configuration.transportAuthBody,
            admissionPolicy = seededPolicy
        )
    }

    /**
     * Build a hosted bootstrap-catalog source for `DistributionGrid`.
     */
    fun bootstrapCatalogSource(
        configuration: DistributionGridBootstrapCatalogConfiguration
    ): DistributionGridBootstrapCatalogSource
    {
        require(configuration.validate()) { "Invalid grid bootstrap catalog configuration: $configuration" }
        return DistributionGridBootstrapCatalogSource(
            sourceId = configuration.sourceId,
            transport = configuration.transport.deepCopy(),
            query = P2PHostedRegistryQuery(
                listingKinds = mutableListOf(P2PHostedListingKind.GRID_REGISTRY),
                categories = configuration.categories.toMutableList(),
                tags = configuration.tags.toMutableList(),
                trustDomainIds = configuration.trustDomainIds.toMutableList()
            ),
            autoPullOnInit = configuration.autoPullOnInit
        )
    }

    /**
     * Build public listing metadata/options for `DistributionGrid` publication helpers.
     */
    fun publicListingOptions(
        configuration: DistributionGridPublicListingDefaultsConfiguration
    ): DistributionGridPublicListingOptions
    {
        require(configuration.validate()) { "Invalid grid public listing configuration: $configuration" }
        return DistributionGridPublicListingOptions(
            title = configuration.title,
            summary = configuration.summary,
            categories = configuration.categories.toMutableList(),
            tags = configuration.tags.toMutableList(),
            requestedLeaseSeconds = configuration.requestedLeaseSeconds
        )
    }

    private fun buildStore(configuration: HostedRegistryConfiguration): P2PHostedRegistryStore
    {
        return when(configuration.storeMode)
        {
            HostedRegistryStoreMode.MEMORY -> InMemoryP2PHostedRegistryStore()
            HostedRegistryStoreMode.FILE_JSON -> FileBackedP2PHostedRegistryStore(configuration.durableFilePath)
        }
    }
}
