package com.TTT.AgentCore.memory

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Exact opaque payload codec used by AgentCore Memory persistence.
 *
 * The codec operates on serialized text only; it has no knowledge of
 * ContextWindow semantics and therefore cannot accidentally summarize or
 * reinterpret a stored value.
 */
object AgentCoreMemoryCodec {
    /** Maximum encoded payload placed in one service record. */
    const val MAX_PAYLOAD_CHARS: Int = 12_000

    /** Encode serialized TPipe data as gzip/base64.
     *
     * @param serialized Serialized TPipe data.
     * @return Encoded payload.
     */
    fun encode(serialized: String): String
    {
        val bytes = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { it.write(serialized) }
        }.toByteArray()
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** Decode a gzip/base64 payload back to the exact serialized text.
     *
     * @param encoded Encoded payload.
     * @return Original serialized text.
     */
    fun decode(encoded: String): String
    {
        val bytes = Base64.getDecoder().decode(encoded)
        return GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** Split an encoded payload into bounded service-record chunks.
     *
     * @param encoded Encoded payload.
     * @return Bounded payload chunks.
     */
    fun chunks(encoded: String): List<String> = encoded.chunked(MAX_PAYLOAD_CHARS)

    /** Return the SHA-256 checksum of the original serialized TPipe payload.
     *
     * @param serialized Serialized TPipe payload.
     * @return Lowercase hexadecimal SHA-256 checksum.
     */
    fun checksum(serialized: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(serialized.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
