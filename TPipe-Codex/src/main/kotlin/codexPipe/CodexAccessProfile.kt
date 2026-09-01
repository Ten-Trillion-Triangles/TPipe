package codexPipe

import codexPipe.auth.CodexAuthManager
import genericOpenAIPipe.access.GenericOpenAIAccessProfile
import genericOpenAIPipe.api.OpenAIResponsesWirePolicy

/**
 * Runtime access profile that adapts a [CodexAuthManager] to GenericOpenAI.
 *
 * The profile owns only request-time authentication and Codex-specific wire
 * policy. It does not expose OAuth tokens to the pipe's serializable state and
 * it does not translate TPipe PCP tools into native Codex tools.
 */
class CodexAccessProfile(
    private val authManager: CodexAuthManager,
) : GenericOpenAIAccessProfile
{
    override val profileName: String = "CodexOAuth"

    override val responsesWirePolicy: OpenAIResponsesWirePolicy = OpenAIResponsesWirePolicy(
        emitMaxOutputTokens = false,
        emitSamplingParameters = false,
        emitUser = false,
        store = false,
        include = listOf("reasoning.encrypted_content"),
        messageItemType = "message",
        forceStreaming = true,
    )

    override suspend fun headersForRequest(
        method: String,
        url: String,
        body: ByteArray,
    ): Map<String, String> = authManager.authorizationHeaders()

    override suspend fun recoverUnauthorized(): Boolean = authManager.recoverUnauthorized()

    override suspend fun recoverUnauthorized(observedHeaders: Map<String, String>): Boolean =
        authManager.recoverUnauthorized(observedHeaders["Authorization"])
}
