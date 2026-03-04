# Getting Started with Ollama

While cloud providers like Bedrock are the high-pressure refineries of the TPipe ecosystem, **OllamaPipe** provides local well water—high-performance, private, and cost-effective AI running directly on your own infrastructure.

OllamaPipe leverages the Ollama runtime to execute models like Llama 3, Mistral, and DeepSeek-R1 with the same unified Pipe interface you use for cloud models.

## Table of Contents
- [Prerequisites](#prerequisites)
- [Basic Usage](#basic-usage)
- [Model Reasoning: DeepSeek-R1](#model-reasoning-deepseek-r1)
- [Infrastructure Configuration](#infrastructure-configuration)
- [Tool Calling (PCP)](#tool-calling-pcp)
- [Ollama vs. Bedrock](#ollama-vs-bedrock)

---

## Prerequisites

1.  **Ollama Installed**: Ensure you have the Ollama runtime installed and running. Download it at [ollama.com](https://ollama.com).
2.  **Models Pulled**: You must pull your desired models before TPipe can use them.
    ```bash
    ollama pull llama3.1
    ollama pull deepseek-r1:1.5b
    ```

---

## Basic Usage: Opening the Local Flow

Setting up an Ollama pipe follows the same plumbing pattern as any other TPipe component.

```kotlin
import ollamaPipe.OllamaPipe
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val pipe = OllamaPipe()
        .setModel("llama3.1")
        .setTemperature(0.2) // Low temperature for deterministic output
        .setSystemPrompt("You are a helpful local engineer.")

    // Connect to the local Ollama server
    pipe.init()

    val result = pipe.execute("Check the water pressure logs.")
    println(result.text)
}
```

---

## Model Reasoning: DeepSeek-R1

OllamaPipe includes specialized logic to extract reasoning from thinking models like DeepSeek-R1. By enabling thinking mode, TPipe automatically strips the `<think>` tags from the final text and places them into the `modelReasoning` field.

```kotlin
val pipe = OllamaPipe()
    .setModel("deepseek-r1:1.5b")
    .enableThink() // Activate the thinking-tag extractor

val result = pipe.execute("Explain the physics of a siphon.")
println("Reasoning (Behind the scenes): ${result.modelReasoning}")
println("Final Answer: ${result.text}")
```

---

## Infrastructure Configuration: Managing the Load

Running models locally requires careful management of your system's pump capacity (CPU, GPU, and RAM). OllamaPipe provides granular controls for resource allocation.

```kotlin
pipe.setGpuSettings(numGpu = 35) // Offload 35 layers to your GPU
    .setNumThread(8)             // Use 8 CPU cores
    .setNumCtx(8192)            // Expand the local context reservoir
```

---

## Tool Calling (PCP)

TPipe-Ollama automatically maps your **Pipe Context Protocol (PCP)** tools to Ollama's native tool-calling system. This allows models to reach for tools like shell commands or HTTP requests while running entirely offline.

```kotlin
val pipe = OllamaPipe()
    .setModel("llama3.1") // Use a model capable of tool calling
    .setPcPContext(myToolBelt)

val result = pipe.execute("Gather data from the local sensors via HTTP.")
```

---

## Ollama vs. Bedrock

| Feature | **BedrockPipe** | **OllamaPipe** |
| :--- | :--- | :--- |
| **Location** | Cloud (AWS) | Local (Your hardware) |
| **Cost** | Per-token (Pay-as-you-go) | Free (Infrastructure cost only) |
| **Privacy** | Cloud-based | 100% Private/Offline |
| **Latency** | Network-dependent | Hardware-dependent |
| **Setup** | IAM & Cloud Config | Local Install & `ollama pull` |

## Next Steps

Now that you've mastered the local flow, learn how to manage the token budget for your local models.

**→ [Context and Tokens - Token Management](../core-concepts/context-and-tokens.md)** - Understanding token usage and limits.
