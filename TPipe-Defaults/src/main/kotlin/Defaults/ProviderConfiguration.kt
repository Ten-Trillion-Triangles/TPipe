package Defaults

import com.TTT.Enums.ContextWindowSettings
import com.TTT.Pipe.TokenBudgetSettings
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
