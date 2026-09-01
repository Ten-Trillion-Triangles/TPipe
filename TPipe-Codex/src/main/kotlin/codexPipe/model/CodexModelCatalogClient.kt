package codexPipe.model

import codexPipe.CodexConstants
import codexPipe.auth.CodexAuthManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URLEncoder

/**
 * Fetches and briefly caches the Codex model catalog.
 *
 * The request is bounded to five seconds and shares one in-memory result for
 * five minutes. Authentication recovery is bounded to one retry on HTTP 401,
 * matching inference transport behavior.
 */
class CodexModelCatalogClient(
    private val authManager: CodexAuthManager,
    private val httpClient: HttpClient = HttpClient(CIO),
    private val baseUrl: String = CodexConstants.CODEX_BASE_URL,
    private val clientVersion: String = CodexConstants.CLIENT_VERSION,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val cacheDurationMillis: Long = 5 * 60 * 1_000L,
    private val requestTimeoutMillis: Long = 5_000L,
)
{
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheMutex = Mutex()
    private var cachedCatalog: CodexModelCatalog? = null

    /** Last successful catalog, including its ETag, or null before first load. */
    val lastCatalog: CodexModelCatalog?
        get() = cachedCatalog

    /**
     * Lists models, reusing a successful five-minute in-memory result.
     *
     * @param forceRefresh Bypass the in-memory cache when true.
     * @return Normalized model metadata.
     */
    suspend fun listModels(forceRefresh: Boolean = false): List<CodexModelInfo> = cacheMutex.withLock {
        val now = nowMillis()
        val cached = cachedCatalog
        if(!forceRefresh && cached != null && now - cached.fetchedAtMillis < cacheDurationMillis)
        {
            return@withLock cached.models
        }

        val catalog = fetchWithRecovery()
        cachedCatalog = catalog
        catalog.models
    }

    private suspend fun fetchWithRecovery(): CodexModelCatalog
    {
        val url = buildUrl()
        for(attempt in 0 until 2)
        {
            var requestHeaders: Map<String, String> = emptyMap()
            val response = withTimeout(requestTimeoutMillis) {
                httpClient.get(url) {
                    requestHeaders = authManager.authorizationHeaders()
                    requestHeaders.forEach { (name, value) -> header(name, value) }
                }
            }
            val body = response.bodyAsText()
            if(response.status.value == 401 && attempt == 0)
            {
                if(authManager.recoverUnauthorized(requestHeaders["Authorization"])) continue
            }
            if(response.status.value !in 200..299)
            {
                throw IOException("Codex model catalog failed with HTTP status ${response.status.value}")
            }
            return parseCatalog(body, response.headers[HttpHeaders.ETag])
        }
        throw IOException("Codex model catalog authorization could not be recovered")
    }

    private fun buildUrl(): String = "${baseUrl.trimEnd('/')}${CodexConstants.MODELS_PATH}" +
        "?client_version=${URLEncoder.encode(clientVersion, Charsets.UTF_8)}"

    private fun parseCatalog(body: String, etag: String?): CodexModelCatalog
    {
        val root = runCatching { json.parseToJsonElement(body) }.getOrElse {
            throw IOException("Codex model catalog returned invalid JSON")
        }
        val array = when(root)
        {
            is JsonArray -> root
            is JsonObject -> root["models"]?.jsonArray
                ?: root["data"]?.jsonArray
                ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val models = array.mapNotNull { (it as? JsonObject)?.toModel() }
            .sortedWith(compareBy<CodexModelInfo> { it.priority == null }
                .thenBy { it.priority ?: Int.MAX_VALUE }
                .thenBy { it.slug })
        return CodexModelCatalog(models = models, etag = etag, fetchedAtMillis = nowMillis())
    }

    private fun JsonObject.toModel(): CodexModelInfo?
    {
        val slug = string("slug") ?: string("id") ?: string("model") ?: return null
        val reasoning = this["supported_reasoning_efforts"] ?: this["supported_reasoning_levels"]
        return CodexModelInfo(
            slug = slug,
            displayName = string("display_name") ?: string("displayName"),
            description = string("description"),
            visibility = string("visibility"),
            priority = number("priority")?.toInt(),
            defaultReasoningLevel = string("default_reasoning_effort") ?: string("default_reasoning_level"),
            supportedReasoningLevels = reasoningStrings(reasoning),
            contextWindow = number("context_window"),
            maxContextWindow = number("max_context_window"),
            effectiveContextWindowPercent = decimal("effective_context_window_percent"),
            inputModalities = strings("input_modalities"),
            supportsVerbosity = boolean("supports_verbosity"),
            serviceTiers = services(this["service_tiers"]),
            defaultServiceTier = string("default_service_tier"),
        )
    }

    private fun reasoningStrings(element: kotlinx.serialization.json.JsonElement?): List<String> =
        (element as? JsonArray).orEmpty().mapNotNull {
            when(it)
            {
                is JsonPrimitive -> it.contentOrNull
                is JsonObject -> it.string("effort") ?: it.string("level") ?: it.string("id")
                else -> null
            }
        }

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun services(element: kotlinx.serialization.json.JsonElement?): List<CodexServiceTier> =
        (element as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id") ?: obj.string("name") ?: return@mapNotNull null
            CodexServiceTier(id, obj.string("name"), obj.string("description"))
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
        ?: string(key)?.toLongOrNull()

    private fun JsonObject.decimal(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
        ?: string(key)?.toDoubleOrNull()

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
