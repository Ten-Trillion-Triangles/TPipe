package genericOpenAIPipe.mantle

import genericOpenAIPipe.env.BedrockMantleEnv

/**
 * Auth-header provider for Amazon Bedrock Mantle requests.
 *
 * Wraps either a [Bearer] token (for long-lived or short-lived Bedrock API
 * keys) or a SigV4 signer (for IAM-tied authentication) and exposes the
 * canonical [authHeaders] entry point used by [genericOpenAIPipe.GenericOpenAIPipe].
 *
 * The signer for [SigV4] is created once and reused across requests; each
 * call to [authHeaders] recomputes the signature for the current request
 * shape.
 */
sealed class BedrockMantleAuth
{
    /**
     * Compute the HTTP headers required to authenticate the given request.
     *
     * The returned map is added on top of any caller-supplied non-auth
     * headers. The [Authorization] header key is always lowercase
     * `authorization`, matching the casing conventions of the rest of the
     * Generic OpenAI module.
     *
     * @param method HTTP method in uppercase (for example `POST`).
     * @param url Full request URL (scheme + host + path + query).
     * @param body Request payload as bytes. May be empty.
     * @param headers Caller-supplied headers. Authentication-specific headers
     *               (Host, X-Amz-Date, X-Amz-Security-Token, etc.) are
     *               merged in by the underlying implementation as required.
     * @return Map of auth header name to header value, always including an
     *         `authorization` entry.
     */
    abstract fun authHeaders(
        method: String,
        url: String,
        body: ByteArray,
        headers: Map<String, String>,
    ): Map<String, String>

    /**
     * Bearer-token authentication using a Bedrock API key. Sends
     * `Authorization: Bearer <apiKey>` on every request.
     */
    data class Bearer(val apiKey: String) : BedrockMantleAuth()
    {
        init
        {
            require(apiKey.isNotBlank()) { "Bearer apiKey cannot be blank" }
        }

        override fun authHeaders(
            method: String,
            url: String,
            body: ByteArray,
            headers: Map<String, String>,
        ): Map<String, String> = mapOf("authorization" to "Bearer $apiKey")
    }

    /**
     * AWS SigV4 authentication using an [SigV4Signer]. Recomputes the
     * signature per request based on the caller's method, URL, and body.
     */
    data class SigV4(val signer: SigV4Signer) : BedrockMantleAuth()
    {
        override fun authHeaders(
            method: String,
            url: String,
            body: ByteArray,
            headers: Map<String, String>,
        ): Map<String, String> = signer.signRequest(method, url, headers, body)
    }

    /**
     * AWS SigV4 chunked-encoding authentication for streaming requests.
     *
     * Carries both the [SigV4Signer] (used to compute the seed signature
     * for the initial request) and a [ChunkedSigV4Signer] (used to compute
     * the per-chunk signatures as bytes flow to the wire). The transport
     * layer calls [signChunk] per body chunk.
     *
     * On the initial request, the headers carry:
     *   - `Transfer-Encoding: chunked`
     *   - `x-amz-content-sha256: STREAMING-AWS4-HMAC-SHA256-PAYLOAD`
     *   - `Content-Encoding: aws-chunked`
     *   - `Authorization: …` with the seed signature (SigV4 over the
     *     canonical request whose payload hash is the streaming constant)
     *   - `x-amz-decoded-content-length: <body bytes>`
     *
     * The body itself is then written as a sequence of chunked blocks
     * (see [ChunkedSigV4Signer] for the per-chunk wire format).
     */
    data class Streaming(
        val initialSigner: SigV4Signer,
        val chunkedSigner: ChunkedSigV4Signer,
        val decodedContentLength: Long,
    ) : BedrockMantleAuth()
    {
        init
        {
            require(decodedContentLength >= 0) {
                "decodedContentLength must be non-negative (got $decodedContentLength)"
            }
        }

        override fun authHeaders(
            method: String,
            url: String,
            body: ByteArray,
            headers: Map<String, String>,
        ): Map<String, String>
        {
            // The seed signature is computed against the canonical
            // request whose payload hash is the streaming-payload marker,
            // not the hash of the body. The body itself is signed per
            // chunk via [signChunk].
            val seedHeaders = initialSigner.signStreamingRequest(method, url, headers)
            val result = LinkedHashMap<String, String>(seedHeaders.size + 4)
            result.putAll(seedHeaders)
            result["transfer-encoding"] = "chunked"
            result["x-amz-content-sha256"] = ChunkedSigV4Signer.STREAMING_CONTENT_SHA256
            result["content-encoding"] = "aws-chunked"
            result["x-amz-decoded-content-length"] = decodedContentLength.toString()
            return result
        }

        /**
         * Sign a single chunk of the chunked-encoding body. The transport
         * layer calls this per chunk as it streams to the wire.
         *
         * @param previousSignatureHex The seed signature (chunk 0) or the
         *           previous chunk's signature hex (chunk N>0).
         * @param chunkBytes The chunk body bytes. May be empty for the
         *           final terminator chunk.
         * @return The chunk's signature plus the chain hash for the next
         *         chunk's "previous" input.
         */
        fun signChunk(
            previousSignatureHex: String,
            chunkBytes: ByteArray,
        ): ChunkedSigV4Signer.ChunkSignatureResult =
            chunkedSigner.signChunk(previousSignatureHex, chunkBytes)
    }

    companion object
    {
        /**
         * Build a [Bearer] auth from a Bedrock API key.
         *
         * @param apiKey Long-term or short-term API key (NOT an IAM access key id).
         */
        fun bearer(apiKey: String): Bearer = Bearer(apiKey)

        /**
         * Build a [SigV4] auth from explicit AWS credentials. Use this when
         * the caller already holds resolved credentials (for example in a test
         * fixture) and wants to skip env-var resolution.
         *
         * @param accessKeyId AWS access key id.
         * @param secretAccessKey AWS secret access key.
         * @param sessionToken Optional session token for temporary credentials.
         * @param region AWS region code.
         * @param service AWS service identifier (defaults to `bedrock-mantle`).
         * @param clock Optional clock for deterministic timestamps in tests.
         */
        fun sigV4(
            accessKeyId: String,
            secretAccessKey: String,
            sessionToken: String? = null,
            region: String,
            service: String = "bedrock-mantle",
            clock: Clock = SystemClock,
        ): SigV4 = SigV4(
            SigV4Signer(
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                sessionToken = sessionToken,
                region = region,
                service = service,
                clock = clock,
            )
        )

        /**
         * Build a [SigV4] auth by resolving credentials from [BedrockMantleEnv].
         *
         * Returns `null` when the env cannot resolve both an access key id
         * and a secret access key, so the caller can fall back to bearer
         * mode without an exception.
         *
         * @param regionOverride Optional region override; when `null`, the
         *                       region is resolved from [BedrockMantleEnv].
         */
        fun sigV4FromEnv(regionOverride: String? = null): SigV4?
        {
            val accessKeyId = BedrockMantleEnv.resolveAccessKeyId()
            val secretAccessKey = BedrockMantleEnv.resolveSecretAccessKey()
            if (accessKeyId.isBlank() || secretAccessKey.isBlank()) return null
            val sessionToken = BedrockMantleEnv.resolveSessionToken()
            val region = regionOverride ?: BedrockMantleEnv.resolveRegion()
            return sigV4(
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                sessionToken = sessionToken,
                region = region,
            )
        }

        /**
         * Build a [Streaming] auth from explicit AWS credentials. Both the
         * seed [SigV4Signer] (for the initial request) and the per-chunk
         * [ChunkedSigV4Signer] share credentials and region.
         *
         * @param accessKeyId AWS access key id.
         * @param secretAccessKey AWS secret access key.
         * @param sessionToken Optional session token for temporary credentials.
         * @param region AWS region code.
         * @param service AWS service identifier (defaults to `bedrock-mantle`).
         * @param decodedContentLength The size of the body in bytes (the
         *           `x-amz-decoded-content-length` header value). Use 0 for
         *           streaming-requests where the length is unknown up
         *           front.
         * @param clock Clock abstraction returning epoch millis (for tests).
         */
        fun streaming(
            accessKeyId: String,
            secretAccessKey: String,
            sessionToken: String? = null,
            region: String,
            service: String = "bedrock-mantle",
            decodedContentLength: Long = 0L,
            clock: Clock = SystemClock,
        ): Streaming
        {
            val initialSigner = SigV4Signer(
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                sessionToken = sessionToken,
                region = region,
                service = service,
                clock = clock,
            )
            val chunkedSigner = ChunkedSigV4Signer(
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                sessionToken = sessionToken,
                region = region,
                service = service,
                clock = clock,
            )
            return Streaming(
                initialSigner = initialSigner,
                chunkedSigner = chunkedSigner,
                decodedContentLength = decodedContentLength,
            )
        }

        /**
         * Build a [Streaming] auth by resolving credentials from
         * [BedrockMantleEnv]. Returns `null` when credentials are
         * unresolvable.
         */
        fun streamingFromEnv(
            regionOverride: String? = null,
            decodedContentLength: Long = 0L,
        ): Streaming?
        {
            val accessKeyId = BedrockMantleEnv.resolveAccessKeyId()
            val secretAccessKey = BedrockMantleEnv.resolveSecretAccessKey()
            if (accessKeyId.isBlank() || secretAccessKey.isBlank()) return null
            val sessionToken = BedrockMantleEnv.resolveSessionToken()
            val region = regionOverride ?: BedrockMantleEnv.resolveRegion()
            return streaming(
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                sessionToken = sessionToken,
                region = region,
                decodedContentLength = decodedContentLength,
            )
        }
    }
}