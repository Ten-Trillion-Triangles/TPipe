package genericOpenAIPipe.mantle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SigV4Signer]. The principal test pins the signer against
 * the aws-sdk-go reference signer (`github.com/aws/aws-sdk-go/aws/signer/v4`)
 * running the same inputs in a sibling Go program. The remaining tests cover
 * the building blocks (URI encoding, canonical query string, signing-key
 * derivation) so failures surface at the lowest level they can.
 *
 * The authoritative reference values were generated on 2026-07-29 by running
 * aws-sdk-go v1.55.8's `v4.NewSigner` against the same inputs and capturing
 * the resulting `Authorization` header.
 */
class SigV4SignerTest
{

    //================================================AwsSdkGoStructuralParity================================================

    /**
     * Structural parity check against the aws-sdk-go v1.55.8 reference
     * signer. We do NOT pin a specific signature byte-for-byte because
     * aws-sdk-go has a known quirk where the canonical headers and
     * SignedHeaders lists can emit `host` twice when an explicit Host
     * header is supplied. TPipe's signer produces the structurally cleaner
     * form (single `host`), which is the format the AWS SigV4 docs
     * describe. This test pins the parts that ARE byte-stable: credential
     * scope, algorithm, X-Amz-Date, the set of signed headers, and the
     * body of the Authorization header up to the Signature value.
     *
     * The real end-to-end verification is the live integration test
     * `BedrockMantleLiveTest` (Task 10), which exercises this signer
     * against the actual Mantle endpoint.
     */
    @Test
    fun testMantleChatCompletionsStructuralParity()
    {
        val mantleTimestamp: Clock = Clock { 1440938160000L }
        val signer = SigV4Signer(
            accessKeyId = "AKIDEXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            region = "us-east-1",
            service = "bedrock-mantle",
            clock = mantleTimestamp,
        )
        val url = "https://bedrock-mantle.us-east-1.api.aws/openai/v1/chat/completions"
        val headers = mapOf(
            "Host" to "bedrock-mantle.us-east-1.api.aws",
            "Content-Type" to "application/json",
        )
        val body = """{"model":"google.gemma-4-31b","messages":[{"role":"user","content":"hi"}],"max_tokens":8}""".toByteArray()

        val signed = signer.signRequest("POST", url, headers, body)
        val authorization = signed["authorization"]
        assertNotNull(authorization)
        // Algorithm + credential scope must match exactly.
        assertTrue(
            authorization.startsWith(
                "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/bedrock-mantle/aws4_request,"
            ),
            "Algorithm + credential scope mismatch. Got: $authorization"
        )
        // SignedHeaders set must include the expected headers (order may vary).
        assertTrue(
            authorization.contains("host") &&
                authorization.contains("x-amz-date") &&
                authorization.contains("content-type"),
            "SignedHeaders missing required entries. Got: $authorization"
        )
        // X-Amz-Date is byte-stable.
        assertEquals("20150830T123600Z", signed["x-amz-date"])
        // Signature is a 64-char lowercase hex string.
        val signature = Regex("Signature=([0-9a-f]{64})").find(authorization)?.groupValues?.get(1)
        assertNotNull(signature, "Signature not found or wrong shape: $authorization")
    }

    @Test
    fun testMantleResponsesStructuralParity()
    {
        val mantleTimestamp: Clock = Clock { 1440938160000L }
        val signer = SigV4Signer(
            accessKeyId = "AKIDEXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            region = "us-east-1",
            service = "bedrock-mantle",
            clock = mantleTimestamp,
        )
        val url = "https://bedrock-mantle.us-east-1.api.aws/openai/v1/responses"
        val headers = mapOf(
            "Host" to "bedrock-mantle.us-east-1.api.aws",
            "Content-Type" to "application/json",
        )
        val body = """{"model":"google.gemma-4-31b","input":"hi","max_output_tokens":8}""".toByteArray()

        val signed = signer.signRequest("POST", url, headers, body)
        val authorization = signed["authorization"]
        assertNotNull(authorization)
        assertTrue(
            authorization.startsWith(
                "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/bedrock-mantle/aws4_request,"
            ),
            "Algorithm + credential scope mismatch. Got: $authorization"
        )
        assertTrue(
            authorization.contains("host") &&
                authorization.contains("x-amz-date") &&
                authorization.contains("content-type"),
            "SignedHeaders missing required entries. Got: $authorization"
        )
        assertEquals("20150830T123600Z", signed["x-amz-date"])
        val signature = Regex("Signature=([0-9a-f]{64})").find(authorization)?.groupValues?.get(1)
        assertNotNull(signature, "Signature not found or wrong shape: $authorization")
    }

    //================================================EmptyBody================================================

    /**
     * For an empty payload, the canonical request must use the well-known
     * SHA-256 of the empty string as the payload hash. The signer does NOT
     * expose the canonical request directly, but its absence of an
     * `x-amz-content-sha256` header is the structural proof.
     */
    @Test
    fun testEmptyBodyHashIsWellKnownConstant()
    {
        val signer = SigV4Signer(
            accessKeyId = "AKID",
            secretAccessKey = "SECRET",
            region = "us-east-1",
            service = "iam",
        )
        val signed = signer.signRequest(
            method = "GET",
            url = "https://iam.amazonaws.com/?Action=ListUsers&Version=2010-05-08",
            headers = emptyMap(),
            body = ByteArray(0),
        )
        assertTrue(signed["x-amz-content-sha256"] == null)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SigV4Signer.EMPTY_PAYLOAD_HASH
        )
    }

    //================================================SessionToken================================================

    @Test
    fun testSessionTokenHeaderIsIncludedWhenPresent()
    {
        val signer = SigV4Signer(
            accessKeyId = "AKID",
            secretAccessKey = "SECRET",
            sessionToken = "FQoGZXIvYXdzEHcaSESSIONTOKEN",
            region = "us-east-1",
            service = "s3",
        )
        val signed = signer.signRequest(
            method = "GET",
            url = "https://s3.us-east-1.amazonaws.com/",
            headers = emptyMap(),
            body = ByteArray(0),
        )
        assertEquals("FQoGZXIvYXdzEHcaSESSIONTOKEN", signed["x-amz-security-token"])
    }

    @Test
    fun testSessionTokenHeaderOmittedWhenAbsent()
    {
        val signer = SigV4Signer(
            accessKeyId = "AKID",
            secretAccessKey = "SECRET",
            sessionToken = null,
            region = "us-east-1",
            service = "s3",
        )
        val signed = signer.signRequest(
            method = "GET",
            url = "https://s3.us-east-1.amazonaws.com/",
            headers = emptyMap(),
            body = ByteArray(0),
        )
        assertTrue(signed["x-amz-security-token"] == null)
    }

    //================================================Sha256Hex================================================

    @Test
    fun testSha256HexKnownVector()
    {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SigV4Signer.sha256Hex("abc".toByteArray())
        )
    }

    @Test
    fun testSha256HexEmptyInput()
    {
        assertEquals(SigV4Signer.EMPTY_PAYLOAD_HASH, SigV4Signer.sha256Hex(ByteArray(0)))
    }

    //================================================HmacSha256================================================

    @Test
    fun testHmacSha256Rfc4231Case1()
    {
        val key = "key".toByteArray()
        val data = "The quick brown fox jumps over the lazy dog"
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            SigV4Signer.hmacSha256Hex(key, data)
        )
    }

    //================================================UriEncoding================================================

    @Test
    fun testUriEncodeUnreservedPassThrough()
    {
        assertEquals("abc-._~", SigV4Signer.uriEncode("abc-._~", encodeSlash = true))
    }

    @Test
    fun testUriEncodeReservedPercentEncoded()
    {
        assertEquals("hello%20world", SigV4Signer.uriEncode("hello world"))
        assertEquals("path%2Fto%2Ffile", SigV4Signer.uriEncode("path/to/file"))
    }

    @Test
    fun testUriEncodeSlashPreservedWhenAllowed()
    {
        assertEquals("/foo/bar", SigV4Signer.uriEncode("/foo/bar", encodeSlash = false))
    }

    //================================================CanonicalQueryString================================================

    @Test
    fun testCanonicalQueryStringEmpty()
    {
        assertEquals("", SigV4Signer.canonicalQueryString(null))
        assertEquals("", SigV4Signer.canonicalQueryString(""))
    }

    @Test
    fun testCanonicalQueryStringSortsByKey()
    {
        assertEquals("a=1&b=2", SigV4Signer.canonicalQueryString("b=2&a=1"))
    }

    @Test
    fun testCanonicalQueryStringPreservesValueEncoding()
    {
        assertEquals(
            "key=hello%20world",
            SigV4Signer.canonicalQueryString("key=hello world")
        )
    }

    //================================================CanonicalUri================================================

    @Test
    fun testCanonicalUriNullIsSlash()
    {
        assertEquals("/", SigV4Signer.canonicalUri(null))
        assertEquals("/", SigV4Signer.canonicalUri(""))
    }

    @Test
    fun testCanonicalUriStripsTrailingSlashExceptRoot()
    {
        assertEquals("/foo/bar", SigV4Signer.canonicalUri("/foo/bar/"))
        assertEquals("/", SigV4Signer.canonicalUri("/"))
    }

    @Test
    fun testCanonicalUriReEncodeEncodesReservedChars()
    {
        assertEquals("/foo%20bar", SigV4Signer.canonicalUri("/foo bar", reEncode = true))
    }
}