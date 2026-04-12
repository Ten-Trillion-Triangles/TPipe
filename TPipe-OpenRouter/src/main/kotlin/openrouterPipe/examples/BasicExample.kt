package openrouterPipe.examples

import openrouterPipe.OpenRouterPipe
import com.TTT.Enums.ProviderName
import kotlinx.coroutines.runBlocking

/**
 * Basic usage examples for OpenRouterPipe.
 *
 * Demonstrates non-streaming chat completions with various models available through OpenRouter.
 * OpenRouter provides access to 300+ models from providers like Anthropic, OpenAI, Google, DeepSeek, Meta, and more.
 *
 * @see <a href="https://openrouter.ai/models">Available OpenRouter Models</a>
 */
fun main(): Unit = runBlocking<Unit> {
    // Example: DeepSeek Chat (free tier available)
    val deepseekPipe = OpenRouterPipe()
    deepseekPipe.setProvider(ProviderName.OpenRouter)
    deepseekPipe.setModel("deepseek/deepseek-chat-v3-0324:free")
    deepseekPipe.setApiKey(System.getenv("OPENROUTER_API_KEY") ?: "")
    deepseekPipe.setSystemPrompt("You are a helpful AI assistant.")
    deepseekPipe.setTemperature(0.7)
    deepseekPipe.setMaxTokens(500)

    deepseekPipe.init()
    val deepseekResult = deepseekPipe.execute("What is the capital of France?")
    println("DeepSeek response: $deepseekResult")
    deepseekPipe.abort()

    // Example: Claude 3 Sonnet via OpenRouter
    val claudePipe = OpenRouterPipe()
    claudePipe.setProvider(ProviderName.OpenRouter)
    claudePipe.setModel("anthropic/claude-3-sonnet:free")
    claudePipe.setApiKey(System.getenv("OPENROUTER_API_KEY") ?: "")
    claudePipe.setSystemPrompt("You are a precise factual assistant.")
    claudePipe.setTemperature(0.5)
    claudePipe.setMaxTokens(300)

    claudePipe.init()
    val claudeResult = claudePipe.execute("List three benefits of renewable energy.")
    println("Claude response: $claudeResult")
    claudePipe.abort()

    // Example: Google Gemini via OpenRouter
    val geminiPipe = OpenRouterPipe()
    geminiPipe.setProvider(ProviderName.OpenRouter)
    geminiPipe.setModel("google/gemini-2.0-flash-thinking-exp:free")
    geminiPipe.setApiKey(System.getenv("OPENROUTER_API_KEY") ?: "")
    geminiPipe.setSystemPrompt("You are a knowledgeable science tutor.")
    geminiPipe.setTemperature(0.8)
    geminiPipe.setMaxTokens(400)

    geminiPipe.init()
    val geminiResult = geminiPipe.execute("Explain photosynthesis in simple terms.")
    println("Gemini response: $geminiResult")
    geminiPipe.abort()

    // Example: Meta Llama via OpenRouter
    val llamaPipe = OpenRouterPipe()
    llamaPipe.setProvider(ProviderName.OpenRouter)
    llamaPipe.setModel("meta-llama/llama-3-8b-instruct:free")
    llamaPipe.setApiKey(System.getenv("OPENROUTER_API_KEY") ?: "")
    llamaPipe.setSystemPrompt("You are a creative writer.")
    llamaPipe.setTemperature(0.9)
    llamaPipe.setMaxTokens(600)

    llamaPipe.init()
    val llamaResult = llamaPipe.execute("Write a haiku about mountains.")
    println("Llama response: $llamaResult")
    llamaPipe.abort()
}
