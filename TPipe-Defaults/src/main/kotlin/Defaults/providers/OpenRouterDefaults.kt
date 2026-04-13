package Defaults.providers

import Defaults.OpenRouterConfiguration
import Defaults.ManifoldDefaults
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.Pipeline
import openrouterPipe.OpenRouterPipe

/**
 * Internal factory for creating minimal OpenRouter-configured Manifold instances.
 */
internal object OpenRouterDefaults
{
    /**
     * Creates a Manifold instance with basic OpenRouter configuration.
     *
     * @param config OpenRouter configuration with model, API key, and endpoint settings
     * @return Manifold instance with basic OpenRouter pipes configured
     */
    fun createManifold(config: OpenRouterConfiguration): Manifold
    {
        val managerPipeline = createManagerPipeline(config)

        return Manifold().apply {
            setManagerPipeline(managerPipeline)
            ManifoldDefaults.applyManifoldMemoryConfiguration(this, config.manifoldMemory)
        }
    }

    /**
     * Creates manager pipeline with specified number of OpenRouter pipes.
     *
     * @param config Configuration containing pipe count and OpenRouter settings
     * @return Pipeline with configured number of OpenRouter pipes
     */
    fun createManagerPipeline(config: OpenRouterConfiguration): Pipeline
    {
        val pipeline = Pipeline()

        for(i in 1..config.pipeCount)
        {
            val pipe = createOpenRouterPipe(config)
            pipeline.add(pipe)
        }

        return pipeline
    }

    /**
     * Creates worker pipe with basic OpenRouter configuration.
     *
     * @param config OpenRouter configuration settings
     * @return Configured OpenRouterPipe for worker tasks
     */
    fun createWorkerPipe(config: OpenRouterConfiguration): OpenRouterPipe
    {
        return createOpenRouterPipe(config)
    }

    /**
     * Creates a configured OpenRouterPipe with all provider-specific settings.
     *
     * @param config OpenRouter configuration with all necessary parameters
     * @return Fully configured OpenRouterPipe instance
     */
    fun createOpenRouterPipe(config: OpenRouterConfiguration): OpenRouterPipe
    {
        return OpenRouterPipe().apply {
            setModel(config.model)
            setApiKey(config.apiKey)

            if(config.baseUrl.isNotBlank())
            {
                setBaseUrl(config.baseUrl)
            }
            if(config.httpReferer.isNotBlank())
            {
                setHttpReferer(config.httpReferer)
            }
            if(config.openRouterTitle.isNotBlank())
            {
                setOpenRouterTitle(config.openRouterTitle)
            }
            // Extended parameters
            config.reasoningEffort?.let { setReasoningEffort(it) }
            config.cacheControl?.let { setCacheControl(it) }
            config.serviceTier?.let { setServiceTier(it) }
            config.sessionId?.let { setSessionId(it) }
            config.logprobs?.let { setLogprobs(it) }
            config.topLogprobs?.let { setTopLogprobs(it) }
            config.minP?.let { setMinP(it) }
            config.topA?.let { setTopA(it) }
            config.providerPreferences?.let { setProviderPreferences(it) }
            config.plugins?.let { setPlugins(it) }
            config.responseFormat?.let { setResponseFormat(it.type, it.jsonSchema) }
        }
    }
}
