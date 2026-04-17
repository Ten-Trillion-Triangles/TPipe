package Defaults

import com.TTT.Pipeline.HistoryDsl
import com.TTT.Pipeline.ManifoldBuilder
import com.TTT.Pipeline.ManifoldStage
import com.TTT.Pipeline.ManagerDsl

/**
 * Defaults-module DSL bridge for configuring a manifold manager from provider-specific defaults inside the root
 * `manifold { ... }` builder.
 *
 * @param builder The ManifoldBuilder to attach defaults configuration to.
 * @param block Builder block that selects a provider-backed defaults configuration.
 */
inline fun ManifoldBuilder<ManifoldStage.Initial>.defaults(crossinline block: DefaultsManifoldDsl.() -> Unit)
{
    val dsl = DefaultsManifoldDsl(this)
    dsl.block()
}

/**
 * Provider-specific defaults bridge for the manifold DSL.
 * Used inside a `manifold { }` builder block via the `defaults { }` method.
 *
 * @param builder The ManifoldBuilder to configure.
 */
class DefaultsManifoldDsl(private val builder: ManifoldBuilder<ManifoldStage.Initial>)
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
        builder.manager {
            defaultsPipeline(ManifoldDefaults.buildDefaultManagerPipeline(configuration))
        }
        builder.history {
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
        builder.manager {
            defaultsPipeline(ManifoldDefaults.buildDefaultManagerPipeline(configuration))
        }
        builder.history {
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