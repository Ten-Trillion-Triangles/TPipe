package genericOpenAIPipe

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Pure validation and classification rules for GenericOpenAI base URLs.
 *
 * The policy deliberately compares the URI host text without DNS resolution.
 * That keeps the security boundary deterministic and prevents a hostname that
 * merely resolves to loopback from being treated as an exact loopback target.
 */
internal object BaseUrlPolicy
{
    /**
     * Validates and normalizes a GenericOpenAI base URL.
     *
     * HTTPS is accepted for any valid host. Plain HTTP is accepted automatically
     * only for exact loopback hosts, unless [allowInsecureHttp] is explicitly set.
     *
     * @param url Candidate base URL.
     * @param allowInsecureHttp Whether to allow valid non-loopback HTTP URLs.
     * @return Trimmed URL without trailing slash.
     * @throws IllegalArgumentException if the URL is malformed or outside policy.
     */
    fun validateAndNormalize(url: String, allowInsecureHttp: Boolean): String
    {
        val normalized = url.trim().trimEnd('/')
        require(normalized.isNotBlank()) { "baseUrl cannot be blank" }

        val uri = parse(normalized)
        val scheme = uri.scheme!!.lowercase(Locale.ROOT)
        val host = uri.host!!
        val isLoopback = isLoopbackHost(host)

        require(scheme == "https" || scheme == "http") {
            "baseUrl must use HTTP or HTTPS (got: $url)"
        }
        require(scheme == "https" || isLoopback || allowInsecureHttp) {
            "baseUrl must use HTTPS for security (got: $url). " +
                "Loopback HTTP is allowed automatically; set TPIPE_ALLOW_INSECURE_BASEURL=true " +
                "or the tpipe.allowInsecureBaseUrl system property to allow non-loopback HTTP."
        }

        return normalized
    }

    /**
     * Returns whether a valid URL targets exact loopback host text.
     *
     * @param url URL to classify.
     * @return `true` for `localhost`, `127.0.0.0/8`, or `::1`; otherwise `false`.
     */
    fun isLoopbackUrl(url: String): Boolean
    {
        val uri = runCatching { parse(url.trim()) }.getOrNull() ?: return false
        return isLoopbackHost(uri.host!!)
    }

    private fun parse(url: String): URI
    {
        val uri = try
        {
            URI(url)
        }
        catch(e: URISyntaxException)
        {
            throw IllegalArgumentException("baseUrl must be a valid HTTP(S) URL (got: $url)", e)
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "https" || scheme == "http") {
            "baseUrl must use HTTP or HTTPS (got: $url)"
        }
        require(uri.rawAuthority != null && uri.host != null) {
            "baseUrl must include a valid host (got: $url)"
        }
        require(uri.rawUserInfo == null) {
            "baseUrl must not include embedded credentials (got: $url)"
        }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "baseUrl must not include a query or fragment (got: $url)"
        }
        require(uri.port >= -1) {
            "baseUrl must include a valid port (got: $url)"
        }

        return uri
    }

    private fun isLoopbackHost(rawHost: String): Boolean
    {
        val host = rawHost.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        if(host == "localhost" || host == "::1") return true

        val octets = host.split('.')
        return octets.size == 4 &&
            octets.all { it.isNotBlank() && it.toIntOrNull() in 0..255 } &&
            octets[0].toInt() == 127
    }
}
