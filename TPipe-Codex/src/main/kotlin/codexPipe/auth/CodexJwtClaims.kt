package codexPipe.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/** Best-effort metadata projection of the claims used by the current Codex client. */
data class CodexJwtClaims(
    val email: String? = null,
    val chatgptPlanType: String? = null,
    val chatgptUserId: String? = null,
    val userId: String? = null,
    val chatgptAccountId: String? = null,
    val workspaceId: String? = null,
    val isFedramp: Boolean = false,
    val expirationEpochSeconds: Long? = null,
)
{
    companion object
    {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses the JWT payload without validating trust or signatures.
         * Returns null for malformed tokens or payloads.
         */
        fun parse(token: String): CodexJwtClaims?
        {
            val payload = token.split('.').getOrNull(1) ?: return null
            val decoded = runCatching {
                Base64.getUrlDecoder().decode(payload.padEnd(((payload.length + 3) / 4) * 4, '='))
                    .toString(Charsets.UTF_8)
            }.getOrNull() ?: return null
            val obj = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull() ?: return null
            return CodexJwtClaims(
                email = string(obj, "email"),
                chatgptPlanType = string(obj, "https://api.openai.com/auth.chatgpt_plan_type"),
                chatgptUserId = string(obj, "https://api.openai.com/auth.chatgpt_user_id"),
                userId = string(obj, "https://api.openai.com/auth.user_id"),
                chatgptAccountId = string(obj, "https://api.openai.com/auth.chatgpt_account_id"),
                workspaceId = string(obj, "https://api.openai.com/auth.chatgpt_workspace_id")
                    ?: string(obj, "workspace_id"),
                isFedramp = boolean(obj, "https://api.openai.com/auth.chatgpt_account_is_fedramp"),
                expirationEpochSeconds = number(obj, "exp"),
            )
        }

        private fun string(obj: JsonObject, key: String): String? =
            (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        private fun boolean(obj: JsonObject, key: String): Boolean
        {
            val value = obj[key] as? JsonPrimitive ?: return false
            return value.booleanOrNull ?: value.contentOrNull?.equals("true", ignoreCase = true) ?: false
        }

        private fun number(obj: JsonObject, key: String): Long? =
            (obj[key] as? JsonPrimitive)?.longOrNull
    }
}
