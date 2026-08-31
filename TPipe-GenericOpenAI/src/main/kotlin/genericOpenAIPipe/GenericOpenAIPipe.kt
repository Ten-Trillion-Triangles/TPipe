package genericOpenAIPipe

import com.TTT.Debug.*
import com.TTT.Enums.ContextWindowSettings
import com.TTT.Enums.ProviderName
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.StreamingCallbackBuilder
import com.TTT.Pipe.TruncationSettings
import com.TTT.Context.Dictionary.BinaryEstimationMode
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import genericOpenAIPipe.env.*
import genericOpenAIPipe.api.*
import genericOpenAIPipe.mantle.BedrockMantleAuth
import genericOpenAIPipe.mantle.BedrockMantleConfiguration
import genericOpenAIPipe.mantle.ChunkedSigV4Signer
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * Fixed 100ms backoff between the first request and the single retry in
 * [GenericOpenAIPipe.runRequestWithRetry]. File-level constant (not a class member)
 * to avoid introducing a companion object that interacts unexpectedly with the
 * serialization layer.
 */
private const val RETRY_BACKOFF_MILLIS: Long = 100L

/**
 * Test-friendly abstraction over [java.net.HttpURLConnection] for the streaming-direct
 * code path. Production default wraps a real HttpURLConnection; tests inject a
 * [HttpStreamingConnectionFactory] that returns canned SSE bodies without making
 * a network call. See `MockStreamingConnectionFactory` in the test source set.
 */
internal interface HttpStreamingConnection : java.io.Closeable
{
    val responseCode: Int
    val outputStream: java.io.OutputStream
    val inputStream: java.io.InputStream
    fun disconnect()
}

internal fun interface HttpStreamingConnectionFactory
{
    /**
     * Opens a connection to [url] with [method] and [headers], returning an
     * [HttpStreamingConnection] whose [outputStream] is ready to receive the
     * request body and whose [inputStream] yields the response body byte-by-byte.
     */
    fun open(
        url: String,
        method: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpStreamingConnection
}

/**
 * TPipe abstraction for Generic OpenAI-compatible APIs.
 *
 * Provides access to OpenAI-compatible /v1/chat/completions endpoints
 * with standard OpenAI parameters. Supports any provider that implements
 * the OpenAI Chat Completions API specification.
 *
 * @see <a href="https://platform.openai.com/docs/api-reference/chat">OpenAI Chat Completions API</a>
 */
@kotlinx.serialization.Serializable
class GenericOpenAIPipe : Pipe()
{

//=========================================Properties===================================================================

    /**
     * API key for authentication.
     * Required for hosted endpoints, but optional for exact loopback endpoints.
     * Marked @Transient to prevent API key from being serialized to disk.
     */
    @kotlinx.serialization.Transient
    private var apiKey: String = ""

    /**
     * API base URL.
     * Defaults to "https://api.openai.com/v1"
     */
    @kotlinx.serialization.Serializable
    private var baseUrl: String = "https://api.openai.com/v1"

    /**
     * Endpoint paths selected for the current API mode.
     * Defaults use the standard Anthropic route and existing OpenAI-compatible routes.
     */
    @kotlinx.serialization.Serializable
    private var endpointProfile: GenericOpenAIEndpointProfile = GenericOpenAIEndpointProfile.DEFAULT

    /**
     * HTTP client for API calls.
     * Initialized in init() and closed in abort().
     */
    @kotlinx.serialization.Transient
    private var httpClient: HttpClient? = null

    /**
     * Tracks whether the pipe created [httpClient] itself (true) or received
     * it from a test seam (false). When false, [abort] must not close the
     * client — the test caller owns it and will close it after the test exits.
     * The default `true` is set in [init] and flipped to `false` by
     * [injectHttpClientForTest].
     */
    @kotlinx.serialization.Transient
    private var ownsHttpClient: Boolean = false

    /**
     * Factory that opens the streaming-direct HTTP connection (used by
     * [executeStreamingDirect] for chunked SSE). The production default wraps
     * `java.net.URL(...).openConnection() as java.net.HttpURLConnection`; tests
     * inject a stub via [injectStreamingConnectionFactoryForTest].
     */
    @kotlinx.serialization.Transient
    private var streamingConnectionFactory: HttpStreamingConnectionFactory? = null

    /**
     * Whether streaming mode is enabled.
     */
    @kotlinx.serialization.Serializable
    override var streamingEnabled: Boolean = false
    private var streamingReasoning: String = ""
    private var streamingInputTokens: Int = 0
    private var streamingOutputTokens: Int = 0
    private var streamingReasoningTokens: Int = 0
    /**
     * The finish_reason captured from the first terminal chunk of the
     * most recent OpenAI Chat Completions streaming call, or null if
     * the stream ended without one (e.g. on `[DONE]` only, or on a
     * mid-stream transport failure). Exposed to downstream consumers
     * via [streamingFinishReason] and surfaced in the
     * API_CALL_SUCCESS trace metadata under the same key.
     *
     * Reset to null at the top of every [executeStreamingDirect] call
     * so stale values from a prior request do not leak into the
     * next request's success metadata.
     */
    var streamingFinishReason: String? = null
        private set

    /**
     * Function calling tool definitions.
     */
    @kotlinx.serialization.Transient
    private var tools: List<ToolDefinition>? = null

    /**
     * Tool choice mode for function calling.
     * Values: "auto", "none", "required"
     */
    @kotlinx.serialization.Transient
    private var toolChoice: String? = null

    /**
     * Whether to enable parallel function calling.
     * Default is true.
     */
    @kotlinx.serialization.Transient
    private var parallelToolCalls: Boolean? = null

    /**
     * Response format for structured output.
     */
    @kotlinx.serialization.Transient
    private var responseFormat: ResponseFormat? = null

    /**
     * Whether to enable structured outputs via json_schema.
     */
    @kotlinx.serialization.Transient
    private var structuredOutputs: Boolean? = null

    /**
     * Output modalities (e.g., ["text", "image", "audio"]).
     */
    @kotlinx.serialization.Transient
    private var modalities: List<String>? = null

    /**
     * Reasoning configuration for reasoning-capable models.
     */
    @kotlinx.serialization.Transient
    private var reasoningConfig: ReasoningConfig? = null

    /**
     * Cache control with TTL for Anthropic-style caching.
     */
    @kotlinx.serialization.Transient
    private var cacheControl: genericOpenAIPipe.env.CacheControl? = null

    /**
     * Frequency penalty for reducing repetition (-2.0 to 2.0).
     */
    @kotlinx.serialization.Transient
    private var frequencyPenalty: Double? = null

    /**
     * Whether to return log probabilities.
     */
    @kotlinx.serialization.Transient
    private var logprobs: Boolean? = null

    /**
     * Number of top log probabilities to return (0-20).
     */
    @kotlinx.serialization.Transient
    private var topLogprobs: Int? = null

    /**
     * MinP sampling parameter (0.0 to 1.0).
     */
    @kotlinx.serialization.Transient
    private var minP: Double? = null

    /**
     * TopA sampling parameter (0.0 to 1.0).
     */
    @kotlinx.serialization.Transient
    private var topA: Double? = null

    @kotlinx.serialization.Transient
    private var reasoningEnabled: Boolean? = null

    /**
     * API mode for selecting OpenAI vs Anthropic API format.
     * Default is [ApiMode.OpenAI] for backward compatibility.
     * Cannot be changed after first API call.
     */
    @kotlinx.serialization.Serializable
    private var apiMode: ApiMode = ApiMode.OpenAI

    /**
     * Tracks whether the first API request has been made.
     * Used to enforce immutability of apiMode after initialization.
     */
    @kotlinx.serialization.Transient
    private var apiModeLocked: Boolean = false

    /**
     * Optional Bedrock Mantle auth override. When set, takes precedence over
     * the bearer / x-api-key headers produced by [getAuthHeaders]. The Mantle
     * setter methods on this class populate this field.
     */
    @kotlinx.serialization.Transient
    private var bedrockMantleAuth: BedrockMantleAuth? = null

    private val responseParser: ResponseParser = ResponseParser.Factory.create()

    private val requestSerializer: RequestSerializer = RequestSerializer.Factory.create()

    /**
     * Returns the appropriate auth headers based on the current [apiMode].
     *
     * OpenAI mode uses Bearer token authentication.
     * Anthropic mode uses x-api-key header with anthropic-version header when keyed.
     * Blank keys omit the credential header; the Anthropic version header remains.
     *
     * When [bedrockMantleAuth] is set, the Mantle auth shape takes precedence
     * and its headers (computed against the supplied request method, URL, and
     * body) are returned instead of the bearer / x-api-key defaults.
     *
     * @param method HTTP method of the outgoing request (uppercase).
     * @param url Full URL of the outgoing request.
     * @param body Request body bytes. May be empty.
     * @return Map of header name to header value.
     */
    private fun getAuthHeaders(
        method: String,
        url: String,
        body: ByteArray,
    ): Map<String, String>
    {
        bedrockMantleAuth?.let { auth ->
            // SigV4 must sign the content-type and payload hash headers.
            // Bearer-mode Mantle auth ignores them, but passing them is harmless.
            val callerHeaders = mapOf(
                "content-type" to "application/json",
                "x-amz-content-sha256" to genericOpenAIPipe.mantle.SigV4Signer.sha256Hex(body),
            )
            return auth.authHeaders(method, url, body, callerHeaders)
        }
        return when(apiMode)
        {
            is ApiMode.OpenAI -> if(apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
            is ApiMode.OpenAIResponses -> if(apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
            is ApiMode.Anthropic -> buildMap {
                if(apiKey.isNotBlank()) put("x-api-key", apiKey)
                put("anthropic-version", "2023-06-01")
            }
        }
    }

    /**
     * Returns the endpoint path selected by the current [endpointProfile] and [apiMode].
     *
     * The default profile uses the standard Anthropic suffix, while custom profiles
     * can select paths such as the common local `/v1` routes or MiniMax's prefixed route.
     *
     * @return The endpoint path (without baseUrl prefix)
     */
    private fun getEndpoint(): String
    {
        return when(apiMode)
        {
            is ApiMode.OpenAI -> endpointProfile.chatCompletionsPath
            is ApiMode.OpenAIResponses -> endpointProfile.responsesPath
            is ApiMode.Anthropic -> endpointProfile.anthropicMessagesPath
        }
    }

//=========================================Builder Methods=============================================================

    /**
     * Sets the API key.
     * @param key The API key
     * @return This pipe instance for fluent chaining
     */
    fun setApiKey(key: String): GenericOpenAIPipe
    {
        apiKey = key
        return this
    }

    /**
     * Sets the base URL.
     * @param url The base URL for the API (defaults to OpenAI's official endpoint)
     * @return This pipe instance for fluent chaining
     */
    fun setBaseUrl(url: String): GenericOpenAIPipe
    {
        baseUrl = BaseUrlPolicy.validateAndNormalize(
            url = url,
            allowInsecureHttp = insecureBaseUrlOverrideEnabled()
        )
        return this
    }

    /**
     * Selects the endpoint paths used by the current API mode.
     *
     * @param profile Endpoint path profile, such as [GenericOpenAIEndpointProfile.localV1].
     * @return This pipe instance for fluent chaining.
     * @throws IllegalStateException if called after the first API request.
     */
    fun setEndpointProfile(profile: GenericOpenAIEndpointProfile): GenericOpenAIPipe
    {
        check(!apiModeLocked) { "endpointProfile cannot be changed after the first API request" }
        endpointProfile = profile
        return this
    }

    private fun insecureBaseUrlOverrideEnabled(): Boolean
    {
        return System.getenv("TPIPE_ALLOW_INSECURE_BASEURL") == "true" ||
            System.getProperty("tpipe.allowInsecureBaseUrl") == "true"
    }

    fun setFrequencyPenalty(penalty: Double): GenericOpenAIPipe
    {
        frequencyPenalty = penalty
        return this
    }

    fun setTools(tools: List<ToolDefinition>): GenericOpenAIPipe
    {
        this.tools = tools
        return this
    }

    /**
     * Sets the tool choice mode for function calling.
     * @param choice Tool choice: "auto", "none", or "required"
     * @return This pipe instance for fluent chaining
     */
    fun setToolChoice(choice: String): GenericOpenAIPipe
    {
        toolChoice = choice
        return this
    }

    /**
     * Sets whether to enable parallel function calling.
     * @param enabled True to enable parallel calls (default true)
     * @return This pipe instance for fluent chaining
     */
    fun setParallelToolCalls(enabled: Boolean): GenericOpenAIPipe
    {
        parallelToolCalls = enabled
        return this
    }

    /**
     * Sets the response format for structured output.
     * @param type Format type: "text", "json_object", or "json_schema"
     * @param jsonSchema Optional JSON schema for json_schema type
     * @return This pipe instance for fluent chaining
     */
    fun setResponseFormat(type: String, jsonSchema: kotlinx.serialization.json.JsonObject? = null): GenericOpenAIPipe
    {
        responseFormat = ResponseFormat(type = type, jsonSchema = jsonSchema)
        return this
    }

    /**
     * Wire-format completion hook — translates TPipe's pipe.jsonOutput (and related
     * pipe-level state) into the OpenAI/Anthropic-compatible response_format field
     * so the provider API enforces JSON-mode at the wire instead of relying on
     * prompt-only instructions.
     *
     * If the user already called [setResponseFormat] explicitly, that wins. If the
     * pipe advertises native JSON support ([supportsNativeJson] = true), no wire
     * format is set. Otherwise, when pipe.jsonOutput is non-empty we set
     * response_format to type "json_object" (the wire-level knob MiniMax-M2.7 and
     * OpenAI-compatible chat-completions endpoints honor to lock the LLM into
     * emitting valid JSON).
     */
    override fun onApplySystemPromptComplete()
    {
        if(responseFormat != null) return
        if(supportsNativeJson) return
        if(jsonOutput.isBlank()) return

        responseFormat = ResponseFormat(type = "json_object", jsonSchema = null)
    }

    /**
     * Sets whether to enable structured outputs via json_schema.
     * @param enabled True to enable structured outputs
     * @return This pipe instance for fluent chaining
     */
    fun setStructuredOutputs(enabled: Boolean): GenericOpenAIPipe
    {
        structuredOutputs = enabled
        return this
    }

    /**
     * Sets the output modalities.
     * @param modalities List of modalities (e.g., ["text", "image", "audio"])
     * @return This pipe instance for fluent chaining
     */
    fun setModalities(modalities: List<String>): GenericOpenAIPipe
    {
        this.modalities = modalities
        return this
    }

    /**
     * Sets the reasoning configuration for reasoning-capable models.
     * @param config Reasoning configuration with effort, maxTokens, exclude, enabled
     * @return This pipe instance for fluent chaining
     */
    fun setReasoningConfig(config: ReasoningConfig): GenericOpenAIPipe
    {
        reasoningConfig = config
        return this
    }

    /**
     * Sets whether to enable streaming mode.
     * @param enabled True to enable streaming
     * @return This pipe instance for fluent chaining
     */
    override fun setStreamingEnabled(enabled: Boolean): GenericOpenAIPipe
    {
        streamingEnabled = enabled
        return this
    }

    /**
     * Registers a callback for streaming response chunks.
     * Automatically enables streaming mode.
     *
     * @param callback Suspendable callback receiving text chunks
     * @param propagateToChildren Whether to propagate the callback to validator,
     *                            transformation, and branch pipes. Defaults to true.
     * @param propagateToReasoning Whether to propagate the callback to the reasoning
     *                             pipe. Defaults to true.
     * @return This pipe instance for fluent chaining
     */
    fun setStreamingCallback(
        callback: suspend (String) -> Unit,
        propagateToChildren: Boolean = true,
        propagateToReasoning: Boolean = true,
    ): GenericOpenAIPipe
    {
        this.streamingEnabled = true
        obtainStreamingCallbackManager().addCallback(callback)
        propagateStreamingCallback(callback, mutableSetOf(), propagateToChildren, propagateToReasoning)
        return this
    }

    /**
     * Configures multiple streaming callbacks using the builder pattern.
     *
     * Mirrors [BedrockPipe.streamingCallbacks] so multi-recipient streaming works
     * uniformly across providers. Each registered callback receives every chunk
     * emitted by the streaming API. Execution mode is sequential by default; call
     * [StreamingCallbackBuilder.concurrent] inside the block to fan chunks to all
     * callbacks in parallel.
     *
     * Every registered callback is also propagated to descendant pipes
     * (validator, transformation, branch, reasoning) via
     * [com.TTT.Pipe.Pipe.propagateStreamingCallback] so chunks emitted anywhere
     * in the pipe tree reach the same sinks. Propagation can be gated via
     * [StreamingCallbackBuilder.propagateToChildren] and
     * [StreamingCallbackBuilder.propagateToReasoning].
     *
     * Example:
     * ```
     * pipe.streamingCallbacks {
     *     propagateToReasoning = false
     *     propagateToChildren = true
     *     add { chunk -> dispatcher.append(connectionId, chunk) }
     *     add { chunk -> metrics.record(chunk.length) }
     *     onError { e, chunk -> log.warn("callback failed", e) }
     * }
     * ```
     *
     * @param builder Lambda that configures the [StreamingCallbackBuilder]
     * @return This pipe instance for method chaining
     */
    fun streamingCallbacks(builder: StreamingCallbackBuilder.() -> Unit): GenericOpenAIPipe
    {
        val callbackBuilder = StreamingCallbackBuilder()
        callbackBuilder.builder()
        val manager = obtainStreamingCallbackManager()
        callbackBuilder.build().getCallbacks().forEach { callback ->
            manager.addCallback(callback)
            propagateStreamingCallback(
                callback,
                mutableSetOf(),
                callbackBuilder.propagateToChildren,
                callbackBuilder.propagateToReasoning,
            )
        }
        streamingEnabled = true
        return this
    }

    /**
     * Enables streaming with optional callback registration.
     *
     * When `callback` is provided it is added to this pipe's
     * [com.TTT.Pipe.StreamingCallbackManager] and propagated to all descendant
     * pipes (validator, transformation, branch, reasoning). Mirrors
     * [com.TTT.Pipe.Pipe.propagateStreamingCallback] so multi-pipe hierarchies
     * receive chunks uniformly across providers.
     *
     * When called without arguments the method flips the streaming flag only.
     * Equivalent to [setStreamingEnabled] with builder return.
     *
     * @param callback Optional suspending callback receiving text chunks
     * @return This pipe instance for method chaining
     */
    @JvmOverloads
    fun enableStreaming(callback: (suspend (String) -> Unit)? = null): GenericOpenAIPipe
    {
        if(callback != null)
        {
            obtainStreamingCallbackManager().addCallback(callback)
            propagateStreamingCallback(callback)
        }
        setStreamingEnabled(true)
        return this
    }

    /**
     * Cache control with TTL for Anthropic-style explicit prompt caching.
     *
     * When set, enables explicit prompt caching on the Anthropic API path.
     * The cache breakpoint is placed on the last system block — caching the
     * full system prompt prefix (tools + system) per Anthropic/MiniMax spec.
     *
     * **TTL behavior by provider:**
     * - Direct Anthropic API: "5m" (default, 5 min) or "1h" (1 hour)
     * - MiniMax /anthropic endpoint: TTL field is IGNORED — cache is always
     *   5 minutes and auto-refreshes on hit at no additional cost
     *
     * **Supported models:** MiniMax-M2.7, M2.5, M2.1, M2.
     * NOT supported on M3 (use passive auto-cache on /v1 instead).
     *
     * @param type Cache type — must be "ephemeral" (the only supported type)
     * @param ttl Time-to-live: "5m" (default) or "1h". Omit for MiniMax compat.
     * @return This pipe instance for fluent chaining
     */
    fun setCacheControl(type: String = "ephemeral", ttl: String? = null): GenericOpenAIPipe
    {
        cacheControl = genericOpenAIPipe.env.CacheControl(type = type, ttl = ttl)
        return this
    }

    /**
     * Sets the API mode for the request format.
     * @param mode [ApiMode.OpenAI] for OpenAI-compatible format (default),
     *             [ApiMode.Anthropic] for Anthropic messages format
     * @return This pipe instance for fluent chaining
     * @throws IllegalStateException if called after the first API request
     */
    fun setApiMode(mode: ApiMode): GenericOpenAIPipe
    {
        check(!apiModeLocked) { "apiMode cannot be changed after the first API request" }
        apiMode = mode
        return this
    }

    /**
     * Sets the [streamingFinishReason] field. Use this to seed an
     * expected reason before a streaming call, or to clear it (pass
     * null) between requests. The field is also reset to null
     * automatically at the top of every [executeStreamingDirect]
     * call and then overwritten with the captured terminal reason
     * when the call returns.
     *
     * @param value The expected finish_reason ("stop", "length",
     *              "tool_calls", "content_filter", ...), or null to
     *              clear.
     * @return This pipe instance for fluent chaining.
     */
    fun setStreamingFinishReason(value: String?): GenericOpenAIPipe
    {
        streamingFinishReason = value
        return this
    }

    /**
     * Configure the pipe to drive Amazon Bedrock Mantle in OpenAI Chat
     * Completions mode.
     *
     * Wires [baseUrl] to the regional Mantle endpoint, sets [apiMode] to
     * [ApiMode.OpenAI], and resolves authentication from
     * [genericOpenAIPipe.env.BedrockMantleEnv] in the following order:
     *   1. AWS SigV4 credentials (access key id + secret access key) when
     *      both are resolvable, producing an [BedrockMantleAuth.SigV4] shape.
     *   2. Bearer mode using whatever API key is currently set on the pipe.
     *   3. Bearer mode using the `BEDROCK_MANTLE_API_KEY` env var.
     *
     * Callers who need fine-grained control should follow up with
     * [setBedrockMantleAuth].
     *
     * @param region AWS region code (for example `us-east-1` or `us-west-2`).
     * @param modelId The Bedrock model identifier to use
     *               (for example `google.gemma-4-31b`).
     * @return This pipe instance for fluent chaining.
     */
    fun setBedrockMantle(region: String, modelId: String): GenericOpenAIPipe
    {
        val config = BedrockMantleConfiguration.forRegion(region, modelId)
        configureBedrockMantle(config)
        return this
    }

    /**
     * Configure the pipe to drive Amazon Bedrock Mantle in OpenAI Responses
     * API mode.
     *
     * Mirrors [setBedrockMantle] but selects [ApiMode.OpenAIResponses] so
     * requests dispatch to the `/v1/responses` endpoint.
     *
     * @param region AWS region code.
     * @param modelId Bedrock model identifier.
     * @return This pipe instance for fluent chaining.
     */
    fun setBedrockMantleWithResponses(region: String, modelId: String): GenericOpenAIPipe
    {
        val config = BedrockMantleConfiguration.forRegionWithResponses(region, modelId)
        configureBedrockMantle(config)
        return this
    }

    /**
     * Replace the Mantle auth shape. Useful for tests that supply an explicit
     * signer or bearer key, and for users who want to skip env-var
     * resolution.
     *
     * Pass `null` to clear any previously set Mantle auth and fall back to
     * the bearer / x-api-key defaults produced by [getAuthHeaders].
     *
     * @param auth Mantle auth shape, or `null` to disable Mantle auth.
     * @return This pipe instance for fluent chaining.
     */
    fun setBedrockMantleAuth(auth: BedrockMantleAuth?): GenericOpenAIPipe
    {
        bedrockMantleAuth = auth
        return this
    }

    /**
     * Internal helper that wires baseUrl, apiMode, modelId, and auth for the
     * given Mantle configuration. Resolves credentials via
     * [genericOpenAIPipe.env.BedrockMantleEnv] and prefers SigV4 when both
     * an access key id and a secret access key are available.
     *
     * Also populates the reasoning-pipe metadata contract keys that
     * [Pipe.getMiddlePromptForReasoning] (Pipe.kt:8033), [Pipe.getFooterPromptForReasoning]
     * (Pipe.kt:8047), and [Pipe.applySystemPrompt] (Pipe.kt:7166-7168) read at
     * execution time. Without these keys present the unguarded casts at
     * Pipe.kt:8033 / 8047 historically threw NPE on every Mantle reasoning
     * invocation; the retry loop absorbed the exception and degraded the
     * visible reasoning output. Mantle has no settings object (it is wired
     * directly, not through `ReasoningBuilder.assignDefaults`), so we write
     * the [Defaults.reasoning.ReasoningSettings] defaults by hand here:
     *
     *   injectMiddlePrompt     = false  (ReasoningSettings:142 default)
     *   injectFooterPrompt     = false  (ReasoningSettings:143 default)
     *   reinforceSystemPrompt  = false  (ReasoningSettings:144 default)
     *
     * Note: the JSON-completion footer prompt that `assignDefaults` installs
     * at ReasoningBuilder.kt:321-325 via setFooterPrompt(...) is intentionally
     * NOT wired here. getFooterPromptForReasoning() (Pipe.kt:8047) only reads
     * `footerPrompt` when the caller has opted in via injectFooterPrompt=true,
     * so the footer would be dead code at the wire unless a Mantle builder
     * flips that gate. Callers that want JSON-completion enforcement on a
     * Mantle reasoning pipe should set injectFooterPrompt=true after
     * construction and then call setFooterPrompt(...) themselves.
     */
    private fun configureBedrockMantle(config: BedrockMantleConfiguration)
    {
        setBaseUrl(config.endpoint())
        setApiMode(config.apiMode)
        setModel(config.modelId)

        // Reasoning-pipe metadata contract — defaults match ReasoningSettings
        // (TPipe-Defaults/src/main/kotlin/Defaults/reasoning/ReasoningBuilder.kt:142-144).
        pipeMetadata["injectMiddlePrompt"] = false
        pipeMetadata["injectFooterPrompt"] = false
        pipeMetadata["reinforceSystemPrompt"] = false

        val sigV4Auth = BedrockMantleAuth.sigV4FromEnv(regionOverride = config.region)
        if (sigV4Auth != null)
        {
            bedrockMantleAuth = sigV4Auth
            return
        }

        // No IAM credentials resolvable; fall back to bearer mode. Prefer the
        // programmatic apiKey the caller has already set (in case they
        // configured one via setApiKey), then the env-var fallback.
        val bearerKey = apiKey.takeIf { it.isNotBlank() }
            ?: System.getenv("BEDROCK_MANTLE_API_KEY")
            ?: ""
        if (bearerKey.isNotBlank())
        {
            if (apiKey.isBlank()) apiKey = bearerKey
            bedrockMantleAuth = BedrockMantleAuth.bearer(bearerKey)
        }
    }

//=========================================Pipe Lifecycle Methods======================================================

    /**
     * Initializes the Generic OpenAI pipe.
     * Validates configuration and sets up the HTTP client.
     * @return This pipe instance
     * @throws IllegalStateException if a non-loopback endpoint has no API key
     */
    override suspend fun init(): Pipe
    {
        super.init()

        baseUrl = BaseUrlPolicy.validateAndNormalize(
            url = baseUrl,
            allowInsecureHttp = insecureBaseUrlOverrideEnabled()
        )

        trace(TraceEventType.PIPE_START, TracePhase.INITIALIZATION,
              metadata = mapOf(
                  "provider" to "GenericOpenAI",
                  "baseUrl" to baseUrl,
                  "model" to model
              ))

        // The apiKey field is only used for bearer / x-api-key auth.
        // When a Mantle auth shape is set, it computes its own headers via
        // [BedrockMantleAuth.authHeaders], so apiKey may legitimately be blank.
        if (apiKey.isBlank() && bedrockMantleAuth == null)
        {
            val resolvedKey = GenericOpenAIEnv.resolveApiKey()
            if (resolvedKey.isNotBlank())
            {
                apiKey = resolvedKey
            }
            else if (!BaseUrlPolicy.isLoopbackUrl(baseUrl))
            {
                throw IllegalStateException("GenericOpenAI API key is required for non-loopback endpoints. Call setApiKey(), genericOpenAIEnv.setApiKey(), or set GENERIC_OPENAI_API_KEY environment variable before init().")
            }
        }

        provider = ProviderName.Gpt

        // Only create the default CIO client when the pipe has not been supplied with
        // a pre-built HttpClient. The test-only [injectHttpClientForTest] path depends
        // on this guard: it sets httpClient before init() so the production client is
        // not allocated and the test's MockEngine (or other custom engine) is honoured.
        if(httpClient == null)
        {
            httpClient = createHttpClient()
            ownsHttpClient = true
        }

        // Default streaming-direct factory wraps java.net.URL + HttpURLConnection.
        // The test seam injects a stub via injectStreamingConnectionFactoryForTest.
        if(streamingConnectionFactory == null)
        {
            streamingConnectionFactory = HttpStreamingConnectionFactory { url, method, headers, connectTimeoutMs, readTimeoutMs ->
                object : HttpStreamingConnection
                {
                    private val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = method
                        doOutput = true
                        connectTimeout = connectTimeoutMs
                        readTimeout = readTimeoutMs
                        headers.forEach { (name, value) -> setRequestProperty(name, value) }
                        setChunkedStreamingMode(0)
                    }
                    override val responseCode: Int get() = conn.responseCode
                    override val outputStream: java.io.OutputStream get() = conn.outputStream
                    override val inputStream: java.io.InputStream get() = conn.inputStream
                    override fun disconnect() { conn.disconnect() }
                    override fun close() { conn.disconnect() }
                }
            }
        }

        trace(TraceEventType.PIPE_SUCCESS, TracePhase.INITIALIZATION,
              metadata = mapOf("initialized" to true))

        return this
    }

    /**
     * Aborts any active generation and cleans up resources.
     *
     * Releases the in-flight Ktor client and stands up a fresh one so the retry
     * path (`Pipe.execute` while-loop at Pipe.kt:5967-5974) has a usable handle
     * when [PipeTimeoutManager] fires and the parent pipeline re-enters
     * [generateTextMultimodal] on the same pipe instance. Closing and reusing
     * the same handle is unsafe — Ktor's CIO engine may surface
     * `IOException: connection closed` on the next request through a closed
     * client. See BUG_GENERICOPENAI_ABORT_NULLS_HTTPCLIENT.md for the full
     * trace evidence and the previous (`httpClient = null`) regression.
     */
    override suspend fun abort()
    {
        trace(TraceEventType.PIPE_FAILURE, TracePhase.EXECUTION,
              metadata = mapOf("action" to "abort", "provider" to "GenericOpenAI"))

        // Release the in-flight client and stand up a fresh one so the retry
        // path has a usable handle. Only the pipe-owned client is closed and
        // recreated; a client injected via [injectHttpClientForTest] is owned
        // by the test caller and must be left alone.
        if(ownsHttpClient)
        {
            httpClient?.close()
            httpClient = createHttpClient()
        }

        super.abort()
    }

//=========================================Generation Methods=========================================================

    /**
     * Generates content with multimodal support.
     * Handles binary content (images) by converting to OpenAI content blocks.
     * @param content Multimodal content with optional binary attachments
     * @return Generated response
     */
    override suspend fun generateContent(content: MultimodalContent): MultimodalContent
    {
        if(!content.hasBinaryContent())
        {
            // Plain-text shortcut: route through the MultimodalContent-returning
            // helper so any `modelReasoning` extracted from the wire response
            // survives the public boundary. The previous shortcut wrapped the
            // String-returning `generateText` in a fresh
            // `MultimodalContent(text = ...)`, which discarded `modelReasoning`
            // even though the wire response carries it.
            return generateTextMultimodal(content.text)
        }

        val blocks = mutableListOf<ContentBlock>()

        if(content.text.isNotEmpty())
        {
            blocks.add(ContentBlock.TextBlock(content.text))
        }

        for(binary in content.binaryContent)
        {
            when(binary)
            {
                is com.TTT.Pipe.BinaryContent.Base64String ->
                {
                    val mimeType = binary.mimeType
                    blocks.add(ContentBlock.ImageUrlBlock(
                        url = "data:$mimeType;base64,${binary.data}",
                        detail = "auto"
                    ))
                }
                is com.TTT.Pipe.BinaryContent.Bytes ->
                {
                    val base64 = java.util.Base64.getEncoder().encodeToString(binary.data)
                    val mimeType = binary.mimeType
                    blocks.add(ContentBlock.ImageUrlBlock(
                        url = "data:$mimeType;base64,$base64",
                        detail = "auto"
                    ))
                }
                is com.TTT.Pipe.BinaryContent.CloudReference ->
                {
                    blocks.add(ContentBlock.ImageUrlBlock(
                        url = binary.uri,
                        detail = "auto"
                    ))
                }
                is com.TTT.Pipe.BinaryContent.TextDocument ->
                {
                    blocks.add(ContentBlock.TextBlock(binary.content))
                }
            }
        }

        val messages = mutableListOf<ChatMessage>()

        if(systemPrompt.isNotEmpty())
        {
            messages.add(ChatMessage(role = "system", content = MessageContent.TextContent(systemPrompt)))
        }

        messages.add(ChatMessage(role = "user", content = MessageContent.MultimodalContent(blocks)))

        val request = GenericOpenAIChatRequest(
            model = model,
            messages = messages,
            temperature = if(temperature > 0.0) temperature else null,
            topP = if(topP > 0.0) topP else null,
            topK = if(topK > 0) topK else null,
            maxTokens = if(maxTokens > 0) maxTokens else null,
            presencePenalty = if(presencePenalty != 0.0) presencePenalty else null,
            frequencyPenalty = frequencyPenalty,
            repetitionPenalty = repetitionPenalty,
            seed = seed,
            stop = stopSequences.takeIf { it.isNotEmpty() },
            tools = tools,
            toolChoice = toolChoice,
            parallelToolCalls = parallelToolCalls,
            responseFormat = responseFormat,
            structuredOutputs = structuredOutputs,
            modalities = modalities,
            reasoning = reasoningConfig,
            cacheControl = cacheControl,
            logitBias = logitBias.takeIf { it.isNotEmpty() },
            logprobs = logprobs,
            topLogprobs = topLogprobs,
            minP = minP,
            topA = topA,
            user = user,
            n = n,
            stream = streamingEnabled
        )

        val responseText = sendRequest(request)
        // Binary-content path: `sendRequest` returns String only, so any
        // `modelReasoning` extracted by the parser is dropped here. Mantle
        // currently serves text-only inputs, so the plain-text shortcut
        // above covers the live use cases; reasoning on binary inputs
        // would require widening `sendRequest` to return MultimodalContent.
        return MultimodalContent(text = responseText)
    }

    /**
     * Sends request and returns response text.
     */
    private suspend fun sendRequest(request: GenericOpenAIChatRequest): String
    {
        val client = httpClient ?: throw IllegalStateException("GenericOpenAIPipe not initialized. Call init() first.")

        apiModeLocked = true

        val jsonRequest = serialize(request, encodedefault = false)

        if(streamingEnabled)
        {
            // When Mantle chunked-encoding streaming SigV4 auth is active,
            // route through the HttpURLConnection direct path because it
            // gives us byte-level control over the chunked body writes
            // (Ktor's setBody path doesn't expose per-chunk signing
            // primitives in a way that lines up with the AWS streaming
            // algorithm). The HttpURLConnection path is also more reliable
            // for reading incremental SSE responses (see comment in
            // executeStreamingDirect).
            if (bedrockMantleAuth is BedrockMantleAuth.Streaming)
            {
                // `executeStreamingDirect` returns MultimodalContent so the
                // reasoning content it accumulates via `streamingReasoningText`
                // survives this Mantle chunked-SigV4 streaming boundary.
                // Unpack `.text` here for the String-returning signature
                // of `sendRequest`.
                val streamedContent = executeStreamingDirect(jsonRequest)
                return streamedContent.text
            }

            val response = withContext(Dispatchers.IO)
            {
                client.post("$baseUrl${getEndpoint()}")
                {
                    contentType(ContentType.Application.Json)
                    getAuthHeaders(
                        method = "POST",
                        url = "$baseUrl${getEndpoint()}",
                        body = jsonRequest.toByteArray(Charsets.UTF_8),
                    ).forEach { (name, value) -> header(name, value) }
                    setBody(jsonRequest)
                }
            }
            // `executeStreaming` returns MultimodalContent so the reasoning content
            // it accumulates via `streamingReasoningText` survives the
            // Ktor-based streaming boundary. Unpack `.text` here for the
            // String signature of `sendRequest`.
            return executeStreaming(response).text
        }
        else
        {
            val responseText = runRequestWithRetry {
                withContext(Dispatchers.IO)
                {
                    client.post("$baseUrl${getEndpoint()}")
                    {
                        contentType(ContentType.Application.Json)
                        getAuthHeaders(
                            method = "POST",
                            url = "$baseUrl${getEndpoint()}",
                            body = jsonRequest.toByteArray(Charsets.UTF_8),
                        ).forEach { (name, value) -> header(name, value) }
                        setBody(jsonRequest)
                    }.bodyAsText()
                }
            }

            val response: GenericOpenAIChatResponse = try
            {
                responseParser.parse(responseText, apiMode)
            }
            catch(e: P2PException)
            {
                throw e
            }
            catch(e: Exception)
            {
                throw P2PException(P2PError.json, "Failed to parse GenericOpenAI response: ${e.message}", e)
            }

            val contentText = when(val msg = response.choices.firstOrNull()?.message?.content)
            {
                is MessageContent.TextContent -> msg.text
                is MessageContent.MultimodalContent -> msg.blocks.filterIsInstance<ContentBlock.TextBlock>().joinToString("") { it.text }
                is MessageContent.PlainContent -> msg.content
                null -> ""
            }
            return contentText
        }
    }

//=========================================Context Management==========================================================

    /**
     * Canonical implementation of the non-streaming text path.
     * Performs the full HTTP call + parse + trace for a text-only
     * prompt and returns the resulting [MultimodalContent] — including
     * any `modelReasoning` extracted from the wire response.
     *
     * The public [generateText] is a thin wrapper that returns `.text`
     * for callers that only need the visible answer. The
     * [generateContent] plain-text shortcut calls this helper directly
     * so `modelReasoning` survives the public boundary — BedrockPipe
     * and OllamaPipe populate `MultimodalContent.modelReasoning` from
     * their wire responses, and this helper does the same.
     */
    protected suspend fun generateTextMultimodal(promptInjector: String): com.TTT.Pipe.MultimodalContent
    {
        val client = httpClient ?: throw IllegalStateException("GenericOpenAIPipe not initialized. Call init() first.")

        apiModeLocked = true

        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf(
                  "provider" to "GenericOpenAI",
                  "model" to model,
                  "baseUrl" to baseUrl,
                  "promptLength" to promptInjector.length,
                  "streaming" to streamingEnabled,
                  "apiType" to when(apiMode)
                  {
                      is ApiMode.OpenAI -> "ChatAPI"
                      is ApiMode.OpenAIResponses -> "ResponsesAPI"
                      is ApiMode.Anthropic -> "AnthropicAPI"
                  }
              ))

        return try
        {
            val messages = mutableListOf<ChatMessage>()

            if(systemPrompt.isNotEmpty())
            {
                messages.add(ChatMessage(role = "system", content = MessageContent.TextContent(systemPrompt)))
            }

            messages.add(ChatMessage(role = "user", content = MessageContent.TextContent(promptInjector)))

            val request = GenericOpenAIChatRequest(
                model = model,
                messages = messages,
                temperature = if(temperature > 0.0) temperature else null,
                topP = if(topP > 0.0) topP else null,
                topK = if(topK > 0) topK else null,
                maxTokens = if(maxTokens > 0) maxTokens else null,
                presencePenalty = if(presencePenalty != 0.0) presencePenalty else null,
                frequencyPenalty = frequencyPenalty,
                repetitionPenalty = repetitionPenalty,
                seed = seed,
                stop = stopSequences.takeIf { it.isNotEmpty() },
                tools = tools,
                toolChoice = toolChoice,
                parallelToolCalls = parallelToolCalls,
                responseFormat = responseFormat,
                structuredOutputs = structuredOutputs,
                modalities = modalities,
                reasoning = reasoningConfig,
                cacheControl = cacheControl,
                logitBias = logitBias.takeIf { it.isNotEmpty() },
                logprobs = logprobs,
                topLogprobs = topLogprobs,
                minP = minP,
                topA = topA,
                user = user,
                n = n,
                stream = streamingEnabled
            )

            val jsonRequest = requestSerializer.serialize(
                request, apiMode,
                // pipeMetadata is `MutableMap<Any, Any>` on the base Pipe class.
                // RequestSerializationOptions expects `Map<String, Any?>` — keys
                // are string constants in practice (see MantleMetadataKeys), values
                // are typed objects. The cast is safe because callers use string keys.
                @Suppress("UNCHECKED_CAST")
                RequestSerializationOptions(metadata = pipeMetadata as Map<String, Any?>),
            )

            if(streamingEnabled)
            {
                // BUG FIX: Ktor CIO's bodyAsChannel does NOT deliver bytes
                // incrementally for chunked transfer-encoded SSE responses
                // through 3.3.x. All data arrives as one batch when the
                // stream closes. Workaround: bypass Ktor entirely for the
                // streaming call and open a direct HttpURLConnection with
                // chunked transfer encoding. We feed the JSON body and read
                // the response line-by-line. The InputStream blocks per
                // line read, so each SSE delta fires emitStreamingChunk as
                // it arrives on the socket — verified empirically via
                // RawHttpStreamingTest (chunks arrive hundreds of ms apart
                // rather than all in one batch).
                // Streaming path: `executeStreamingDirect` returns MultimodalContent
                // so the reasoning content it accumulates via
                // `streamingReasoningText` round-trips through
                // `modelReasoning` on the way out.
                return withContext(Dispatchers.IO)
                {
                    executeStreamingDirect(jsonRequest)
                }
            }
            else
            {
                val responseText = runRequestWithRetry {
                    withContext(Dispatchers.IO)
                    {
                        client.post("$baseUrl${getEndpoint()}")
                        {
                            contentType(ContentType.Application.Json)
                            getAuthHeaders(
                                method = "POST",
                                url = "$baseUrl${getEndpoint()}",
                                body = jsonRequest.toByteArray(Charsets.UTF_8),
                            ).forEach { (name, value) -> header(name, value) }

                            setBody(jsonRequest)
                        }.bodyAsText()
                    }
                }

                val response: GenericOpenAIChatResponse = try
                {
                    responseParser.parse(responseText, apiMode)
                }
                catch(e: P2PException)
                {
                    throw e
                }
                catch(e: Exception)
                {
                    throw P2PException(P2PError.json, "Failed to parse GenericOpenAI response: ${e.message}", e)
                }

                val contentText = when(val msg = response.choices.firstOrNull()?.message?.content)
                {
                    is MessageContent.TextContent -> msg.text
                    is MessageContent.MultimodalContent -> msg.blocks.filterIsInstance<ContentBlock.TextBlock>().joinToString("") { it.text }
                    is MessageContent.PlainContent -> msg.content
                    null -> ""
                }

                val usage = response.usage
                val inputTokens = usage?.promptTokens ?: 0
                val outputTokens = usage?.completionTokens ?: 0
                val totalTokens = usage?.totalTokens ?: 0

                val reasoningContent = response.reasoningContent ?: ""
                val result = com.TTT.Pipe.MultimodalContent(
                    text = contentText,
                    modelReasoning = reasoningContent
                )

                val successMetadata = mutableMapOf<String, Any>(
                    "inputTokens" to inputTokens,
                    "outputTokens" to outputTokens,
                    "totalTokens" to totalTokens,
                    "responseLength" to contentText.length,
                    "model" to response.model,
                    "success" to true,
                    "apiType" to when(apiMode)
                    {
                        is ApiMode.OpenAI -> "ChatAPI"
                        is ApiMode.OpenAIResponses -> "ResponsesAPI"
                        is ApiMode.Anthropic -> "AnthropicAPI"
                    },
                    "finishReason" to (response.choices.firstOrNull()?.finishReason ?: "unknown"),
                    "stopReason" to (response.choices.firstOrNull()?.finishReason ?: "unknown"),
                    "responseId" to (response.id ?: "unknown"),
                    "systemFingerprint" to (response.systemFingerprint ?: "none")
                )
                if(reasoningContent.isNotEmpty())
                {
                    successMetadata["reasoningLength"] = reasoningContent.length
                    successMetadata["reasoningSupported"] = true
                }
                if(response.usage?.completionTokensDetails?.reasoningTokens != null)
                {
                    successMetadata["reasoningTokens"] = response.usage!!.completionTokensDetails!!.reasoningTokens!!
                }

                trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
                      content = result,
                      metadata = successMetadata)

                // Return the full MultimodalContent (carries both `text` and
                // `modelReasoning`) so callers can route reasoning to its
                // own content block. The public `generateText` override
                // below extracts `.text` for callers that only need the
                // visible answer.
                return result
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

            when(e)
            {
                is HttpRequestTimeoutException -> throw P2PException(P2PError.transport, "Request timeout", e)
                is java.net.SocketTimeoutException -> throw P2PException(P2PError.transport, "Socket timeout", e)
                is java.net.ConnectException -> throw P2PException(P2PError.transport, "Connection failed", e)
                else -> throw e
            }
        }
    }

    /**
     * Thin wrapper over [generateTextMultimodal] that discards
     * `modelReasoning` and returns only the visible answer. Kept for
     * source compatibility with the abstract base-class signature
     * declared in `Pipe.generateText(promptInjector: String): String` —
     * Kotlin does not allow widening the return type in an override,
     * so the canonical implementation lives in [generateTextMultimodal]
     * and this method delegates to it.
     *
     * @param promptInjector Text to inject into the prompt
     * @return Generated response text (visible answer only)
     */
    override suspend fun generateText(promptInjector: String): String
    {
        return generateTextMultimodal(promptInjector).text
    }

    /**
     * Direct streaming call using [java.net.HttpURLConnection] instead of
     * the Ktor CIO client. Bypasses Ktor because its CIO engine buffers
     * chunked transfer-encoded SSE responses through 3.3.x — the
     * ByteReadChannel returned from bodyAsChannel only delivers bytes
     * once the response stream is closed, defeating the whole point of
     * streaming. Verified empirically: KtorSsePluginTest confirms the
     * SSE plugin streams correctly (using an internal channel that
     * reads the body bytes as they arrive); RawHttpStreamingTest
     * confirms HttpURLConnection streams correctly. Reading the
     * InputStream line by line yields chunks hundreds of ms apart as
     * the server sends them.
     *
     * This function produces the same emitStreamingChunk side effects
     * as the Ktor path so the streaming callback wiring is unchanged.
     * It returns the accumulated text plus any captured `modelReasoning`
     * as a [MultimodalContent].
     *
     * AWS Mantle workaround — the [DONE] early-return guard at the
     * top of the SSE `data:` branch and the `finish_reason` terminal
     * capture in the OpenAI branch below exist because AWS Bedrock
     * Mantle's chunked-SigV4 streaming transport keeps the TCP
     * connection alive after the model has finished generating. The
     * 65,536-byte signed body chunks carry trailing bytes (heartbeat,
     * chunked-SigV4 terminator, partial retry traffic) that hold the
     * socket open indefinitely. Without these explicit termination
     * signals the parser would rely on socket EOF that never arrives,
     * and the pipe would hang for the full
     * [HttpURLConnection.readTimeoutMs] (`120_000` ms) instead of
     * returning within milliseconds of the model's [DONE] sentinel.
     * If AWS fixes the chunked-SigV4 keepalive-after-`[DONE]`
     * behavior, these guards become redundant but harmless — the
     * parser will still terminate on [DONE] before EOF regardless of
     * whether the server flushes the socket. Revisit this KDoc and
     * the [DONE]/finish_reason guards only when the streaming
     * timeout has been empirically confirmed to no longer be the
     * primary failure mode against Mantle in production.
     *
     * Exposed surface — the captured terminal reason is written to
     * [streamingFinishReason] (a `var` on this class with a public
     * `private set`) and to the API_CALL_SUCCESS trace metadata
     * under the same key, so downstream consumers can read the
     * reason without re-parsing the SSE stream. Seed or clear the
     * field via [setStreamingFinishReason].
     *
     * Mid-stream failure policy — when the read loop throws
     * `IOException` (e.g. Mantle mid-stream network blip, socket
     * reset, broken pipe), the catch emits API_CALL_FAILURE with
     * a diagnostic metadata block (`streamingFinishReason`,
     * `partialTextLength`, `elapsedMs`, `transportErrorKind`,
     * `transportErrorMessage`) and throws
     * `P2PException(P2PError.transport, ...)`. The `retryable`
     * flag in the metadata is `true` only when the caller has
     * configured TPipe's generic pipe retry policy
     * (`timeoutStrategy = PipeTimeoutStrategy.Retry` AND
     * `maxRetryAttempts > 0`). When `retryable` is false (the
     * TPipe default — `PipeTimeoutStrategy.Fail`), the failure
     * is terminal and the trace file is the visible error record;
     * when true, TPipe's [com.TTT.Pipe.PipeTimeoutManager] catches
     * the propagated exception and schedules another attempt per
     * its own retry policy. There is no streaming-internal retry
     * loop — every retry attempt is owned by TPipe's generic
     * retry layer, not this code.
     */
    private suspend fun executeStreamingDirect(jsonRequest: String): com.TTT.Pipe.MultimodalContent
    {
        val textBuilder = StringBuilder()

        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf(
                  "step" to "streamingStart",
                  "streaming" to true,
                  "transport" to "HttpURLConnection",
                  "apiMode" to when(apiMode) { is ApiMode.OpenAI -> "OpenAI"; is ApiMode.OpenAIResponses -> "OpenAIResponses"; is ApiMode.Anthropic -> "Anthropic" }
              ))

        java.net.HttpURLConnection.setFollowRedirects(false)
        val conn = (streamingConnectionFactory ?: error("streamingConnectionFactory not initialized")).open(
            url = "$baseUrl${getEndpoint()}",
            method = "POST",
            headers = buildMap {
                put("Content-Type", "application/json")
                putAll(
                    getAuthHeaders(
                        method = "POST",
                        url = "$baseUrl${getEndpoint()}",
                        body = jsonRequest.toByteArray(Charsets.UTF_8),
                    )
                )
            },
            connectTimeoutMs = 30_000,
            readTimeoutMs = 120_000
        )

        // Write the body. When Mantle streaming SigV4 auth is in use,
        // write the body as chunked-encoded blocks per the AWS S3
        // streaming spec (and the Mantle streaming docs). Otherwise,
        // write the body in a single shot as today.
        conn.outputStream.use { out ->
            val bodyBytes = jsonRequest.toByteArray(Charsets.UTF_8)
            val streamingAuth = bedrockMantleAuth as? BedrockMantleAuth.Streaming
            if (streamingAuth != null)
            {
                writeChunkedRequestBody(out, bodyBytes, streamingAuth)
            }
            else
            {
                out.write(bodyBytes)
            }
        }

        val reasoningBuilder = StringBuilder()
        var totalInputTokens = 0
        var totalOutputTokens = 0
        var totalReasoningTokens = 0
        // Captured from the first non-null finish_reason across the
        // choices[]. Defensive termination signal — the spec lets a
        // server emit finish_reason on the terminal chunk without
        // sending a separate `data: [DONE]` sentinel. We break out
        // of the SSE loop on the first non-null value so the parser
        // does not block waiting for socket EOF after the model has
        // finished generating. See the [DONE] guard below for the
        // primary termination trigger.
        var streamingFinishReason: String? = null

        // Read SSE events line by line. lineSequence() reads from the
        // BufferedReader one line at a time, which blocks per-line —
        // so each SSE delta fires emitStreamingChunk as it arrives on
        // the socket. This is the key behavior the Ktor bodyAsChannel
        // path does NOT exhibit.
        // Flag that the consumer has explicitly terminated the SSE loop
        // (either via the [DONE] guard or a non-null finish_reason).
        // Sequence.forEach cannot be broken out of via return@label —
        // Kotlin's sequence iteration is a while(hasNext()) loop and
        // returning from the action lambda only returns from the
        // action. The iterator's hasNext() call then blocks on
        // readLine() waiting for more data that never arrives (the
        // connection is alive but the SSE stream has terminated via
        // [DONE] or finish_reason). We use a labeled while loop on
        // the iterator directly so `break@lineLoop` exits the loop
        // without another iterator.hasNext() call.
        // Reset the exposed terminal reason at the top of every
        // streaming call so stale values from a prior request do
        // not leak into the next request's success metadata.
        this.streamingFinishReason = null
        // Track the start of the streaming read so the catch block
        // below can report elapsed time in the failure metadata.
        val streamingStartMs = System.currentTimeMillis()
        try
        {
            java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            var lastEventType: String? = null
            val lineIterator = reader.lineSequence().iterator()
            lineLoop@ while(lineIterator.hasNext())
            {
                val rawLine = lineIterator.next()
                val line = rawLine.trimEnd()
                if(line.isEmpty())
                {
                    // blank separator between events
                    continue@lineLoop
                }
                if(line.startsWith("event: "))
                {
                    lastEventType = line.substringAfter("event: ").trim()
                    continue@lineLoop
                }
                if(line.startsWith("data: "))
                {
                    val dataLine = line.substringAfter("data: ")
                    // OpenAI Chat Completions spec mandates `data: [DONE]`
                    // as the terminal sentinel. Without this guard, the
                    // sentinel reaches the JSON parser below and throws;
                    // the silent catch swallows the throw and the parser
                    // waits for socket EOF. Against AWS Mantle (which
                    // keeps the TCP connection alive after [DONE] because
                    // of its chunked-SigV4 transport) the parser hangs for
                    // the full readTimeoutMs instead of terminating within
                    // milliseconds. See the function-level KDoc for the
                    // AWS workaround note.
                    if(dataLine.trim() == "[DONE]")
                    {
                        break@lineLoop
                    }
                    when(apiMode)
                    {
                        is ApiMode.OpenAIResponses ->
                        {
                            val parsed = try { OpenAIResponsesSseParser.parseLine("data: $dataLine") } catch(_: Exception) { null }
                            if(parsed != null)
                            {
                                when(parsed)
                                {
                                    is OpenAIResponsesStreamEvent.ResponseOutputTextDelta ->
                                    {
                                        if(parsed.delta.isNotEmpty())
                                        {
                                            textBuilder.append(parsed.delta)
                                            emitStreamingChunk(parsed.delta)
                                        }
                                    }
                                    is OpenAIResponsesStreamEvent.ResponseReasoningTextDelta ->
                                    {
                                        if(parsed.delta.isNotEmpty())
                                        {
                                            reasoningBuilder.append(parsed.delta)
                                        }
                                    }
                                    is OpenAIResponsesStreamEvent.ResponseCompleted ->
                                    {
                                        val usage = parsed.response.usage
                                        if(usage != null)
                                        {
                                            totalInputTokens = usage.inputTokens
                                            totalOutputTokens = usage.outputTokens
                                            totalReasoningTokens = usage.outputTokensDetails?.reasoningTokens ?: 0
                                        }
                                        applyResponsesTerminalTextFallback(parsed, textBuilder)
                                    }
                                    is OpenAIResponsesStreamEvent.ResponseFailed ->
                                    {
                                        val failMsg = parsed.response.error?.message
                                            ?: "OpenAI Responses stream reported response.failed (id=${parsed.response.id})"
                                        trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                                              metadata = mapOf(
                                                  "reason" to "responseFailed",
                                                  "responseId" to parsed.response.id,
                                                  "apiType" to "ResponsesAPI"
                                              ))
                                        throw P2PException(P2PError.transport, failMsg, Exception(failMsg))
                                    }
                                    else ->
                                    {
                                        // ResponseOutputTextDone and other terminal-shaped events:
                                        // delegate to the fallback helper for any text it can recover.
                                        applyResponsesTerminalTextFallback(parsed, textBuilder)
                                    }
                                }
                            }
                        }
                        is ApiMode.OpenAI ->
                        {
                            // Legacy chat-completions API: SSE data lines
                            // contain JSON with `choices[].delta.content`.
                            // Parse them here directly — no dedicated
                            // StreamEvent class exists in this codebase.
                            try
                            {
                                val element = Json.parseToJsonElement(dataLine)
                                val obj = element as? JsonObject
                                val choicesArr = obj?.get("choices") as? JsonArray
                                choicesArr?.forEach { choiceEl ->
                                    val choiceObj = choiceEl as? JsonObject
                                    val deltaObj = choiceObj?.get("delta") as? JsonObject
                                    val contentEl = deltaObj?.get("content")
                                    val content = (contentEl as? JsonPrimitive)?.content
                                    if(!content.isNullOrEmpty())
                                    {
                                        textBuilder.append(content)
                                        emitStreamingChunk(content)
                                    }
                                    // Terminal-chunk detection: spec lets the
                                    // server emit finish_reason on the final
                                    // chunk without a follow-up `data: [DONE]`
                                    // sentinel. Capture the first non-null value
                                    // and stop the SSE loop — otherwise the
                                    // parser waits for socket EOF that may not
                                    // arrive before the 120-second readTimeoutMs
                                    // (Mantle chunked-SigV4 keeps the socket
                                    // alive). The primary termination trigger is
                                    // the [DONE] guard above; this is the
                                    // belt-and-suspenders layer.
                                    val finishReasonEl = choiceObj?.get("finish_reason")
                                    val finishReason = (finishReasonEl as? JsonPrimitive)?.contentOrNull
                                    if(finishReason != null && streamingFinishReason == null)
                                    {
                                        streamingFinishReason = finishReason
                                        break@lineLoop
                                    }
                                }
                            }
                            catch(_: Exception)
                            {
                                // Skip malformed JSON line
                            }
                        }
                        is ApiMode.Anthropic ->
                        {
                            // Use AnthropicSseParser (the wrapper that manually dispatches
                            // by the outer `type` field) rather than calling
                            // `deserialize<AnthropicStreamEvent>` directly. The wire shape
                            // for content_block_delta carries `index` and `delta` at the outer
                            // level — not nested under a `chunk` key — so the sealed class
                            // cannot be polymorphic-decoded from the raw payload.
                            //
                            // Pass the raw `data: …` line directly to parseAnthropicLine —
                            // it strips its own prefix. The previous code path passed the
                            // already-stripped `sseLine.content` to parseAnthropicLine, which
                            // then took the `else -> Done` branch because the JSON did not
                            // start with `data:`.
                            val parsed: AnthropicStreamEvent = AnthropicSseParser.parseAnthropicLine("data: $dataLine")
                            if(parsed is AnthropicStreamEvent.ContentBlockDelta)
                            {
                                when(val delta = parsed.chunk.delta)
                                {
                                    is AnthropicDelta.TextDelta ->
                                    {
                                        if(delta.text.isNotEmpty())
                                        {
                                            textBuilder.append(delta.text)
                                            emitStreamingChunk(delta.text)
                                        }
                                    }
                                    is AnthropicDelta.ThinkingDelta ->
                                    {
                                        if(delta.thinking.isNotEmpty())
                                        {
                                            reasoningBuilder.append(delta.thinking)
                                        }
                                    }
                                    is AnthropicDelta.InputJsonDelta ->
                                    {
                                        // Structured output partial JSON — caller handles separately.
                                    }
                                }
                            }
                            else if(parsed is AnthropicStreamEvent.MessageDelta && parsed.stopReason != null)
                            {
                                // end of stream
                            }
                        }
                    }
                }
                lastEventType = null
            }
        }
        }
        catch(e: java.io.IOException)
        {
            // Mid-stream transport failure during the SSE read loop.
            // The loop has read whatever bytes had already arrived, so
            // textBuilder and streamingFinishReason reflect partial
            // state. Emit an API_CALL_FAILURE with the diagnostic
            // metadata block, then throw P2PException so the call
            // site propagates a typed transport failure.
            //
            // The `retryable` flag tells TPipe's PipeTimeoutManager
            // whether the configured timeoutStrategy=Retry +
            // maxRetryAttempts>0 path should fire. When retryable is
            // false (the default for callers who haven't opted in),
            // PipeTimeoutManager treats the failure as terminal and
            // the trace file is the visible error record.
            val retryable = this.timeoutStrategy == com.TTT.Pipe.PipeTimeoutStrategy.Retry
                && this.maxRetryAttempts > 0
            val elapsedMs = System.currentTimeMillis() - streamingStartMs
            val partialTextLength = textBuilder.length
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  error = e,
                  metadata = mapOf(
                      "reason" to "midStreamTransportFailure",
                      "streaming" to true,
                      "apiType" to when(apiMode)
                      {
                          is ApiMode.OpenAI -> "ChatAPI"
                          is ApiMode.OpenAIResponses -> "ResponsesAPI"
                          is ApiMode.Anthropic -> "AnthropicAPI"
                      },
                      "transport" to "HttpURLConnection",
                      "retryable" to retryable,
                      "transportErrorKind" to (e::class.simpleName ?: "IOException"),
                      "transportErrorMessage" to (e.message ?: ""),
                      "elapsedMs" to elapsedMs,
                      "partialTextLength" to partialTextLength,
                      "streamingFinishReason" to (this.streamingFinishReason ?: "null")
                  ))
            throw P2PException(
                P2PError.transport,
                "Streaming read failed mid-stream after ${partialTextLength} chars / ${elapsedMs}ms " +
                    "(${e::class.simpleName}: ${e.message}). retryable=$retryable",
                e
            )
        }

        streamingReasoning = reasoningBuilder.toString()
        streamingInputTokens = totalInputTokens
        streamingOutputTokens = totalOutputTokens
        streamingReasoningTokens = totalReasoningTokens
        // Expose the captured terminal reason on the class field so
        // downstream consumers (validators, post-call analytics,
        // streaming callbacks) can read it without parsing the SSE
        // stream themselves. null when the stream terminated on
        // [DONE] without a finish_reason chunk.
        this.streamingFinishReason = streamingFinishReason

        val resultText = textBuilder.toString()

        // OpenAI family: a completed-but-empty response is a typed provider failure
        // (the model produced no usable text in any of the supported shapes — deltas,
        // done-event, completed-response output). Without this check the pipe records
        // success=true with responseLength=0, which downstream validators misclassify
        // as a validator-pipe termination.
        if((apiMode is ApiMode.OpenAIResponses || apiMode is ApiMode.OpenAI) && resultText.isEmpty())
        {
            val errMessage = "OpenAI streaming produced no output text " +
                "(mode=${if(apiMode is ApiMode.OpenAIResponses) "ResponsesAPI" else "ChatAPI"}, " +
                "inputTokens=$streamingInputTokens, outputTokens=$streamingOutputTokens, " +
                "model=$model)"
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  metadata = mapOf(
                      "reason" to "emptyProviderResponse",
                      "inputTokens" to streamingInputTokens,
                      "outputTokens" to streamingOutputTokens,
                      "streaming" to true,
                      "apiType" to if(apiMode is ApiMode.OpenAIResponses) "ResponsesAPI" else "ChatAPI"
                  ))
            throw P2PException(P2PError.transport, errMessage, Exception(errMessage))
        }

        val streamingReasoningText = when(apiMode)
        {
            is ApiMode.OpenAIResponses, is ApiMode.Anthropic -> streamingReasoning
            else -> ""
        }
        val streamingInputTok = when(apiMode)
        {
            is ApiMode.OpenAIResponses -> streamingInputTokens
            else -> 0
        }
        val streamingOutputTok = when(apiMode)
        {
            is ApiMode.OpenAIResponses -> streamingOutputTokens
            else -> 0
        }
        val streamingReasonTok = when(apiMode)
        {
            is ApiMode.OpenAIResponses -> streamingReasoningTokens
            else -> 0
        }

        val result = com.TTT.Pipe.MultimodalContent(
            text = resultText,
            modelReasoning = streamingReasoningText
        )

        val streamingMetadata = mutableMapOf<String, Any>(
            "inputTokens" to streamingInputTok,
            "outputTokens" to streamingOutputTok,
            "totalTokens" to (streamingInputTok + streamingOutputTok),
            "responseLength" to resultText.length,
            "model" to model,
            "streaming" to true,
            "success" to true,
            "transport" to "HttpURLConnection",
            "apiType" to when(apiMode)
            {
                is ApiMode.OpenAI -> "ChatAPI"
                is ApiMode.OpenAIResponses -> "ResponsesAPI"
                is ApiMode.Anthropic -> "AnthropicAPI"
            }
        )
        // Surface the captured terminal reason when the chat-completions
        // API emitted one. This is the most operationally relevant
        // signal for OpenAI ChatAPI callers: a null finish_reason means
        // the stream terminated on the [DONE] sentinel without a
        // finish_reason chunk (rare but valid); "stop" is the normal
        // case; "length" indicates the model hit the token cap;
        // "content_filter" means the response was filtered mid-stream.
        if(this.streamingFinishReason != null)
        {
            streamingMetadata["streamingFinishReason"] = this.streamingFinishReason!!
        }
        if(streamingReasoningText.isNotEmpty())
        {
            streamingMetadata["reasoningLength"] = streamingReasoningText.length
            streamingMetadata["reasoningSupported"] = true
        }
        if(streamingReasonTok > 0)
        {
            streamingMetadata["reasoningTokens"] = streamingReasonTok
        }

        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              content = result,
              metadata = streamingMetadata)

        // Notify subscribers that the LLM has finished generating.
        // Success-path only — the IOException catch above and the
        // empty-response guard throw before reaching this point.
        emitStreamEnd()

        // Return the MultimodalContent (carries both `text` and `modelReasoning`)
        // so callers can route reasoning to its own content block. The
        // non-streaming path does the same via `generateTextMultimodal`.
        return result
    }

    /**
     * Write [body] to [output] as AWS SigV4 chunked-transfer-encoded
     * blocks for Mantle streaming. The wire format per chunk is:
     *
     * ```
     * <size_hex>;<signature>\r\n
     * <chunk_body>\r\n
     * ```
     *
     * where `<size_hex>` is the chunk's byte length as 5-character
     * lowercase hex, and `<signature>` is the per-chunk signature from
     * the chain in [auth]. After the last body chunk a 0-byte
     * terminator chunk is emitted (`0;<terminator-sig>\r\n\r\n`).
     *
     * The seed signature (the initial-request `Authorization` header
     * value) is passed as the first argument to `auth.signChunk(...)`
     * and is supplied by the caller via the headers map set up earlier
     * in [executeStreamingDirect].
     *
     * @param output The OutputStream to write to (already-flushed response
     *               body stream of an HttpURLConnection).
     * @param body The request body bytes (the marshalled JSON).
     * @param auth The chunked-encoding auth shape carrying the chunked
     *             signer.
     */
    private fun writeChunkedRequestBody(
        output: java.io.OutputStream,
        body: ByteArray,
        auth: BedrockMantleAuth.Streaming,
    )
    {
        val chunkSize = ChunkedSigV4Signer.CHUNK_SIZE_BYTES
        var previousSignature = extractStreamingSeedSignature(auth)
        var offset = 0
        while (offset < body.size)
        {
            val end = minOf(offset + chunkSize, body.size)
            val chunk = body.copyOfRange(offset, end)
            val chunkResult = auth.signChunk(previousSignature, chunk)
            val sizeHex = chunk.size.toString(16).padStart(5, '0').lowercase()
            output.write("$sizeHex;${chunkResult.signatureHex}\r\n".toByteArray(Charsets.US_ASCII))
            output.write(chunk)
            output.write("\r\n".toByteArray(Charsets.US_ASCII))
            offset = end
            previousSignature = chunkResult.signatureHex
        }

        // Final 0-byte terminator chunk. Its "previous" signature is the
        // last body chunk's signature.
        val terminator = auth.signChunk(previousSignature, ByteArray(0))
        output.write("0;${terminator.signatureHex}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /**
     * Extract the seed signature (the `authorization` header value) from
     * the [BedrockMantleAuth.Streaming] shape's `authHeaders` map. The
     * seed signature is the initial-request SigV4 signature computed
     * against the streaming constant as the payload hash.
     */
    private fun extractStreamingSeedSignature(auth: BedrockMantleAuth.Streaming): String
    {
        // Recompute the seed headers using the same method/url/body
        // shape the streaming-direct path used. The authHeaders
        // method computes the seed signature and returns the full
        // header map; we only need the authorization header value.
        val seedHeaders = auth.authHeaders(
            method = "POST",
            url = "$baseUrl${getEndpoint()}",
            body = ByteArray(0),
            headers = mapOf("Content-Type" to "application/json"),
        )
        return seedHeaders["authorization"]
            ?: throw IllegalStateException("Streaming auth did not produce a seed signature")
    }

    /**
     * Executes a streaming request and accumulates the response.
     * Returns the accumulated text plus any captured `modelReasoning`
     * as a [MultimodalContent].
     *
     * @param httpResponse The HTTP response from the streaming endpoint
     * @return Accumulated response text plus any captured modelReasoning
     */
    private suspend fun executeStreaming(httpResponse: HttpResponse): com.TTT.Pipe.MultimodalContent
    {
        val channel = httpResponse.bodyAsChannel()
        val textBuilder = StringBuilder()

        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf(
                  "step" to "streamingStart",
                  "streaming" to true,
                  "apiMode" to when(apiMode) { is ApiMode.OpenAI -> "OpenAI"; is ApiMode.OpenAIResponses -> "OpenAIResponses"; is ApiMode.Anthropic -> "Anthropic" }
              ))

        // Branch on apiMode: Anthropic uses AnthropicSseParser with different SSE format
        // OpenAI uses existing SseParser path
        // OpenAIResponses uses OpenAIResponsesSseParser with response.* event names
        when(apiMode)
        {
            is ApiMode.Anthropic -> executeStreamingAnthropic(channel, textBuilder)
            is ApiMode.OpenAI -> executeStreamingOpenAI(channel, textBuilder)
            is ApiMode.OpenAIResponses -> executeStreamingOpenAIResponses(channel, textBuilder)
        }

        val resultText = textBuilder.toString()

        // Mirror of the HttpURLConnection empty-failure guard: a completed-but-empty
        // OpenAI family stream is a typed provider failure, not API success.
        if((apiMode is ApiMode.OpenAIResponses || apiMode is ApiMode.OpenAI) && resultText.isEmpty())
        {
            val errMessage = "OpenAI streaming produced no output text " +
                "(mode=${if(apiMode is ApiMode.OpenAIResponses) "ResponsesAPI" else "ChatAPI"}, " +
                "inputTokens=$streamingInputTokens, outputTokens=$streamingOutputTokens, " +
                "model=$model)"
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  metadata = mapOf(
                      "reason" to "emptyProviderResponse",
                      "inputTokens" to streamingInputTokens,
                      "outputTokens" to streamingOutputTokens,
                      "streaming" to true,
                      "apiType" to if(apiMode is ApiMode.OpenAIResponses) "ResponsesAPI" else "ChatAPI"
                  ))
            throw P2PException(P2PError.transport, errMessage, Exception(errMessage))
        }

        val streamingReasoningText = when(apiMode)
        {
            is ApiMode.OpenAIResponses, is ApiMode.Anthropic -> streamingReasoning
            else -> ""
        }
        val streamingInputTok = when(apiMode)
        {
            is ApiMode.OpenAIResponses -> streamingInputTokens
            else -> 0
        }
        val streamingOutputTok = when(apiMode)
        {
            is ApiMode.OpenAIResponses -> streamingOutputTokens
            else -> 0
        }
        val streamingReasonTok = when(apiMode)
        {
            is ApiMode.OpenAIResponses -> streamingReasoningTokens
            else -> 0
        }

        val result = com.TTT.Pipe.MultimodalContent(
            text = resultText,
            modelReasoning = streamingReasoningText
        )

        val streamingMetadata = mutableMapOf<String, Any>(
            "inputTokens" to streamingInputTok,
            "outputTokens" to streamingOutputTok,
            "totalTokens" to (streamingInputTok + streamingOutputTok),
            "responseLength" to resultText.length,
            "model" to model,
            "streaming" to true,
            "success" to true,
            "apiType" to when(apiMode)
            {
                is ApiMode.OpenAI -> "ChatAPI"
                is ApiMode.OpenAIResponses -> "ResponsesAPI"
                is ApiMode.Anthropic -> "AnthropicAPI"
            }
        )
        if(streamingReasoningText.isNotEmpty())
        {
            streamingMetadata["reasoningLength"] = streamingReasoningText.length
            streamingMetadata["reasoningSupported"] = true
        }
        if(streamingReasonTok > 0)
        {
            streamingMetadata["reasoningTokens"] = streamingReasonTok
        }

        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              content = result,
              metadata = streamingMetadata)

        // Notify subscribers that the LLM has finished generating.
        // Success-path only — the IOException catch above and the
        // empty-response guard throw before reaching this point.
        emitStreamEnd()

        // Return the MultimodalContent (carries both `text` and `modelReasoning`)
        // so callers can route reasoning to its own content block. The
        // non-streaming path does the same via `generateTextMultimodal`.
        return result
    }

    /**
     * Handles streaming response for [ApiMode.OpenAI].
     * Uses OpenAI SSE format: `data: {...}` lines with [StreamingChunk] deserialization.
     * @param channel The HTTP response channel
     * @param textBuilder Accumulator for response text
     */
    private suspend fun executeStreamingOpenAI(
        channel: ByteReadChannel,
        textBuilder: StringBuilder
    )
    {
        while(!channel.isClosedForRead)
        {
            val line = channel.readUTF8Line() ?: break

            val sseLine = SseParser.parseLine(line)

            when(sseLine)
            {
                is SseParser.SseLine.Done -> break
                is SseParser.SseLine.Empty, is SseParser.SseLine.Comment -> continue
                is SseParser.SseLine.Data ->
                {
                    // Check for SSE error events before attempting StreamingChunk deserialization
                    val sseError = try { deserialize<GenericOpenAIErrorResponse>(sseLine.content) } catch(e: Exception) { null }
                    if(sseError != null && sseError.error.message.isNotEmpty())
                    {
                        val p2pError = when(sseError.error.type)
                        {
                            "authentication_error" -> P2PError.auth
                            "rate_limit_error" -> P2PError.transport
                            "invalid_request_error", "invalid_api_key" -> P2PError.prompt
                            "api_error", "server_error" -> P2PError.transport
                            null, "" -> P2PError.transport
                            else -> P2PError.transport
                        }
                        throw P2PException(p2pError, "GenericOpenAI streaming error: ${sseError.error.message}", Exception(sseError.error.message))
                    }

                    val chunk = SseParser.parseChunk(sseLine.content) ?: continue
                    val contentDelta = SseParser.extractContent(chunk)

                    if(contentDelta.isNotEmpty())
                    {
                        textBuilder.append(contentDelta)
                        emitStreamingChunk(contentDelta)
                    }
                }
                is SseParser.SseLine.Invalid -> continue
            }
        }
        // Stream ended successfully (via [DONE] sentinel or channel EOF).
        emitStreamEnd()
    }

    /**
     * Handles streaming response for [ApiMode.Anthropic].
     * Uses Anthropic SSE format: `event:` prefix + `data:` lines with [AnthropicStreamingChunk] deserialization.
     * Anthropic errors are thrown by [AnthropicSseParser], not deserialized from content.
     * @param channel The HTTP response channel
     * @param textBuilder Accumulator for response text
     */
    private suspend fun executeStreamingAnthropic(
        channel: ByteReadChannel,
        textBuilder: StringBuilder,
        reasoningBuilder: StringBuilder = StringBuilder()
    )
    {
        // For Anthropic streaming, we track the current event type from `event:` lines
        // The `data:` line that follows uses the appropriate parser
        var currentEventType: String? = null
        var lineCount = 0

        while(!channel.isClosedForRead)
        {
            val line = channel.readUTF8Line() ?: break
            lineCount++
            val trimmed = line.trim()

            // Empty line — skip (blank separator between events)
            if(trimmed.isEmpty())
            {
                continue
            }

            // `event:` prefix line — set the current event type for the next data line
            if(trimmed.startsWith("event: "))
            {
                currentEventType = trimmed.substringAfter("event: ").trim()
                continue
            }

            // `data:` line — parse based on current event type
            if(trimmed.startsWith("data: "))
            {
                val dataLine = trimmed

                // Only process content_block_delta events — ignore ping, message_start, content_block_start
                // message_delta signals end of stream (stop_reason available)
                when(currentEventType)
                {
                    "content_block_delta" ->
                    {
                        // parseAnthropicLine accepts either a `data: …` line or a bare JSON
                        // payload, and dispatches manually by the outer `type` field. The
                        // sealed class `AnthropicStreamEvent` does not support direct
                        // polymorphic deserialization because its subclasses do not share
                        // a common `type` field shape (see AnthropicStreaming.kt).
                        val event: AnthropicStreamEvent = AnthropicSseParser.parseAnthropicLine(dataLine)
                        if(event is AnthropicStreamEvent.ContentBlockDelta)
                        {
                            when(val delta = event.chunk.delta)
                            {
                                is AnthropicDelta.TextDelta ->
                                {
                                    if(delta.text.isNotEmpty())
                                    {
                                        textBuilder.append(delta.text)
                                        emitStreamingChunk(delta.text)
                                    }
                                }
                                is AnthropicDelta.ThinkingDelta ->
                                {
                                    if(delta.thinking.isNotEmpty())
                                    {
                                        reasoningBuilder.append(delta.thinking)
                                    }
                                }
                                is AnthropicDelta.InputJsonDelta ->
                                {
                                    // Structured output partial JSON — caller handles separately.
                                }
                            }
                        }
                    }
                    "message_delta" ->
                    {
                        // message_delta is the final event — stream ends here
                        // No content to emit, just break to return accumulated text
                        break
                    }
                    // ping, message_start, content_block_start, message_stop, etc. — ignore, continue
                    else ->
                    {
                        // Unknown or non-content event — continue without terminating
                        // Task 8: unknown events must NOT return Done to avoid premature stream termination
                        if(currentEventType == "message_stop")
                        {
                            break
                        }
                        // all other unknown events — just continue reading
                        continue
                    }
                }

                // Reset event type after processing data line
                currentEventType = null
            }
        }
        // Stream ended successfully (via message_delta / message_stop break).
        emitStreamEnd()
    }

    /**
     * Handles streaming response for [ApiMode.OpenAIResponses].
     * Uses the OpenAI Responses SSE format: `event: <type>` + `data: <json>` lines
     * (or bare `data:` lines with `type` inside the JSON), terminated by
     * `response.completed` / `response.failed`.
     *
     * Only [OpenAIResponsesStreamEvent.ResponseOutputTextDelta] deltas are appended
     * to [textBuilder]; every other event is ignored. Errors are thrown by the parser
     * (via [P2PException]) and abort the stream.
     *
     * @param channel The HTTP response channel
     * @param textBuilder Accumulator for response text
     */
    private suspend fun executeStreamingOpenAIResponses(
        channel: ByteReadChannel,
        textBuilder: StringBuilder
    )
    {
        // Reset cross-call state so a previous aborted stream does not leak.
        streamingReasoning = ""
        streamingInputTokens = 0
        streamingOutputTokens = 0
        streamingReasoningTokens = 0

        val reasoningBuilder = StringBuilder()
        var totalInputTokens = 0
        var totalOutputTokens = 0
        var totalReasoningTokens = 0

        while(!channel.isClosedForRead)
        {
            val line = channel.readUTF8Line() ?: break
            val event = try
            {
                OpenAIResponsesSseParser.parseLine(line)
            }
            catch(e: P2PException)
            {
                throw e
            }
            catch(e: Exception)
            {
                // Malformed single line should not kill the whole stream; skip it.
                continue
            }

            when(event)
            {
                is OpenAIResponsesStreamEvent.ResponseOutputTextDelta ->
                {
                    if(event.delta.isNotEmpty())
                    {
                        textBuilder.append(event.delta)
                        emitStreamingChunk(event.delta)
                    }
                }
                is OpenAIResponsesStreamEvent.ResponseReasoningTextDelta ->
                {
                    if(event.delta.isNotEmpty())
                    {
                        reasoningBuilder.append(event.delta)
                    }
                }
                is OpenAIResponsesStreamEvent.ResponseCompleted ->
                {

                    val usage = event.response.usage
                    if(usage != null)
                    {
                        totalInputTokens = usage.inputTokens
                        totalOutputTokens = usage.outputTokens
                        totalReasoningTokens = usage.outputTokensDetails?.reasoningTokens ?: 0
                    }
                    applyResponsesTerminalTextFallback(event, textBuilder)
                    break
                }
                is OpenAIResponsesStreamEvent.ResponseFailed ->
                {
                    val failMsg = event.response.error?.message
                        ?: "OpenAI Responses stream reported response.failed (id=${event.response.id})"
                    trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                          metadata = mapOf(
                              "reason" to "responseFailed",
                              "responseId" to event.response.id,
                              "apiType" to "ResponsesAPI"
                          ))
                    throw P2PException(P2PError.transport, failMsg, Exception(failMsg))
                }
                else ->
                {
                    // ResponseOutputTextDone and other terminal-shaped events:
                    // delegate to the fallback helper for any text it can recover.
                    applyResponsesTerminalTextFallback(event, textBuilder)
                }
            }
        }

        // Surface the accumulated reasoning on the trace event so it lands in
        // the trace as `reasoningContent` via the base Pipe class auto-trace.
        streamingReasoning = reasoningBuilder.toString()
        streamingInputTokens = totalInputTokens
        streamingOutputTokens = totalOutputTokens
        streamingReasoningTokens = totalReasoningTokens

        // Stream ended successfully (via ResponseCompleted break).
        // ResponseFailed throws before this point and never reaches here,
        // so error paths do not fire onComplete.
        emitStreamEnd()
    }

    /**
     * Applies the OpenAI Responses terminal-text fallback contract to [textBuilder].
     *
     * Order of preference:
     *   1. [OpenAIResponsesStreamEvent.ResponseOutputTextDone] — if the builder is
     *      still empty when this event arrives, use the done-event's accumulated
     *      [OpenAIResponsesStreamEvent.ResponseOutputTextDone.text] as the only source
     *      of truth. Skip when deltas already produced text to avoid duplication.
     *   2. [OpenAIResponsesStreamEvent.ResponseCompleted] — if the builder is still
     *      empty when the completion event arrives, walk
     *      `response.output[*].content[*].output_text.text` (mirroring the
     *      non-streaming `OpenAIResponsesResponseParser.extractTextAndRefusal` shape)
     *      and concatenate the text parts.
     *
     * Any other event type is a no-op. The HttpURLConnection and Ktor streaming
     * loops both call this from inside their `when` dispatch so the contract stays
     * identical between the two paths.
     *
     * @param event The parsed SSE event to apply
     * @param textBuilder The accumulating text buffer; mutated in place
     * @return Number of characters added to [textBuilder] by this event (0 if no-op)
     */
    private fun applyResponsesTerminalTextFallback(
        event: OpenAIResponsesStreamEvent,
        textBuilder: StringBuilder
    ): Int
    {
        return when(event)
        {
            is OpenAIResponsesStreamEvent.ResponseOutputTextDone ->
            {
                if(textBuilder.isEmpty() && event.text.isNotEmpty())
                {
                    textBuilder.append(event.text)
                    event.text.length
                }
                else
                {
                    0
                }
            }
            is OpenAIResponsesStreamEvent.ResponseCompleted ->
            {
                if(textBuilder.isNotEmpty()) return 0
                val recovered = StringBuilder()
                for(item in event.response.output)
                {
                    if(item is OpenAIResponsesOutputItem.Message)
                    {
                        for(part in item.content)
                        {
                            if(part is OpenAIResponsesContentPart.OutputText)
                            {
                                recovered.append(part.text)
                            }
                        }
                    }
                }
                if(recovered.isNotEmpty())
                {
                    textBuilder.append(recovered)
                    recovered.length
                }
                else
                {
                    0
                }
            }
            else -> 0
        }
    }

//=========================================Context Management==========================================================

    /**
     * Truncates module context using OpenAI-conservative token estimation by default.
     * For multimodal Mantle models, populates per-model binary TruncationSettings overrides
     * so the binary token-counting pipeline at Dictionary.countBinaryTokens produces correct
     * per-model estimates. Mirrors the BedrockPipe.kt pattern.
     *
     * Mantle multimodal models in scope (per /home/cage/Desktop/Workspaces/TPipe/md/00-synthesis-table.md
     * + the pi-bedrock-mantle README's live model list):
     *   - OpenAI: gpt-5.5, gpt-5.4, gpt-oss-120b, gpt-oss-20b (default conservative)
     *   - Anthropic via Mantle: claude-3-haiku, claude-3-sonnet (patch formula, 1,369 at 1024^2)
     *   - Mistral via Mantle: magistral-small, ministral-3*, mistral-large-3 (Pixtral 4,159 at 1024^2)
     *   - Voxtral via Mantle: voxtral-mini, voxtral-small (12.5 tokens/sec, duration-dependent)
     *   - Qwen3-VL via Mantle: qwen3-vl-235b (1,024 at 1024^2)
     *   - Z.AI via Mantle: glm-4.x, glm-5 (no published vision formula; default)
     *   - Moonshot via Mantle: kimi-k2.5 (1,369 at 1024^2)
     *   - NVIDIA via Mantle: nemotron-nano-12b-v2 (1,280 at 1024^2)
     *   - Writer via Mantle: palmyra-vision-7b (1,728 at 1024^2)
     *   - Google via Mantle: gemma-3-{4b,12b,27b}-it (256 flat)
     *
     * @return This pipe instance
     */
    override fun truncateModuleContext(): Pipe
    {
        // Use conservative defaults for OpenAI-compatible models
        contextWindowTruncation = ContextWindowSettings.TruncateTop
        countSubWordsInFirstWord = true
        favorWholeWords = true
        countOnlyFirstWordFound = false
        splitForNonWordChar = true
        alwaysSplitIfWholeWordExists = false
        countSubWordsIfSplit = false
        nonWordSplitCount = 2
        tokenCountingBias = 0.0

        // Per-model binary TruncationSettings overrides for Mantle multimodal models.
        // Same architectural pattern as BedrockPipe.kt: the binary* fields live ONLY on
        // the TruncationSettings data class; we set them on tokenBudgetSettings.truncationSettings
        // (creating it if null) so the framework's getTruncationSettings() returns them correctly.
        // Tier-1 (binaryMimeOverride) is the only working path because the current BpeEncoder
        // interface receives base64-encoded text chunks, not raw bytes.
        val binarySettings = when
        {
            model.contains("anthropic.claude") ->
                TruncationSettings(
                    binaryTokenEstimation = BinaryEstimationMode.HYBRID,
                    binaryMimeOverride = mapOf(
                        "image/jpeg" to 1369,
                        "image/png" to 1369,
                        "image/gif" to 1369,
                        "image/webp" to 1369,
                        "application/pdf" to 1369
                    ),
                    binaryEncoderThresholdBytes = 0,
                    binaryFudgeFactor = 1.0
                )
            model.contains("google.gemma-3") ->
                TruncationSettings(
                    binaryTokenEstimation = BinaryEstimationMode.HYBRID,
                    binaryMimeOverride = mapOf(
                        "image/jpeg" to 256,
                        "image/png" to 256,
                        "image/webp" to 256,
                        "image/gif" to 256
                    )
                )
            model.contains("voxtral") ->
                TruncationSettings(
                    binaryTokenEstimation = BinaryEstimationMode.HYBRID
                )
            model.contains("mistral") ->
                TruncationSettings(
                    binaryTokenEstimation = BinaryEstimationMode.HYBRID,
                    binaryMimeOverride = mapOf(
                        "image/jpeg" to 4159,
                        "image/png" to 4159,
                        "image/webp" to 4159
                    )
                )
            model.contains("qwen") ->
                TruncationSettings(
                    binaryTokenEstimation = BinaryEstimationMode.HYBRID,
                    binaryMimeOverride = mapOf(
                        "image/jpeg" to 1024,
                        "image/png" to 1024,
                        "image/webp" to 1024
                    )
                )
            model.contains("kimi") || model.contains("moonshotai") ->
                TruncationSettings(
                    binaryTokenEstimation = BinaryEstimationMode.HYBRID,
                    binaryMimeOverride = mapOf(
                        "image/jpeg" to 1369,
                        "image/png" to 1369,
                        "image/webp" to 1369
                    ),
                    binaryEncoderThresholdBytes = 0,
                    binaryFudgeFactor = 1.05
                )
            model.contains("nvidia.nemotron") ->
                TruncationSettings(
                    binaryTokenEstimation = BinaryEstimationMode.HYBRID,
                    binaryMimeOverride = mapOf(
                        "image/jpeg" to 1280,
                        "image/png" to 1280
                    )
                )
            model.contains("writer.palmyra") ->
                TruncationSettings(
                    binaryTokenEstimation = BinaryEstimationMode.HYBRID,
                    binaryMimeOverride = mapOf(
                        "image/jpeg" to 1728,
                        "image/png" to 1728
                    ),
                    binaryFudgeFactor = 1.10
                )
            model.contains("amazon.titan-embed-image") ->
                TruncationSettings(
                    binaryTokenEstimation = BinaryEstimationMode.HYBRID,
                    binaryMimeOverride = mapOf(
                        "image/jpeg" to 1,
                        "image/png" to 1
                    )
                )
            model.contains("amazon.nova-2-multimodal-embeddings") ->
                TruncationSettings(
                    binaryTokenEstimation = BinaryEstimationMode.HYBRID,
                    binaryMimeOverride = mapOf(
                        "image/jpeg" to 1,
                        "image/png" to 1,
                        "image/webp" to 1
                    )
                )
            model.contains("amazon.nova-2-sonic") || model.contains("amazon.nova-sonic") ->
                TruncationSettings(
                    binaryTokenEstimation = BinaryEstimationMode.HYBRID
                )
            else -> null
        }
        if(binarySettings != null)
        {
            tokenBudgetSettings?.let { tbs ->
                tbs.truncationSettings = binarySettings
            }
        }

        if(truncateContextAsString)
        {
            contextWindow.combineAndTruncateAsString(userPrompt, contextWindowSize, multiplyWindowSizeBy, contextWindowTruncation, countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound, splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount)
        }
        else
        {
            contextWindow.selectAndTruncateContext(userPrompt, contextWindowSize, multiplyWindowSizeBy, contextWindowTruncation, countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound, splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount)
        }
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
     * Cleans prompt text for Generic OpenAI compatibility.
     * OpenAI-compatible APIs use standard formatting, so no special cleanup is needed.
     * @param content The content to clean
     * @return The cleaned content
     */
    override fun cleanPromptText(content: MultimodalContent): MultimodalContent
    {
        return content
    }

    /**
     * Provider-side response cleanup. Some endpoints wrap their payload in
     * auxiliary reasoning surfaces (e.g. `&lt;think&gt;...&lt;/think&gt;` blocks from MiniMax-M2.7,
     * DeepSeek-R1, OpenAI o-series). TPipe core stays content-agnostic — the wire
     * format translation is already done in [onApplySystemPromptComplete]; this
     * hook is the symmetric exit-side cleanup, run after the model returns but
     * before TPipe parses.
     *
     * @param text Raw text returned by the wire.
     * @return Input with provider-local wrapping removed; identity if no cleanup needed.
     */
    override fun cleanResponseText(text: String): String
    {
        return ResponseShapeNormalizer.stripThinkTags(text)
    }

//=========================================Test-Only Helpers==========================================================
// The methods in this section exist solely to support the test suite. They let
// the pipe be driven without a real HTTP server. They are intentionally minimal — they only expose
// the seams the test suite actually uses (endpoint / auth-header introspection,
// HTTP-client injection, suspendable init/abort, and a non-`Pipeline` text generation
// path that bypasses `Pipeline.execute` so MockEngine round-trips stay synchronous).

//=========================================Transport Retry==========================================================

    /**
     * Runs [block] and retries it once after a 100ms backoff if it throws an
     * [IOException].
     *
     * Background: Ktor's CIO engine surfaces transport-level failures
     * ([java.net.SocketTimeoutException], raw [java.io.EOFException] from a
     * mid-stream cut, [io.ktor.client.plugins.HttpRequestTimeoutException],
     * [java.net.ConnectException]) as subclasses of [java.io.IOException]. None of
     * these were retried inside `client.post(...).bodyAsText()` previously, so a
     * single transient blip aborted the entire pump-station dispatch.
     *
     * Retry policy is deliberately narrow:
     *   - retries exactly once (no exponential backoff, no jitter);
     *   - retries ONLY on [IOException]; HTTP error responses, parse failures, and
     *     programmer errors propagate unchanged so the caller's outer catch can
     *     classify them;
     *   - the second [IOException] propagates unchanged so the pipe's outer catch
     *     can wrap it into [P2PException] with the [P2PError.transport] code,
     *     preserving the original exception as `cause`.
     *
     * @param block The suspending operation to run. May throw [IOException].
     * @return The value returned by [block] on the first or second attempt.
     * @throws IOException If both attempts throw [IOException]; the second exception is rethrown.
     */
    private suspend fun <T> runRequestWithRetry(block: suspend () -> T): T
    {
        return try
        {
            block()
        }
        catch(e: IOException)
        {
            delay(RETRY_BACKOFF_MILLIS)
            try
            {
                block()
            }
            catch(retryFailure: IOException)
            {
                throw retryFailure
            }
        }
    }

    /**
     * Returns the endpoint the pipe would POST to, for the current [apiMode].
     * Visible only to tests in the same module.
     */
    fun internalGetEndpointForTest(): String = getEndpoint()

    /**
     * Returns the auth headers the pipe would attach, for the current [apiMode].
     * Visible only to tests in the same module.
     */
    fun internalGetAuthHeadersForTest(): Map<String, String> =
        getAuthHeaders(method = "GET", url = baseUrl, body = ByteArray(0))

    /**
     * Replaces the internal Ktor [HttpClient] with a caller-supplied one. The pipe
     * does not own the new client — the caller is responsible for closing it.
     * Visible only to tests in the same module.
     */
    fun injectHttpClientForTest(client: HttpClient)
    {
        ownsHttpClient = false
        httpClient = client
    }

    /**
     * Returns the current internal Ktor [HttpClient] for assertion in tests that
     * verify the abort/retry-path contract. Production code MUST NOT use this.
     */
    fun httpClientForTest(): HttpClient? = httpClient

    /**
     * Builds the default Ktor [HttpClient] used by this pipe. Called from both
     * [init] (when no test-supplied client is present) and [abort] (to give the
     * retry path a fresh handle). Centralised here so the timeout configuration
     * lives in one place — the previous inline copy in [init] and the
     * `httpClient = null` regression in [abort] are the bugs this helper
     * prevents recurring.
     */
    private fun createHttpClient(): HttpClient = HttpClient(CIO)
    {
        install(HttpTimeout)
        {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    /**
     * Replaces the streaming-connection factory with a caller-supplied one for tests
     * that need to control the HttpURLConnection used by [executeStreamingDirect].
     * The production factory wraps java.net.URL(...). Test factories should record
     * the request and return canned SSE bodies via [HttpStreamingConnection.inputStream].
     */
    internal fun injectStreamingConnectionFactoryForTest(factory: HttpStreamingConnectionFactory)
    {
        streamingConnectionFactory = factory
    }

    /**
     * Suspending wrapper around [init] used by tests that need the pipe fully
     * initialised without going through `Pipeline.init(true)`.
     */
    suspend fun initForTest()
    {
        init()
    }

    /**
     * Suspending wrapper around [abort] used by tests so the test does not need
     * a `runBlocking { abort() }` boilerplate.
     */
    suspend fun abortForTest()
    {
        abort()
    }

    /**
     * Drives a single text generation through the pipe. Equivalent to
     * [generateText] but exposed to the test suite so dispatch tests can run
     * without standing up a full [com.TTT.Pipeline.Pipeline].
     */
    suspend fun generateTextForTest(prompt: String): String
    {
        return generateText(prompt)
    }
}
