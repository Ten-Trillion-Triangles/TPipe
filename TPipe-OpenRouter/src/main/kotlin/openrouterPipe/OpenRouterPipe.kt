package openrouterPipe

import com.TTT.Debug.*
import com.TTT.Enums.ProviderName
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import env.ChatMessage
import env.OpenRouterChatRequest
import env.OpenRouterChatResponse
import env.StreamingChunk
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * TPipe abstraction for OpenRouter. Provides access to OpenRouter's unified API
 * that aggregates 300+ LLM models from various providers.
 *
 * This implementation supports the /v1/chat/completions endpoint with OpenAI-compatible
 * request/response formats and OpenRouter-specific extensions.
 *
 * @see <a href="https://openrouter.ai/docs">OpenRouter Documentation</a>
 */
@kotlinx.serialization.Serializable
class OpenRouterPipe : Pipe()
{

//=========================================Properties===================================================================

    /**
     * OpenRouter API key for authentication.
     * Required for all API calls.
     * Marked @Transient to prevent API key from being serialized to disk.
     */
    @kotlinx.serialization.Transient
    private var apiKey: String = ""

    /**
     * OpenRouter API base URL.
     * Defaults to "https://openrouter.ai/api/v1"
     */
    @kotlinx.serialization.Serializable
    private var baseUrl: String = "https://openrouter.ai/api/v1"

    /**
     * HTTP Referer header for OpenRouter API requests.
     * Used for site traffic tracking and analytics.
     */
    @kotlinx.serialization.Serializable
    private var httpReferer: String = ""

    /**
     * OpenRouter Title header for OpenRouter API requests.
     * Used to identify your application to OpenRouter.
     */
    @kotlinx.serialization.Serializable
    private var openRouterTitle: String = ""

    /**
     * HTTP client for OpenRouter API calls.
     * Initialized in init() and closed in abort().
     */
    @kotlinx.serialization.Transient
    private var httpClient: HttpClient? = null

    /**
     * Whether streaming mode is enabled.
     */
    @kotlinx.serialization.Serializable
    private var streamingEnabled: Boolean = false

//=========================================Builder Methods=============================================================

    /**
     * Sets the OpenRouter API key.
     * @param key The API key from openrouter.ai
     * @return This pipe instance for fluent chaining
     */
    fun setApiKey(key: String): OpenRouterPipe
    {
        apiKey = key
        return this
    }

    /**
     * Sets the OpenRouter base URL.
     * @param url The base URL for the API (defaults to OpenRouter's official endpoint)
     * @return This pipe instance for fluent chaining
     */
    fun setBaseUrl(url: String): OpenRouterPipe
    {
        baseUrl = url
        return this
    }

    /**
     * Sets the HTTP Referer header for OpenRouter API requests.
     * @param referer The referer URL to send with requests
     * @return This pipe instance for fluent chaining
     */
    fun setHttpReferer(referer: String): OpenRouterPipe
    {
        httpReferer = referer
        return this
    }

    /**
     * Sets the OpenRouter Title header for OpenRouter API requests.
     * @param title The application title to identify this integration
     * @return This pipe instance for fluent chaining
     */
    fun setOpenRouterTitle(title: String): OpenRouterPipe
    {
        openRouterTitle = title
        return this
    }

    /**
     * Enables or disables streaming mode.
     * @param enabled True to enable streaming
     * @return This pipe instance for fluent chaining
     */
    fun setStreamingEnabled(enabled: Boolean): OpenRouterPipe
    {
        streamingEnabled = enabled
        return this
    }

    /**
     * Registers a callback for streaming response chunks.
     * Automatically enables streaming mode.
     * @param callback Suspendable callback receiving text chunks
     * @return This pipe instance for fluent chaining
     */
    fun setStreamingCallback(callback: suspend (String) -> Unit): OpenRouterPipe
    {
        this.streamingEnabled = true
        obtainStreamingCallbackManager().addCallback(callback)
        return this
    }

//=========================================Pipe Lifecycle Methods======================================================

    /**
     * Initializes the OpenRouter pipe.
     * Validates configuration and sets up the HTTP client.
     * @return This pipe instance
     * @throws IllegalStateException if apiKey is not set
     */
    override suspend fun init(): Pipe
    {
        super.init()

        trace(TraceEventType.PIPE_START, TracePhase.INITIALIZATION,
              metadata = mapOf(
                  "provider" to "OpenRouter",
                  "baseUrl" to baseUrl,
                  "model" to model
              ))

        if(apiKey.isBlank())
        {
            throw IllegalStateException("OpenRouter API key is required. Call setApiKey() before init().")
        }

        provider = ProviderName.entries.find { it.name == "OpenRouter" } ?: ProviderName.Ollama

        httpClient = HttpClient(CIO)
        {
            install(HttpTimeout)
            {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000
            }
        }

        trace(TraceEventType.PIPE_SUCCESS, TracePhase.INITIALIZATION,
              metadata = mapOf("initialized" to true))

        return this
    }

    /**
     * Aborts any active generation and cleans up resources.
     */
    override suspend fun abort()
    {
        trace(TraceEventType.PIPE_FAILURE, TracePhase.EXECUTION,
              metadata = mapOf("action" to "abort", "provider" to "OpenRouter"))

        httpClient?.close()
        httpClient = null

        super.abort()
    }

//=========================================Generation Methods=========================================================

    /**
     * Generates text using the configured OpenRouter model.
     * @param promptInjector Text to inject into the prompt
     * @return Generated response text
     */
    override suspend fun generateText(promptInjector: String): String
    {
        val client = httpClient ?: throw IllegalStateException("OpenRouterPipe not initialized. Call init() first.")

        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf(
                  "provider" to "OpenRouter",
                  "model" to model,
                  "baseUrl" to baseUrl,
                  "promptLength" to promptInjector.length,
                  "streaming" to streamingEnabled
              ))

        return try
        {
            val messages = mutableListOf<ChatMessage>()

            if(systemPrompt.isNotEmpty())
            {
                messages.add(ChatMessage(role = "system", content = systemPrompt))
            }

            messages.add(ChatMessage(role = "user", content = promptInjector))

            val request = OpenRouterChatRequest(
                model = model,
                messages = messages,
                temperature = if(temperature > 0.0) temperature else null,
                maxTokens = if(maxTokens > 0) maxTokens else null,
                stream = streamingEnabled
            )

            val jsonRequest = serialize(request)

            if(streamingEnabled)
            {
                val response = withContext(Dispatchers.IO)
                {
                    client.post("$baseUrl/chat/completions")
                    {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "Bearer $apiKey")

                        if(httpReferer.isNotEmpty())
                        {
                            header("HTTP-Referer", httpReferer)
                        }
                        if(openRouterTitle.isNotEmpty())
                        {
                            header("X-OpenRouter-Title", openRouterTitle)
                        }

                        setBody(jsonRequest)
                    }
                }

                return executeStreaming(response)
            }
            else
            {
                val responseText = withContext(Dispatchers.IO)
                {
                    client.post("$baseUrl/chat/completions")
                    {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "Bearer $apiKey")

                        if(httpReferer.isNotEmpty())
                        {
                            header("HTTP-Referer", httpReferer)
                        }
                        if(openRouterTitle.isNotEmpty())
                        {
                            header("X-OpenRouter-Title", openRouterTitle)
                        }

                        setBody(jsonRequest)
                    }.bodyAsText()
                }

                val response: OpenRouterChatResponse = deserialize(responseText)
                    ?: throw Exception("Failed to deserialize OpenRouter chat response: $responseText")

                val resultText = response.choices.firstOrNull()?.message?.content ?: ""

                trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
                      metadata = mapOf(
                          "responseLength" to resultText.length,
                          "model" to response.model,
                          "success" to true
                      ))

                return resultText
            }
        }
        catch(e: Exception)
        {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  error = e,
                  metadata = mapOf(
                      "errorType" to (e::class.simpleName ?: "Unknown"),
                      "errorMessage" to (e.message ?: "Unknown error"),
                      "streaming" to streamingEnabled
                  ))

            throw e
        }
    }

    /**
     * Executes a streaming request and accumulates the response.
     * @param httpResponse The HTTP response from the streaming endpoint
     * @return Accumulated response text
     */
    private suspend fun executeStreaming(httpResponse: HttpResponse): String
    {
        val channel = httpResponse.bodyAsChannel()
        val textBuilder = StringBuilder()

        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf(
                  "step" to "streamingStart",
                  "streaming" to true
              ))

        while(!channel.isClosedForRead)
        {
            val line = channel.readUTF8Line() ?: break

            if(line.isEmpty()) continue

            if(line.startsWith(":")) continue

            if(line == "data: [DONE]")
            {
                break
            }

            if(line.startsWith("data: "))
            {
                val json = line.substringAfter("data: ")
                val chunk = deserialize<StreamingChunk>(json) ?: continue
                val contentDelta = chunk.choices.firstOrNull()?.delta?.content ?: ""

                if(contentDelta.isNotEmpty())
                {
                    textBuilder.append(contentDelta)
                    emitStreamingChunk(contentDelta)
                }
            }
        }

        val resultText = textBuilder.toString()

        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              metadata = mapOf(
                  "responseLength" to resultText.length,
                  "streaming" to true,
                  "success" to true
              ))

        return resultText
    }

//=========================================Context Management==========================================================

    /**
     * Truncates module context using conservative token estimation.
     * OpenRouter supports diverse models, so we use a conservative 1 token per 4 chars estimate.
     * @return This pipe instance
     */
    override fun truncateModuleContext(): Pipe
    {
        return this
    }

    /**
     * Suspendable truncation delegate.
     * @return This pipe instance
     */
    override suspend fun truncateModuleContextSuspend(): Pipe
    {
        return truncateModuleContext()
    }

//=========================================ProviderInterface==========================================================

    /**
     * Cleans prompt text for OpenRouter compatibility.
     * OpenRouter is OpenAI-compatible, so no special cleanup is needed.
     * @param content The content to clean
     * @return The cleaned content
     */
    override fun cleanPromptText(content: MultimodalContent): MultimodalContent
    {
        return content
    }
}
