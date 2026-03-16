package Defaults

import com.TTT.Pipeline.HistoryDsl
import com.TTT.Pipeline.ManifoldDsl
import com.TTT.Pipeline.ManagerDsl

/**
 * Defaults-module DSL bridge for configuring a manifold manager from provider-specific defaults inside the root
 * `manifold { ... }` builder.
 *
 * @param block Builder block that selects a provider-backed defaults configuration.
 */
fun ManifoldDsl.defaults(block: DefaultsManifoldDsl.() -> Unit)
{
    val builder = DefaultsManifoldDsl(this)
    builder.block()
}

/**
 * Provider-specific defaults bridge for the manifold DSL.
 *
 * @param manifoldDsl Root manifold DSL being configured.
 */
class DefaultsManifoldDsl(private val manifoldDsl: ManifoldDsl)
{
    /**
     * Configure the manifold manager from a Bedrock defaults configuration.
     *
     * @param configuration Bedrock manager defaults configuration.
     */
    fun bedrock(configuration: BedrockConfiguration)
    {
        require(configuration.validate()) { "Invalid Bedrock configuration: $configuration" }

        /**
         * Mirror both the provider-backed manager pipeline and the provider's manifold-memory configuration so the DSL
         * behaves the same way as ManifoldDefaults.withBedrock(...).
         */
        manifoldDsl.manager {
            defaultsPipeline(ManifoldDefaults.buildDefaultManagerPipeline(configuration))
        }
        manifoldDsl.history {
            applyDefaults(configuration.manifoldMemory)
        }
    }

    /**
     * Configure the manifold manager from an Ollama defaults configuration.
     *
     * @param configuration Ollama manager defaults configuration.
     */
    fun ollama(configuration: OllamaConfiguration)
    {
        require(configuration.validate()) { "Invalid Ollama configuration: $configuration" }

        /**
         * Mirror both the provider-backed manager pipeline and the provider's manifold-memory configuration so the DSL
         * behaves the same way as ManifoldDefaults.withOllama(...).
         */
        manifoldDsl.manager {
            defaultsPipeline(ManifoldDefaults.buildDefaultManagerPipeline(configuration))
        }
        manifoldDsl.history {
            applyDefaults(configuration.manifoldMemory)
        }
    }
}

/**
 * Bind a manager pipeline that uses the defaults-module standard agent-dispatch pipe naming convention.
 *
 * @param pipeline Provider-backed manager pipeline produced by `TPipe-Defaults`.
 */
fun ManagerDsl.defaultsPipeline(pipeline: com.TTT.Pipeline.Pipeline)
{
    pipeline(pipeline)
    agentDispatchPipe("Agent caller pipe")
}

/**
 * Apply a `TPipe-Defaults` manager-memory configuration to the manifold DSL history block.
 *
 * @param memoryConfiguration Defaults-module manager-memory configuration to mirror into the core DSL.
 */
fun HistoryDsl.applyDefaults(memoryConfiguration: ManifoldMemoryConfiguration)
{
    if(memoryConfiguration.managerTruncationMethod != null)
    {
        defaultsTruncationMethod(memoryConfiguration.managerTruncationMethod!!)
    }

    if(memoryConfiguration.managerContextWindowSize != null)
    {
        defaultsContextWindowSize(memoryConfiguration.managerContextWindowSize!!)
    }

    if(memoryConfiguration.enableManagerBudgetControl || memoryConfiguration.managerTokenBudget != null)
    {
        autoTruncate()
    }

    if(memoryConfiguration.managerTokenBudget != null)
    {
        managerTokenBudget(memoryConfiguration.managerTokenBudget!!)
    }
}
