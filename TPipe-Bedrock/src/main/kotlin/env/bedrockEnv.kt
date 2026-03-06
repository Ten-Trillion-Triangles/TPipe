package env

import java.io.File
import java.security.PublicKey

/**
 * Environment configuration object for managing AWS Bedrock model to inference profile mappings.
 * Handles loading, searching, and filtering of available foundation models across AWS regions.
 */
object bedrockEnv
{
    /**
     * Internal map storing model ID to inference profile ID mappings.
     * Key: AWS Bedrock foundation model ID
     * Value: User-configured inference profile ID (empty string if using direct model calls)
     */
    private val modelToInferenceMap = mutableMapOf<String, String>()
    
    private var configLoaded = false

    /**
     * Internal stores for aws public and secret keys. This allows for programmatic key retrieval instead
     * of depending on env vars, or the credential file. [bedrockPipe.BedrockPipe.init] will check for this
     * data first and attempt to set the keys using this if the public key is not empty.
     */
    private var publicKey = ""
    private var secretKey = ""

    /**
     * Programmatic setter for defining aws security credentials needed to access AWS bedrock
     * services.
     */
    fun setKeys(public: String, secret: String)
    {
        publicKey = public
        secretKey = secret
    }

    /**
     * Programmatic getter for reading the stored keys. Returns both as a pair.
     * A is the public key. B is the secret key.
     */
    fun getKeys() : Pair<String, String>
    {
        return Pair<String, String>(publicKey, secretKey)
    }
    
    /**
     * Loads inference configuration from ~/.aws/inference.txt file.
     * Creates default configuration file if it doesn't exist.
     * File format: modelId=inferenceProfileId
     */
    fun loadInferenceConfig()
    {
        val configFile = File(System.getProperty("user.home"), ".aws/inference.txt")
        if(!configFile.exists())
        {
            createDefaultInferenceConfig(configFile)
        }
        configFile.readLines().forEach { line ->
            if(line.contains("="))
            {
                val (key, value) = line.split("=", limit = 2)
                modelToInferenceMap[key.trim()] = value.trim()
            }
        }
        configLoaded = true
    }
    
    /**
     * Creates default inference configuration file with all available AWS Bedrock foundation models.
     * Models are listed with empty inference profile IDs for user configuration.
     * 
     * @param configFile The configuration file to create
     */
    private fun createDefaultInferenceConfig(configFile: File)
    {
        configFile.parentFile.mkdirs()
        val serverlessModels = listOf(
            // AI21 Jamba models
            "ai21.jamba-1-5-large-v1:0",
            "ai21.jamba-1-5-mini-v1:0",
            // Amazon Nova models
            "amazon.nova-lite-v1:0",
            "amazon.nova-lite-v1:0:24k",
            "amazon.nova-lite-v1:0:300k",
            "amazon.nova-micro-v1:0",
            "amazon.nova-micro-v1:0:128k",
            "amazon.nova-micro-v1:0:24k",
            "amazon.nova-premier-v1:0",
            "amazon.nova-premier-v1:0:1000k",
            "amazon.nova-premier-v1:0:20k",
            "amazon.nova-premier-v1:0:8k",
            "amazon.nova-premier-v1:0:mm",
            "amazon.nova-pro-v1:0",
            "amazon.nova-pro-v1:0:24k",
            "amazon.nova-pro-v1:0:300k",
            "amazon.nova-sonic-v1:0",
            "amazon.nova-2-lite-v1:0",
            "amazon.nova-2-pro-v1:0",
            // Amazon Titan models
            "amazon.titan-text-express-v1",
            "amazon.titan-text-express-v1:0:8k",
            "amazon.titan-text-lite-v1",
            "amazon.titan-text-lite-v1:0:4k",
            "amazon.titan-text-premier-v1:0",
            // Anthropic Claude models
            "anthropic.claude-3-5-haiku-20241022-v1:0",
            "anthropic.claude-3-5-sonnet-20240620-v1:0",
            "anthropic.claude-3-5-sonnet-20240620-v1:0:18k",
            "anthropic.claude-3-5-sonnet-20240620-v1:0:200k",
            "anthropic.claude-3-5-sonnet-20240620-v1:0:51k",
            "anthropic.claude-3-5-sonnet-20241022-v2:0",
            "anthropic.claude-3-5-sonnet-20241022-v2:0:18k",
            "anthropic.claude-3-5-sonnet-20241022-v2:0:200k",
            "anthropic.claude-3-5-sonnet-20241022-v2:0:51k",
            "anthropic.claude-3-7-sonnet-20250219-v1:0",
            "anthropic.claude-3-haiku-20240307-v1:0",
            "anthropic.claude-3-haiku-20240307-v1:0:200k",
            "anthropic.claude-3-haiku-20240307-v1:0:48k",
            "anthropic.claude-3-opus-20240229-v1:0",
            "anthropic.claude-3-opus-20240229-v1:0:12k",
            "anthropic.claude-3-opus-20240229-v1:0:200k",
            "anthropic.claude-3-opus-20240229-v1:0:28k",
            "anthropic.claude-3-sonnet-20240229-v1:0",
            "anthropic.claude-3-sonnet-20240229-v1:0:200k",
            "anthropic.claude-3-sonnet-20240229-v1:0:28k",
            "anthropic.claude-instant-v1",
            "anthropic.claude-instant-v1:2:100k",
            "anthropic.claude-opus-4-1-20250805-v1:0",
            "anthropic.claude-opus-4-20250514-v1:0",
            "anthropic.claude-sonnet-4-20250514-v1:0",
            "anthropic.claude-v2",
            "anthropic.claude-v2:0:100k",
            "anthropic.claude-v2:0:18k",
            "anthropic.claude-v2:1",
            "anthropic.claude-v2:1:18k",
            "anthropic.claude-v2:1:200k",
            // Cohere models
            "cohere.command-light-text-v14",
            "cohere.command-light-text-v14:7:4k",
            "cohere.command-r-plus-v1:0",
            "cohere.command-r-v1:0",
            "cohere.command-text-v14",
            "cohere.command-text-v14:7:4k",
            "cohere.rerank-v3-5:0",
            // DeepSeek models
            "deepseek.r1-v1:0",
            "deepseek.v3-v1:0",
            // Luma models
            "luma.ray-v2:0",
            // Meta Llama models
            "meta.llama3-1-405b-instruct-v1:0",
            "meta.llama3-1-70b-instruct-v1:0",
            "meta.llama3-1-70b-instruct-v1:0:128k",
            "meta.llama3-1-8b-instruct-v1:0",
            "meta.llama3-1-8b-instruct-v1:0:128k",
            "meta.llama3-2-11b-instruct-v1:0",
            "meta.llama3-2-11b-instruct-v1:0:128k",
            "meta.llama3-2-1b-instruct-v1:0",
            "meta.llama3-2-1b-instruct-v1:0:128k",
            "meta.llama3-2-3b-instruct-v1:0",
            "meta.llama3-2-3b-instruct-v1:0:128k",
            "meta.llama3-2-90b-instruct-v1:0",
            "meta.llama3-2-90b-instruct-v1:0:128k",
            "meta.llama3-3-70b-instruct-v1:0",
            "meta.llama3-70b-instruct-v1:0",
            "meta.llama3-8b-instruct-v1:0",
            "meta.llama4-maverick-17b-instruct-v1:0",
            "meta.llama4-scout-17b-instruct-v1:0",
            // Mistral models
            "mistral.mistral-7b-instruct-v0:2",
            "mistral.mistral-large-2402-v1:0",
            "mistral.mistral-large-2407-v1:0",
            "mistral.mistral-small-2402-v1:0",
            "mistral.mixtral-8x7b-instruct-v0:1",
            "mistral.pixtral-large-2502-v1:0",
            // OpenAI models (us-west-2 only)
            "openai.gpt-oss-120b-1:0",
            "openai.gpt-oss-20b-1:0",
            // TwelveLabs models
            "twelvelabs.pegasus-1-2-v1:0",
            // Writer models
            "writer.palmyra-x4-v1:0",
            "writer.palmyra-x5-v1:0",
            // MiniMax models
            "minimax.minimax-m2",
            "moonshot.kimi-k2-thinking",
            "moonshot.kimi-k2-thinking:0",
            // Inference profile models (us.* prefixed access)
            "us.amazon.nova-lite-v1:0",
            "us.amazon.nova-2-lite-v1:0",
            "us.amazon.nova-2-pro-v1:0",
            "us.amazon.nova-micro-v1:0",
            "us.amazon.nova-premier-v1:0",
            "us.amazon.nova-pro-v1:0",
            "eu.amazon.nova-2-lite-v1:0",
            "apac.amazon.nova-2-lite-v1:0",
            "global.amazon.nova-2-lite-v1:0",
            "us.anthropic.claude-3-5-haiku-20241022-v1:0",
            "us.anthropic.claude-3-5-sonnet-20240620-v1:0",
            "us.anthropic.claude-3-5-sonnet-20241022-v2:0",
            "us.anthropic.claude-3-7-sonnet-20250219-v1:0",
            "us.anthropic.claude-3-haiku-20240307-v1:0",
            "us.anthropic.claude-3-opus-20240229-v1:0",
            "us.anthropic.claude-3-sonnet-20240229-v1:0",
            "us.anthropic.claude-opus-4-1-20250805-v1:0",
            "us.anthropic.claude-opus-4-20250514-v1:0",
            "us.anthropic.claude-sonnet-4-20250514-v1:0",
            "us.deepseek.r1-v1:0",
            "us.meta.llama3-1-70b-instruct-v1:0",
            "us.meta.llama3-1-8b-instruct-v1:0",
            "us.meta.llama3-2-11b-instruct-v1:0",
            "us.meta.llama3-2-1b-instruct-v1:0",
            "us.meta.llama3-2-3b-instruct-v1:0",
            "us.meta.llama3-2-90b-instruct-v1:0",
            "us.meta.llama3-3-70b-instruct-v1:0",
            "us.meta.llama4-maverick-17b-instruct-v1:0",
            "us.meta.llama4-scout-17b-instruct-v1:0",
            "us.mistral.pixtral-large-2502-v1:0",
            "us.twelvelabs.pegasus-1-2-v1:0",
            "us.writer.palmyra-x4-v1:0",
            "us.writer.palmyra-x5-v1:0"
        )
        configFile.writeText(serverlessModels.joinToString("\n") { "$it=" })
    }
    
    /**
     * Retrieves the inference profile ID for a given model ID.
     * 
     * @param modelId The AWS Bedrock foundation model ID
     * @return The inference profile ID if configured, null if not found, empty string for direct calls
     */
    fun getInferenceProfileId(modelId: String): String? = modelToInferenceMap[modelId]
    
    /**
     * Searches for models containing the specified query string (case-insensitive).
     * 
     * @param query The search term to match against model IDs
     * @return Sorted list of matching model IDs
     */
    fun searchModels(query: String): List<String> {
        return modelToInferenceMap.keys.filter { 
            it.contains(query, ignoreCase = true) 
        }.sorted()
    }
    
    /**
     * Retrieves all models from a specific provider.
     * 
     * @param provider The provider name (e.g., "anthropic", "meta", "amazon")
     * @return Sorted list of model IDs from the specified provider
     */
    fun getModelsByProvider(provider: String): List<String> {
        return modelToInferenceMap.keys.filter { 
            it.startsWith(provider.lowercase(), ignoreCase = true) 
        }.sorted()
    }
    
    /**
     * Retrieves all available model IDs.
     * 
     * @return Sorted list of all model IDs in the configuration
     */
    fun getAllModels(): List<String> = modelToInferenceMap.keys.sorted()
    
    /**
     * Advanced search function with filtering by region, provider, and exact model match.
     * 
     * @param region AWS region code (e.g., "us-east-1", "eu-west-1") - filters by regional availability
     * @param provider Provider name prefix (e.g., "anthropic", "meta") - case-insensitive
     * @param exactModel Exact model ID to match - case-insensitive
     * @return Map of model ID to inference profile ID for matching models
     */
    fun searchModelsAdvanced(
        region: String? = null,
        provider: String? = null,
        exactModel: String? = null
    ): Map<String, String> {
        return modelToInferenceMap.filter { (modelId, profileId) ->
            val matchesRegion = region?.let { 
                isModelAvailableInRegion(modelId, it) 
            } ?: true
            
            val matchesProvider = provider?.let { 
                modelId.startsWith(it.lowercase(), ignoreCase = true) 
            } ?: true
            
            val matchesExact = exactModel?.let { 
                modelId.equals(it, ignoreCase = true) 
            } ?: true
            
            matchesRegion && matchesProvider && matchesExact
        }
    }
    
    /**
     * Determines if a model is available in the specified AWS region.
     * Based on AWS Bedrock regional model availability.
     * 
     * @param modelId The AWS Bedrock foundation model ID
     * @param region The AWS region code
     * @return True if the model is available in the region, false otherwise
     */
    private fun isModelAvailableInRegion(modelId: String, region: String): Boolean {
        // Region-specific model availability based on AWS Bedrock documentation
        return when(region.lowercase()) {
            "us-east-1", "us-west-2" -> true // All models available
            "eu-west-1", "eu-central-1" -> {
                !modelId.contains("openai") && 
                !modelId.contains("llama3-1-405b") &&
                !modelId.contains("llama4") &&
                !modelId.contains("writer.palmyra")
            }
            "ap-southeast-1", "ap-northeast-1" -> {
                modelId.startsWith("anthropic") || 
                modelId.startsWith("amazon.nova") ||
                modelId.startsWith("amazon.titan-text-express")
            }
            "ca-central-1" -> {
                modelId.startsWith("anthropic.claude-3-haiku") ||
                modelId.startsWith("anthropic.claude-3-sonnet") ||
                modelId.startsWith("amazon.titan") ||
                modelId.startsWith("meta.llama3-") ||
                modelId.startsWith("mistral")
            }
            else -> false
        }
    }
    
    /**
     * Binds an inference profile ID to a model ID and saves the configuration to file.
     * Updates both the in-memory map and the ~/.aws/inference.txt file.
     * 
     * @param modelId The AWS Bedrock foundation model ID
     * @param inferenceProfileId The inference profile ID to bind (empty string for direct calls)
     * @return True if successful
     */
    fun bindInferenceProfile(modelId: String, inferenceProfileId: String): Boolean {
        modelToInferenceMap[modelId] = inferenceProfileId
        
        if(configLoaded)
        {
            val configFile = File(System.getProperty("user.home"), ".aws/inference.txt")
            val content = modelToInferenceMap.entries.sortedBy { it.key }
                .joinToString("\n") { "${it.key}=${it.value}" }
            
            configFile.writeText(content)
        }
        return true
    }
}
