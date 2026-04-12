# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test Commands

```bash
# Run all tests
./gradlew test

# Run a specific test class
./gradlew :test --tests "*.ManifoldDslTest"

# Run tests with more memory
./gradlew test -Dorg.gradle.jvmargs="-Xmx1g"

# Compile without testing
./gradlew build -x test

# Clean build
./gradlew clean
```

## Project Overview

TPipe is the **Agent Operating Environment** for engineering robust, deterministic AI systems. It treats LLM interactions as data flowing through a managed plumbing system:
- **Pipes** (Valves) transport data to/from LLMs
- **Pipelines** (Mainlines) route data through multiple pipes
- **ContextWindow/ContextBank** (Reservoirs) provide persistent state

Built on Kotlin and GraalVM with strict resource accounting, secure sandboxing, and structured reasoning.

## Architecture

### Core Abstraction Layers

1. **Pipe** (`src/main/kotlin/Pipe/`) - Abstract base class for LLM interactions. Concrete implementations (BedrockPipe, OllamaPipe) inherit from this. Each pipe executes a single LLM call with full context management.

2. **Pipeline** (`src/main/kotlin/Pipeline/Pipeline.kt`) - Chains multiple pipes together, passing output from one as input to the next. Supports error handling, retry logic, and transformation functions.

3. **Containers** (`src/main/kotlin/Pipeline/`) - Higher-level orchestration patterns:
   - **Manifold** - Multi-agent orchestration with manager/worker pattern, P2P dispatch, and ConverseHistory state sharing
   - **Junction** - Collaborative discussion, voting, and workflow handoff
   - **Connector** - Conditional pipeline routing (branching)
   - **Splitter** - Parallel pipeline execution
   - **MultiConnector** - Complex routing with multiple conditions
   - **DistributionGrid** - Distributed node routing, discovery, and remote handoff

4. **Context System** (`src/main/kotlin/Context/`):
   - **ContextWindow** - Per-run memory storage with token budgeting
   - **ContextBank** - Global persistent memory across sessions
   - **MiniBank** - Multi-page context handling
   - **ConverseHistory** - Conversation state for multi-turn interactions

5. **P2P System** (`src/main/kotlin/P2P/`) - Distributed agent communication with registry, discovery, routing, and authentication

6. **Pipe Context Protocol (PCP)** (`src/main/kotlin/PipeContextProtocol/`) - Secure multi-language tool execution (Kotlin, JS, Python) with AST validation

### Key Entry Points

- `Application.kt` - Main entry point supporting `--http`, `--stdio-once`, `--stdio-loop`, `--pcp-stdio-once`, `--pcp-stdio-loop`, `--remote-memory` modes
- `Manifold.kt` - Orchestration harness with `execute()` method that runs the manager-worker loop
- `Pipeline.kt` - Pipeline execution via `execute()` method

### Enums Location

New enums should be added to `src/main/kotlin/Enums/` as individual files (e.g., `SummaryMode.kt`).

## Module Structure

```
TPipe/                      # Main library
├── src/main/kotlin/
│   ├── Pipeline/           # Pipeline, Manifold, Junction, Connector, Splitter, etc.
│   ├── Pipe/               # Pipe base class
│   ├── Context/            # ContextWindow, ContextBank, LoreBook, etc.
│   ├── P2P/                # P2P agent communication
│   ├── PipeContextProtocol/ # PCP tool execution
│   ├── Enums/              # Enum types
│   ├── Debug/              # Tracing and debugging
│   └── Util/               # Utilities
├── src/test/kotlin/        # Tests
TPipe-Bedrock/              # AWS Bedrock provider
TPipe-Ollama/               # Ollama local model provider
TPipe-MCP/                  # Model Context Protocol bridge
TPipe-Defaults/             # Pre-configured components
TPipe-Tuner/                # Tuning utilities
TPipe-TraceServer/          # Remote trace dashboard
```

## Important Patterns

### RuntimeState
Properties annotated with `@RuntimeState` are tracked as runtime state and preserved across certain lifecycle transitions. See `src/main/kotlin/Util/RuntimeState.kt`.

### DSL Builders
Manifold uses a Kotlin DSL builder pattern with `@DslMarker` annotations. The DSL is defined in `ManifoldDsl.kt` with nested builder classes for each configuration block (`manager { }`, `worker("name") { }`, `history { }`, `validation { }`, `tracing { }`, `summaryPipeline { }`).

### Serialization
TPipe uses Kotlinx Serialization (`@Serializable` annotation). Serialization setup is in `Serialization.kt`.

## Requirements

- **Java 24** (GraalVM CE 24 recommended)
- **Kotlin 1.9.0+**
- **Gradle 8.14.3** (wrapper provided)

## Key Files for Common Tasks

| Task | Key Files |
|------|-----------|
| Adding a new Pipeline container | `Pipeline/Pipeline.kt`, `Pipeline/Manifold.kt` |
| P2P agent communication | `P2P/P2PRegistry.kt`, `P2P/P2PInterface.kt` |
| Context management | `Context/ContextWindow.kt`, `Context/ContextBank.kt` |
| PCP tool execution | `PipeContextProtocol/Pcp.kt`, `PipeContextProtocol/FunctionInvoker.kt` |
| Tracing/debugging | `Debug/Trace.kt`, `Debug/TraceConfig.kt` |
| New enum type | `Enums/` directory (follow existing pattern) |
