package Defaults

import com.TTT.Debug.TraceConfig
import com.TTT.Enums.ContextWindowSettings
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PRequirements
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipeline.DistributionGridDurableStore
import com.TTT.Pipeline.DistributionGridMemoryPolicy
import com.TTT.Pipeline.DistributionGridPeerDiscoveryMode
import com.TTT.Pipeline.DistributionGridRegistryAdvertisement
import com.TTT.Pipeline.DistributionGridRegistryMetadata
import com.TTT.Pipeline.DistributionGridRoutingPolicy
import com.TTT.Pipeline.DistributionGridTrustVerifier
import env.bedrockEnv

/**
 * Base configuration interface for provider-specific parameters.
 * Each provider implements this to define their required configuration.
 */
sealed class ProviderConfiguration 
{
    /**
     * Validates the configuration parameters for this provider.
     *
     * @return true if configuration is valid, false otherwise
     */
    abstract fun validate(): Boolean
}

/**
 * Configuration surface for Manifold-specific manager-memory behavior in `TPipe-Defaults`.
 *
 * @param enableManagerBudgetControl If true, enable built-in manager shared-history control on created manifolds.
 * @param managerTokenBudget Optional explicit token budget for manager shared-history control.
 * @param managerContextWindowSize Optional compatibility context-window override when explicit manager token budgeting is not supplied.
 * @param managerTruncationMethod Optional truncation method for the manager shared-history path.
 */
data class ManifoldMemoryConfiguration(
    var enableManagerBudgetControl: Boolean = true,
    var managerTokenBudget: TokenBudgetSettings? = null,
    var managerContextWindowSize: Int? = null,
    var managerTruncationMethod: ContextWindowSettings? = null
)

/**
 * Configuration for AWS Bedrock provider with region, credentials, and model settings.
 *
 * @param region AWS region for Bedrock API calls (required)
 * @param model Bedrock model identifier to use (required)
 * @param pipeCount Number of pipes to create in the manager pipeline
 * @param inferenceProfile Optional inference profile for binding calls
 * @param useConverseApi Whether to use Converse API instead of Invoke API
 * @param accessKey AWS access key (optional if using profile or IAM)
 * @param secretKey AWS secret key (optional if using profile or IAM)
 * @param sessionToken AWS session token for temporary credentials (optional)
 * @param profileName AWS profile name to use (optional)
 * @param manifoldMemory Manifold manager-memory defaults applied by `TPipe-Defaults`
 */
data class BedrockConfiguration(
    var region: String,
    var model: String,
    var pipeCount: Int = 2,
    var inferenceProfile: String = "",
    var useConverseApi: Boolean = true,
    var accessKey: String? = null,
    var secretKey: String? = null,
    var sessionToken: String? = null,
    var profileName: String? = null,
    var manifoldMemory: ManifoldMemoryConfiguration = ManifoldMemoryConfiguration()
) : ProviderConfiguration() 
{
    override fun validate(): Boolean = region.isNotBlank() && model.isNotBlank() && pipeCount > 0

    fun make(region: String, model: String)
    {
        val inferenceProfile = bedrockEnv.getInferenceProfileId(model)
        this.inferenceProfile = inferenceProfile ?: ""
        this.region = region
        this.model = model
    }
}

/**
 * Configuration for Ollama provider with host, model, and connection settings.
 *
 * @param model Model name to use (required)
 * @param pipeCount Number of pipes to create in the manager pipeline
 * @param host Ollama server host
 * @param port Ollama server port
 * @param timeout Connection timeout in milliseconds
 * @param useHttps Whether to use HTTPS for connections
 * @param manifoldMemory Manifold manager-memory defaults applied by `TPipe-Defaults`
 */
data class OllamaConfiguration(
    val model: String,
    val pipeCount: Int = 2,
    val host: String = "localhost",
    val port: Int = 11434,
    val timeout: Long = 30000,
    val useHttps: Boolean = false,
    val manifoldMemory: ManifoldMemoryConfiguration = ManifoldMemoryConfiguration()
) : ProviderConfiguration() 
{
    override fun validate(): Boolean = host.isNotBlank() && model.isNotBlank() && port > 0 && pipeCount > 0
}

/**
 * Optional descriptor or requirements seed for one defaults-owned `DistributionGrid` role.
 *
 * @param descriptor Optional explicit descriptor override for the seeded role.
 * @param requirements Optional explicit requirements override for the seeded role.
 */
data class DistributionGridRoleSeed(
    var descriptor: P2PDescriptor? = null,
    var requirements: P2PRequirements? = null
)

/**
 * Optional non-provider defaults that may be mirrored into the `DistributionGrid` DSL along with provider-backed
 * router and worker roles.
 *
 * Unset values intentionally mean "leave the core DSL or runtime default alone."
 *
 * @param p2pDescriptor Optional outward grid-node descriptor seed.
 * @param p2pRequirements Optional outward grid-node requirements seed.
 * @param routerSeed Optional descriptor or requirements seed for the defaults-owned router role.
 * @param workerSeed Optional descriptor or requirements seed for the defaults-owned worker role.
 * @param routingPolicy Optional routing-policy seed.
 * @param memoryPolicy Optional memory-policy seed.
 * @param traceConfig Optional trace configuration seed.
 * @param discoveryMode Optional discovery-mode seed.
 * @param registryMetadata Optional registry-metadata seed.
 * @param trustVerifier Optional trust verifier seed.
 * @param bootstrapRegistries Optional bootstrap registry seeds.
 * @param durableStore Optional durable-store seed.
 * @param maxHops Optional `DistributionGrid.setMaxHops(...)` seed.
 * @param rpcTimeoutMillis Optional `DistributionGrid.setRpcTimeout(...)` seed.
 * @param maxSessionDurationSeconds Optional `DistributionGrid.setMaxSessionDuration(...)` seed.
 */
data class DistributionGridDefaultsConfiguration(
    var p2pDescriptor: P2PDescriptor? = null,
    var p2pRequirements: P2PRequirements? = null,
    var routerSeed: DistributionGridRoleSeed = DistributionGridRoleSeed(),
    var workerSeed: DistributionGridRoleSeed = DistributionGridRoleSeed(),
    var routingPolicy: DistributionGridRoutingPolicy? = null,
    var memoryPolicy: DistributionGridMemoryPolicy? = null,
    var traceConfig: TraceConfig? = null,
    var discoveryMode: DistributionGridPeerDiscoveryMode? = null,
    var registryMetadata: DistributionGridRegistryMetadata? = null,
    var trustVerifier: DistributionGridTrustVerifier? = null,
    var bootstrapRegistries: MutableList<DistributionGridRegistryAdvertisement> = mutableListOf(),
    var durableStore: DistributionGridDurableStore? = null,
    var maxHops: Int? = null,
    var rpcTimeoutMillis: Long? = null,
    var maxSessionDurationSeconds: Int? = null
)

/**
 * Grid-specific Bedrock defaults configuration.
 *
 * @param region AWS region for Bedrock API calls.
 * @param model Bedrock model identifier to use.
 * @param inferenceProfile Optional inference profile for binding calls.
 * @param useConverseApi Whether to use Converse API instead of Invoke API.
 * @param accessKey AWS access key.
 * @param secretKey AWS secret key.
 * @param sessionToken AWS session token for temporary credentials.
 * @param profileName AWS profile name to use.
 * @param gridDefaults Optional non-provider `DistributionGrid` defaults to mirror into the DSL.
 */
data class BedrockGridConfiguration(
    var region: String,
    var model: String,
    var inferenceProfile: String = "",
    var useConverseApi: Boolean = true,
    var accessKey: String? = null,
    var secretKey: String? = null,
    var sessionToken: String? = null,
    var profileName: String? = null,
    var gridDefaults: DistributionGridDefaultsConfiguration = DistributionGridDefaultsConfiguration()
) : ProviderConfiguration()
{
    override fun validate(): Boolean = region.isNotBlank() && model.isNotBlank()

    internal fun toProviderConfiguration(): BedrockConfiguration
    {
        return BedrockConfiguration(
            region = region,
            model = model,
            pipeCount = 1,
            inferenceProfile = inferenceProfile,
            useConverseApi = useConverseApi,
            accessKey = accessKey,
            secretKey = secretKey,
            sessionToken = sessionToken,
            profileName = profileName
        )
    }
}

/**
 * Grid-specific Ollama defaults configuration.
 *
 * @param model Model name to use.
 * @param host Ollama host.
 * @param port Ollama port.
 * @param timeout Connection timeout in milliseconds.
 * @param useHttps Whether to use HTTPS for connections.
 * @param gridDefaults Optional non-provider `DistributionGrid` defaults to mirror into the DSL.
 */
data class OllamaGridConfiguration(
    val model: String,
    val host: String = "localhost",
    val port: Int = 11434,
    val timeout: Long = 30000,
    val useHttps: Boolean = false,
    val gridDefaults: DistributionGridDefaultsConfiguration = DistributionGridDefaultsConfiguration()
) : ProviderConfiguration()
{
    override fun validate(): Boolean = host.isNotBlank() && model.isNotBlank() && port > 0

    internal fun toProviderConfiguration(): OllamaConfiguration
    {
        return OllamaConfiguration(
            model = model,
            pipeCount = 1,
            host = host,
            port = port,
            timeout = timeout,
            useHttps = useHttps
        )
    }
}
