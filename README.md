# TPipe - The Agent Operating Environment

TPipe is an Agent Operating Environment designed for engineering robust, deterministic AI systems that can be embedded anywhere. Think of it as **Municipal Plumbing** for your LLMs: data flows through **Pipes** (Valves), gets routed along **Pipelines** (Mainlines), and pools into your ContextWindow and ContextBank (Reservoirs). Built on Kotlin and GraalVM, it provides strict resource accounting, secure sandboxing, and structured reasoning for production-grade multi-agent swarms.

> 💡 **Tip:** Whether you are building a simple chatbot or a massive multi-agent swarm, TPipe gives you the deterministic control and "Developer-in-the-loop" validation you need to keep your AI on track.

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

#### Developer-in-the-Loop Processing
- [Developer-in-the-Loop Functions](docs/core-concepts/developer-in-the-loop.md) - Code-based validation and transformation
- [Developer-in-the-Loop Pipes](docs/core-concepts/developer-in-the-loop-pipes.md) - AI-powered validation and transformation

#### Advanced Features
- [Reasoning Pipes](docs/core-concepts/reasoning-pipes.md) - Chain-of-thought reasoning capabilities
- [Streaming Callbacks](docs/core-concepts/streaming-callbacks.md) - Real-time token streaming with multiple callbacks
- [Pipeline Flow Control](docs/core-concepts/pipeline-flow-control.md) - Dynamic routing and conditional execution
- [Error Handling and Propagation](docs/core-concepts/error-handling.md) - Programmatic error capture and debugging
- [Tracing and Debugging](docs/core-concepts/tracing-and-debugging.md) - Monitoring and troubleshooting

### 🏗️ Container Architecture

Advanced pipeline orchestration and multi-agent systems:

- [Container Overview](docs/containers/container-overview.md) - Introduction to TPipe containers
- [Manifold - Multi-Agent Orchestration](docs/containers/manifold.md) - Coordinating multiple AI agents
- [Connector - Pipeline Branching](docs/containers/connector.md) - Conditional pipeline routing
- [Splitter - Parallel Processing](docs/containers/splitter.md) - Concurrent pipeline execution
- [Junction - Pipeline Merging](docs/containers/junction.md) - Combining pipeline results
- [MultiConnector - Advanced Routing](docs/containers/multiconnector.md) - Complex routing patterns
- [DistributionGrid - Load Balancing](docs/containers/distributiongrid.md) - Distributed processing
- [Cross-Cutting Topics](docs/containers/cross-cutting-topics.md) - Shared container concepts

### 🔧 Advanced Concepts

Complex features and protocol integration:

#### Pipe Context Protocol (PCP)
- [Pipe Context Protocol Overview](docs/advanced-concepts/pipe-context-protocol.md) - TPipe's native tool protocol
- [Basic PCP Usage](docs/advanced-concepts/basic-pcp-usage.md) - Getting started with PCP
- [Intermediate PCP Features](docs/advanced-concepts/intermediate-pcp-features.md) - Advanced PCP capabilities
- [PCP Kotlin and JavaScript Support](docs/advanced-concepts/pcp-kotlin-javascript.md) - Kotlin/JS scripting in PCP
- [Advanced Session Management](docs/advanced-concepts/advanced-session-management.md) - Complex session handling
- [Conversation History Management](docs/advanced-concepts/conversation-history-management.md) - Managing conversation state

#### Memory and Agent Systems
- [Remote Memory](docs/advanced-concepts/remote-memory.md) - Distributed memory hosting and access
- [Memory Introspection](docs/advanced-concepts/memory-introspection.md) - Agent memory access control

#### P2P Agent Communication
- [P2P Overview](docs/advanced-concepts/p2p/p2p-overview.md) - Distributed agent communication
- [P2P Descriptors and Transport](docs/advanced-concepts/p2p/p2p-descriptors-and-transport.md) - Agent discovery and addressing
- [P2P Registry and Routing](docs/advanced-concepts/p2p/p2p-registry-and-routing.md) - Agent management and routing
- [P2P Requests and Templates](docs/advanced-concepts/p2p/p2p-requests-and-templates.md) - Request handling and templates
- [P2P Requirements and Validation](docs/advanced-concepts/p2p/p2p-requirements-and-validation.md) - Security and validation

### ☁️ Provider Integration

Integration guides for different AI providers:

#### AWS Bedrock
- [Getting Started with TPipe-Bedrock](docs/bedrock/getting-started.md) - Setup, configuration, and first steps
- [AWS Bedrock Inference Binding](docs/bedrock/inference-binding.md) - Cross-region model access and configuration
- [AWS Bedrock Guardrails](docs/bedrock/guardrails.md) - Content safety and moderation with Guardrails

### 📚 API Reference

Complete API documentation for all TPipe components:

#### Core APIs
- [Pipe Class API](docs/api/pipe.md) - Complete Pipe class reference
- [Pipeline Class API](docs/api/pipeline.md) - Pipeline orchestration methods
- [MultimodalContent API](docs/api/multimodal-content.md) - Content handling and processing

#### Context Management APIs
- [ContextWindow API](docs/api/context-window.md) - Memory and context operations
- [ContextBank API](docs/api/context-bank.md) - Global context management
- [ContextLock API](docs/api/context-lock.md) - Context access control and security
- [MiniBank API](docs/api/minibank.md) - Multi-page context handling
- [ConverseHistory API](docs/api/converse-history.md) - Conversation management
- [TodoList API](docs/api/todolist.md) - Task management for AI workflows
- [Dictionary API](docs/api/dictionary.md) - Token counting and truncation
- [Lorebook API](docs/api/lorebook.md) - Knowledge base management

#### Advanced APIs
- [Debug Package API](docs/api/debug-package.md) - Tracing and monitoring tools
- [P2P Interface API](docs/api/p2p-interface.md) - Agent communication interface
- [P2P Package API](docs/api/p2p-package.md) - Distributed agent system
- [PipeContextProtocol API](docs/api/pipe-context-protocol.md) - Tool execution framework
- [TPipeConfig API](docs/api/tpipe-config.md) - Configuration and directory management
- [Util Package API](docs/api/util-package.md) - Utility functions and helpers

#### Extension APIs
- [TPipe-MCP Package API](docs/api/tpipe-mcp-package.md) - Model Context Protocol bridge
- [TPipe-Defaults API](docs/api/tpipe-defaults-package.md) - Pre-configured components and reasoning

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
- **Timeout and retry system** with automatic recovery from transient failures and hanging LLM calls
- **Pipeline pause/resume control** with declarative pause points and developer-in-the-loop workflows
- **Global context sharing** across applications via ContextBank
- **Remote memory hosting** for distributed agent systems with MemoryServer and MemoryClient
- **Memory introspection** for autonomous agents with controlled memory access
- **Retrieval functions** for lazy-loading context from databases and APIs
- **Context access control** with ContextLock enforcement for secure lorebook and page management
- **Developer-in-the-loop integration** with code and AI-powered validation
- **Chain-of-thought reasoning** with multiple strategies and focus points
- **Multi-provider AI support** (AWS Bedrock, Ollama, extensible architecture)
- **Kotlin and JavaScript scripting** in PCP alongside Python and native functions
- **Comprehensive debugging** with detailed tracing and monitoring
- **Multi-Stream and Independent Tracing** for parallel pipelines and complex orchestration
- **Cross-region inference** with automatic profile binding for AWS Bedrock
- **Service tier optimization** for AWS Bedrock (Reserved, Priority, Standard, Flex)
- **Content safety with AWS Bedrock Guardrails** for automatic content moderation and policy enforcement
- **Enhanced security** with DNS rebinding protection, AST-based Python validation, and UUID session IDs

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
