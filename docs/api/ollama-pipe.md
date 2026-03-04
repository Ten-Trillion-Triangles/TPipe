# Ollama Pipe Class API

The `OllamaPipe` class is the Local Well Water—a TPipe implementation for interacting with local Ollama instances. It provides the same unified interface as cloud-based pipes while offering granular control over local hardware resources, private model execution, and specialized extraction for reasoning models like DeepSeek-R1.

```kotlin
class OllamaPipe : Pipe()
```

## Table of Contents
- [Server Configuration](#server-configuration)
- [Operational Modes](#operational-modes)
- [Inference Settings: Tuning the Flow](#inference-settings-tuning-the-flow)
- [Resource Management: Hardware Controls](#resource-management-hardware-controls)
- [Streaming and Telemetry](#streaming-and-telemetry)

---

## Server Configuration

#### `setIP(ip: String)` / `setPort(port: Int)`
Defines the network coordinates for the Ollama server.
- **Default**: `127.0.0.1:11434`.

#### `init(): Pipe`
Initializes the valve. It automatically checks if the Ollama server is active; if not, it attempts to "Boot" the server in the background using `ollama serve`.

---

## Operational Modes

#### `useChatApi(): OllamaPipe`
Switches the pipe to the modern `/api/chat` endpoint. This is the industrial standard for TPipe, enabling multi-turn conversation history and native tool-calling (PCP).

#### `enableThink(): OllamaPipe`
The Reasoning Extractor. Specifically designed for models like **DeepSeek-R1**, this mode automatically identifies `<think>` tags in the model's output, strips them from the final text, and places them into the `modelReasoning` field for high-resolution auditing.

---

## Inference Settings: Tuning the Flow

#### `setKeepAlive(duration: String)`
Controls how long the model remains loaded in your system's VRAM after a request.
- **`5m`**: (Default) Unloads after 5 minutes of inactivity.
- **`-1`**: Keeps the model loaded indefinitely for zero-latency follow-up calls.

#### `setMirostat(mode: Int, ...)`
Enables Mirostat sampling to control perplexity and improve the coherence of long-form generation.

---

## Resource Management: Hardware Controls

Running AI locally requires precise management of your "Pump Capacity" (CPU and GPU).

#### `setGpuSettings(numGpu: Int, mainGpu: Int? = null)`
Defines the number of model layers to offload to the GPU. This is critical for maximizing performance on systems with limited VRAM.

#### `setNumThread(numThread: Int)`
Sets the number of CPU cores dedicated to the model's "Thinking."

#### `setNumCtx(numCtx: Int)`
Expands the local context reservoir. This defines the absolute limit of tokens the local runner can hold in its active memory.

---

## Streaming and Telemetry

#### `enableStreaming(callback, showReasoning, streamReasoning)`
Opens the real-time flow of tokens.
- **`streamReasoning`**: If true, the raw "Thoughts" (from models like DeepSeek) are also streamed to the callback, providing immediate feedback during complex reasoning tasks.

#### `streamingCallbacks { ... }`
Allows you to attach multiple specialized "Gauges" (callbacks) to the stream for simultaneous UI updates and logging.

---

## Key Operational Behaviors

### 1. Unified Parameter Mapping
OllamaPipe automatically translates standard TPipe parameters—such as `temperature`, `maxTokens`, and `stopSequences`—into the exact format required by the Ollama runner. This ensures that your pipelines work identically whether they are plumbed into Bedrock or a local Llama model.

### 2. Native Tool Integration
TPipe-Ollama maps your **Tool Belt (PCP)** definitions directly to Ollama's native tool-calling capabilities, allowing for secure, offline tool execution.

### 3. Automated Server Lifecycle
The `init()` method reduces infrastructure overhead by managing the background process for Ollama, ensuring the "Well" is always pumping when your application needs it.
