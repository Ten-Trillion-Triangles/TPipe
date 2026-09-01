package codexPipe.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * One-way importer for the file-backed ChatGPT token shape used by Codex CLI.
 * Keyring-only and unrelated auth modes are intentionally ignored.
 */
class CodexCliCredentialImporter(
    private val store: CodexCredentialStore,
    private val authFile: Path = CodexPaths.codexCliAuthFile(),
)
{
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Imports valid OAuth tokens when the TPipe store is empty.
     *
     * @return Imported credentials, or null when no importable file exists.
     */
    fun importIfMissing(): CodexOAuthCredentials?
    {
        if(store.load() != null || !Files.exists(authFile)) return null

        val root = runCatching { json.parseToJsonElement(Files.readString(authFile)).jsonObject }
            .getOrNull() ?: return null
        val authMode = (root["auth_mode"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        if(authMode != null && listOf("api", "pat", "personal", "agent", "bedrock", "keyring")
                .any { authMode.contains(it) })
        {
            return null
        }
        val tokens = root["tokens"]?.jsonObject ?: return null
        val idToken = tokenString(tokens["id_token"]) ?: return null
        val accessToken = tokenString(tokens["access_token"]) ?: return null
        val refreshToken = tokenString(tokens["refresh_token"]) ?: return null
        if(idToken.isBlank() || accessToken.isBlank() || refreshToken.isBlank()) return null

        val credentials = CodexOAuthCredentials.fromTokens(
            idToken = idToken,
            accessToken = accessToken,
            refreshToken = refreshToken,
            explicitAccountId = tokenString(tokens["account_id"]),
            explicitWorkspaceId = tokenString(tokens["workspace_id"]),
            lastRefresh = parseTimestamp(root["last_refresh"]),
        )
        store.save(credentials)
        return credentials
    }

    private fun tokenString(element: JsonElement?): String?
    {
        if(element is JsonPrimitive) return element.contentOrNull
        val objectValue = element as? JsonObject ?: return null
        return tokenString(objectValue["raw_jwt"])
            ?: tokenString(objectValue["token"])
    }

    private fun parseTimestamp(element: JsonElement?): Long?
    {
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.contentOrNull?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        }
    }
}
