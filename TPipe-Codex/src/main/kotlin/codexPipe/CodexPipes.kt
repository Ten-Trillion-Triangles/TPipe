package codexPipe

import codexPipe.auth.CodexAuthManager
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.ApiMode

/** Factory helpers for subscription-backed Codex pipes. */
object CodexPipes
{
    /**
     * Creates a GenericOpenAI pipe configured for the Codex Responses transport.
     *
     * @param model Codex model slug selected by the caller or model catalog.
     * @param authManager Shared OAuth manager. Reuse one manager across a pipeline.
     * @return Configured [GenericOpenAIPipe] using [ApiMode.OpenAIResponses].
     */
    fun create(
        model: String,
        authManager: CodexAuthManager = CodexAuthManager.default(),
    ): GenericOpenAIPipe
    {
        require(model.isNotBlank()) { "Codex model cannot be blank" }
        return GenericOpenAIPipe()
            .setBaseUrl(CodexConstants.CODEX_BASE_URL)
            .setApiMode(ApiMode.OpenAIResponses)
            .setAccessProfile(CodexAccessProfile(authManager))
            .apply { setModel(model) }
    }
}

/**
 * Applies Codex OAuth transport settings to an existing GenericOpenAI pipe.
 *
 * @param authManager Shared OAuth manager for the configured pipeline.
 * @param model Optional model override; the existing model remains when null.
 * @return This pipe for fluent configuration.
 */
fun GenericOpenAIPipe.useCodexOAuth(
    authManager: CodexAuthManager,
    model: String? = null,
): GenericOpenAIPipe
{
    model?.let {
        require(it.isNotBlank()) { "Codex model cannot be blank" }
        setModel(it)
    }
    return setBaseUrl(CodexConstants.CODEX_BASE_URL)
        .setApiMode(ApiMode.OpenAIResponses)
        .setAccessProfile(CodexAccessProfile(authManager))
}
