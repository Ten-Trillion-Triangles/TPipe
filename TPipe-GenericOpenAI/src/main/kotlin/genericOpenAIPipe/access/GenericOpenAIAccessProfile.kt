package genericOpenAIPipe.access

import genericOpenAIPipe.api.OpenAIResponsesWirePolicy

/**
 * Supplies request authentication and endpoint-specific transport behavior to
 * [genericOpenAIPipe.GenericOpenAIPipe].
 *
 * Access profiles are deliberately provider-neutral. They are transient runtime
 * collaborators and are never part of a serialized pipe configuration.
 */
interface GenericOpenAIAccessProfile
{
    /** Safe name used for diagnostics and trace metadata. */
    val profileName: String

    /** Optional policy for the OpenAI Responses serializer and wire transport. */
    val responsesWirePolicy: OpenAIResponsesWirePolicy?

    /**
     * Builds authentication and profile headers for one request.
     *
     * @param method HTTP method.
     * @param url Fully resolved request URL.
     * @param body UTF-8 request body bytes.
     * @return Headers that may be applied to the request.
     */
    suspend fun headersForRequest(
        method: String,
        url: String,
        body: ByteArray,
    ): Map<String, String>

    /**
     * Recovers authentication after an unauthorized response.
     *
     * The profile must not issue the retry itself. Returning `true` only tells
     * GenericOpenAI that one bounded retry may be attempted with rebuilt headers.
     */
    suspend fun recoverUnauthorized(): Boolean

    /**
     * Recovers authentication for a request that used [observedHeaders].
     *
     * The default delegates to [recoverUnauthorized] so existing access
     * profiles retain source compatibility. Profiles that rotate credentials
     * can use the observed bearer token to recognize a refresh performed by a
     * concurrent request and avoid rotating the refresh token twice.
     *
     * @param observedHeaders Headers applied to the unauthorized request.
     * @return True when a bounded retry may use refreshed credentials.
     */
    suspend fun recoverUnauthorized(observedHeaders: Map<String, String>): Boolean = recoverUnauthorized()
}
