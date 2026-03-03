# P2P Package API

## Table of Contents
- [Overview](#overview)
- [P2PDescriptor](#p2pdescriptor)
- [P2PRequest](#p2prequest)
- [P2PResponse](#p2presponse)
- [P2PRequirements](#p2prequirements)
- [P2PRegistry](#p2pregistry)
- [P2PHost](#p2phost)

## Overview

The P2P package enables distributed agent communication in TPipe, providing infrastructure for agent registration, discovery, request routing, and secure inter-agent communication over Tpipe (in-process), HTTP, and Stdio transports.

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

### P2PTransport
Defines how to reach an agent.
- `transportMethod`: `Transport.Tpipe`, `Transport.Http`, or `Transport.Stdio`.
- `transportAddress`: The identifier, URL, or executable path.
- `transportAuthBody`: Credentials for this specific transport.

---

## P2PRequest

Request object for agent communication.
- `transport`: Target agent address.
- `returnAddress`: Response routing information.
- `prompt`: `MultimodalContent` request data.
- `context`: Optional `ContextWindow` injection.
- `authBody`: Authentication credentials.
- `pcpRequest`: Optional `PcPRequest` for tool execution.

---

## P2PResponse

Response object containing execution results or rejection information.
- `output`: Successful execution result as `MultimodalContent`.
- `rejection`: `P2PRejection` if the request failed validation or execution.

### P2PRejection
- `errorType`: `auth`, `prompt`, `json`, `content`, `transport`, or `none`.
- `reason`: Human-readable error message.

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

### Authentication Mechanism
The `authMechanism` lambda is used to validate the `authBody` of an incoming request.

---

## P2PRegistry

Singleton registry managing agent registration and request routing.

### Public Properties
- `globalAuthMechanism`: A `(suspend (authBody: String) -> Boolean)?` property for transport-level authentication of incoming HTTP, Stdio, and Memory requests.

### Public Functions
- `register(agent: P2PInterface)`: Registers a local agent.
- `executeP2pRequest(request: P2PRequest)`: Processes an incoming request.
- `sendP2pRequest(request: AgentRequest)`: Dispatches a request to a local or remote agent based on the registry.

---

## P2PHost

Hosts for standalone P2P execution.

### `P2PStdioHost` (Object)
Handles P2P requests over standard streams.
- `runOnce()`: Processes one request and exits.
- `runLoop()`: Processes requests in a loop until "exit".

### HTTP Hosting
Handled via the Ktor module in `Application.kt`. Responds to `POST /p2p` with a `P2PRequest` body.
