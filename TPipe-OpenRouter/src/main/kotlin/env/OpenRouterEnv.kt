package env

/**
 * Central environment configuration for OpenRouter API credentials and settings.
 *
 * Manages the API key centrally with support for programmatic setting,
 * environment variable fallback, and test overrides.
 *
 * Usage:
 * ```
 * // Set API key programmatically
 * openrouterEnv.setApiKey("sk-...")
 *
 * // Or rely on OPENROUTER_API_KEY environment variable
 * // (OpenRouterPipe.init() checks env var automatically)
 *
 * // For testing: override the API key
 * openrouterEnv.setApiKey("test-key")
 * openrouterEnv.clearApiKey() // reset
 * ```
 *
 * @see OpenRouterPipe
 */
object OpenRouterEnv
{
    private var apiKey: String = ""

    /**
     * Sets the OpenRouter API key programmatically.
     *
     * @param key The API key from openrouter.ai
     */
    fun setApiKey(key: String)
    {
        apiKey = key
    }

    /**
     * Returns the currently configured API key, or empty string if not set.
     *
     * @return The API key or empty string
     */
    fun getApiKey(): String = apiKey

    /**
     * Returns the API key from the OPENROUTER_API_KEY environment variable,
     * or empty string if not set.
     *
     * @return Environment API key or empty string
     */
    fun getApiKeyFromEnv(): String = System.getenv("OPENROUTER_API_KEY") ?: ""

    /**
     * Clears the programmatically set API key.
     * After calling this, only the environment variable will be used.
     */
    fun clearApiKey()
    {
        apiKey = ""
    }

    /**
     * Returns the effective API key: programmatic key if set, otherwise env var.
     *
     * @return The effective API key
     */
    fun resolveApiKey(): String = apiKey.ifBlank { getApiKeyFromEnv() }

    /**
     * Returns true if any API key is configured (programmatic or env var).
     *
     * @return true if an API key is available
     */
    fun hasApiKey(): Boolean = resolveApiKey().isNotBlank()
}
