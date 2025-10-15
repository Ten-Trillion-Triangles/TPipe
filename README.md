# TPipe - Advanced AI Pipeline Framework

TPipe is a sophisticated Kotlin framework for building complex AI processing pipelines with advanced context management, human-in-the-loop capabilities, and multi-provider AI model support.

## Documentation

### 🚀 Getting Started

Start here for installation and your first TPipe application:

- [Installation and Setup](docs/getting-started/installation-and-setup.md) - Requirements, installation, and environment setup
- [First Steps](docs/getting-started/first-steps.md) - Your first pipe and pipeline

### 🧠 Core Concepts

Essential TPipe features organized by complexity:

#### Fundamentals
- [Pipe Class - Core Concepts](docs/core-concepts/pipe-class.md) - Understanding the fundamental Pipe class
- [Pipeline Class - Orchestrating Multiple Pipes](docs/core-concepts/pipeline-class.md) - Chaining pipes together
- [JSON Schema and System Prompts](docs/core-concepts/json-and-system-prompts.md) - Structured AI interactions

#### Context and Memory
- [Context Window - Memory Storage and Retrieval](docs/core-concepts/context-window.md) - TPipe's memory system
- [Context and Tokens - Token Management](docs/core-concepts/context-and-tokens.md) - Managing token usage and limits
- [Token Counting, Truncation, and Tokenizer Tuning](docs/core-concepts/token-counting-and-truncation.md) - Advanced token handling
- [Automatic Context Injection](docs/core-concepts/automatic-context-injection.md) - Seamless context integration

#### Global Context Management
- [ContextBank - Global Context Integration](docs/core-concepts/context-bank-integration.md) - Global context repository
- [Page Keys and Global Context](docs/core-concepts/page-keys-and-global-context.md) - Organized context retrieval
- [MiniBank and Multiple Page Keys](docs/core-concepts/minibank-and-multiple-page-keys.md) - Multi-context management
- [Pipeline Context Integration](docs/core-concepts/pipeline-context-integration.md) - Context sharing within pipelines

#### Human-in-the-Loop Processing
- [Human-in-the-Loop Functions](docs/core-concepts/human-in-the-loop.md) - Code-based validation and transformation
- [Human-in-the-Loop Pipes](docs/core-concepts/human-in-the-loop-pipes.md) - AI-powered validation and transformation

#### Advanced Features
- [Reasoning Pipes](docs/core-concepts/reasoning-pipes.md) - Chain-of-thought reasoning capabilities
- [Pipeline Flow Control](docs/core-concepts/pipeline-flow-control.md) - Dynamic routing and conditional execution
- [Tracing and Debugging](docs/core-concepts/tracing-and-debugging.md) - Monitoring and troubleshooting

### ☁️ AWS Bedrock Integration

Complete guide to using TPipe with AWS Bedrock foundation models:

- [Getting Started with TPipe-Bedrock](docs/bedrock/getting-started.md) - Setup, configuration, and first steps
- [AWS Bedrock Inference Binding](docs/bedrock/inference-binding.md) - Cross-region model access and configuration

## Quick Start

```kotlin
import bedrockPipe.BedrockPipe

val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("You are a helpful assistant.")
    .setTemperature(0.7)

val result = pipe.execute("What is artificial intelligence?")
println(result.text)
```

## Key Features

- **Multi-stage AI workflows** with sophisticated error handling
- **Global context sharing** across applications via ContextBank
- **Human-in-the-loop integration** with code and AI-powered validation
- **Chain-of-thought reasoning** with multiple strategies and focus points
- **Multi-provider AI support** (AWS Bedrock, Ollama, extensible architecture)
- **Comprehensive debugging** with detailed tracing and monitoring
- **Cross-region inference** with automatic profile binding for AWS Bedrock

## Requirements

- **Java 24** or higher (GraalVM CE 24 recommended)
- **Kotlin 1.9.0** or higher
- **Gradle** with Kotlin DSL

## Installation

```kotlin
dependencies {
    implementation("com.TTT:TPipe-Core:1.0.0")
    implementation("com.TTT:TPipe-Bedrock:1.0.0")  // For AWS Bedrock
    implementation("com.TTT:TPipe-Ollama:1.0.0")   // For Ollama
}
```
# TPipe
