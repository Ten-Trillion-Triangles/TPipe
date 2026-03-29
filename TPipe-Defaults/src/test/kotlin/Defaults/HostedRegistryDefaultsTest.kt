package Defaults

import com.TTT.P2P.P2PHostedListingKind
import com.TTT.P2P.P2PHostedRegistryClient
import com.TTT.P2P.P2PHostedRegistryPolicySettings
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PTransport
import com.TTT.PipeContextProtocol.Transport
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HostedRegistryDefaultsTest
{
    @Test
    fun publicRegistryHostUsesExpectedPolicyAndStoreDefaults()
    {
        val tempFile = Files.createTempFile("hosted-registry-defaults", ".json")
        val host = HostedRegistryDefaults.buildPublicRegistryHost(
            HostedRegistryConfiguration(
                registryName = "defaults-public-registry",
                transport = P2PTransport(Transport.Tpipe, "defaults-public-registry"),
                storeMode = HostedRegistryStoreMode.FILE_JSON,
                durableFilePath = tempFile.toString()
            )
        )

        try
        {
            P2PRegistry.register(
                agent = host,
                transport = host.getP2pTransport(),
                descriptor = host.getP2pDescription(),
                requirements = host.getP2pRequirements()
            )

            val info = runBlocking {
                P2PHostedRegistryClient.getRegistryInfo(host.getP2pTransport())
            }

            assertNotNull(info)
            assertEquals("defaults-public-registry", info.registryName)
            assertEquals("file-json", info.durableStoreKind)
            assertTrue(!info.requireAuthForRead)
            assertTrue(info.requireAuthForWrite)
        }
        finally
        {
            P2PRegistry.remove(host.getP2pTransport())
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun privateRegistryHostRequiresAuthenticatedReads()
    {
        val host = HostedRegistryDefaults.buildPrivateRegistryHost(
            HostedRegistryConfiguration(
                registryName = "defaults-private-registry",
                transport = P2PTransport(Transport.Tpipe, "defaults-private-registry"),
                policySettings = P2PHostedRegistryPolicySettings(
                    authMechanism = { it == "reader-token" },
                    operatorRefs = mutableSetOf("operator-token")
                )
            )
        )

        try
        {
            P2PRegistry.register(
                agent = host,
                transport = host.getP2pTransport(),
                descriptor = host.getP2pDescription(),
                requirements = host.getP2pRequirements()
            )

            val info = runBlocking {
                P2PHostedRegistryClient.getRegistryInfo(
                    transport = host.getP2pTransport(),
                    authBody = "reader-token"
                )
            }

            assertNotNull(info)
            assertTrue(info.requireAuthForRead)
            assertTrue(info.requireAuthForWrite)
            assertTrue(info.operatorManaged)
        }
        finally
        {
            P2PRegistry.remove(host.getP2pTransport())
        }
    }

    @Test
    fun trustedSourceAndGridHelpersSeedExpectedQueries()
    {
        val source = HostedRegistryDefaults.trustedPublicAgentSource(
            TrustedRegistrySourceConfiguration(
                sourceId = "trusted-public-agents",
                transport = P2PTransport(Transport.Http, "https://catalog.example"),
                autoPullOnRegister = true,
                categories = mutableListOf("research/agent"),
                tags = mutableListOf("search"),
                requireVerificationEvidence = true,
                minimumRemainingLeaseMillis = 10_000L
            )
        )

        assertEquals(listOf(P2PHostedListingKind.AGENT), source.query.listingKinds)
        assertEquals(listOf("research/agent"), source.query.categories)
        assertTrue(source.autoPullOnRegister)
        assertTrue(source.admissionPolicy!!.requireVerificationEvidence)
        assertEquals(10_000L, source.admissionPolicy!!.minimumRemainingLeaseMillis)

        val bootstrap = HostedRegistryDefaults.bootstrapCatalogSource(
            DistributionGridBootstrapCatalogConfiguration(
                sourceId = "bootstrap-source",
                transport = P2PTransport(Transport.Http, "https://grid-catalog.example"),
                authBody = "bootstrap-auth",
                transportAuthBody = "Bearer bootstrap-auth",
                autoPullOnInit = true,
                categories = mutableListOf("grid/registry"),
                trustDomainIds = mutableListOf("public-grid")
            )
        )

        assertEquals(listOf(P2PHostedListingKind.GRID_REGISTRY), bootstrap.query.listingKinds)
        assertEquals(listOf("grid/registry"), bootstrap.query.categories)
        assertEquals(listOf("public-grid"), bootstrap.query.trustDomainIds)
        assertEquals("bootstrap-auth", bootstrap.authBody)
        assertEquals("Bearer bootstrap-auth", bootstrap.transportAuthBody)
        assertTrue(bootstrap.autoPullOnInit)
    }

    @Test
    fun publicListingOptionsHelperIsPureMetadataScaffolding()
    {
        val options = HostedRegistryDefaults.publicListingOptions(
            DistributionGridPublicListingDefaultsConfiguration(
                title = "Public Grid Node",
                summary = "Published through defaults helpers.",
                categories = mutableListOf("grid/node"),
                tags = mutableListOf("worker", "public"),
                requestedLeaseSeconds = 600
            )
        )

        assertEquals("Public Grid Node", options.title)
        assertEquals("Published through defaults helpers.", options.summary)
        assertEquals(listOf("grid/node"), options.categories)
        assertEquals(listOf("worker", "public"), options.tags)
        assertEquals(600, options.requestedLeaseSeconds)
    }
}
