package genericOpenAIPipe.env

/**
 * Central environment configuration for Generic OpenAI API credentials and settings.
 *
 * Manages the API key centrally with support for programmatic setting,
 * environment variable fallback, and test overrides.
 *
 * Usage:
 * ```
 * // Set API key programmatically
 * genericOpenAIEnv.setApiKey("sk-...")
 *
 * // Or rely on GENERIC_OPENAI_API_KEY environment variable
 * // (GenericOpenAIPipe.init() checks env var automatically)
 *
 * // For testing: override the API key
 * genericOpenAIEnv.setApiKey("test-key")
 * genericOpenAIEnv.clearApiKey() // reset
 * ```
 *
 * @see GenericOpenAIPipe
 */
object GenericOpenAIEnv
{
    private var apiKey: String = ""

    /**
     * Sets the Generic OpenAI API key programmatically.
     *
     * @param key The API key
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
     * Returns the API key from the GENERIC_OPENAI_API_KEY environment variable,
     * or empty string if not set.
     *
     * @return Environment API key or empty string
     */
    fun getApiKeyFromEnv(): String = System.getenv("GENERIC_OPENAI_API_KEY") ?: ""

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