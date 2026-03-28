package Defaults

import com.TTT.Pipeline.DistributionGridDsl

/**
 * Defaults-module DSL bridge for configuring a `DistributionGrid` from provider-specific defaults inside the root
 * `distributionGrid { ... }` builder.
 *
 * @param block Builder block that selects one provider-backed defaults configuration.
 */
fun DistributionGridDsl.defaults(block: DefaultsDistributionGridDsl.() -> Unit)
{
    val builder = DefaultsDistributionGridDsl(this)
    builder.block()
}

/**
 * Provider-specific defaults bridge for the `DistributionGrid` DSL.
 *
 * @param gridDsl Root `DistributionGrid` DSL being configured.
 */
class DefaultsDistributionGridDsl(private val gridDsl: DistributionGridDsl)
{
    private var providerConfigured = false

    /**
     * Configure the grid from a Bedrock defaults configuration.
     *
     * @param configuration Bedrock grid defaults configuration.
     */
    fun bedrock(configuration: BedrockGridConfiguration)
    {
        require(!providerConfigured) { "DistributionGrid defaults already selected a provider." }
        require(configuration.validate()) { "Invalid Bedrock grid configuration: $configuration" }
        providerConfigured = true
        DistributionGridDefaults.applyBedrockDefaults(gridDsl, configuration)
    }

    /**
     * Configure the grid from an Ollama defaults configuration.
     *
     * @param configuration Ollama grid defaults configuration.
     */
    fun ollama(configuration: OllamaGridConfiguration)
    {
        require(!providerConfigured) { "DistributionGrid defaults already selected a provider." }
        require(configuration.validate()) { "Invalid Ollama grid configuration: $configuration" }
        providerConfigured = true
        DistributionGridDefaults.applyOllamaDefaults(gridDsl, configuration)
    }
}
