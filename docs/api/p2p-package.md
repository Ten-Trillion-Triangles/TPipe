# P2P Package API

## Table of Contents
- [Overview](#overview)
- [P2PDescriptor](#p2pdescriptor)
- [P2PRequest](#p2prequest)
- [P2PResponse](#p2presponse)
- [P2PRequirements](#p2prequirements)
- [P2PRegistry](#p2pregistry)
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
- **`agentName`**: Unique agent identifier
- **`agentDescription`**: Human-readable capability description
- **`agentSkills`**: Granular skill definitions for LLM understanding

**Communication:**
- **`transport`**: Connection method and addressing
- **`requiresAuth`**: Authentication requirement flag
- **`usesConverse`**: Conversation history protocol usage

**Capabilities:**
- **`allowsAgentDuplication`**: Pipeline copying for customization
- **`allowsCustomContext`**: External context injection
- **`allowsCustomAgentJson`**: Dynamic schema modification
- **`allowsExternalContext`**: TPipe ContextWindow acceptance

**Content Support:**
- **`supportedContentTypes`**: Accepted content types (text, image, video, etc.)
- **`contextWindowSize`**: Maximum token capacity
- **`inputPromptSchema`**: Required input format

**Privacy & Recording:**
- **`recordsInteractionContext`**: Context storage notification
- **`recordsPromptContent`**: Prompt recording notification

**Protocol Support:**
- **`contextProtocol`**: Supported context protocols (PCP, MCP, etc.)
- **`pcpDescriptor`**: PCP tool configuration

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

**Routing:**
- **`transport`**: Target agent address
- **`returnAddress`**: Response routing information

**Content:**
- **`prompt`**: Multimodal request content
- **`context`**: Optional context injection

**Customization:**
- `inputSchema` / `outputSchema` - Dynamic schema overrides
- **`customContextDescriptions`**: Per-pipe context instructions
- **`contextExplanationMessage`**: Context usage instructions

**Security:**
- **`authBody`**: Authentication credentials

**Tools:**
- **`pcpRequest`**: PCP tool execution requests

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

### Public Properties

**`output`** - Successful execution result as MultimodalContent
**`rejection`** - Failure information if request was rejected

### Supporting Classes

**P2PRejection:**
```kotlin
@Serializable
data class P2PRejection(
    var errorType: P2PError = P2PError.none,
    var reason: String = ""
)
```

**P2PError Enum:**
- **`auth`**: Authentication failure
- **`prompt`**: Prompt format/content issues
- **`json`**: Schema validation failure
- **`content`**: Unsupported content type
- **`transport`**: Connection/routing failure
- **`none`**: No error

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

### Key Properties

**Input Requirements:**
- **`requireConverseInput`**: Mandate conversation format
- **`acceptedContent`**: Allowed content types
- `maxTokens` / `maxBinarySize` - Size limits

**Security:**
- **`allowExternalConnections`**: External agent access
- **`authMechanism`**: Custom authentication function

**Customization:**
- **`allowAgentDuplication`**: Pipeline copying permission
- `allowCustomContext` / `allowCustomJson` - Dynamic modification permissions

---

## P2PRegistry

Singleton registry managing agent registration, discovery, and request routing.

```kotlin
object P2PRegistry
```

### Public Properties

**`agentListMutex`** - Thread safety for agent registration
**`requestTemplates`** - Template storage for simplified request construction

### Public Functions

#### Agent Registration

**`register(agent: P2PInterface, transport: P2PTransport, descriptor: P2PDescriptor, requirements: P2PRequirements)`**
Registers agent with explicit configuration.

**`register(agent: P2PInterface)`**
Registers agent using stored interface configuration with automatic requirement inference.

**Behavior:** Auto-generates requirements from descriptor if not provided. Sets sensible defaults based on descriptor capabilities.

#### Agent Management

**`remove(transport: P2PTransport)`** / **`remove(agent: P2PInterface)`**
Removes agent from registry.

**`loadAgents(agents: List<P2PDescriptor>)`**
Loads external agent descriptors for client-side calls.

**Behavior:** Populates client agent list and request templates for simplified LLM-to-agent communication.

#### Agent Discovery

**`listGlobalAgents(): List<P2PDescriptor>`**
Returns agents allowing external connections.

**`listLocalAgents(container: Any): List<P2PDescriptor>`**
Returns agents local to specific container.

#### Request Processing

**`executeP2pRequest(request: P2PRequest): P2PResponse`**
Processes P2P requests with full validation and routing.

**Behavior:**
- Validates agent existence and requirements
- Performs security checks and content validation
- Routes to appropriate agent interface
- Returns structured response or rejection

**`sendP2pRequest(request: AgentRequest, httpAuthBody: String = "", p2pAuthBody: String = "", template: P2PRequest? = null): P2PResponse`**
Client-side request sending with template support.

**Behavior:**
- Resolves agent from simplified request
- Builds full P2P request from templates
- Handles both local and remote agent routing
- Supports authentication injection

#### Validation

**`checkAgentRequirements(request: P2PRequest, requirements: P2PRequirements, agent: P2PInterface): Pair<Boolean, P2PRejection?>`**
Comprehensive request validation against agent requirements.

**Behavior:**
- Validates conversation format requirements
- Checks content type compatibility
- Enforces token and binary size limits
- Validates authentication credentials
- Ensures duplication permissions for advanced features

---

## Supporting Classes

### CustomJsonSchema

Dynamic JSON schema container for request customization.

```kotlin
@Serializable
data class CustomJsonSchema(
    var schemaContainer: MutableMap<String, Pair<String, String>> = mutableMapOf()
)
```

**Public Functions:**
- **`add(pipeName: String, description: String, jsonObject: Any)`**: Adds schema for specific pipe
- **`newSchema(pipeName: String, description: String, jsonObject: Any): CustomJsonSchema?`**: Creates schema from object

### AgentRequest

Simplified request format for LLM-generated agent calls.

```kotlin
@Serializable
data class AgentRequest(
    var agentName: String = "",
    var promptSchema: InputSchema = InputSchema.plainText,
    var prompt: String = "",
    var content: String = "",
    var pcpRequest: PcPRequest = PcPRequest()
)
```

**Public Functions:**
- **`buildP2PRequest(template: P2PRequest? = null): P2PRequest`**: Builds full request from template
- **`buildRequestFromRegistry(templateRef: Any): P2PRequest`**: Uses registry template

### AgentDescriptor

LLM-friendly simplified agent descriptor.

```kotlin
@Serializable
data class AgentDescriptor(
    var agentName: String = "",
    var description: String = "",
    var skills: P2PSkills = P2PSkills("", ""),
    var inputMethod: InputSchema = InputSchema.plainText,
    var inputSchema: String = "",
    var tools: PcpContext? = null
)
```

**Companion Object:**
- **`buildFromDescriptor(descriptor: P2PDescriptor): AgentDescriptor`**: Creates simplified version

---

## Enums

### ContextProtocol
```kotlin
enum class ContextProtocol { pcp, mcp, provider, none }
```

### SupportedContentTypes
```kotlin
enum class SupportedContentTypes { text, image, video, audio, application, other, none }
```

### InputSchema
```kotlin
enum class InputSchema { plainText, json, xml, html, csv, tsv, yaml, markdown, bytes, other, none }
```

## Key Behaviors

### Agent Registration Model
P2PRegistry supports both explicit registration with full configuration and automatic registration with requirement inference from descriptors.

### Security Framework
Comprehensive validation system checks authentication, content types, size limits, and capability permissions before allowing agent execution.

### Template System
Request templates simplify LLM-to-agent communication by providing reusable configuration patterns and reducing token usage.

### Dual Agent Support
Registry handles both local TPipe agents and external remote agents with unified discovery and routing mechanisms.

### Thread Safety
Mutex protection ensures safe concurrent agent registration and request processing in multi-threaded environments.
