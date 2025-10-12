import bedrockPipe.BedrockPipe
import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Enums.ProviderName
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.BinaryContent
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("=== GPT-OSS Comprehensive Test ===")
    
    // Test 1: Basic text generation
    println("\n1. Testing basic text generation...")
    val basicPipe = BedrockPipe()
        .setProvider(ProviderName.Aws)
        .setModel("openai.gpt-oss-20b-1:0")
        .setSystemPrompt("You are a helpful assistant")
        .setTemperature(0.7)
        .setMaxTokens(500)
    
    (basicPipe as BedrockPipe).setRegion("us-west-2")

    try {
        basicPipe.init()
        val result = basicPipe.execute("What is 2+2?")
        println("✓ Basic generation: ${result.take(100)}...")
    } catch (e: Exception) {
        println("✗ Basic generation failed: ${e.message}")
    }
    
    // Test 2: Reasoning mode
    println("\n2. Testing reasoning mode...")
    val reasoningPipe = BedrockPipe()
        .setProvider(ProviderName.Aws)
        .setModel("openai.gpt-oss-120b-1:0")
        .setSystemPrompt("You are a logical reasoning assistant")
        .setReasoning()
        .setMaxTokens(1000)
    
    (reasoningPipe as BedrockPipe).setRegion("us-west-2")

    try {
        reasoningPipe.init()
        val result = reasoningPipe.execute("If all cats are animals and Fluffy is a cat, what can we conclude?")
        println("✓ Reasoning mode: ${result.take(100)}...")
    } catch (e: Exception) {
        println("✗ Reasoning mode failed: ${e.message}")
    }
    
    // Test 3: Converse API
    println("\n3. Testing Converse API...")
    val conversePipe = BedrockPipe()
        .setProvider(ProviderName.Aws)
        .setModel("openai.gpt-oss-20b-1:0")
        .setSystemPrompt("You are a conversational assistant")
        .setMaxTokens(500)
    
    (conversePipe as BedrockPipe).setRegion("us-west-2").useConverseApi()

    try {
        conversePipe.init()
        val result = conversePipe.execute("Hello, how are you?")
        println("✓ Converse API: ${result.take(100)}...")
    } catch (e: Exception) {
        println("✗ Converse API failed: ${e.message}")
    }
    
    // Test 4: Multimodal support
    println("\n4. Testing multimodal support...")
    val multimodalPipe = BedrockMultimodalPipe()
        .setProvider(ProviderName.Aws)
        .setModel("openai.gpt-oss-20b-1:0")
        .setSystemPrompt("You are a document analysis assistant")
        .setMaxTokens(500)
    
    multimodalPipe.setRegion("us-west-2")

    try {
        multimodalPipe.init()
        val content = MultimodalContent(
            text = "Analyze this document:",
            binaryContent = mutableListOf(
                BinaryContent.TextDocument("Sample document content for analysis", "sample.txt")
            )
        )
        val result = multimodalPipe.execute(content)
        println("✓ Multimodal: ${result.text.take(100)}...")
    } catch (e: Exception) {
        println("✗ Multimodal failed: ${e.message}")
    }
    
    // Test 5: Region validation
    println("\n5. Testing region validation...")
    val invalidRegionPipe = BedrockPipe()
        .setProvider(ProviderName.Aws)
        .setModel("openai.gpt-oss-20b-1:0")
    
    (invalidRegionPipe as BedrockPipe).setRegion("us-east-1") // Invalid region for GPT-OSS

    try {
        invalidRegionPipe.init()
        println("✗ Region validation failed - should have thrown exception")
    } catch (e: IllegalArgumentException) {
        println("✓ Region validation: ${e.message}")
    } catch (e: Exception) {
        println("✗ Unexpected error: ${e.message}")
    }
    
    // Test 6: Parameter mapping
    println("\n6. Testing parameter mapping...")
    val paramPipe = BedrockPipe()
        .setProvider(ProviderName.Aws)
        .setModel("openai.gpt-oss-20b-1:0")
        .setTemperature(0.9)
        .setTopP(0.8)
        .setMaxTokens(200)
        .setStopSequences(listOf("END", "STOP"))
    
    (paramPipe as BedrockPipe).setRegion("us-west-2")

    try {
        paramPipe.init()
        val result = paramPipe.execute("Count to 5")
        println("✓ Parameter mapping: ${result.take(100)}...")
    } catch (e: Exception) {
        println("✗ Parameter mapping failed: ${e.message}")
    }
    
    println("\n=== Test Complete ===")
}