# P2P Package API

## Table of Contents
- [Overview](#overview)
- [P2PDescriptor](#p2pdescriptor)
- [P2PRequest](#p2prequest)
- [P2PResponse](#p2presponse)
- [P2PRequirements](#p2prequirements)
- [P2PRegistry](#p2pregistry)
- [P2PHost](#p2phost)
- [Supporting Classes](#supporting-classes)
- [Enums](#enums)

## Overview

The P2P package enables distributed agent communication in TPipe, providing comprehensive infrastructure for agent registration, discovery, request routing, and secure inter-agent communication.

## P2PDescriptor

Comprehensive agent capability and configuration descriptor.

```kotlin
@Serializable
data class P2PDescriptor(
    var agentName: String,
    var agentDescription: String,
    var transport: P2PTransport,
    var requiresAuth: Boolean,
    var usesConverse: Boolean,
    var allowsAgentDuplication: Boolean,
    var allowsCustomContext: Boolean,
    var allowsCustomAgentJson: Boolean,
    var recordsInteractionContext: Boolean,
    var recordsPromptContent: Boolean,
    var allowsExternalContext: Boolean,
    var contextProtocol: ContextProtocol,
    var inputPromptSchema: String = "",
    var contextProtocolSchema: String = "",
    var contextWindowSize: Int = 32000,
    var supportedContentTypes: MutableList<SupportedContentTypes> = mutableListOf(SupportedContentTypes.text),
    var agentSkills: MutableList<P2PSkills>? = null,
    var pcpDescriptor: PcpContext = PcpContext(),
    var allowedModels: MutableMap<String, MutableList<String>>? = null,
    var requestTemplate: P2PRequest? = null
)
```

### Key Properties

**Agent Identity:**
- `agentName` - Unique agent identifier.
- `agentDescription` - Human-readable capability description.
- `agentSkills` - Granular skill definitions for LLM understanding.

**Communication:**
- `transport` - P2PTransport defining connection method and addressing.
- `requiresAuth` - Authentication requirement flag.
- `usesConverse` - Conversation history protocol usage.

**Capabilities:**
- `allowsAgentDuplication` - Pipeline copying for customization.
- `allowsCustomContext` - External context injection.
- `allowsCustomAgentJson` - Dynamic schema modification.
- `allowsExternalContext` - TPipe ContextWindow acceptance.

**Content Support:**
- `supportedContentTypes` - Accepted content types (text, image, video, etc.).
- `contextWindowSize` - Maximum token capacity.

---

## P2PRequest

Comprehensive request object for agent communication.

```kotlin
@Serializable
data class P2PRequest(
    var transport: P2PTransport = P2PTransport(),
    var returnAddress: P2PTransport = P2PTransport(),
    var prompt: MultimodalContent = MultimodalContent(),
    var authBody: String = "",
    var contextExplanationMessage: String = "",
    var context: ContextWindow? = null,
    var customContextDescriptions: MutableMap<String, String>? = null,
    var pcpRequest: PcPRequest? = null,
    var inputSchema: CustomJsonSchema? = null,
    var outputSchema: CustomJsonSchema? = null
)
```

### Key Properties
- `transport` - Target agent address.
- `prompt` - Multimodal request content.
- `context` - Optional context injection.
- `authBody` - Authentication credentials for global or per-agent auth.
- `pcpRequest` - PCP tool execution requests nested within the P2P call.

---

## P2PResponse

Response object containing execution results or rejection information.

```kotlin
@Serializable
data class P2PResponse(
    var output: MultimodalContent? = null,
    var rejection: P2PRejection? = null
)
```

### P2PRejection
```kotlin
@Serializable
data class P2PRejection(
    var errorType: P2PError = P2PError.none,
    var reason: String = ""
)
```
**P2PError Enum:** `auth`, `prompt`, `json`, `content`, `transport`, `none`.

---

## P2PRequirements

Security and compatibility requirements for agent access control.

```kotlin
data class P2PRequirements(
    var requireConverseInput: Boolean = false,
    var allowAgentDuplication: Boolean = false,
    var allowCustomContext: Boolean = false,
    var allowCustomJson: Boolean = false,
    var allowExternalConnections: Boolean = false,
    var acceptedContent: MutableList<SupportedContentTypes>? = null,
    var maxTokens: Int = 30000,
    var tokenCountingSettings: TruncationSettings? = null,
    var maxBinarySize: Int = 20 * 1024,
    var authMechanism: (suspend (String) -> Boolean)? = null
)
```

---

## P2PRegistry

Singleton registry managing agent registration and routing.

### Public Properties
- `globalAuthMechanism`: A `(suspend (authBody: String) -> Boolean)?` property for transport-level authentication of incoming HTTP and Stdio P2P requests.

### Public Functions
- `register(agent: P2PInterface)`: Registers an agent using its internal descriptor.
- `executeP2pRequest(request: P2PRequest)`: Processes requests with full validation and routing.
- `sendP2pRequest(request: AgentRequest)`: Client-side helper for model-driven agent calls.

---

## P2PHost

Hosts for standalone P2P traffic.

### `P2PStdioHost` (Object)
Handles P2P requests over standard streams.
- `runOnce()`: Processes one JSON request from `stdin` and exits.
- `runLoop()`: Processes multiple requests until "exit" is received.

### HTTP Hosting
Accessible via `POST /p2p` when the TPipe host is started with the `--http` or `--remote-memory` flags.

---

## Supporting Classes

### CustomJsonSchema
Dynamic JSON schema container for request/output customization.

### AgentRequest
Simplified request format (`agentName`, `prompt`, `content`) generated by LLMs to invoke collaborators.

---

## Enums

- **ContextProtocol**: `pcp`, `mcp`, `provider`, `none`.
- **SupportedContentTypes**: `text`, `image`, `video`, `audio`, `application`, `other`, `none`.
- **Transport**: `Tpipe`, `Http`, `Stdio`.
