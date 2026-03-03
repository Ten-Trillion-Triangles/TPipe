# TPipe - The Agent Operating Environment

TPipe is a professional-grade operating environment for building robust, deterministic AI agents. Built on Kotlin and GraalVM, it provides the "Operating System" features your agents need: memory management, tool execution, inter-agent communication, and strict security sandboxing.

---

## 🚀 Getting Started

New to TPipe? Start here to build your first intelligent pipeline.

- **[Installation and Setup](docs/getting-started/installation-and-setup.md)**: Requirements and environment setup.
- **[First Steps](docs/getting-started/first-steps.md)**: Build your first Pipe and Pipeline.
- **[CLI Reference](docs/getting-started/cli-reference.md)**: Hosting agents and tool servers from the command line.

---

## 🧠 Core Concepts: Managing Knowledge

TPipe's power lies in how it manages information across complex agent swarms.

- **[Context Windows](docs/core-concepts/context-window.md)**: The "Short-term Memory" of an agent.
- **[Central Library (ContextBank)](docs/core-concepts/context-bank-integration.md)**: How agents share knowledge and state.
- **[Distributed Swarms (Remote Memory)](docs/advanced-concepts/remote-memory.md)**: Sharing state across multiple servers.
- **[Token Mastery](docs/core-concepts/token-counting-and-truncation.md)**: Controlling context size and budget precisely.

---

## 🛠️ Advanced Concepts: Taking Action

Transform your agents from "chatbots" into autonomous "doers."

- **[The Tool Belt (PCP)](docs/advanced-concepts/pipe-context-protocol.md)**: Letting agents use terminal commands, web APIs, and Python.
- **[Collaborative Office (P2P)](docs/advanced-concepts/p2p/p2p-overview.md)**: Building specialized agents that call each other.

---

## 🏗️ Architecture: Orchestration

Scale your agents using TPipe's modular container system.

- **[Container Overview](docs/containers/container-overview.md)**: The building blocks of agent swarms.
- **[Multi-Agent Orchestration (Manifold)](docs/containers/manifold.md)**: Coordinating teams of agents.
- **[Parallel Processing (Splitter)](docs/containers/splitter.md)**: Running multiple pipelines at once.

---

## 📚 API Reference

Detailed technical documentation for every TPipe component.

- **[ContextBank API](docs/api/context-bank.md)**: Global state and memory management.
- **[Collaborative P2P API](docs/api/p2p-package.md)**: Agent-to-agent communication.
- **[Tool execution PCP API](docs/api/pipe-context-protocol.md)**: Sandbox and tool infrastructure.
- **[Global Config API](docs/api/tpipe-config.md)**: Environment and cluster settings.

---

## Requirements
- **Java 24** (GraalVM CE 24 recommended)
- **Kotlin 1.9.0+**
- **Gradle**
