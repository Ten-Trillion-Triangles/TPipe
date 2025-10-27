package Defaults

import Defaults.providers.BedrockDefaults
import Defaults.providers.OllamaDefaults
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.Pipeline
import com.TTT.Enums.ProviderName
import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Context.ConverseHistory
import com.TTT.Enums.ContextWindowSettings
import com.TTT.P2P.AgentRequest
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TruncationSettings
import com.TTT.Pipeline.TaskProgress
import ollamaPipe.OllamaPipe

/**
 * Central factory for creating pre-configured Manifold instances with provider-specific defaults.
 * This module handles all provider integration while keeping the base TPipe module clean.
 */
object ManifoldDefaults 
{
    /**
     * Creates a Manifold instance configured for AWS Bedrock with optimized defaults.
     *
     * @param configuration Bedrock-specific configuration including region, credentials, and model settings
     * @return Fully configured Manifold instance ready for multi-agent orchestration
     * @throws IllegalArgumentException if configuration is invalid
     * @throws RuntimeException if Bedrock provider is not available
     */
    fun withBedrock(configuration: BedrockConfiguration): Manifold 
    {
        require(configuration.validate()) { "Invalid Bedrock configuration: $configuration" }
        
        return try 
        {
            BedrockDefaults.createManifold(configuration)
        } catch (e: Exception) 
        {
            throw RuntimeException("Failed to create Bedrock Manifold: ${e.message}", e)
        }
    }
    
    /**
     * Creates a Manifold instance configured for Ollama with optimized defaults.
     *
     * @param configuration Ollama-specific configuration including host, model, and connection settings
     * @return Fully configured Manifold instance ready for multi-agent orchestration
     * @throws IllegalArgumentException if configuration is invalid
     * @throws RuntimeException if Ollama provider is not available
     */
    fun withOllama(configuration: OllamaConfiguration): Manifold 
    {
        require(configuration.validate()) { "Invalid Ollama configuration: $configuration" }
        
        return try 
        {
            OllamaDefaults.createManifold(configuration)
        } catch (e: Exception) 
        {
            throw RuntimeException("Failed to create Ollama Manifold: ${e.message}", e)
        }
    }
    
    /**
     * Lists all available providers that can be used for Manifold configuration.
     *
     * @return List of provider names that have implementations available
     */
    fun getAvailableProviders(): List<String> 
    {
        val providers = mutableListOf<String>()
        
        if (isProviderAvailable("bedrock")) providers.add("bedrock")
        if (isProviderAvailable("ollama")) providers.add("ollama")
        
        return providers
    }
    
    /**
     * Checks if a specific provider is available for use.
     *
     * @param providerName Name of the provider to check
     * @return true if provider is available, false otherwise
     */
    fun isProviderAvailable(providerName: String): Boolean 
    {
        return try 
        {
            when (providerName.lowercase()) 
            {
                "bedrock" -> {
                    Class.forName("bedrockPipe.BedrockPipe")
                    true
                }
                "ollama" -> {
                    Class.forName("ollamaPipe.OllamaPipe")
                    true
                }
                else -> false
            }
        } catch (e: ClassNotFoundException) 
        {
            false
        }
    }
    
    /**
     * Builder that constructs a solid default manager pipeline already configured to act as a manager for worker pipes.
     * Provides a solid set of instructions to cover most general cases that would need a manifold without asking the
     * user to build their own pipeline from scratch.
     *
     * @param bedrockConfig Bedrock configuration for AWS provider
     * @return Configured Pipeline ready to be used as a manager pipeline
     */
    fun buildDefaultManagerPipeline(bedrockConfig: BedrockConfiguration): Pipeline
    {
        val managerPipeline = BedrockDefaults.createManagerPipeline(bedrockConfig)
        assignManagerPipelineDefaults(managerPipeline)
        return managerPipeline
    }
    
    /**
     * Builder that constructs a solid default manager pipeline already configured to act as a manager for worker pipes.
     * Provides a solid set of instructions to cover most general cases that would need a manifold without asking the
     * user to build their own pipeline from scratch.
     *
     * @param ollamaConfig Ollama configuration for local Ollama provider
     * @return Configured Pipeline ready to be used as a manager pipeline
     */
    fun buildDefaultManagerPipeline(ollamaConfig: OllamaConfiguration): Pipeline
    {
        val managerPipeline = OllamaDefaults.createManagerPipeline(ollamaConfig)
        assignManagerPipelineDefaults(managerPipeline)
        return managerPipeline
    }


    fun assignManagerPipelineDefaults(pipeline: Pipeline) : Pipeline
    {
        //Get the 3 pipes this pipeline is supposed to have by default.
        val allPipes = pipeline.getPipes()

        //We can't handle custom user pipelines as this is a default creator. So throw if we don't match.
        if(allPipes.size != 2)
        {
            throw IllegalArgumentException("The pipeline must have exactly 2 pipes.")
        }

        /**
         * First pipe in the pipeline. This pipe is responsible for looking at the status of the task. And determining
         * where it stands and what actions might be needed to complete it.
         *
         * Will pass the converse history as pipeline context. So forwards pipes will need to pull pipeline context.
         */
        val entryPipe = allPipes[0]
            .setPipeName("entry pipe")
            .setJsonOutput(TaskProgress())
            .setTemperature(.6)
            .setTopP(.65)
            .requireJsonPromptInjection()
            .setMaxTokens(32000)
            .setContextWindowSize(108000)
            .autoTruncateContext()
            .setContextWindowSettings(ContextWindowSettings.TruncateTop)
            .setSystemPrompt("""Your job is to examine a given task and determine if it has been completed or not.
                |You will be given a json ConverseHistory object that contains the conversion between the user,
                |the system assistant, and the agents the system assistant has called to work on completing the job
                |the user has given them.
            """.trimMargin())
            .setJsonInput(ConverseHistory())
            .setMiddlePrompt("""You must examine this json conversation history and determine if the task has been
                |definitively, beyond any shadow of a doubt, solved.
            """.trimMargin())
            .setJsonOutput(TaskProgress())
            .setFooterPrompt("""You must set the boolean in the above json to true if the task has been completed.
                |Otherwise, you must fill the json and return it as your output.
            """.trimMargin())
            .updatePipelineContextOnExit()
            .applySystemPrompt()

        /**
         * Second pipe in the pipeline. This pipe handles making the actual agent to call to the target agent
         * running inside our manifold.
         */
        val agentSelectorPipe = allPipes[1]
            .setPipeName("Agent caller pipe")
            .setTemperature(.6)
            .setTopP(.7)
            .requireJsonPromptInjection()
            .setMaxTokens(32000)
            .setContextWindowSize(108000)
            .setContextWindowSettings(ContextWindowSettings.TruncateTop)
            .autoTruncateContext()
            .pullPipelineContext()
            .setSystemPrompt("""You are an AI agent manager. Your job is to determine what AI agent to give the
                |next steps of a given task to.
            """.trimMargin())
            .setJsonInput(TaskProgress())
            .setJsonOutput(AgentRequest())
            .setMiddlePrompt("""
                
                - taskDescription informs you of what the overall task is.
                
                - nextTaskInstructions informs you of what the next steps needed to take are. You must consider
                what AI agent in your agent list is best suited for the job.
                
                - taskProgressStatus is a short summary of what you has been done so far. There will also be a far
                larger full conversation history supplied later.
                
                - isTaskComplete denotes if the task has already been finished.
            """.trimIndent())
            .autoInjectContext("""You will be provided with the full conversation history between
                |the user, the system, and the agents that have worked on this task thus far. Each role is as follows:
                |
                |- System: This is your role. Any parts of the conversation with this role were actions you, or another
                |manager above you has taken in managing this task.
                |
                |- User: This is the original user prompt. This was passed to this manager system to automate solving
                |the given task.
                |
                |- Agent: This is any agent that you have previously issued a task to. It contains their response, and
                |actions they took based on your input.
                |
            """.trimMargin())
            .setFooterPrompt("""#Important
                |1. You must only return a Pipe-To-Pipe agent protocol json response. 
                |2. You must very clearly instruct the agent exactly what you need done. And place these instructions
                |into the p2p prompt variable.
                |3. You may request tool calls the agent has in advance if the agent has listed a given tool you are 
                | trying to request is available.
                | 
                | ##Finally
                | You are designed to solve any task at hand. You must always find an agent to 
                | hand the next part of the task off to. Always pick the available agent that is best suited to the
                | next steps of finishing the given task.
                | 
            """.trimMargin())

        return pipeline

    }
}
