#!/usr/bin/env kotlin

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
@file:DependsOn("aws.sdk.kotlin:bedrockruntime:1.0.30")

import kotlinx.coroutines.runBlocking
import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.InvokeModelRequest
import kotlinx.serialization.json.*
import java.io.File

suspend fun testDeepSeekWithInferenceProfile() {
    println("Testing DeepSeek R1 with inference profile binding...")
    
    // Load inference config
    val inferenceFile = File(System.getProperty("user.home"), ".aws/inference.txt")
    val inferenceMap = mutableMapOf<String, String>()
    
    if (inferenceFile.exists()) {
        inferenceFile.readLines().forEach { line ->
            if (line.contains("=")) {
                val (model, profile) = line.split("=", limit = 2)
                inferenceMap[model] = profile
            }
        }
    }
    
    val modelId = "deepseek.r1-v1:0"
    val inferenceProfileId = inferenceMap[modelId]
    
    println("Model ID: $modelId")
    println("Inference Profile ID: $inferenceProfileId")
    
    if (inferenceProfileId.isNullOrEmpty()) {
        println("No inference profile bound for DeepSeek - using direct model call")
        return
    }
    
    // Create Bedrock client
    val client = BedrockRuntimeClient {
        region = "us-east-2"
    }
    
    // Build DeepSeek request
    val prompt = "What is 15% of 240? Show your reasoning."
    val requestJson = buildJsonObject {
        put("max_tokens", 150)
        put("temperature", 0.7)
        put("top_p", 0.9)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", "You are an expert reasoning assistant.")
            }
            addJsonObject {
                put("role", "user")
                put("content", prompt)
            }
        }
    }.toString()
    
    println("Request JSON: $requestJson")
    
    try {
        // Make the API call using inference profile
        val invokeRequest = InvokeModelRequest {
            this.modelId = inferenceProfileId
            body = requestJson.toByteArray()
            contentType = "application/json"
        }
        
        println("Calling Bedrock with inference profile: $inferenceProfileId")
        val response = client.invokeModel(invokeRequest)
        val responseBody = response.body?.let { String(it) } ?: ""
        
        println("Response received:")
        println(responseBody)
        
        // Extract text from response
        try {
            val json = Json.parseToJsonElement(responseBody).jsonObject
            val choices = json["choices"]?.jsonArray
            if (choices != null && choices.isNotEmpty()) {
                val message = choices[0].jsonObject["message"]?.jsonObject
                val content = message?.get("content")?.jsonPrimitive?.content
                if (!content.isNullOrEmpty()) {
                    println("\nExtracted response:")
                    println(content)
                } else {
                    println("No content found in response")
                }
            } else {
                println("No choices found in response")
            }
        } catch (e: Exception) {
            println("Error parsing response: ${e.message}")
        }
        
    } catch (e: Exception) {
        println("Error calling Bedrock: ${e.javaClass.simpleName}: ${e.message}")
        e.printStackTrace()
    }
    
    client.close()
}

runBlocking {
    val accessKey = System.getenv("AWS_ACCESS_KEY_ID")
    val secretKey = System.getenv("AWS_SECRET_ACCESS_KEY")
    val bearerToken = System.getenv("AWS_BEARER_TOKEN_BEDROCK")
    
    if ((accessKey.isNullOrEmpty() || secretKey.isNullOrEmpty()) && bearerToken.isNullOrEmpty()) {
        println("Skipping test - AWS credentials not found (AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY or AWS_BEARER_TOKEN_BEDROCK)")
        return@runBlocking
    }
    
    testDeepSeekWithInferenceProfile()
}