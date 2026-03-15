package com.TTT.Debug

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers

@Serializable
data class TracePayload(val pipelineId: String, val htmlContent: String, val name: String, val status: String)

object RemoteTraceDispatcher {

    /**
     * Dispatches a trace summary and detailed HTML report for a pipeline ID 
     * to a remote TraceServer.
     * @param pipelineId The ID of the pipeline being traced.
     * @param name Optional display name for the trace.
     * @param status Final execution status (e.g. SUCCESS, FAILURE).
     */
    fun dispatchTrace(pipelineId: String, name: String = pipelineId, status: String = "SUCCESS")
    {
        val baseUrl = RemoteTraceConfig.remoteServerUrl ?: return

        // Ensure valid remote URL before doing the expensive HTML export
        val urlString = if(baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl

        val htmlContent = try {
            PipeTracer.exportTraceWithoutDispatch(pipelineId, TraceFormat.HTML)
        } catch(e: Exception) {
            e.printStackTrace()
            return
        }

        val payload = TracePayload(pipelineId, htmlContent, name, status)
        val jsonPayload = Json.encodeToString(TracePayload.serializer(), payload)

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$urlString/api/traces")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")

                // Agents use standard auth header configuration
                RemoteTraceConfig.authHeader?.let {
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
            } catch(e: Exception) {
                println("Error dispatching trace $pipelineId: ${e.message}")
            }
        }
    }
}
