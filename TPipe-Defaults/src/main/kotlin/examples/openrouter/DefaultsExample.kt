package examples.openrouter

import Defaults.OpenRouterConfiguration
import Defaults.ManifoldDefaults
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.Pipeline
import openrouterPipe.OpenRouterPipe
import com.TTT.Enums.ProviderName
import kotlinx.coroutines.runBlocking

/**
 * TPipe-Defaults integration examples for OpenRouter.
 *
 * Demonstrates creating OpenRouter-backed Manifolds and Pipelines
 * using the TPipe-Defaults factory methods.
 *
 * @see ManifoldDefaults
 * @see OpenRouterConfiguration
 */
fun main(): Unit = runBlocking<Unit> {
    // Example 1: Create a simple OpenRouter Manifold with defaults
    val simpleConfig = OpenRouterConfiguration(
        model = "deepseek/deepseek-chat-v3-0324:free",
        apiKey = System.getenv("OPENROUTER_API_KEY") ?: "",
        pipeCount = 2
    )

    val simpleManifold = ManifoldDefaults.withOpenRouter(simpleConfig)
    println("Created simple OpenRouter Manifold: $simpleManifold")

    // Example 2: Create a Manifold with custom base URL (for proxy/custom endpoints)
    val customUrlConfig = OpenRouterConfiguration(
        model = "anthropic/claude-3-sonnet:free",
        apiKey = System.getenv("OPENROUTER_API_KEY") ?: "",
        pipeCount = 3,
        baseUrl = "https://openrouter.ai/api/v1" // Default, can be overridden
    )

    val customManifold = ManifoldDefaults.withOpenRouter(customUrlConfig)
    println("Created OpenRouter Manifold with custom URL: $customManifold")

    // Example 3: Build a default manager pipeline directly
    val pipelineConfig = OpenRouterConfiguration(
        model = "google/gemini-2.0-flash-exp:free",
        apiKey = System.getenv("OPENROUTER_API_KEY") ?: "",
        pipeCount = 2
    )

    val managerPipeline = ManifoldDefaults.buildDefaultManagerPipeline(pipelineConfig)
    println("Created default manager pipeline: $managerPipeline")

    // Example 4: Create with OpenRouter-specific headers
    val headerConfig = OpenRouterConfiguration(
        model = "meta-llama/llama-3-8b-instruct:free",
        apiKey = System.getenv("OPENROUTER_API_KEY") ?: "",
        pipeCount = 2,
        httpReferer = "https://my-app.example.com",
        openRouterTitle = "My TPipe Application"
    )

    val headerManifold = ManifoldDefaults.withOpenRouter(headerConfig)
    println("Created OpenRouter Manifold with headers: $headerManifold")

    // Example 5: Check provider availability
    val providers = ManifoldDefaults.getAvailableProviders()
    println("Available providers: $providers")
    println("OpenRouter available: ${ManifoldDefaults.isProviderAvailable("openrouter")}")

    // Example 6: Use OpenRouterDefaults factory directly for advanced control
    val advancedConfig = OpenRouterConfiguration(
        model = "deepseek/deepseek-chat-v3-0324:free",
        apiKey = System.getenv("OPENROUTER_API_KEY") ?: "",
        pipeCount = 4
    )

    // Create pipeline directly
    val pipeline = Pipeline()
    for (i in 1..advancedConfig.pipeCount) {
        val pipe = OpenRouterPipe()
        pipe.setProvider(ProviderName.OpenRouter)
        pipe.setModel(advancedConfig.model)
        pipe.setApiKey(advancedConfig.apiKey)
        pipeline.add(pipe)
    }
    println("Created advanced pipeline with ${pipeline.getPipes().size} pipes")
}
