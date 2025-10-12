# TPipe

A Kotlin-based AI pipeline orchestration framework that provides unified interfaces across multiple AI providers with sophisticated context management and validation systems.

## What TPipe Solves

Unlike traditional AI agent frameworks, TPipe focuses on deterministic execution flow, native function integration, and robust handling of long-running complex tasks through advanced tokenization and context selection algorithms.

| Problem | Traditional Frameworks | TPipe Solution |
|---------|----------------------|----------------|
| **Provider Lock-in** | Single provider or basic switching | Unified interface across AWS Bedrock, OpenAI, Gemini, Ollama, NovelAI |
| **Context Management** | Simple concatenation or basic memory | LoreBook weighted system with hit-count prioritization |
| **Function Integration** | JSON serialization overhead | Native Kotlin functions with direct memory access |
| **Validation & Recovery** | Basic error handling | Multi-tier validation with AI-powered error correction |
| **Tokenization** | Provider-specific estimation | Dictionary-based accurate counting |
| **State Management** | Session-based or stateless | Persistent global context with thread-safe operations |
| **Execution Model** | Event-driven reactive loops | Deterministic pipeline with async/await patterns |

## Key Features

- **Multi-Provider Support**: AWS Bedrock, OpenAI, Gemini, Ollama, NovelAI with provider-specific optimizations
- **Native Function Calls**: Direct code integration without serialization overhead
- **Advanced Context Management**: LoreBook-based weighted context with intelligent selection algorithms
- **Sophisticated Tokenization**: Dictionary-based token counting and truncation system
- **Multi-Tier Validation**: Code-based validators, AI-powered validation pipes, and automatic recovery
- **Concurrent Execution**: Coroutine-based async pipeline processing with mutex-based thread safety

## Quick Start

### Basic Pipe Usage

```kotlin
val pipe = OllamaPipe()
    .setModel("llama3")
    .setSystemPrompt("You are a helpful assistant")
    .setTemperature(0.7)
    .setMaxTokens(2048)

// Text-only execution (legacy)
val textResult = runBlocking {
    pipe.execute("Analyze this document: $documentText")
}

// Multimodal execution (recommended)
val multimodalResult = runBlocking {
    val content = MultimodalContent(
        text = "Analyze this document",
        binaryContent = listOf(
            BinaryContent.Bytes(documentBytes, "application/pdf")
        )
    )
    pipe.execute(content)
}
```

### Pipeline Construction

```kotlin
val extractionPipe = OllamaPipe()
    .setModel("llama3:8b")
    .setSystemPrompt("Extract key information")

val analysisPipe = OllamaPipe()
    .setModel("llama3:70b")
    .setSystemPrompt("Perform detailed analysis")

val pipeline = Pipeline()
    .add(extractionPipe)
    .add(analysisPipe)
    .init()

// Text-only pipeline execution
val textResult = runBlocking {
    pipeline.execute("Process this document: $content")
}

// Multimodal pipeline execution - returns full MultimodalContent
val multimodalResult = runBlocking {
    val input = MultimodalContent(text = "Process this document")
    pipeline.execute(input) // Returns MultimodalContent with text and binary data
}
```

### Native Function Integration

```kotlin
// Legacy string-based functions (still supported)
fun validateOutput(json: String): Boolean {
    return json.contains("summary") && json.length > 100
}

// Multimodal functions (recommended)
fun validateMultimodal(content: MultimodalContent): Boolean {
    return content.text.contains("summary") && 
           content.binaryContent.isNotEmpty()
}

val pipe = OllamaPipe()
    .setModel("llama3")
    .setValidatorFunction(::validateMultimodal)
    .setTransformationFunction { content ->
        content.copy(
            text = processAndCleanJson(content.text),
            binaryContent = processBinaryData(content.binaryContent)
        )
    }
```

## Architecture

### Core Components

- **Pipe**: Abstract base class for individual AI operations with model configuration, context management, and validation
- **Pipeline**: Orchestrates multiple pipes in sequence with shared context and error handling
- **ContextWindow**: Intelligent context management with weighted LoreBook entries and token-aware selection
- **ContextBank**: Thread-safe global context sharing across pipes and pipelines
- **Dictionary**: Advanced tokenization system for accurate token counting and context truncation

### Provider Modules

- **TPipe-Bedrock**: AWS Bedrock implementation supporting Claude, Titan, Llama, Mistral, Cohere, and DeepSeek models
- **TPipe-Ollama**: Local Ollama integration for on-device AI processing
- **Extensible**: Create custom provider modules by extending the Pipe class

## Installation

Add TPipe to your Kotlin project:

```kotlin
dependencies {
    implementation("com.ttt:tpipe-core:1.0.0")
    implementation("com.ttt:tpipe-bedrock:1.0.0") // For AWS Bedrock
    implementation("com.ttt:tpipe-ollama:1.0.0")  // For Ollama
}
```

## Documentation

- [Code Examples](Examples.md) - Comprehensive usage examples
- [Provider Modules](TPipe-Bedrock/README.md) - AWS Bedrock integration guide

## Use Cases

- **Enterprise Document Processing**: Multi-stage analysis with context persistence
- **Complex Validation Workflows**: AI-powered validation with automatic error recovery
- **Multi-Provider AI Orchestration**: Seamless switching between local and cloud models
- **Long-Running AI Tasks**: Persistent context management for multi-hour workflows
- **Mission-Critical Operations**: Deterministic execution with comprehensive error handling

## License

This project uses the Ktor framework. See [Ktor Documentation](https://ktor.io/docs/home.html) for more information.