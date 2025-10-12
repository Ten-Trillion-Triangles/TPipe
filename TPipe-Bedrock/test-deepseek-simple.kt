#!/usr/bin/env kotlin

// Simple test to verify DeepSeek works with inference profile
import java.io.File

fun main() {
    val accessKey = System.getenv("AWS_ACCESS_KEY_ID")
    val secretKey = System.getenv("AWS_SECRET_ACCESS_KEY")
    val bearerToken = System.getenv("AWS_BEARER_TOKEN_BEDROCK")
    
    if ((accessKey.isNullOrEmpty() || secretKey.isNullOrEmpty()) && bearerToken.isNullOrEmpty()) {
        println("Skipping test - AWS credentials not found (AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY or AWS_BEARER_TOKEN_BEDROCK)")
        return
    }
    
    println("Testing DeepSeek R1 inference profile binding...")
    
    // Check if inference config exists and has DeepSeek binding
    val inferenceFile = File(System.getProperty("user.home"), ".aws/inference.txt")
    if (!inferenceFile.exists()) {
        println("ERROR: No inference config file found at ~/.aws/inference.txt")
        return
    }
    
    val lines = inferenceFile.readLines()
    // amazonq-ignore-next-line
    val deepseekLine = lines.find { it.startsWith("deepseek.r1-v1:0=") }
    
    if (deepseekLine == null) {
        println("ERROR: DeepSeek R1 model not found in inference config")
        return
    }
    
    val (modelId, profileId) = deepseekLine.split("=", limit = 2)
    
    if (profileId.isEmpty()) {
        println("INFO: DeepSeek R1 is configured for direct calls (no inference profile)")
    } else {
        println("SUCCESS: DeepSeek R1 is bound to inference profile: $profileId")
        println("Model ID: $modelId")
        println("Profile ID: $profileId")
        
        // Verify the binding format
        if (profileId.startsWith("us.deepseek")) {
            println("✓ Inference profile format looks correct")
        } else {
            println("⚠ Unusual inference profile format - expected 'us.deepseek.*'")
        }
    }
    
    println("\nInference configuration is ready for testing!")
    println("You can now run BedrockPipe tests with model 'deepseek.r1-v1:0'")
}