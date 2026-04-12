package openrouterPipe.examples

import openrouterPipe.OpenRouterPipe
import com.TTT.Enums.ProviderName
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/**
 * Streaming usage examples for OpenRouterPipe.
 *
 * Demonstrates real-time streaming responses with callback-based chunk processing.
 * Streaming is useful for long-form content generation where users want to see
 * results as they arrive rather than waiting for the complete response.
 *
 * Note: Streaming callbacks must be extracted as typed vals and pipe methods
 * called separately (non-fluent) to avoid Kotlin type inference issues.
 */
fun main(): Unit = runBlocking<Unit> {
    // Example 1: Streaming with callback (non-fluent pattern required)
    val accumulated = StringBuilder()
    val chunkCount = AtomicInteger(0)

    // Callback must be a typed val - cannot be inline in builder chain
    val streamingCallback: suspend (String) -> Unit = { chunk ->
        accumulated.append(chunk)
        chunkCount.incrementAndGet()
        print(chunk) // Print each chunk as it arrives
    }

    val streamingPipe = OpenRouterPipe()
    streamingPipe.setProvider(ProviderName.OpenRouter)
    streamingPipe.setModel("deepseek/deepseek-chat-v3-0324:free")
    streamingPipe.setApiKey(System.getenv("OPENROUTER_API_KEY") ?: "")
    streamingPipe.setSystemPrompt("You are a helpful assistant that provides detailed explanations.")
    streamingPipe.setTemperature(0.7)
    streamingPipe.setMaxTokens(500)
    streamingPipe.setStreamingEnabled(true)
    streamingPipe.setStreamingCallback(streamingCallback)

    streamingPipe.init()
    println("\n--- Streaming Response ---")
    val result = streamingPipe.execute("Explain how computers work in one sentence.")
    println("\n--- Full Accumulated Response ---")
    println(accumulated.toString())
    println("--- Chunk count: ${chunkCount.get()} ---")
    println("--- Pipe Return Value ---")
    println(result)
    streamingPipe.abort()

    // Example 2: Streaming with multiple callbacks
    val secondAccumulated = StringBuilder()
    val secondChunkCount = AtomicInteger(0)

    val callback1: suspend (String) -> Unit = { _ ->
        secondChunkCount.incrementAndGet()
    }
    val callback2: suspend (String) -> Unit = { chunk ->
        secondAccumulated.append(chunk)
    }

    val multiCallbackPipe = OpenRouterPipe()
    multiCallbackPipe.setProvider(ProviderName.OpenRouter)
    multiCallbackPipe.setModel("anthropic/claude-3-haiku:free")
    multiCallbackPipe.setApiKey(System.getenv("OPENROUTER_API_KEY") ?: "")
    multiCallbackPipe.setSystemPrompt("You are a concise assistant.")
    multiCallbackPipe.setTemperature(0.5)
    multiCallbackPipe.setMaxTokens(200)
    multiCallbackPipe.setStreamingEnabled(true)
    multiCallbackPipe.setStreamingCallback(callback1)
    multiCallbackPipe.setStreamingCallback(callback2)

    multiCallbackPipe.init()
    val multiResult = multiCallbackPipe.execute("What is AI?")
    println("\n--- Multi-Callback Test ---")
    println("Chunks received: ${secondChunkCount.get()}")
    println("Accumulated text: $secondAccumulated")
    println("Pipe return: $multiResult")
    multiCallbackPipe.abort()

    // Example 3: Non-streaming comparison
    println("\n--- Non-Streaming Comparison ---")
    val nonStreamingPipe = OpenRouterPipe()
    nonStreamingPipe.setProvider(ProviderName.OpenRouter)
    nonStreamingPipe.setModel("deepseek/deepseek-chat-v3-0324:free")
    nonStreamingPipe.setApiKey(System.getenv("OPENROUTER_API_KEY") ?: "")
    nonStreamingPipe.setSystemPrompt("You are a helpful assistant.")
    nonStreamingPipe.setTemperature(0.7)
    nonStreamingPipe.setMaxTokens(200)

    nonStreamingPipe.init()
    val nonStreamingResult = nonStreamingPipe.execute("What is 2+2?")
    println("Non-streaming result: $nonStreamingResult")
    nonStreamingPipe.abort()
}
