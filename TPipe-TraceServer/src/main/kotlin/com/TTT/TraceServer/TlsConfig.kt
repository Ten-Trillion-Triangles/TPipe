package com.TTT.TraceServer

/**
 * TLS configuration for the TraceServer HTTPS listener.
 *
 * The configuration is intentionally restricted to the JDK KeyStore + TrustManager
 * surface so that it remains compatible with GraalVM native-image (no BouncyCastle,
 * no certificate generation library). Pair this with the `--enable-https` build
 * argument already used in the worktree's `graalvmNative` block when the artifact
 * is built for native-image.
 *
 * @property enabled when `true` an additional HTTPS connector is bound on [TraceServerConfig.tlsPort].
 * @property keyStorePath absolute path to a JKS or PKCS12 keystore. Required when [enabled] is `true`.
 * @property keyStorePassword password used to unlock the keystore (may be `null` for unprotected keystores).
 * @property keyAlias alias of the private key entry used for the server certificate.
 * @property keyPassword optional password for the private key entry (defaults to [keyStorePassword] when `null`).
 * @property trustStorePath optional truststore for mutual TLS verification. When `null` the JVM default
 *  truststore is used as the source of trusted client certificates.
 * @property trustStorePassword password used to unlock [trustStorePath] (when supplied).
 * @property mutualTls when `true` the server requests and validates a client certificate.
 * @property protocols ordered list of TLS protocols the server will negotiate. Defaults to TLS 1.3 and 1.2.
 */
data class TlsConfig(
    val enabled: Boolean = false,
    val keyStorePath: String? = null,
    val keyStorePassword: String? = null,
    val keyAlias: String? = null,
    val keyPassword: String? = null,
    val trustStorePath: String? = null,
    val trustStorePassword: String? = null,
    val mutualTls: Boolean = false,
    val protocols: List<String> = listOf("TLSv1.3", "TLSv1.2")
)
{
    /**
     * Returns `true` when the configuration is internally consistent. A disabled
     * configuration is always considered valid. A misconfigured enabled
     * configuration will be reported by the server at boot time.
     */
    fun isValid(): Boolean
    {
        if(!enabled) return true
        if(keyStorePath.isNullOrBlank()) return false
        if(keyAlias.isNullOrBlank()) return false
        return true
    }
}
