package com.TTT.TraceServer.auth

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64

/**
 * Stable, portable representation of a hashed password. The wire format is
 * `@Serializable` so admins can POST a pre-computed hash to
 * `POST /api/auth/login` from out-of-band tools.
 *
 * @property algorithm the KDF algorithm identifier (e.g. `PBKDF2WithHmacSHA256`).
 * @property salt base64-url-encoded salt.
 * @property iterations iteration count for the KDF. Defaults to 600 000 to
 *  align with OWASP guidance for PBKDF2-HMAC-SHA256 as of 2025.
 * @property hash base64-url-encoded derived key bytes.
 */
@Serializable
data class HashedPassword(
    val algorithm: String,
    val salt: String,
    val iterations: Int,
    val hash: String
)
{
    companion object {
        private val RNG = SecureRandom()

        /**
         * Generates a fresh random salt of [bytes] bytes and returns it
         * base64-url-encoded. Used by [Pbkdf2PasswordHasher] and exposed for
         * tests and admin tools.
         */
        fun randomSalt(bytes: Int = 16): String
        {
            val buf = ByteArray(bytes)
            RNG.nextBytes(buf)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
        }

        /**
         * Encodes a derived-key byte array to the canonical base64-url form
         * used by [hash] above.
         */
        fun encodeHash(raw: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

        /**
         * Decodes a [HashedPassword.salt] or [HashedPassword.hash] value back
         * to the raw bytes. Returns an empty array when the input is blank
         * so callers don't have to special-case the "no salt" path. Falls
         * back to standard base64 if the url decoder rejects the input
         * (legacy hashes from before the v2 wire format).
         */
        fun decode(encoded: String): ByteArray
        {
            if(encoded.isBlank()) return ByteArray(0)
            return try
            {
                Base64.getUrlDecoder().decode(encoded)
            } catch (e: IllegalArgumentException)
            {
                Base64.getDecoder().decode(encoded)
            }
        }
    }
}
