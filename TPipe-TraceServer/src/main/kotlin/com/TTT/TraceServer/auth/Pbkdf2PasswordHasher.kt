package com.TTT.TraceServer.auth

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Default [PasswordHasher] for the v2 dashboard. Uses the JDK's
 * `SecretKeyFactory("PBKDF2WithHmacSHA256")` so the implementation has no
 * third-party dependencies and remains GraalVM native-image compatible.
 *
 * @property iterations the KDF iteration count. 600 000 is the OWASP 2025
 *  baseline for PBKDF2-HMAC-SHA256; operators that need to tune for
 *  faster boot or stronger brute-force resistance can lower or raise it
 *  via the constructor.
 * @property keyLengthBits length of the derived key. 256 bits matches a
 *  SHA-256 output and is the recommended v2 default.
 */
class Pbkdf2PasswordHasher(
    private val iterations: Int = 600_000,
    private val keyLengthBits: Int = 256
) : PasswordHasher {

    init
    {
        require(iterations >= 100_000) { "PBKDF2 iteration count must be >= 100000 (got $iterations)" }
        require(keyLengthBits in listOf(128, 192, 256, 384, 512)) {
            "PBKDF2 key length must be one of 128/192/256/384/512 (got $keyLengthBits)"
        }
    }

    override val defaultIterations: Int get() = iterations

    override fun hash(plaintext: String, salt: ByteArray?): HashedPassword
    {
        val effectiveSalt = salt ?: ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(plaintext.toCharArray(), effectiveSalt, iterations, keyLengthBits)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        val derived = try
        {
            factory.generateSecret(spec).encoded
        } finally
        {
            spec.clearPassword()
        }
        return HashedPassword(
            algorithm = ALGORITHM,
            salt = HashedPassword.encodeHash(effectiveSalt),
            iterations = iterations,
            hash = HashedPassword.encodeHash(derived)
        )
    }

    override fun verify(plaintext: String, expected: HashedPassword): Boolean
    {
        // Re-derive with the algorithm/iterations/salt that were stored. This
        // is intentionally lenient: a server that bumps the iteration count
        // can still verify older hashes by passing the expected values to
        // hash() instead of relying on the default-iterations field.
        val saltBytes = HashedPassword.decode(expected.salt)
        val spec = PBEKeySpec(plaintext.toCharArray(), saltBytes, expected.iterations, keyLengthBits)
        val factory = SecretKeyFactory.getInstance(expected.algorithm)
        val derived = try
        {
            factory.generateSecret(spec).encoded
        } catch (e: Exception)
        {
            // Algorithm mismatch or invalid parameters - treat as no match
            // but never leak the underlying exception type to the caller.
            return false
        } finally
        {
            spec.clearPassword()
        }
        val expectedBytes = HashedPassword.decode(expected.hash)
        if(expectedBytes.size != derived.size) return false
        return MessageDigest.isEqual(expectedBytes, derived)
    }

    companion object {
        /** Canonical Java algorithm name for the v2 default. */
        const val ALGORITHM: String = "PBKDF2WithHmacSHA256"
    }
}
