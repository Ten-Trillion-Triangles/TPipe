# TPipe: The Agent Operating Environment

**Built by [Ten Trillion Triangles](https://tentrilliontriangles.com)**

TPipe is an Agent Operating Environment designed for engineering robust, deterministic AI systems that can be embedded anywhere. Think of it as **Municipal Plumbing** for your LLMs: data flows through **Pipes** (Valves), gets routed along **Pipelines** (Mainlines), and pools into your ContextWindow and ContextBank (Reservoirs). Built on Kotlin and GraalVM, it provides strict resource accounting, secure sandboxing, and structured reasoning for production-grade multi-agent swarms.

**TPipe is the Agent Operating Environment**

TPipe provides the managed substrate for AI agents, moving beyond simple library wrappers into a production-grade runtime. It treats LLM interactions as data flowing through a managed plumbing system: **Pipes** (Valves) transport data, **Pipelines** (Mainlines) route it, and **ContextWindow**/**ContextBank** (Reservoirs) provide persistent state. Built on Kotlin and GraalVM, it provides strict resource accounting, secure sandboxing, and structured reasoning for production-grade autonomous systems.

## Different by Design: Substrate vs. Harness

TPipe is a **Substrate**, not just a harness or a library. It provides the underlying structure and foundations that agents inhabit, managing memory, enforcing protocols, and governing resources.

### Traditional Harness
```mermaid
graph LR
    App --> Library --> LLM
```

### TPipe Substrate
```mermaid
graph TD
    subgraph Substrate [TPipe Operating Environment]
        P[Pipes] <--> CB[(ContextBank)]
        P <--> PCP{PCP Protocol}
        P <--> TB[Token Budget]
    end
    App --> P
    P --> LLM
```

## Core Pillars

### 1. Runtime Control
*   **Deterministic Pipelines**: Orchestrate multi-stage AI workflows with sophisticated error handling and recovery.
*   **Pause/Resume/Jump**: Granular control over execution flow with declarative pause points for developer-in-the-loop validation.
*   **Resource Governance**: Strict token budgeting, automatic truncation, and cost enforcement.

### 2. Long-horizon Coherence
*   **ContextBank**: A global, persistent memory layer that maintains state across sessions and distributed systems.
*   **Semantic Compression**: Reduce prompt overhead through natural language legends and automatic context injection.
*   **Managed Reservoirs**: Hierarchical memory management with Page Keys and MiniBanks.

### 3. Bounded Autonomy
*   **Pipe Context Protocol (PCP)**: Secure, multi-language tool execution (Kotlin, JS, Python) with strict AST validation.
*   **Memory Introspection**: Controlled agent access to their own memory systems for self-correction.
*   **Guardrails & Security**: Built-in content moderation, DNS rebinding protection, and secure sandboxing.

## Case Studies
Explore how TPipe is used in the field for high-stakes automation:
- [Headless Use-Cases: TPipe in the Field](docs/case-studies/headless-use-cases.md)

## Documentation

### 🚀 Getting Started

Start here for installation and your first TPipe application:

- [Installation and Setup](docs/getting-started/installation-and-setup.md) - Requirements, installation, and environment setup
- [First Steps](docs/getting-started/first-steps.md) - Your first pipe and pipeline

### 🧠 Core Concepts

Essential TPipe features organized by complexity:

#### Fundamentals
- [Why TPipe? Architectural Deep Dive](docs/core-concepts/why-tpipe.md) - The paradigm shift from libraries to substrates
- [Pipe Class - Core Concepts](docs/core-concepts/pipe-class.md) - Understanding the fundamental Pipe class
- [Pipeline Class - Orchestrating Multiple Pipes](docs/core-concepts/pipeline-class.md) - Chaining pipes together
- [JSON Schema and System Prompts](docs/core-concepts/json-and-system-prompts.md) - Structured AI interactions

#### Context and Memory
- [Context Window - Memory Storage and Retrieval](docs/core-concepts/context-window.md) - TPipe's memory system
- [Context and Tokens - Token Management](docs/core-concepts/context-and-tokens.md) - Managing token usage and limits
- [Token Counting, Truncation, and Tokenizer Tuning](docs/core-concepts/token-counting-and-truncation.md) - Advanced token handling
- [Automatic Context Injection](docs/core-concepts/automatic-context-injection.md) - Seamless context integration
- [Semantic Compression - Prompt Token Reduction](docs/core-concepts/semantic-compression.md) - Legend-backed prompt compression for natural language

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
- [KillSwitch - Token Limit Enforcement](docs/core-concepts/killswitch.md) - Emergency safety mechanism for token exceedance
- [Merged PCP + JSON Output Mode](docs/core-concepts/merged-pcp-json-output.md) - Simultaneous structured JSON and PCP tool calling
- [Timeout and Retry System](docs/core-concepts/timeout-and-retry.md) - Pressure relief valves for transient failures

### 🏗️ Container Architecture

Advanced pipeline orchestration and multi-agent systems:

- [Container Overview](docs/containers/container-overview.md) - Introduction to TPipe containers
- [Manifold - Multi-Agent Orchestration](docs/containers/manifold.md) - Coordinating multiple AI agents
- [Manifold DSL Builder](docs/containers/manifold.md#dsl-builder) - Build and initialize manifolds in one Kotlin DSL block
- [Manifold Setup Checklist](docs/containers/manifold.md#startup-checklist) - Required manager, worker, memory, and `init()` steps before startup
- [Connector - Pipeline Branching](docs/containers/connector.md) - Conditional pipeline routing
- [Splitter - Parallel Processing](docs/containers/splitter.md) - Concurrent pipeline execution
- [Junction - Discussion and Workflow Harness](docs/containers/junction.md) - Multi-agent discussion, voting, and workflow handoff
- [PumpStation - Judge/Dispatch/Path Harness](docs/containers/pumpstation.md) - Runtime agentic harness with judge, dispatch, paths, memory management, and goal validation
- [PumpStation Magic Contracts](docs/core-concepts/pumpstation-magic-contracts.md) - LLM JSON contracts (judge, dispatch, path-safety, health, lorebook, goal) and where the data classes live
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

#### Tracing and Monitoring
- [TraceServer - Remote Trace Dashboard](docs/advanced-concepts/trace-server.md) - Real-time web dashboard for viewing agent traces

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

#### Ollama
- [Getting Started with TPipe-Ollama](docs/ollama/getting-started.md) - Local model setup and configuration

#### OpenRouter
- [Getting Started with TPipe-OpenRouter](docs/openrouter/getting-started.md) - Unified API access to 300+ models

### 📚 Case Studies

Real-world patterns and comparisons:

- [Grounded Case Studies](docs/case-studies/grounded-case-studies.md) - TPipe as an operating environment for advanced systems
- [Headless Use-Cases](docs/case-studies/headless-use-cases.md) - TPipe in autonomous, headless-first deployments
- [TPipe vs Apache Camel](docs/comparison/TPipe-vs-Apache-Camel-Comparison.md) - Complete feature comparison

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

#### Provider Pipe APIs
- [GenericOpenAI Pipe API](docs/api/generic-openai-pipe.md) - Generic OpenAI-compatible provider interface
- [Ollama Pipe API](docs/api/ollama-pipe.md) - Local Ollama model interface
- [OpenRouter Pipe API](docs/api/openrouter-pipe.md) - OpenRouter unified API interface

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
import com.TTT.Pipe.BedrockPipe

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
- **Multi-provider AI support** (AWS Bedrock, Ollama, OpenRouter, extensible architecture)
- **Kotlin and JavaScript scripting** in PCP alongside Python and native functions
- **Comprehensive debugging** with detailed tracing and monitoring
- **Remote trace dashboard** with TraceServer for centralized real-time trace viewing
- **Multi-Stream and Independent Tracing** for parallel pipelines and complex orchestration
- **Unified authentication** with AuthRegistry for automatic credential injection across remote services
- **Cross-region inference** with automatic profile binding for AWS Bedrock
- **Service tier optimization** for AWS Bedrock (Reserved, Priority, Standard, Flex)
- **Content safety with AWS Bedrock Guardrails** for automatic content moderation and policy enforcement
- **Enhanced security** with DNS rebinding protection, AST-based Python validation, and UUID session IDs
- **Merged PCP + JSON mode** for simultaneous structured output and tool calling in a single response

## Requirements

- **Java 24** or higher (GraalVM CE 24 recommended)
- **Kotlin 2.2.20** or higher
- **Gradle** with Kotlin DSL

## Installation

```kotlin
repositories {
    maven { url = uri("https://raw.githubusercontent.com/Ten-Trillion-Triangles/TPipe/main/maven") }
}

dependencies {
    implementation("com.TTT:TPipe:1.0.0")
    implementation("com.TTT:TPipe-Bedrock:1.0.0")  // For AWS Bedrock
    implementation("com.TTT:TPipe-Ollama:1.0.0")   // For Ollama
    implementation("com.TTT:TPipe-OpenRouter:1.0.0") // For OpenRouter
    implementation("com.TTT:TPipe-Defaults:1.0.0") // Reasoning pipes and pre-configured components
    implementation("com.TTT:TPipe-TraceServer:1.0.0") // Remote trace dashboard
}
```

## Licensing

TPipe is triple-licensed to meet the needs of both open-source developers and enterprise organizations.

*   **Open Source (AGPL-3.0)**: Free, requires derivative works to also be open-source.
*   **Startup**: Free closed-source for companies under $1M annual revenue. Also available to OSI-approved FOSS projects using TPipe as a dependency — see the [TPipe Startup License](https://www.tentrilliontriangles.com/licenses/LICENSE.TPipe-Startup.txt) for conditions.
*   **Commercial**: Above $1M revenue — see [TPipe Pricing](https://tentrilliontriangles.com/pricing) for tiers and terms.

Contact [contact@tentrilliontriangles.com](mailto:contact@tentrilliontriangles.com) for commercial and enterprise inquiries.