# P2P Requirements and Validation

P2PRequirements defines validation rules that run before agent execution. Configure these on your P2PInterface implementation.

## Configuration

```kotlin
val requirements = P2PRequirements(
    requireConverseInput = false,        // Require Converse format
    allowAgentDuplication = true,        // Allow pipeline forking
    allowCustomContext = true,           // Allow custom context injection
    allowCustomJson = true,              // Allow schema overrides
    allowExternalConnections = true,     // Allow cross-process calls
    acceptedContent = mutableListOf(     // Allowed MIME types
        SupportedContentTypes.text,
        SupportedContentTypes.image
    ),
    maxTokens = 8192,                   // Token limit
    tokenCountingSettings = mySettings,  // Truncation config
    maxBinarySize = 20 * 1024 * 1024,  // 20MB binary limit
    authMechanism = { token ->           // Auth validation
        validateToken(token)
    }
)
```

## Validation Order

1. **Converse format** - Checks for ConverseData/ConverseHistory JSON if required
2. **External access** - Blocks external calls if disabled
3. **Duplication policy** - Rejects schema/context overrides if not allowed
4. **Token limits** - Counts tokens using provided settings
5. **Content types** - Validates binary content MIME types
6. **Authentication** - Runs auth hook if present

## Common Patterns

### Secure Agent
```kotlin
P2PRequirements(
    allowExternalConnections = false,
    requireConverseInput = true,
    authMechanism = { token -> verifyHMAC(token) }
)
```

### Flexible Agent
```kotlin
P2PRequirements(
    allowAgentDuplication = true,
    allowCustomContext = true,
    allowCustomJson = true,
    maxTokens = 32000
)
```

### Content-Restricted Agent
```kotlin
P2PRequirements(
    acceptedContent = mutableListOf(SupportedContentTypes.text),
    maxBinarySize = 1024 * 1024  // 1MB limit
)
```

## Error Handling

Failed validation returns `P2PRejection` with:
- `errorType`: `auth`, `prompt`, `json`, `content`, or `transport`
- `reason`: Human-readable error message

Check these fields when debugging agent calls or building retry logic.
