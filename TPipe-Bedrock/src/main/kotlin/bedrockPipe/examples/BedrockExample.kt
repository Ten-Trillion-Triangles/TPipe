package bedrockPipe.examples

import bedrockPipe.BedrockPipe
import com.TTT.Enums.ProviderName
import kotlinx.coroutines.runBlocking

/**
 * Example usage of BedrockPipe with various AWS Bedrock models
 */
fun main() = runBlocking {
    // Claude 3 example
    val claudePipe = BedrockPipe()
        .setProvider(ProviderName.Aws)
        .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    (claudePipe as BedrockPipe).setRegion("us-east-1")
    claudePipe
        .setSystemPrompt("You are a helpful AI assistant.")
        .setUserPrompt("Please analyze:")
        .setTemperature(0.7)
        .setMaxTokens(1000)
        .setTopP(0.9)
        .setStopSequences(listOf("END", "STOP"))
    
    claudePipe.init()
    // amazonq-ignore-next-line
    val claudeResult = claudePipe.execute("What are the benefits of cloud computing?")
    
    // Titan example
    val titanPipe = BedrockPipe()
        .setProvider(ProviderName.Aws)
        .setModel("amazon.titan-text-express-v1")
    (titanPipe as BedrockPipe).setRegion("us-west-2")
    titanPipe
        .setSystemPrompt("You are a technical writer.")
        .setTemperature(0.5)
        .setMaxTokens(500)
    
    titanPipe.init()
    // amazonq-ignore-next-line
    val titanResult = titanPipe.execute("Explain machine learning in simple terms.")
    
    // Llama example with context
    val llamaPipe = BedrockPipe()
        .setProvider(ProviderName.Aws)
        .setModel("meta.llama2-70b-chat-v1")
    (llamaPipe as BedrockPipe).setRegion("us-east-1")
    llamaPipe
        .setTemperature(0.8)
        .setMaxTokens(800)
        .setContextWindowSize(4000)
        .pullGlobalContext()
    
    // Context will be added through global context or other means
    // llamaPipe.contextWindow is protected - use global context instead
    
    llamaPipe.init()
    val llamaResult = llamaPipe.execute("How can AI help in our industry?")
    
    // Cohere with validation
    val coherePipe = BedrockPipe()
        .setProvider(ProviderName.Aws)
        .setModel("cohere.command-text-v14")
    (coherePipe as BedrockPipe).setRegion("us-east-1")
    coherePipe
        .setTemperature(0.6)
        .setTopP(0.8)
        .setTopK(50)
        .setValidatorFunction { content ->
            content.text.isNotEmpty() && content.text.length > 10
        }
        .setTransformationFunction { content ->
            val trimmed = content.text.trim().take(200) + if (content.text.length > 200) "..." else ""
            content.text = trimmed
            content
        }
    
    coherePipe.init()
    val cohereResult = coherePipe.execute("Write a brief summary of renewable energy.")
    
    // DeepSeek R1 example
    val deepseekPipe = BedrockPipe()
        .setProvider(ProviderName.Aws)
        .setModel("deepseek.deepseek-r1")
    (deepseekPipe as BedrockPipe).setRegion("us-east-1")
    deepseekPipe
        .setSystemPrompt("You are an expert reasoning assistant.")
        .setTemperature(0.7)
        .setMaxTokens(1000)
        .setTopP(0.9)
    
    deepseekPipe.init()
    val deepseekResult = deepseekPipe.execute("Solve this step by step: What is 15% of 240?")
}