package com.TTT.AgentCore.gateway

import com.TTT.MCP.Client.McpRemoteAuthProvider
import com.TTT.MCP.Client.McpRemoteClient
import com.TTT.MCP.Client.McpRemoteRequestSigner
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authentication seam for Gateway MCP requests. */
fun interface AgentCoreGatewayAuth : McpRemoteAuthProvider

/** Credentials used by the request-aware Gateway SigV4 signer. */
data class AgentCoreGatewayCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String? = null
)

/** Supplies fresh AWS credentials for each Gateway request signature. */
fun interface AgentCoreGatewayCredentialsProvider {
    /** Resolve credentials for one outgoing request. */
    suspend fun credentials(): AgentCoreGatewayCredentials
}

/**
 * Request-aware AWS SigV4 authentication for an IAM-authorized Gateway.
 *
 * The signer receives the final MCP URL, headers, and serialized request body
 * so the authorization covers the exact bytes sent by [McpRemoteClient].
 */
class AgentCoreGatewaySigV4Auth(
    private val region: String,
    private val credentialsProvider: AgentCoreGatewayCredentialsProvider,
    private val service: String = "bedrock-agentcore",
    private val clock: Clock = Clock.systemUTC()
) : McpRemoteRequestSigner {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withLocale(Locale.ROOT)
        .withZone(ZoneOffset.UTC)
    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd")
        .withLocale(Locale.ROOT)
        .withZone(ZoneOffset.UTC)

    override suspend fun sign(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray
    ): Map<String, String> {
        val credentials = credentialsProvider.credentials()
        val instant = Instant.now(clock)
        val amzDate = timestampFormat.format(instant)
        val date = dateFormat.format(instant)
        val payloadHash = sha256(body)
        val uri = URI(url)
        val canonicalHeaders = headers
            .filterKeys { key ->
                !key.equals("authorization", ignoreCase = true) &&
                    !key.equals("x-amz-date", ignoreCase = true) &&
                    !key.equals("x-amz-content-sha256", ignoreCase = true) &&
                    !key.equals("x-amz-security-token", ignoreCase = true) &&
                    !key.equals("host", ignoreCase = true)
            }
            .mapKeys { (key, _) -> key.lowercase(Locale.ROOT) }
            .mapValues { (_, value) -> value.trim().replace(Regex("\\s+"), " ") }
            .toMutableMap()
        canonicalHeaders["host"] = uri.authority
        canonicalHeaders["x-amz-content-sha256"] = payloadHash
        canonicalHeaders["x-amz-date"] = amzDate
        credentials.sessionToken?.takeIf { it.isNotBlank() }?.let {
            canonicalHeaders["x-amz-security-token"] = it
        }
        val sortedHeaders = canonicalHeaders.toSortedMap()
        val signedHeaders = sortedHeaders.keys.joinToString(";")
        val canonicalHeaderText = sortedHeaders.entries.joinToString(separator = "") { (key, value) ->
            "$key:$value\n"
        }
        val canonicalRequest = listOf(
            method.uppercase(Locale.ROOT),
            canonicalPath(uri),
            canonicalQuery(uri),
            canonicalHeaderText,
            signedHeaders,
            payloadHash
        ).joinToString("\n")
        val scope = "$date/$region/$service/aws4_request"
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            scope,
            sha256(canonicalRequest.toByteArray())
        ).joinToString("\n")
        val signingKey = hmac(
            hmac(
                hmac(
                    hmac("AWS4${credentials.secretAccessKey}".toByteArray(), date),
                    region
                ),
                service
            ),
            "aws4_request"
        )
        val signature = hmac(signingKey, stringToSign).toHex()
        val authorization = "AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId}/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"
        return buildMap {
            put("Host", uri.authority)
            put("x-amz-date", amzDate)
            put("x-amz-content-sha256", payloadHash)
            put("Authorization", authorization)
            credentials.sessionToken?.takeIf { it.isNotBlank() }?.let {
                put("x-amz-security-token", it)
            }
        }
    }

    private fun canonicalPath(uri: URI): String {
        val rawPath = uri.rawPath.ifEmpty { "/" }
        return buildString {
            var index = 0
            while(index < rawPath.length)
            {
                val character = rawPath[index]
                if(character == '%' && index + 2 < rawPath.length)
                {
                    val high = rawPath[index + 1].digitToIntOrNull(16)
                    val low = rawPath[index + 2].digitToIntOrNull(16)
                    if(high != null && low != null)
                    {
                        append('%')
                        append("0123456789ABCDEF"[high])
                        append("0123456789ABCDEF"[low])
                        index += 3
                        continue
                    }
                }

                append(awsEncode(character.toString(), encodeSlash = false))
                index++
            }
        }
    }

    private fun canonicalQuery(uri: URI): String = uri.rawQuery.orEmpty()
        .split('&')
        .filter { it.isNotEmpty() }
        .map { part ->
            val separator = part.indexOf('=')
            val name = if (separator >= 0) part.substring(0, separator) else part
            val value = if (separator >= 0) part.substring(separator + 1) else ""
            awsEncode(percentDecode(name), encodeSlash = true) to
                awsEncode(percentDecode(value), encodeSlash = true)
        }
        .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        .joinToString("&") { (name, value) -> "$name=$value" }

    /** Decode URI percent escapes without treating a literal plus as a space. */
    private fun percentDecode(value: String): String {
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while(index < value.length)
        {
            if(value[index] == '%' && index + 2 < value.length)
            {
                val high = value[index + 1].digitToIntOrNull(16)
                val low = value[index + 2].digitToIntOrNull(16)
                if(high != null && low != null)
                {
                    bytes.write((high shl 4) or low)
                    index += 3
                    continue
                }
            }

            val character = value[index].toString().toByteArray(Charsets.UTF_8)
            bytes.write(character)
            index++
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun awsEncode(value: String, encodeSlash: Boolean): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val character = byte.toInt().and(0xff).toChar()
            if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
                character in "-_.~" || (!encodeSlash && character == '/')
            ) {
                append(character)
            }
            else {
                append('%')
                append("0123456789ABCDEF"[byte.toInt().ushr(4).and(0x0f)])
                append("0123456789ABCDEF"[byte.toInt().and(0x0f)])
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, algorithm))
        doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it) }
}
