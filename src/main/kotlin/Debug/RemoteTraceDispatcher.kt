package com.TTT.Debug

import com.TTT.Config.AuthRegistry
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers

/**
 * Wire payload sent from the TPipe client to the remote TraceServer.
 *
 * v2 of the wire format adds an optional [kind] discriminator so the dashboard
 * can recognize container-class traces (e.g. `"pumpstation"`) distinctly.
 * The field defaults to `null`, which keeps the payload wire-compatible with
 * v1 callers that do not yet serialize `kind`.
 */
@Serializable
data class TracePayload(
    val pipelineId: String,
    val htmlContent: String,
    val name: String,
    val status: String,
    val kind: String? = null,  // v2 wire; v1 callers serialize without it
)

object RemoteTraceDispatcher {

    /**
     * Dispatches a trace summary and detailed HTML report for a pipeline ID
     * to a remote TraceServer.
     * @param pipelineId The ID of the pipeline being traced.
     * @param name Optional display name for the trace.
     * @param status Final execution status (e.g. SUCCESS, FAILURE).
     * @param kind Optional container-class discriminator (e.g. `"pumpstation"`)
     *   forwarded to TraceServer so the dashboard can distinguish trace sources.
     *   `null` (v1 default) is preserved so legacy callers remain wire-compatible.
     */
    fun dispatchTrace(
        pipelineId: String,
        name: String = pipelineId,
        status: String = "SUCCESS",
        kind: String? = null,
    )
    {
        val baseUrl = RemoteTraceConfig.remoteServerUrl ?: return

        // Ensure valid remote URL before doing the expensive HTML export
        val urlString = if(baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl

        val htmlContent = try {
            PipeTracer.exportTraceWithoutDispatch(pipelineId, TraceFormat.HTML)
        }catch(e: Exception)
        {
            e.printStackTrace()
            return
        }

        val payload = TracePayload(pipelineId, htmlContent, name, status, kind)
        val jsonPayload = Json.encodeToString(TracePayload.serializer(), payload)

        // Resolve auth token automatically if not manually set
        val resolvedAuthHeader = RemoteTraceConfig.authHeader 
            ?: AuthRegistry.getToken(baseUrl).takeIf { it.isNotEmpty() }?.let { "Bearer $it" }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$urlString/api/traces")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")

                // Agents use standard auth header configuration
                resolvedAuthHeader?.let {
                    connection.setRequestProperty("Authorization", it)
                }

                connection.doOutput = true
                connection.outputStream.use { os ->
                    val input = jsonPayload.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                val responseCode = connection.responseCode
                if(responseCode != 200)
                {
                    println("Failed to dispatch trace $pipelineId to remote server. Status code: $responseCode")
                }
            }catch(e: Exception)
            {
                println("Error dispatching trace $pipelineId: ${e.message}")
            }
        }
    }
}
