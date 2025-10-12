import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.InvokeModelRequest
import aws.smithy.kotlin.runtime.http.engine.okhttp.OkHttpEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class DebugClaudeTest {
    
    @Test
    fun debugClaudeCall() {
        TestCredentialUtils.requireAwsCredentials()
        
        runBlocking(Dispatchers.IO) {
            withTimeout(60000) { // 60 second timeout
                try {
                    println("Creating Bedrock client...")
                    val bedrockClient = BedrockRuntimeClient {
                        region = "us-east-1"
                        httpClient(OkHttpEngine) {
                            socketReadTimeout = 30.seconds
                            connectTimeout = 60.seconds
                        }
                    }
                    println("Bedrock client created")
                    
                    println("Building request...")
                    val requestJson = buildJsonObject {
                        put("anthropic_version", "bedrock-2023-05-31")
                        put("max_tokens", 100)
                        put("system", "You are a helpful assistant.")
                        putJsonArray("messages") {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", "What is 2+2?")
                            })
                        }
                        put("temperature", 0.3)
                    }.toString()
                    
                    println("Request JSON: $requestJson")
                    
                    val invokeRequest = InvokeModelRequest {
                        modelId = "anthropic.claude-3-sonnet-20240229-v1:0"
                        body = requestJson.toByteArray()
                        contentType = "application/json"
                    }
                    
                    println("Sending request to Bedrock...")
                    val response = bedrockClient.invokeModel(invokeRequest)
                    println("Response received!")
                    
                    val responseBody = response.body?.let { String(it) } ?: ""
                    println("Response body: $responseBody")
                    
                    // Parse response
                    val json = Json.parseToJsonElement(responseBody).jsonObject
                    val text = json["results"]?.jsonArray?.firstOrNull()?.jsonObject?.get("outputText")?.jsonPrimitive?.content ?: ""
                    
                    println("CLAUDE RESPONSE:")
                    println("Length: ${text.length}")
                    println("Content: '$text'")
                    
                } catch (e: Exception) {
                    println("ERROR: ${e.javaClass.simpleName}: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
}