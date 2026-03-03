# Ollama Pipe Class API

## Table of Contents
- [Overview](#overview)
- [Public Functions](#public-functions)
  - [Server Configuration](#server-configuration)
  - [API Mode](#api-mode)
  - [Inference Settings](#inference-settings)
  - [Resource Management](#resource-management)
  - [Streaming](#streaming)
- [Ollama-Specific Options](#ollama-specific-options)

## Overview

The `OllamaPipe` class provides a TPipe abstraction for interacting with local Ollama instances. It supports multimodal inputs, streaming, reasoning extraction (specifically for models like DeepSeek-R1), and native tool calling.

```kotlin
class OllamaPipe : Pipe()
```

## Public Functions

### Server Configuration

#### `setIP(ip: String): OllamaPipe`
Sets the IP address of the Ollama server. Defaults to `127.0.0.1`.

#### `setPort(port: Int): OllamaPipe`
Sets the port number of the Ollama server. Defaults to `11434`.

#### `init(): Pipe`
Initializes the pipe. Checks if the Ollama server is running; if not, attempts to start it in the background using `ollama serve`.

### API Mode

#### `useChatApi(): OllamaPipe`
Switches to the modern `/api/chat` endpoint. This is the default and enables conversation history and native tool calling.

#### `useLegacyApi(): OllamaPipe`
Switches to the legacy `/api/generate` endpoint.

### Inference Settings

#### `setKeepAlive(duration: String): OllamaPipe`
Sets how long the model stays loaded in memory after a request.
- `5m`: 5 minutes (default)
- `1h`: 1 hour
- `0`: Unload immediately
- `-1`: Keep loaded indefinitely

#### `enableThink(): OllamaPipe`
Enables reasoning extraction for models that use `<think>` tags (e.g., DeepSeek-R1). Extracted reasoning is populated in `MultimodalContent.modelReasoning`.

#### `setMinP(minP: Float): OllamaPipe`
Sets the minimum probability threshold for token sampling.

#### `setTypicalP(typicalP: Float): OllamaPipe`
Sets the typical probability for sampling.

#### `setMirostat(mode: Int, eta: Float? = null, tau: Float? = null): OllamaPipe`
Enables and configures Mirostat sampling.
- `mode`: 0 (off), 1 (Mirostat), 2 (Mirostat 2.0)

#### `setRepeatLastN(n: Int): OllamaPipe`
Sets how many tokens back to look for repeat penalty.

#### `setPenalizeNewline(penalize: Boolean): OllamaPipe`
Controls whether the model is penalized for generating newlines.

### Resource Management

#### `setGpuSettings(numGpu: Int, mainGpu: Int? = null): OllamaPipe`
Configures GPU offloading.
- `numGpu`: Number of layers to offload to GPU.
- `mainGpu`: The ID of the main GPU to use.

#### `setNumThread(numThread: Int): OllamaPipe`
Sets the number of CPU threads to use for generation.

#### `setBatchSize(batchSize: Int): OllamaPipe`
Sets the prompt processing batch size.

#### `setNumCtx(numCtx: Int): OllamaPipe`
Sets the model's context window size (tokens).

#### `setLowVram(lowVram: Boolean): OllamaPipe`
Enables low VRAM mode for limited hardware.

#### `setNuma(useNuma: Boolean): OllamaPipe`
Enables NUMA optimization on supported systems.

### Streaming

#### `enableStreaming(callback: (suspend (String) -> Unit)? = null, showReasoning: Boolean = false, streamReasoning: Boolean = true): OllamaPipe`
Enables real-time streaming of responses.
- `callback`: Suspendable function receiving text chunks.
- `showReasoning`: Propagates streaming to reasoning pipes.
- `streamReasoning`: Whether to emit reasoning chunks to the callback.

#### `setStreamingCallback(callback: suspend (String) -> Unit): OllamaPipe`
Registers a single streaming callback and enables streaming.

#### `streamingCallbacks(builder: StreamingCallbackBuilder.() -> Unit): OllamaPipe`
Configures multiple streaming callbacks using a DSL.

---

## Ollama-Specific Options

OllamaPipe automatically maps standard TPipe parameters (`temperature`, `topP`, `topK`, `maxTokens`, `stopSequences`, `repetitionPenalty`) to the equivalent Ollama options. Advanced options listed above allow for fine-tuned control over the local runner environment.
