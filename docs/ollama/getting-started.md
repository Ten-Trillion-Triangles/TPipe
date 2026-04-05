# Getting Started with Ollama

## Introduction

TPipe-Ollama provides a powerful, local alternative to cloud-based AI providers. It leverages the Ollama runtime to run high-performance models like Llama 3, Mistral, and DeepSeek-R1 directly on your machine.

## Prerequisites

1.  **Ollama Installed:** Ensure you have Ollama installed and available in your system's PATH. You can download it from [ollama.com](https://ollama.com).
2.  **Models Pulled:** Pull the models you want to use before running TPipe.
    ```bash
    ollama pull llama3
    ollama pull deepseek-r1:1.5b
    ```

## Basic Usage

To get started, create an `OllamaPipe` instance and configure the model.

```kotlin
import ollamaPipe.OllamaPipe
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val pipe = OllamaPipe()
        .setModel("llama3")
        .setTemperature(0.7)
        .setSystemPrompt("You are a helpful local assistant.")

    // Initialize the pipe (starts Ollama server if not running)
    pipe.init()

    val result = pipe.execute("What is the speed of light?")
    println(result.text)
}
```

## Advanced Features

### Multimodal Support

OllamaPipe supports multimodal models like `llava`. You can pass images as base64 strings or byte arrays.

```kotlin
val result = pipe.execute(MultimodalContent(
    text = "Describe this image.",
    binaryContent = mutableListOf(BinaryContent.Bytes(imageBytes))
))
```

### DeepSeek-R1 Reasoning

To extract reasoning from models like DeepSeek-R1 that use `<think>` tags, enable the thinking mode.

```kotlin
val pipe = OllamaPipe()
    .setModel("deepseek-r1:1.5b")
    .enableThink() // Extracts <think> tags

val result = pipe.execute("Explain quantum entanglement.")
println("Reasoning: ${result.modelReasoning}")
println("Answer: ${result.text}")
```

### Native Tool Calling

TPipe automatically maps PCP tools to Ollama's native tool calling system.

```kotlin
val pipe = OllamaPipe()
    .setModel("llama3.1") // Tool-calling capable model
    .setPcPContext(myPcpContext)

val result = pipe.execute("Check the current system CPU usage.")
// result.text will contain the tool call JSON
```

### Resource Configuration

For large models or specific hardware, you can configure resource limits.

```kotlin
pipe.setGpuSettings(numGpu = 35) // Offload 35 layers to GPU
    .setNumThread(8)             // Use 8 CPU threads
    .setNumCtx(8192)            // Increase context window
```

## Comparison with BedrockPipe

| Feature | BedrockPipe | OllamaPipe |
| :--- | :--- | :--- |
| **API Endpoint** | AWS Bedrock SDK | `/api/chat` (local) |
| **Reasoning** | `reasoningContent` | `<think>` Extraction |
| **Tool Calling** | Converse API Tools | Native Tool Calling |
| **Streaming** | Stream Handler | Ktor Async Client |
| **Multimodal** | Base64 Images | Base64 Images |
| **Context Management** | S3 / Managed | Local Memory |
## Next Steps

- [Pipe Class API](../api/pipe.md) - Continue into the core API reference.
