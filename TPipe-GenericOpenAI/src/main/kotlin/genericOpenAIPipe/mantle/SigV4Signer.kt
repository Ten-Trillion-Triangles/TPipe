package genericOpenAIPipe.mantle

import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Clock abstraction used by [SigV4Signer] to obtain the current time. Test
 * code can supply a fixed-clock implementation to produce deterministic
 * timestamps; production code uses [SystemClock] which delegates to
 * `System.currentTimeMillis()`.
 */
fun interface Clock
{
    fun nowMillis(): Long
}

/** Default clock that delegates to `System.currentTimeMillis()`. */
object SystemClock : Clock
{
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * Pure-Java AWS Signature Version 4 (SigV4) request signer.
 *
 * Implements the signing protocol documented by AWS for general-purpose API
 * requests. No AWS SDK dependency. The signer is reused across requests: it
 * holds immutable credential + region + service state and computes a fresh
 * signature per request at call time.
 *
 * Algorithm summary (per AWS Signature Version 4 for API requests):
 *
 *  1. Hash the request payload with SHA-256.
 *  2. Build a canonical request string from method, URI, query string, headers
 *     (lowercased, sorted), signed-headers list, and the payload hash.
 *  3. Build a string-to-sign from the algorithm, ISO-8601 basic timestamp,
 *     credential scope, and the SHA-256 hash of the canonical request.
 *  4. Derive a signing key by chaining HMAC-SHA256 over `AWS4{secret}`,
 *     date, region, and service.
 *  5. Compute the signature as HMAC-SHA256(signing-key, string-to-sign),
 *     lowercase hex.
 *  6. Build the `Authorization` header from the credential scope, signed
 *     headers list, and signature.
 *
 * Required request headers on every signed request:
 *  - `Host` (added automatically by [signRequest] when missing)
 *  - `X-Amz-Date` (added by [signRequest] when missing; format
 *    `yyyyMMdd'T'HHmmss'Z'`)
 *
 * Optional but added automatically when a session token is configured:
 *  - `X-Amz-Security-Token`
 *
 * `X-Amz-Content-SHA256` is computed and embedded in the canonical request
 * but is NOT added to the request headers (matching aws-sdk-go reference
 * behavior at `aws/signer/v4/v4_test.go:202`).
 *
 * @see <a href="https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html">AWS Signature Version 4 for API requests</a>
 * @see <a href="https://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html">Signing AWS API requests</a>
 *
 * @property accessKeyId AWS access key id.
 * @property secretAccessKey AWS secret access key.
 * @property sessionToken Optional session token for temporary credentials.
 *                        When present, an `X-Amz-Security-Token` header is
 *                        added to the request and the credential scope is
 *                        unchanged.
 * @property region AWS region code (for example `us-east-1`).
 * @property service AWS service identifier. Defaults to `bedrock-mantle`,
 *                  matching the documented Mantle endpoint service code.
 * @property clock Clock abstraction returning epoch millis. Defaults to
 *                [SystemClock]. Override in tests for deterministic
 *                timestamps.
 */
class SigV4Signer(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String? = null,
    val region: String,
    val service: String = "bedrock-mantle",
    private val clock: Clock = SystemClock,
)
{
    init
    {
        require(accessKeyId.isNotBlank()) { "accessKeyId cannot be blank" }
        require(secretAccessKey.isNotBlank()) { "secretAccessKey cannot be blank" }
        require(region.isNotBlank()) { "region cannot be blank" }
        require(service.isNotBlank()) { "service cannot be blank" }
    }

    /**
     * Sign a request and return the full set of HTTP headers the caller
     * should attach to the outgoing request.
     *
     * The returned map contains the `Authorization` header, the timestamp
     * header, the (optional) session-token header, and any caller-supplied
     * headers that participate in the signature. The caller should NOT add
     * `Host` or `X-Amz-Date` headers — those are produced here.
     *
     * @param method HTTP method in uppercase (for example `POST`).
     * @param url The full request URL (scheme + host + path + query).
     * @param headers Caller-supplied headers. Headers with names that
     *                participate in the signature (Host, Content-Type, and any
     *                X-Amz-* headers) should be supplied here; the returned
     *                map preserves them.
     * @param body Request payload as bytes. May be empty. For empty payloads
     *              the canonical payload hash is the well-known SHA-256 of the
     *              empty string.
     * @return Map of header name to header value, including the
     *         `Authorization` header. Header names are lowercase, matching
     *         AWS SigV4 conventions.
     */
    fun signRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): Map<String, String>
    {
        val canonicalMethod = method.uppercase()

        // Use java.net.URL rather than java.net.URI because URL is lenient
        // about percent-encoded characters that don't form a valid escape
        // pair (which AWS SigV4 signers routinely encounter when the caller
        // passes through opaque path components containing reserved chars).
        // However, URL truncates path content on characters like `$%` that
        // look like escape sequences, so we recover the raw path-and-query
        // substring from the original URL after the host portion.
        val parsed = URL(url)
        val canonicalUri = canonicalUri(extractRawPath(url, parsed))
        val canonicalQuery = canonicalQueryString(parsed.query)

        val payloadHash = sha256Hex(body)

        val now = clock.nowMillis()
        val amzDate = formatAmzDate(now)
        val dateStamp = amzDate.substring(0, 8)

        // Build the header map that participates in the signature. Headers are
        // stored under their lowercase name. Required: Host, X-Amz-Date.
        // Plus any caller headers with x- prefix or host. The
        // X-Amz-Content-SHA256 value participates in the canonical request
        // but is intentionally NOT added as a signed header (matching
        // aws-sdk-go reference behavior).
        val signedHeaderEntries: MutableMap<String, String> = LinkedHashMap()

        val hostHeader = headers.entries.firstOrNull { it.key.equals("Host", ignoreCase = true) }?.value
            ?: parsed.host?.let { if (parsed.port != -1 && parsed.port != 443 && parsed.port != 80) "$it:${parsed.port}" else it }
            ?: throw IllegalArgumentException("Cannot determine Host for $url")
        // Caller-supplied Host header takes precedence. If absent, derive
        // from the URL. We do NOT dedupe or merge; an explicit Host in the
        // caller-supplied headers is signed as supplied (matching aws-sdk-go
        // reference behavior at aws-sdk-go/v4/v4_test.go:202).
        signedHeaderEntries["host"] = hostHeader.trim()

        headers.forEach { (name, value) ->
            val lower = name.lowercase()
            if (lower == "host") return@forEach
            // AWS SigV4 signs the small set of well-known headers plus any
            // caller-provided `x-amz-*` headers. Per the AWS-published
            // reference test (aws-sdk-go aws/signer/v4/v4_test.go:202), the
            // set is {content-length, content-type, host, x-amz-date,
            // x-amz-meta-*, x-amz-security-token, x-amz-target}; we treat
            // any header whose lowercase name begins with `x-amz-` or is
            // exactly one of the well-known content headers as signed.
            if (lower.startsWith("x-amz-") ||
                lower == "content-type" ||
                lower == "content-length") {
                signedHeaderEntries[lower] = value.trim()
            }
        }

        signedHeaderEntries["x-amz-date"] = amzDate

        if (!sessionToken.isNullOrBlank()) {
            signedHeaderEntries["x-amz-security-token"] = sessionToken
        }

        val signedHeadersList = signedHeaderEntries.keys.sorted().joinToString(";")
        // Per the AWS SigV4 spec, canonical headers are
        // "header1:value1\nheader2:value2\n\n" — each entry ends with a
        // single \n, and there is a trailing blank line after the last
        // entry (which separates the headers from the signed-headers
        // line). We achieve this by giving each entry a trailing \n
        // and joining with an empty separator, then appending one
        // more \n to introduce the blank line.
        val canonicalHeaders = signedHeaderEntries.entries.sortedBy { it.key }
            .joinToString(separator = "") { "${it.key}:${it.value}\n" } + "\n"

        val canonicalRequest = buildString {
            append(canonicalMethod).append('\n')
            append(canonicalUri).append('\n')
            append(canonicalQuery).append('\n')
            append(canonicalHeaders)
            append(signedHeadersList).append('\n')
            append(payloadHash)
        }

        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = buildString {
            append(ALGORITHM).append('\n')
            append(amzDate).append('\n')
            append(credentialScope).append('\n')
            append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
        }

        val signingKey = deriveSigningKey(dateStamp)
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val authorization = "$ALGORITHM " +
            "Credential=$accessKeyId/$credentialScope, " +
            "SignedHeaders=$signedHeadersList, " +
            "Signature=$signature"

        val debugFlag = headers["__tpipeDebugCanonicalRequest"]
        val collectDebug = debugFlag != null && debugFlag.isNotBlank()

        val result: MutableMap<String, String> = LinkedHashMap()
        signedHeaderEntries.forEach { (k, v) -> result[k] = v }
        result["authorization"] = authorization

        // Diagnostic introspection is only exposed via the side-channel
        // [authHeadersForTest] helper. We do NOT add the multi-line
        // canonical_request / string_to_sign values to the returned map
        // because Ktor rejects HTTP headers containing newline characters
        // and these debug values contain newlines throughout.

        return result
    }

    /**
     * Sign a request that will be sent with chunked-transfer-encoding
     * (AWS S3 / Bedrock Mantle streaming). The canonical request uses
     * the streaming-payload constant as the payload hash instead of the
     * hash of the supplied body.
     *
     * The caller is responsible for adding
     * `Transfer-Encoding: chunked`,
     * `Content-Encoding: aws-chunked`, and
     * `x-amz-decoded-content-length: <bytes>` to the outbound request.
     * The signature returned in [signRequest] covers the canonical
     * request with the streaming constant as the payload hash.
     *
     * @param method HTTP method (uppercase).
     * @param url Full request URL.
     * @param headers Caller-supplied headers. Will be merged into the
     *               signature; the streaming constant is added
     *               automatically.
     * @return Map of header name to header value, including the
     *         `authorization` (seed signature), `x-amz-content-sha256`
     *         (the streaming constant), and the host / x-amz-date
     *         headers.
     */
    fun signStreamingRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
    ): Map<String, String>
    {
        // Reuse signRequest with an empty body; the resulting signature
        // is computed against the empty-payload hash, which is NOT the
        // AWS streaming constant. So we override the payload hash by
        // post-processing the canonical request before signing.
        //
        // Simpler approach: sign with the streaming constant as the
        // payload hash directly. That requires computing the canonical
        // request manually, which signRequest already does for a given
        // body. So we synthesize a body whose hash IS the streaming
        // constant and pass it through signRequest.
        //
        // The streaming constant is a hex SHA256. We can't just feed it
        // to signRequest (which SHA256s the body). Instead, we sign
        // with the streaming constant injected post-hoc:
        //   1. Build the canonical request against the EMPTY body.
        //   2. Replace the trailing payload-hash line with the streaming
        //      constant.
        //   3. Compute the canonical-request hash, build the string-to-sign,
        //      sign with the signing key.
        //
        // This duplicates parts of signRequest but keeps the change
        // minimal and behavior-preserving for the existing non-streaming
        // path.

        val canonicalMethod = method.uppercase()
        val parsed = URL(url)
        val canonicalUri = canonicalUri(extractRawPath(url, parsed))
        val canonicalQuery = canonicalQueryString(parsed.query)

        val now = clock.nowMillis()
        val amzDate = formatAmzDate(now)
        val dateStamp = amzDate.substring(0, 8)

        val signedHeaderEntries: MutableMap<String, String> = LinkedHashMap()
        val hostHeader = headers.entries.firstOrNull { it.key.equals("Host", ignoreCase = true) }?.value
            ?: parsed.host?.let { if (parsed.port != -1 && parsed.port != 443 && parsed.port != 80) "$it:${parsed.port}" else it }
            ?: throw IllegalArgumentException("Cannot determine Host for $url")
        signedHeaderEntries["host"] = hostHeader.trim()

        headers.forEach { (name, value) ->
            val lower = name.lowercase()
            if (lower == "host") return@forEach
            if (lower.startsWith("x-amz-") ||
                lower == "content-type" ||
                lower == "content-length") {
                signedHeaderEntries[lower] = value.trim()
            }
        }

        signedHeaderEntries["x-amz-date"] = amzDate

        if (!sessionToken.isNullOrBlank()) {
            signedHeaderEntries["x-amz-security-token"] = sessionToken
        }

        val signedHeadersList = signedHeaderEntries.keys.sorted().joinToString(";")
        // Per the AWS SigV4 spec, canonical headers are
        // "header1:value1\nheader2:value2\n\n" — each entry ends with a
        // single \n, and there is a trailing blank line after the last
        // entry (which separates the headers from the signed-headers
        // line). We achieve this by giving each entry a trailing \n
        // and joining with an empty separator, then appending one
        // more \n to introduce the blank line.
        val canonicalHeaders = signedHeaderEntries.entries.sortedBy { it.key }
            .joinToString(separator = "") { "${it.key}:${it.value}\n" } + "\n"

        val canonicalRequest = buildString {
            append(canonicalMethod).append('\n')
            append(canonicalUri).append('\n')
            append(canonicalQuery).append('\n')
            append(canonicalHeaders)
            append(signedHeadersList).append('\n')
            // Streaming constant: the canonical request's payload hash is
            // the streaming-payload marker, NOT the hash of the body.
            append(STREAMING_PAYLOAD_HASH)
        }

        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = buildString {
            append(ALGORITHM).append('\n')
            append(amzDate).append('\n')
            append(credentialScope).append('\n')
            append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
        }

        val signingKey = deriveSigningKey(dateStamp)
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val authorization = "$ALGORITHM " +
            "Credential=$accessKeyId/$credentialScope, " +
            "SignedHeaders=$signedHeadersList, " +
            "Signature=$signature"

        val result: MutableMap<String, String> = LinkedHashMap()
        signedHeaderEntries.forEach { (k, v) -> result[k] = v }
        result["authorization"] = authorization
        result["x-amz-content-sha256"] = STREAMING_PAYLOAD_HASH
        return result
    }

    /**
     * Derive the SigV4 signing key by chaining HMAC-SHA256 over `AWS4{secret}`,
     * date, region, and service, returning the final 32-byte key.
     *
     * Exposed as `internal` so [ChunkedSigV4Signer] can reuse the same
     * derivation chain without re-implementing the HMAC-SHA256 chain.
     */
    internal fun deriveSigningKey(dateStamp: String): ByteArray
    {
        val kSecret = ("AWS4$secretAccessKey").toByteArray(Charsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        return hmacSha256(kService, "aws4_request")
    }

    /**
     * Recover the raw path substring of [url]. `URL.getPath()` truncates the
     * path on characters that look like malformed percent escapes; this
     * helper slices the original [url] string from the end of the authority
     * (computed from [parsed]) up to the start of any query string, preserving
     * the full raw bytes. Returns `/` when no path is present.
     */
    private fun extractRawPath(url: String, parsed: URL): String
    {
        val authorityEnd = findAuthorityEnd(url, parsed)
        if (authorityEnd < 0) return "/"
        val afterAuthority = url.substring(authorityEnd)
        val queryStart = afterAuthority.indexOf('?')
        val rawPath = if (queryStart >= 0) afterAuthority.substring(0, queryStart) else afterAuthority
        return if (rawPath.isEmpty()) "/" else rawPath
    }

    /**
     * Locate the end offset of the authority (host + optional port + optional
     * userinfo) within [url]. The authority is the substring following
     * `://` and preceding the first `/`, `?`, or `#`.
     */
    private fun findAuthorityEnd(url: String, parsed: URL): Int
    {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return -1
        val afterScheme = schemeEnd + 3
        for (i in afterScheme until url.length)
        {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') return i
        }
        return url.length
    }

    companion object
    {
        /** SigV4 algorithm identifier used in the Authorization header and string-to-sign. */
        const val ALGORITHM: String = "AWS4-HMAC-SHA256"

        /**
         * SHA-256 hash of the empty string, lowercase hex. Used as the payload
         * hash for requests with no body.
         */
        const val EMPTY_PAYLOAD_HASH: String =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        /**
         * Streaming-payload marker used as the canonical-request payload hash
         * for AWS SigV4 chunked-transfer-encoding requests. Matches the
         * documented constant at `docs.aws.amazon.com/AmazonS3/latest/developerguide/sigv4-streaming.html`.
         */
        const val STREAMING_PAYLOAD_HASH: String =
            "STREAMING-AWS4-HMAC-SHA256-PAYLOAD"

        /**
         * Lowercase-hex SHA-256 of [payload].
         */
        fun sha256Hex(payload: ByteArray): String
        {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(payload)
            return bytes.toHex()
        }

        /**
         * Compute HMAC-SHA256 with [key] over [data] (UTF-8 bytes), returning
         * raw bytes.
         */
        fun hmacSha256(key: ByteArray, data: String): ByteArray
        {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data.toByteArray(Charsets.UTF_8))
        }

        /**
         * Compute HMAC-SHA256 with [key] over [data] (UTF-8 bytes), returning
         * lowercase hex.
         */
        fun hmacSha256Hex(key: ByteArray, data: String): String =
            hmacSha256(key, data).toHex()

        /**
         * Apply the SigV4 URI-encoding rules to [value]. Unreserved characters
         * (per RFC 3986) pass through unchanged. Everything else is
         * percent-encoded with uppercase hex.
         */
        fun uriEncode(value: String, encodeSlash: Boolean = true): String
        {
            val sb = StringBuilder(value.length)
            for (byte in value.toByteArray(Charsets.UTF_8))
            {
                val b = byte.toInt() and 0xff
                val c = b.toChar()
                val unreserved = (c in 'A'..'Z') || (c in 'a'..'z') ||
                    (c in '0'..'9') || c == '-' || c == '_' || c == '.' || c == '~'
                val isSlash = c == '/'
                when
                {
                    unreserved -> sb.append(c)
                    isSlash && !encodeSlash -> sb.append(c)
                    else -> sb.append('%').append(String.format("%02X", b))
                }
            }
            return sb.toString()
        }

        /**
         * Build the canonical URI for a request path. Per AWS SigV4, the
         * canonical URI is the URI-encoded path (with each path segment
         * URI-encoded). When [path] is already in URI-encoded form (as
         * returned by `URL.getPath()` or as supplied by the caller), the
         * default behavior is to pass it through unchanged — re-encoding an
         * already-encoded path would double-encode characters and produce a
         * signature mismatch with the AWS reference SDK.
         *
         * Pass `reEncode = true` to re-encode from a raw (unencoded) path;
         * this is the path the AWS SDK would take if the caller supplied a
         * path string instead of a fully-encoded URL.
         *
         * When [path] is null or blank, AWS SigV4 specifies a single forward
         * slash.
         */
        fun canonicalUri(path: String?, reEncode: Boolean = false): String
        {
            if (path.isNullOrEmpty()) return "/"
            val normalized = if (path.length > 1 && path.endsWith('/')) path.dropLast(1) else path
            return if (reEncode) uriEncode(normalized, encodeSlash = false) else normalized
        }

        /**
         * Build the canonical query string. Parameters are sorted by encoded
         * name. Each parameter is `name=value` with both URI-encoded using
         * the SigV4 rules; `=` separates them within a parameter; `&`
         * separates parameters.
         */
        fun canonicalQueryString(rawQuery: String?): String
        {
            if (rawQuery.isNullOrBlank()) return ""
            val parts = rawQuery.split('&').filter { it.isNotBlank() }
            val pairs = parts.map { part ->
                val eq = part.indexOf('=')
                if (eq < 0) {
                    uriEncode(part) to ""
                } else {
                    uriEncode(part.substring(0, eq)) to uriEncode(part.substring(eq + 1))
                }
            }
            return pairs.sortedBy { it.first }
                .joinToString("&") { (k, v) -> if (v.isEmpty()) k else "$k=$v" }
        }

        /**
         * Format an epoch-millis instant as the SigV4 ISO-8601 basic-format
         * timestamp: `yyyyMMdd'T'HHmmss'Z'`.
         */
        fun formatAmzDate(epochMillis: Long): String
        {
            // Convert to UTC components using java.time
            val instant = java.time.Instant.ofEpochMilli(epochMillis)
            val formatter = java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC)
            return formatter.format(instant)
        }

        private fun ByteArray.toHex(): String
        {
            val sb = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xff
                sb.append(HEX_CHARS[v ushr 4])
                sb.append(HEX_CHARS[v and 0x0f])
            }
            return sb.toString()
        }

        private val HEX_CHARS: CharArray = "0123456789abcdef".toCharArray()
    }
}