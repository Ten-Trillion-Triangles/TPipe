package cli

import env.bedrockEnv

/**
 * Command line interface for managing AWS Bedrock model to inference profile mappings.
 * 
 * Provides both interactive and command-line modes for configuring model bindings
 * in the ~/.aws/inference.txt file. Supports searching, filtering, and binding
 * operations for AWS Bedrock foundation models.
 * 
 * Usage:
 * - Interactive mode: Run without arguments
 * - Command mode: kotlin -cp jars cli.InferenceConfigCli [command] [args]
 * 
 * Commands:
 * - list [filter] - List all models or filter by term
 * - search <term> - Search models containing term
 * - provider <name> - List models by provider
 * - region <region> - List models available in region
 * - bind <model> <profile> - Bind model to inference profile
 * - help - Show help information
 */
object InferenceConfigCli
{
    
    /**
     * Main entry point for the CLI application.
     * 
     * Loads inference configuration and routes to either command handling
     * or interactive mode based on arguments provided.
     * 
     * @param args Command line arguments
     */
    @JvmStatic
    fun main(args: Array<String>)
    {
        println("AWS Bedrock Inference Profile Configuration Tool")
        println("=".repeat(50))
        
        // Load inference profile mappings from configuration file
        bedrockEnv.loadInferenceConfig()
        
        if(args.isNotEmpty())
        {
            handleCommand(args)
        }

        else
        {
            interactiveMode()
        }
    }
    
    /**
     * Handles command-line mode operations.
     * 
     * Parses the first argument as a command and routes to appropriate
     * handler functions with remaining arguments.
     * 
     * @param args Command line arguments array
     */
    private fun handleCommand(args: Array<String>)
    {
        // Route command to appropriate handler based on first argument
        when(args[0].lowercase())
        {
            "list" -> listModels(args.getOrNull(1))
            "search" -> searchModels(args.getOrNull(1) ?: "")
            "bind" -> bindModel(args.getOrNull(1), args.getOrNull(2))
            "provider" -> listByProvider(args.getOrNull(1))
            "region" -> listByRegion(args.getOrNull(1))
            "help" -> showHelp()
            else -> {
                println("Unknown command: ${args[0]}")
                showHelp()
            }
        }
    }
    
    /**
     * Runs the interactive command loop.
     * 
     * Displays menu options and processes user input until exit is selected.
     * Provides numbered menu interface for all available operations.
     */
    private fun interactiveMode()
    {
        while(true)
        {
            println("\nOptions:")
            println("1. List all models")
            println("2. Search models")
            println("3. List by provider")
            println("4. List by region")
            println("5. Bind inference profile")
            println("6. Show current bindings")
            println("7. Exit")
            print("Choose option (1-7): ")
            
            when(readLine()?.trim())
            {
                "1" -> listModels()
                "2" -> {
                    print("Enter search term: ")
                    searchModels(readLine()?.trim() ?: "")
                }
                "3" -> {
                    print("Enter provider (anthropic, meta, amazon, etc.): ")
                    listByProvider(readLine()?.trim())
                }
                "4" -> {
                    print("Enter region (us-east-1, eu-west-1, etc.): ")
                    listByRegion(readLine()?.trim())
                }
                "5" -> {
                    print("Enter model ID: ")
                    val modelId = readLine()?.trim()
                    print("Enter inference profile ID (empty for direct call): ")
                    val profileId = readLine()?.trim() ?: ""
                    bindModel(modelId, profileId)
                }
                "6" -> showCurrentBindings()
                "7" -> {
                    println("Goodbye!")
                    return
                }
                else -> println("Invalid option. Please choose 1-7.")
            }
        }
    }
    
    /**
     * Lists available models with optional filtering.
     * 
     * Shows all models or filters by search term. Displays current
     * inference profile bindings for each model.
     * 
     * @param filter Optional search term to filter model names
     */
    private fun listModels(filter: String? = null)
    {
        // Get models list - all models or filtered by search term
        val models: List<String> = if(filter.isNullOrEmpty())
        {
            bedrockEnv.getAllModels()
        }

        else
        {
            bedrockEnv.searchModels(filter)
        }
        
        println("\nAvailable models:")
        // Display each model with its current binding status
        models.forEachIndexed { index: Int, model: String ->
            val profileId = bedrockEnv.getInferenceProfileId(model)
            val binding = if(profileId.isNullOrEmpty()) "direct" else profileId
            println("${index + 1}. $model -> $binding")
        }
        println("Total: ${models.size} models")
    }
    
    /**
     * Searches for models containing the specified query string.
     * 
     * Performs case-insensitive search across all model IDs and
     * displays matching results with their current bindings.
     * 
     * @param query Search term to match against model names
     */
    private fun searchModels(query: String)
    {
        if(query.isEmpty())
        {
            println("Please provide a search term.")
            return
        }
        
        // Perform case-insensitive search and display results
        val results: List<String> = bedrockEnv.searchModels(query)
        println("\nSearch results for '$query':")
        results.forEachIndexed { index: Int, model: String ->
            val profileId = bedrockEnv.getInferenceProfileId(model)
            val binding = if(profileId.isNullOrEmpty()) "direct" else profileId
            println("${index + 1}. $model -> $binding")
        }
        println("Found: ${results.size} models")
    }
    
    /**
     * Lists models from a specific provider.
     * 
     * Filters models by provider prefix (e.g., 'anthropic', 'meta', 'amazon')
     * and displays them with their current inference profile bindings.
     * 
     * @param provider Provider name to filter by (case-insensitive)
     */
    private fun listByProvider(provider: String?)
    {
        if(provider.isNullOrEmpty())
        {
            println("Please provide a provider name.")
            return
        }
        
        // Filter models by provider prefix and display results
        val models: List<String> = bedrockEnv.getModelsByProvider(provider)
        println("\nModels from provider '$provider':")
        models.forEachIndexed { index: Int, model: String ->
            val profileId = bedrockEnv.getInferenceProfileId(model)
            val binding = if(profileId.isNullOrEmpty()) "direct" else profileId
            println("${index + 1}. $model -> $binding")
        }
        println("Total: ${models.size} models")
    }
    
    /**
     * Lists models available in a specific AWS region.
     * 
     * Uses regional availability data to filter models and shows
     * which models are accessible in the specified region.
     * 
     * @param region AWS region code (e.g., 'us-east-1', 'eu-west-1')
     */
    private fun listByRegion(region: String?)
    {
        if(region.isNullOrEmpty())
        {
            println("Please provide a region name.")
            return
        }
        
        // Use advanced search to filter by regional availability
        val models: Map<String, String> = bedrockEnv.searchModelsAdvanced(region = region)
        println("\nModels available in region '$region':")
        models.entries.forEachIndexed { index: Int, entry: Map.Entry<String, String> ->
            val (model, profileId) = entry
            val binding = if(profileId.isEmpty()) "direct" else profileId
            println("${index + 1}. $model -> $binding")
        }
        println("Total: ${models.size} models")
    }
    
    /**
     * Binds a model to an inference profile or direct calls.
     * 
     * Updates the model configuration to use either an inference profile
     * ARN or direct model calls. Saves changes to the configuration file.
     * 
     * @param modelId AWS Bedrock model ID to configure
     * @param inferenceProfileId Inference profile ID or empty for direct calls
     */
    private fun bindModel(modelId: String?, inferenceProfileId: String?)
    {
        if(modelId.isNullOrEmpty())
        {
            println("Please provide a model ID.")
            return
        }
        
        // Bind model to inference profile or direct calls
        val profileId = inferenceProfileId ?: ""
        val success = bedrockEnv.bindInferenceProfile(modelId, profileId)
        
        if(success)
        {
            val binding = if(profileId.isEmpty()) "direct calls" else "profile: $profileId"
            println("✓ Successfully bound $modelId to $binding")
        }
        else
        {
            println("✗ Failed to bind model. Model ID not found: $modelId")
            println("Use 'search' or 'list' to find valid model IDs.")
        }
    }
    
    /**
     * Displays all current inference profile bindings.
     * 
     * Shows which models are bound to inference profiles and provides
     * a summary of total bound vs unbound models.
     */
    private fun showCurrentBindings()
    {
        // Get all models and filter for those with inference profile bindings
        val allModels: List<String> = bedrockEnv.getAllModels()
        val boundModels = allModels.filter { model: String -> 
            val profileId = bedrockEnv.getInferenceProfileId(model)
            !profileId.isNullOrEmpty()
        }
        
        println("\nCurrent inference profile bindings:")
        // Display bound models or indicate if none are bound
        if(boundModels.isEmpty())
        {
            println("No models are bound to inference profiles (all using direct calls)")
        }
        else
        {
            boundModels.forEachIndexed { index: Int, model: String ->
                val profileId = bedrockEnv.getInferenceProfileId(model)
                println("${index + 1}. $model -> $profileId")
            }
        }
        println("Total bound: ${boundModels.size}/${allModels.size} models")
    }
    
    /**
     * Displays comprehensive help information.
     * 
     * Shows usage examples, available commands, supported providers,
     * and configuration file format details.
     */
    private fun showHelp()
    {
        println("\nUsage:")
        println("  kotlin -cp build/libs/tpipe-bedrock.jar cli.InferenceConfigCli [command] [args]")
        println("\nCommands:")
        println("  list [filter]     - List all models or filter by term")
        println("  search <term>     - Search models containing term")
        println("  provider <name>   - List models by provider")
        println("  region <region>   - List models available in region")
        println("  bind <model> <profile> - Bind model to inference profile")
        println("  help              - Show this help")
        println("\nExamples:")
        println("  kotlin -cp build/libs/tpipe-bedrock.jar cli.InferenceConfigCli list claude")
        println("  kotlin -cp build/libs/tpipe-bedrock.jar cli.InferenceConfigCli search anthropic")
        println("  kotlin -cp build/libs/tpipe-bedrock.jar cli.InferenceConfigCli provider meta")
        println("  kotlin -cp build/libs/tpipe-bedrock.jar cli.InferenceConfigCli region us-east-1")
        println("  kotlin -cp build/libs/tpipe-bedrock.jar cli.InferenceConfigCli bind anthropic.claude-3-sonnet-20240229-v1:0 my-profile-id")
        println("\nRun without arguments for interactive mode.")
    }
}