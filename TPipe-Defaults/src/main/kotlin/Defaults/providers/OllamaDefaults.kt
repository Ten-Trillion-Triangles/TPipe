package Defaults.providers

import Defaults.OllamaConfiguration
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.TaskProgress
import ollamaPipe.OllamaPipe

/**
 * Internal factory for creating minimal Ollama-configured Manifold instances.
 */
internal object OllamaDefaults 
{
    /**
     * Creates a Manifold instance with basic Ollama configuration.
     *
     * @param config Ollama configuration with model, host, and connection settings
     * @return Manifold instance with basic Ollama pipes configured
     */
    fun createManifold(config: OllamaConfiguration): Manifold 
    {
        val managerPipeline = createManagerPipeline(config)
        
        return Manifold().apply {
            setManagerPipeline(managerPipeline)
        }
    }
    
    /**
     * Creates manager pipeline with specified number of Ollama pipes.
     *
     * @param config Configuration containing pipe count and Ollama settings
     * @return Pipeline with configured number of Ollama pipes
     */
    fun createManagerPipeline(config: OllamaConfiguration): Pipeline
    {
        val pipeline = Pipeline()
        
        //Create the specified number of pipes for the manager pipeline
        for (i in 2..config.pipeCount)
        {
            val pipe = createOllamaPipe(config)
            pipeline.add(pipe)
        }
        
        return pipeline
    }
    
    /**
     * Creates worker pipe with basic Ollama configuration.
     *
     * @param config Ollama configuration settings
     * @return Configured OllamaPipe for worker tasks
     */
    fun createWorkerPipe(config: OllamaConfiguration): OllamaPipe
    {
        return createOllamaPipe(config)
    }
    
    /**
     * Creates a configured OllamaPipe with all provider-specific settings.
     *
     * @param config Ollama configuration with all necessary parameters
     * @return Fully configured OllamaPipe instance
     */
    fun createOllamaPipe(config: OllamaConfiguration): OllamaPipe
    {
        return OllamaPipe().apply {
            setModel(config.model)
            setIP(config.host)
            setPort(config.port)

            setJsonInput(TaskProgress())
            
            //Note: OllamaPipe doesn't appear to have timeout or HTTPS methods
            //These would need to be handled through other configuration
        }
    }
}
