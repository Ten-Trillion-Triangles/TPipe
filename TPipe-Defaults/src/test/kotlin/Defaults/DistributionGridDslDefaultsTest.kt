package Defaults

import com.TTT.Debug.TraceConfig
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.DistributionGrid
import com.TTT.Pipeline.DistributionGridDirective
import com.TTT.Pipeline.DistributionGridDirectiveKind
import com.TTT.Pipeline.DistributionGridDurableState
import com.TTT.Pipeline.DistributionGridDurableStore
import com.TTT.Pipeline.DistributionGridFailure
import com.TTT.Pipeline.DistributionGridNodeAdvertisement
import com.TTT.Pipeline.DistributionGridNodeMetadata
import com.TTT.Pipeline.DistributionGridPeerDiscoveryMode
import com.TTT.Pipeline.DistributionGridProtocolVersion
import com.TTT.Pipeline.DistributionGridRegistryAdvertisement
import com.TTT.Pipeline.DistributionGridRegistryMetadata
import com.TTT.Pipeline.DistributionGridRegistryMode
import com.TTT.Pipeline.DistributionGridRoutingPolicy
import com.TTT.Pipeline.DistributionGridTracePolicy
import com.TTT.Pipeline.DistributionGridTrustVerifier
import com.TTT.Pipeline.distributionGrid
import com.TTT.PipeContextProtocol.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression coverage for the `TPipe-Defaults` `DistributionGrid` DSL bridge and raw defaults factory.
 */
class DistributionGridDslDefaultsTest
{
    /**
     * Verifies that Bedrock defaults seed provider-backed router and worker pipelines and that the defaults router
     * can short-circuit from a caller-supplied directive without invoking the model.
     */
    @Test
    fun bedrockDefaultsConfigureRunnableGrid()
    {
        val grid = distributionGrid {
            defaults {
                bedrock(
                    BedrockGridConfiguration(
                        region = "us-east-1",
                        model = "anthropic.claude-3-haiku-20240307-v1:0"
                    )
                )
            }
        }

        val pipelineNames = grid.getPipelinesFromInterface()
            .flatMap { it.getPipes() }
            .map { it.pipeName }
            .sorted()

        assertEquals(
            listOf("distribution-grid-default-router", "distribution-grid-default-worker"),
            pipelineNames
        )

        val content = MultimodalContent(text = "Return immediately.")
        content.metadata["distributionGridDirective"] = DistributionGridDirective(
            kind = DistributionGridDirectiveKind.RETURN_TO_SENDER,
            notes = "caller supplied return"
        )

        val result = kotlinx.coroutines.runBlocking {
            grid.executeLocal(content)
        }

        val outcome = result.metadata["distributionGridOutcome"] as? com.TTT.Pipeline.DistributionGridOutcome
        assertEquals("Return immediately.", result.text)
        assertEquals(com.TTT.Pipeline.DistributionGridDirectiveKind.RETURN_TO_SENDER.name, outcome?.returnMode?.name)
    }

    /**
     * Verifies that broad optional policy defaults are mirrored into the core `DistributionGrid` DSL only when
     * explicitly supplied.
     */
    @Test
    fun ollamaDefaultsApplyOptionalPolicySeeds()
    {
        val durableStore = RecordingDurableStore()
        val trustVerifier = AllowAllTrustVerifier()
        val bootstrapRegistry = buildRegistryAdvertisement(
            registryId = "defaults-registry",
            transportAddress = "https://registry.example"
        )

        val grid = distributionGrid {
            defaults {
                ollama(
                    OllamaGridConfiguration(
                        model = "llama3.1",
                        gridDefaults = DistributionGridDefaultsConfiguration(
                            p2pDescriptor = buildGridDescriptor(
                                nodeId = "defaults-grid-node",
                                transportAddress = "defaults-grid-node"
                            ).apply {
                                agentDescription = "Defaults-configured grid node"
                            },
                            p2pRequirements = P2PRequirements(
                                allowExternalConnections = true,
                                maxTokens = 4096
                            ),
                            routingPolicy = DistributionGridRoutingPolicy(
                                allowRetrySamePeer = true,
                                maxRetryCount = 2,
                                maxHopCount = 7
                            ),
                            memoryPolicy = com.TTT.Pipeline.DistributionGridMemoryPolicy(
                                outboundTokenBudget = 3072,
                                summaryBudget = 256
                            ),
                            traceConfig = TraceConfig(enabled = true),
                            discoveryMode = DistributionGridPeerDiscoveryMode.HYBRID,
                            registryMetadata = bootstrapRegistry.metadata,
                            trustVerifier = trustVerifier,
                            bootstrapRegistries = mutableListOf(bootstrapRegistry),
                            durableStore = durableStore,
                            maxHops = 12,
                            rpcTimeoutMillis = 91_000L,
                            maxSessionDurationSeconds = 720
                        )
                    )
                )
            }
        }

        assertEquals("defaults-grid-node", grid.getP2pDescription()!!.agentName)
        assertTrue(grid.getP2pRequirements()!!.allowExternalConnections)
        assertEquals(4096, grid.getP2pRequirements()!!.maxTokens)
        assertEquals(7, grid.getRoutingPolicy().maxHopCount)
        assertEquals(3072, grid.getMemoryPolicy().outboundTokenBudget)
        assertEquals(256, grid.getMemoryPolicy().summaryBudget)
        assertTrue(grid.isTracingEnabled())
        assertEquals(DistributionGridPeerDiscoveryMode.HYBRID, grid.getDiscoveryMode())
        assertEquals(listOf("defaults-registry"), grid.getBootstrapRegistryIds())
        assertEquals("defaults-registry", grid.getRegistryMetadata()!!.registryId)
        assertSame(trustVerifier, grid.getTrustVerifier())
        assertSame(durableStore, grid.getDurableStore())
        assertEquals(12, grid.getMaxHops())
        assertEquals(91_000L, grid.getRpcTimeout())
        assertEquals(720, grid.getMaxSessionDuration())
    }

    /**
     * Verifies that the raw defaults factory and the DSL bridge assemble equivalent grids from the same Bedrock
     * defaults configuration.
     */
    @Test
    fun rawFactoryMatchesDslBridge()
    {
        val configuration = BedrockGridConfiguration(
            region = "us-east-1",
            model = "anthropic.claude-3-haiku-20240307-v1:0",
            gridDefaults = DistributionGridDefaultsConfiguration(
                routingPolicy = DistributionGridRoutingPolicy(
                    allowRetrySamePeer = true,
                    maxRetryCount = 1
                ),
                memoryPolicy = com.TTT.Pipeline.DistributionGridMemoryPolicy(
                    outboundTokenBudget = 2048
                )
            )
        )

        val rawGrid = DistributionGridDefaults.withBedrock(configuration)
        val dslGrid = distributionGrid {
            defaults {
                bedrock(configuration)
            }
        }

        assertEquals(
            rawGrid.getPipelinesFromInterface().flatMap { it.getPipes() }.map { it.pipeName }.sorted(),
            dslGrid.getPipelinesFromInterface().flatMap { it.getPipes() }.map { it.pipeName }.sorted()
        )
        assertEquals(rawGrid.getRoutingPolicy(), dslGrid.getRoutingPolicy())
        assertEquals(rawGrid.getMemoryPolicy(), dslGrid.getMemoryPolicy())
    }

    /**
     * Verifies that defaults reject overlapping singleton ownership instead of silently overriding the caller.
     */
    @Test
    fun defaultsFailFastOnConflicts()
    {
        assertFailsWith<IllegalArgumentException> {
            distributionGrid {
                router(com.TTT.Pipeline.Pipeline())
                defaults {
                    bedrock(
                        BedrockGridConfiguration(
                            region = "us-east-1",
                            model = "anthropic.claude-3-haiku-20240307-v1:0"
                        )
                    )
                }
            }
        }

        assertFailsWith<IllegalArgumentException> {
            distributionGrid {
                memory {
                    outboundTokenBudget(1024)
                }
                defaults {
                    ollama(
                        OllamaGridConfiguration(
                            model = "llama3.1",
                            gridDefaults = DistributionGridDefaultsConfiguration(
                                memoryPolicy = com.TTT.Pipeline.DistributionGridMemoryPolicy(
                                    outboundTokenBudget = 2048
                                )
                            )
                        )
                    )
                }
            }
        }
    }

    /**
     * Verifies that additive concerns remain available when defaults leave them unset.
     */
    @Test
    fun defaultsRemainAdditiveForUnsetConcerns()
    {
        val grid = distributionGrid {
            defaults {
                bedrock(
                    BedrockGridConfiguration(
                        region = "us-east-1",
                        model = "anthropic.claude-3-haiku-20240307-v1:0"
                    )
                )
            }

            memory {
                outboundTokenBudget(4096)
            }

            peerDescriptor(
                buildGridDescriptor(
                    nodeId = "external-peer",
                    transportAddress = "https://peer.example",
                    transportMethod = Transport.Http
                )
            )
        }

        assertEquals(4096, grid.getMemoryPolicy().outboundTokenBudget)
        assertEquals(listOf("Http::https://peer.example"), grid.getExternalPeerKeys())
    }

    private fun buildGridDescriptor(
        nodeId: String,
        transportAddress: String,
        transportMethod: Transport = Transport.Tpipe
    ): P2PDescriptor
    {
        return P2PDescriptor(
            agentName = nodeId,
            agentDescription = "DistributionGrid defaults test node",
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
            distributionGridMetadata = DistributionGridNodeMetadata(
                nodeId = nodeId,
                supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
                roleCapabilities = mutableListOf("Router", "Worker"),
                supportedTransports = mutableListOf(transportMethod),
                defaultTracePolicy = DistributionGridTracePolicy(),
                defaultRoutingPolicy = DistributionGridRoutingPolicy(),
                actsAsRegistry = false
            )
        )
    }

    private fun buildRegistryAdvertisement(
        registryId: String,
        transportAddress: String
    ): DistributionGridRegistryAdvertisement
    {
        return DistributionGridRegistryAdvertisement(
            transport = P2PTransport(
                transportMethod = Transport.Http,
                transportAddress = transportAddress
            ),
            metadata = DistributionGridRegistryMetadata(
                registryId = registryId,
                trustDomainId = "primary-trust-domain",
                mode = DistributionGridRegistryMode.DEDICATED
            ),
            discoveredAtEpochMillis = 1L,
            expiresAtEpochMillis = Long.MAX_VALUE
        )
    }

    private class RecordingDurableStore : DistributionGridDurableStore
    {
        override suspend fun saveState(state: DistributionGridDurableState)
        {
        }

        override suspend fun loadState(taskId: String): DistributionGridDurableState?
        {
            return null
        }

        override suspend fun resumeState(taskId: String): DistributionGridDurableState?
        {
            return null
        }

        override suspend fun clearState(taskId: String): Boolean
        {
            return true
        }

        override suspend fun archiveState(taskId: String): Boolean
        {
            return true
        }
    }

    private class AllowAllTrustVerifier : DistributionGridTrustVerifier
    {
        override suspend fun verifyRegistryAdvertisement(
            candidate: DistributionGridRegistryAdvertisement,
            trustedParents: List<DistributionGridRegistryAdvertisement>
        ): DistributionGridFailure?
        {
            return null
        }

        override suspend fun verifyNodeAdvertisement(
            registryAdvertisement: DistributionGridRegistryAdvertisement,
            candidate: DistributionGridNodeAdvertisement
        ): DistributionGridFailure?
        {
            return null
        }
    }
}
