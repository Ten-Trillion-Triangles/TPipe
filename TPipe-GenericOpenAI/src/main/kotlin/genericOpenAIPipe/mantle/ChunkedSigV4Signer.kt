package genericOpenAIPipe.mantle

import genericOpenAIPipe.mantle.SigV4Signer.Companion.hmacSha256
import genericOpenAIPipe.mantle.SigV4Signer.Companion.sha256Hex

/**
 * Per-chunk AWS Signature Version 4 signer for the chunked-encoding
 * payload format used by Amazon Bedrock Mantle streaming endpoints.
 *
 * Mantle accepts `Transfer-Encoding: chunked` +
 * `x-amz-content-sha256: STREAMING-AWS4-HMAC-SHA256-PAYLOAD` +
 * `Content-Encoding: aws-chunked` for SigV4-authenticated streaming. The
 * body is split into fixed-size chunks (default 65,536 bytes per the AWS
 * SDK convention); the final chunk may be smaller; an additional 0-byte
 * terminator chunk closes the stream.
 *
 * Algorithm (per AWS S3 SigV4 streaming spec, which Mantle inherits —
 * see `docs.aws.amazon.com/AmazonS3/latest/developerguide/sigv4-streaming.html`):
 *
 *  1. The initial request is signed like a normal SigV4 request, with
 *     `x-amz-content-sha256: STREAMING-AWS4-HMAC-SHA256-PAYLOAD` as the
 *     payload-hash header and the standard canonical-request string-to-sign.
 *     The resulting signature is the **seed signature**.
 *  2. For each body chunk the client writes a block:
 *
 *     ```
 *     <size_hex>;<chunk-signature>\r\n
 *     <body_bytes>\r\n
 *     ```
 *
 *     where `<size_hex>` is the byte-length of the body chunk as
 *     5-character lowercase hex (e.g. `10000` for 65536), and
 *     `<chunk-signature>` is computed against the chunk string-to-sign:
 *
 *     ```
 *     AWS4-HMAC-SHA256-PAYLOAD
 *     <amz-date>
 *     <credential-scope>
 *     <previous-signature-hex>
 *     e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
 *     <sha256-of-this-chunk-data>
 *     ```
 *
 *     The "previous signature" for the first chunk is the seed signature;
 *     for subsequent chunks, it is the previous chunk's signature. The
 *     chain ensures chunks are sent in order.
 *  3. A final 0-byte terminator chunk closes the stream.
 *
 * @property accessKeyId AWS access key id.
 * @property secretAccessKey AWS secret access key.
 * @property sessionToken Optional session token for temporary credentials.
 * @property region AWS region code (for example `us-east-1`).
 * @property service AWS service identifier. Defaults to `bedrock-mantle`.
 * @property clock Clock abstraction returning epoch millis.
 */
class ChunkedSigV4Signer(
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
     * Result of signing a single chunk. The caller writes the chunk-block
     * `<size_hex>;<signature>\r\n<bytes>\r\n` to the output stream, then
     * passes [nextSeedSha256Hex] (or [seedSignature] for the first chunk)
     * to the next call.
     *
     * @property signatureHex The chunk's lowercase-hex signature.
     * @property amzContentSha256Header The value to use for
     *           `x-amz-content-sha256` on the chunk block. By convention
     *           this is `seedSignature;nextSeedSha256Hex` for streaming
     *           SigV4, but Mantle clients typically omit the per-chunk
     *           header and rely on the per-chunk signature line alone.
     * @property nextSeedSha256Hex The SHA256 of [signatureHex], which
     *           chains into the next chunk's string-to-sign.
     */
    data class ChunkSignatureResult(
        val signatureHex: String,
        val amzContentSha256Header: String,
        val nextSeedSha256Hex: String,
    )

    /**
     * Sign a single chunk of the chunked-encoding body. The caller passes
     * the **seed signature** (from the initial request) for the first
     * chunk, and the **previous chunk's signature** for subsequent chunks.
     *
     * @param previousSignatureHex The seed signature (for chunk 0) or
     *           the previous chunk's signature hex (for chunk N>0).
     * @param chunkBytes The chunk body bytes. May be empty for the final
     *           terminator chunk.
     * @return The chunk's signature plus the SHA256 to use as the next
     *         chunk's "previous signature" input.
     */
    fun signChunk(
        previousSignatureHex: String,
        chunkBytes: ByteArray,
    ): ChunkSignatureResult
    {
        require(previousSignatureHex.isNotBlank()) {
            "previousSignatureHex cannot be blank — pass the seed signature for chunk 0"
        }

        val now = clock.nowMillis()
        val amzDate = SigV4Signer.formatAmzDate(now)
        val dateStamp = amzDate.substring(0, 8)
        val credentialScope = "$dateStamp/$region/$service/aws4_request"

        // The previous signature is part of the chain but the canonical
        // string-to-sign for a chunk is NOT a full SigV4 canonical
        // request — it's the six-line "AWS4-HMAC-SHA256-PAYLOAD" form
        // documented in the S3 streaming spec.
        val chunkSha256 = sha256Hex(chunkBytes)

        val stringToSign = buildString {
            append("AWS4-HMAC-SHA256-PAYLOAD").append('\n')
            append(amzDate).append('\n')
            append(credentialScope).append('\n')
            append(previousSignatureHex).append('\n')
            append(EMPTY_PAYLOAD_HASH).append('\n')
            append(chunkSha256)
        }

        val signingKey = SigV4Signer(
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            sessionToken = sessionToken,
            region = region,
            service = service,
            clock = clock,
        ).deriveSigningKey(dateStamp)

        val signature = SigV4Signer.hmacSha256Hex(signingKey, stringToSign)
        val nextSeed = sha256Hex(signature.toByteArray(Charsets.US_ASCII))
        // Mantle's wire format uses the signature alone as the per-chunk
        // x-amz-content-sha256 value; we expose the signature here for
        // the canonical Mantle encoding. Callers that need the
        // ";chain" form (e.g. S3 streaming) can format it themselves.
        val amzContentSha256Header = signature
        return ChunkSignatureResult(
            signatureHex = signature,
            amzContentSha256Header = amzContentSha256Header,
            nextSeedSha256Hex = nextSeed,
        )
    }

    companion object
    {
        /**
         * The value carried by `x-amz-content-sha256` on the initial request
         * to indicate that the body is signed via the chunked-streaming
         * algorithm.
         */
        const val STREAMING_CONTENT_SHA256: String = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD"

        /**
         * Recommended chunk size per the AWS SDK convention. Matches the
         * AWS S3 streaming example (64 KiB per non-terminal chunk).
         */
        const val CHUNK_SIZE_BYTES: Int = 65_536

        /**
         * SHA256 of the empty string, lowercase hex. Carried in the chunk
         * string-to-sign regardless of chunk size.
         */
        const val EMPTY_PAYLOAD_HASH: String =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}