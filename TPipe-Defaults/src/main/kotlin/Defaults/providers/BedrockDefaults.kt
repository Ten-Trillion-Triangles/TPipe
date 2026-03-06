package Defaults.providers

import Defaults.BedrockConfiguration
import Defaults.ManifoldDefaults
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.Pipeline
import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Pipeline.TaskProgress

/**
 * Internal factory for creating minimal Bedrock-configured Manifold instances.
 */
internal object BedrockDefaults 
{
    /**
     * Creates a Manifold instance with basic Bedrock configuration.
     *
     * @param config Bedrock configuration with model, region, and API settings
     * @return Manifold instance with basic Bedrock pipes configured
     */
    fun createManifold(config: BedrockConfiguration): Manifold 
    {
        val managerPipeline = ManifoldDefaults.buildDefaultManagerPipeline(config)
        
        return Manifold().apply {
            setManagerPipeline(managerPipeline)
        }
    }
    
    /**
     * Creates manager pipeline with specified number of Bedrock pipes.
     *
     * @param config Configuration containing pipe count and Bedrock settings
     * @return Pipeline with configured number of Bedrock pipes
     */
    fun createManagerPipeline(config: BedrockConfiguration): Pipeline
    {
        val pipeline = Pipeline()
        
        //Create the specified number of pipes for the manager pipeline
        for(i in 1 .. config.pipeCount)
        {
            val pipe = createBedrockPipe(config)
            pipeline.add(pipe)
        }

        return pipeline
    }
    
    /**
     * Creates worker pipe with basic Bedrock configuration.
     *
     * @param config Bedrock configuration settings
     * @return Configured BedrockPipe for worker tasks
     */
    fun createWorkerPipe(config: BedrockConfiguration): BedrockMultimodalPipe
    {
        return createBedrockPipe(config)
    }
    
    /**
     * Creates a configured BedrockPipe with all provider-specific settings.
     *
     * @param config Bedrock configuration with all necessary parameters
     * @return Fully configured BedrockPipe instance
     */
    fun createBedrockPipe(config: BedrockConfiguration): BedrockMultimodalPipe
    {
        return BedrockMultimodalPipe().apply {
            //Set model - if inference profile is provided, use it directly as the model ID
            if(config.inferenceProfile.isNotEmpty())
            {
                setModel(config.inferenceProfile)
            }
            
            else 
            {
                setModel(config.model)
            }
            
            setRegion(config.region)
            
            //Set API type based on configuration
            if(config.useConverseApi)
            {
                useConverseApi()
            }
        }
    }
}
