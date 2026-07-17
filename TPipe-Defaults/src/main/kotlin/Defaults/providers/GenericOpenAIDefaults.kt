package Defaults.providers

import Defaults.GenericOpenAIConfiguration
import Defaults.ManifoldDefaults
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.Pipeline
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.ApiMode

/**
 * Internal factory for creating minimal GenericOpenAI-configured Manifold instances.
 *
 * The GenericOpenAI pipe is provider-agnostic — it speaks the OpenAI wire format but
 * the underlying `baseUrl` can target any OpenAI-compatible endpoint (OpenAI, MiniMax,
 * Together, Anyscale, local Ollama, vLLM, llama.cpp, ...).
 */
internal object GenericOpenAIDefaults
{
    /**
     * Creates a Manifold instance with basic GenericOpenAI configuration.
     *
     * @param config GenericOpenAI configuration with model, apiKey, and endpoint settings.
     * @return Manifold instance with configured GenericOpenAI pipes.
     */
    fun createManifold(config: GenericOpenAIConfiguration): Manifold
    {
        val managerPipeline = createManagerPipeline(config)

        return Manifold().apply {
            setManagerPipeline(managerPipeline)
            ManifoldDefaults.applyManifoldMemoryConfiguration(this, config.manifoldMemory)
        }
    }

    /**
     * Creates a manager pipeline with the configured number of GenericOpenAI pipes.
     *
     * @param config Configuration containing pipe count and GenericOpenAI settings.
     * @return Pipeline with the configured number of GenericOpenAI pipes.
     */
    fun createManagerPipeline(config: GenericOpenAIConfiguration): Pipeline
    {
        val pipeline = Pipeline()

        for(i in 1..config.pipeCount)
        {
            val pipe = createGenericOpenAIPipe(config)
            pipeline.add(pipe)
        }

        return pipeline
    }

    /**
     * Creates a worker pipe with basic GenericOpenAI configuration.
     *
     * @param config GenericOpenAI configuration settings.
     * @return Configured GenericOpenAIPipe ready to use as a worker.
     */
    fun createWorkerPipe(config: GenericOpenAIConfiguration): GenericOpenAIPipe
    {
        return createGenericOpenAIPipe(config)
    }

    /**
     * Creates a configured GenericOpenAIPipe with all provider-specific settings applied.
     *
     * @param config GenericOpenAI configuration with all necessary parameters.
     * @return Fully configured GenericOpenAIPipe instance.
     */
    fun createGenericOpenAIPipe(config: GenericOpenAIConfiguration): GenericOpenAIPipe
    {
        return GenericOpenAIPipe().apply {
            setModel(config.model)

            if(config.apiKey.isNotBlank())
            {
                setApiKey(config.apiKey)
            }
            if(config.baseUrl.isNotBlank())
            {
                setBaseUrl(config.baseUrl)
            }

            /**
             * The pipe exposes a sealed [ApiMode] type. The configuration hands us a string
             * ("OpenAI", "OpenAIResponses", "Anthropic") so callers do not need to drag the
             * GenericOpenAI package surface into their import graph. Unknown values fall back
             * to the OpenAI default, matching the pipe's own default.
             */
            val resolvedMode = when(config.apiMode)
            {
                "Anthropic" -> ApiMode.Anthropic
                "OpenAIResponses" -> ApiMode.OpenAIResponses
                else -> ApiMode.OpenAI
            }
            setApiMode(resolvedMode)

            config.parallelToolCalls?.let { setParallelToolCalls(it) }
            config.structuredOutputs?.let { setStructuredOutputs(it) }
        }
    }
}
