package com.TTT.TraceServer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.nio.file.Path
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class TraceServerHttpsTest {

    private var runningEngine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    @AfterEach
    fun stopServer() {
        runningEngine?.let { stopTraceServer(it) }
        runningEngine = null
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * Generates a self-signed PKCS12 keystore by invoking the JDK's `keytool`.
     * This keeps the test portable across JDK versions without depending on
     * internal `sun.security.x509` APIs.
     */
    private fun buildKeyStore(tempDir: Path, alias: String, password: String): File {
        val keyStoreFile = tempDir.resolve("keystore.p12").toFile()
        val dname = "CN=localhost,O=TPipe-Test"
        val keytoolArgs = listOf(
            "keytool",
            "-genkeypair",
            "-alias", alias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "365",
            "-keystore", keyStoreFile.absolutePath,
            "-storetype", "PKCS12",
            "-storepass", password,
            "-keypass", password,
            "-dname", dname,
            "-ext", "SAN=DNS:localhost,IP:127.0.0.1"
        )
        val process = ProcessBuilder(keytoolArgs)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "keytool failed with exit code $exitCode: $output" }
        return keyStoreFile
    }

    /**
     * Boots a TraceServer with TLS enabled on a free port, using the supplied
     * keystore. Returns the HTTPS port.
     */
    private fun bootTls(keystore: File, password: String, alias: String): Int {
        val config = TraceServerConfigBridge.legacy().copy(
            port = freePort(),
            tlsPort = freePort(),
            tls = TlsConfig(
                enabled = true,
                keyStorePath = keystore.absolutePath,
                keyStorePassword = password,
                keyAlias = alias,
                keyPassword = password,
                protocols = listOf("TLSv1.3", "TLSv1.2")
            )
        )
        val engine = startTraceServer(config, wait = false)
        runningEngine = engine
        return config.tlsPort
    }

    /**
     * A trust-all X509TrustManager used by the test client to bypass the
     * self-signed cert validation. NEVER use this in production.
     */
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    @Test
    fun httpsHealthEndpointResponds(@TempDir tempDir: Path) {
        val alias = "sampleAlias"
        val password = "test-password"
        val keystore = buildKeyStore(tempDir, alias, password)
        val tlsPort = bootTls(keystore, password, alias)

        val client = HttpClient(CIO) {
            engine {
                https {
                    trustManager = trustAll
                }
            }
        }
        try
        {
            runBlocking {
                val response = client.get("https://localhost:$tlsPort/api/health")
                assert(response.status == HttpStatusCode.OK) { "https://localhost:$tlsPort/api/health should return 200, got ${response.status}" }
                val body = response.bodyAsText()
                assert(body.contains("\"status\"")) { "expected status key in body: $body" }
                assert(body.contains("\"ok\"")) { "expected ok value in body: $body" }
            }
        } finally
        {
            client.close()
        }
    }
}
