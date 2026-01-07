# AWS Bedrock Guardrails Implementation Plan

## Overview & Purpose
This plan implements comprehensive AWS Bedrock Guardrails support in TPipe-Bedrock. Guardrails provide configurable safeguards to detect and filter harmful content, denied topics, sensitive information (PII), and custom word filters. This implementation adds both inline guardrails (during model inference) and standalone guardrail evaluation capabilities.

## Scope & Guiding Rules
- **Module discipline:** Keep all implementation under `TPipe-Bedrock/src/main/kotlin/bedrockPipe/`, tests under `TPipe-Bedrock/src/test/kotlin/bedrockPipe/`
- **Additive only:** Do not modify existing functionality, only add new guardrail capabilities
- **TPipe standards:** Follow all formatting rules from `.amazonq/rules/TPipe-Formatting.md`
- **Documentation:** Update `docs/bedrock/` with comprehensive guardrail usage examples
- **Comprehensive support:** Implement both inline and standalone guardrail APIs

## Goals & Success Criteria
1. **Inline Guardrails:** Add guardrail configuration to InvokeModel and Converse API calls
2. **Standalone Evaluation:** Implement ApplyGuardrail API for independent content evaluation
3. **Configuration Management:** Provide fluent API for guardrail configuration
4. **Response Processing:** Handle guardrail interventions, masking, and blocking
5. **Comprehensive Testing:** Full unit test coverage with mocked AWS responses
6. **Documentation:** Clear examples and best practices for guardrail usage

## Technical Research Summary

### AWS Bedrock Guardrails Features
Based on AWS documentation research:

**Guardrail Policies:**
1. **Content Filters** - Detect harmful content (Hate, Insults, Sexual, Violence, Misconduct, Prompt Attack)
2. **Denied Topics** - Block specific topics defined by the user
3. **Sensitive Information Filters** - Detect/mask PII (SSN, addresses, names, etc.) and custom regex
4. **Word Filters** - Block specific words, phrases, and profanity (exact match)
5. **Image Content Filters** - Filter harmful visual content
6. **Contextual Grounding** - Ensure responses are grounded and relevant

**API Integration Points:**
- **InvokeModel/Converse:** Inline guardrails during model inference
- **ApplyGuardrail:** Standalone content evaluation without model inference

**Response Actions:**
- **NONE** - No guardrail intervention
- **GUARDRAIL_INTERVENED** - Content blocked or masked

## Current TPipe-Bedrock Status Analysis
**Existing Guardrail Support:**
- ✅ GuardrailStreamConfiguration import exists
- ✅ Basic guardrail config copying for streaming (lines 1823-1828)
- ✅ Guardrail trace parsing for reasoning extraction (lines 3529-3532)
- ❌ **MISSING:** Public API for setting guardrail configuration
- ❌ **MISSING:** ApplyGuardrail standalone API implementation
- ❌ **MISSING:** Guardrail response processing and intervention handling
- ❌ **MISSING:** Comprehensive guardrail configuration options

## Implementation Tasks

### Task 1 - Add Required AWS SDK Imports
**File:** `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`

Add missing guardrail imports:
```kotlin
import aws.sdk.kotlin.services.bedrockruntime.model.GuardrailConfiguration
import aws.sdk.kotlin.services.bedrockruntime.model.ApplyGuardrailRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ApplyGuardrailResponse
import aws.sdk.kotlin.services.bedrockruntime.model.GuardrailContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.GuardrailTextBlock
import aws.sdk.kotlin.services.bedrockruntime.model.GuardrailContentSource
import aws.sdk.kotlin.services.bedrockruntime.model.GuardrailTrace
```

### Task 2 - Add Guardrail Configuration Properties
**File:** `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`

Add private properties for guardrail configuration:
```kotlin
/**
 * Guardrail identifier for content filtering and safety policies.
 * Can be guardrail ID or ARN.
 */
private var guardrailIdentifier: String = ""

/**
 * Version of the guardrail to use. Can be version number or "DRAFT".
 */
private var guardrailVersion: String = ""

/**
 * Guardrail trace setting for debugging guardrail decisions.
 * Values: "enabled", "disabled", "enabled_full"
 */
private var guardrailTrace: String = "disabled"
```

### Task 3 - Implement Guardrail Configuration Methods
**File:** `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`

Add fluent API methods following TPipe patterns:

```kotlin
/**
 * Sets the guardrail to use for content filtering and safety policies.
 * Guardrails evaluate both user inputs and model responses against configured policies
 * including content filters, denied topics, sensitive information filters, and word filters.
 * 
 * @param identifier The guardrail identifier (ID or ARN)
 * @param version The guardrail version to use (version number or "DRAFT")
 * @param enableTrace Enable guardrail tracing for debugging (default: false)
 * @return This BedrockPipe instance for method chaining
 * 
 * @see clearGuardrail to remove guardrail configuration
 * @see applyGuardrailStandalone for standalone content evaluation
 * 
 * @since Requires bedrock:ApplyGuardrail IAM permission
 */
fun setGuardrail(identifier: String, version: String = "DRAFT", enableTrace: Boolean = false): BedrockPipe
{
    this.guardrailIdentifier = identifier
    this.guardrailVersion = version
    this.guardrailTrace = if (enableTrace) "enabled" else "disabled"
    return this
}

/**
 * Enables full guardrail tracing which includes both detected and non-detected content
 * for enhanced debugging. Only applies to content filters, denied topics, sensitive
 * information PII detection, and contextual grounding policies.
 * 
 * @return This BedrockPipe instance for method chaining
 */
fun enableFullGuardrailTrace(): BedrockPipe
{
    this.guardrailTrace = "enabled_full"
    return this
}

/**
 * Clears the guardrail configuration, disabling content filtering.
 * 
 * @return This BedrockPipe instance for method chaining
 */
fun clearGuardrail(): BedrockPipe
{
    this.guardrailIdentifier = ""
    this.guardrailVersion = ""
    this.guardrailTrace = "disabled"
    return this
}
```

### Task 4 - Implement Standalone ApplyGuardrail API
**File:** `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`

Add standalone guardrail evaluation method:

```kotlin
/**
 * Evaluates content against the configured guardrail without invoking foundation models.
 * This allows independent content validation at any stage of your application flow.
 * 
 * @param content The text content to evaluate
 * @param source Whether content is from user input ("INPUT") or model output ("OUTPUT")
 * @param fullOutput Return full assessment including non-detected content for debugging
 * @return ApplyGuardrailResponse containing action taken and detailed assessments
 * 
 * @throws IllegalStateException if guardrail is not configured
 * @throws IllegalArgumentException if client is not initialized
 * 
 * @see setGuardrail to configure guardrail before calling this method
 * 
 * @since Requires bedrock:ApplyGuardrail IAM permission
 */
suspend fun applyGuardrailStandalone(
    content: String, 
    source: String = "INPUT",
    fullOutput: Boolean = false
): ApplyGuardrailResponse?
{
    // Validate guardrail configuration
    if (guardrailIdentifier.isEmpty()) {
        trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
              metadata = mapOf("error" to "Guardrail not configured"))
        throw IllegalStateException("Guardrail must be configured before calling applyGuardrailStandalone. Use setGuardrail() first.")
    }
    
    // Validate client initialization
    val client = bedrockClient ?: run {
        trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
              metadata = mapOf("error" to "Client not initialized"))
        throw IllegalArgumentException("BedrockPipe must be initialized before applying guardrails. Call init() first.")
    }
    
    trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
          metadata = mapOf(
              "method" to "applyGuardrailStandalone",
              "guardrailId" to guardrailIdentifier,
              "guardrailVersion" to guardrailVersion,
              "source" to source,
              "contentLength" to content.length,
              "fullOutput" to fullOutput
          ))
    
    return try {
        val request = ApplyGuardrailRequest {
            this.guardrailIdentifier = this@BedrockPipe.guardrailIdentifier
            this.guardrailVersion = this@BedrockPipe.guardrailVersion
            this.source = GuardrailContentSource.fromValue(source)
            this.content = listOf(
                GuardrailContentBlock {
                    text = GuardrailTextBlock {
                        text = content
                    }
                }
            )
            if (fullOutput) {
                this.outputScope = "FULL"
            }
        }
        
        val response = client.applyGuardrail(request)
        
        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              metadata = mapOf(
                  "action" to response.action.toString(),
                  "outputCount" to response.outputs.size,
                  "hasAssessments" to response.assessments.isNotEmpty()
              ))
        
        response
        
    } catch (e: Exception) {
        trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
              metadata = mapOf("error" to e.message), error = e)
        null
    }
}
```

### Task 5 - Update InvokeModel Integration
**File:** `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`

Modify existing InvokeModel calls to include guardrail configuration:

```kotlin
// In generateText() method, update InvokeModelRequest building
val invokeRequest = InvokeModelRequest {
    this.modelId = targetModelId
    this.body = requestJson.encodeToByteArray()
    
    // Add guardrail configuration if set
    if (guardrailIdentifier.isNotEmpty()) {
        this.guardrailIdentifier = this@BedrockPipe.guardrailIdentifier
        this.guardrailVersion = this@BedrockPipe.guardrailVersion
        this.trace = GuardrailTrace.fromValue(guardrailTrace)
    }
}
```

### Task 6 - Update Converse API Integration
**File:** `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`

Modify existing Converse calls to include guardrail configuration:

```kotlin
// In generateWithConverseApi() method, update ConverseRequest building
val converseRequest = ConverseRequest {
    this.modelId = modelId
    this.messages = messages
    this.system = systemMessages
    this.inferenceConfig = inferenceConfiguration
    
    // Add guardrail configuration if set
    if (guardrailIdentifier.isNotEmpty()) {
        this.guardrailConfig = GuardrailConfiguration {
            this.guardrailIdentifier = this@BedrockPipe.guardrailIdentifier
            this.guardrailVersion = this@BedrockPipe.guardrailVersion
            this.trace = guardrailTrace
        }
    }
}
```

### Task 7 - Add Guardrail Response Processing
**File:** `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`

Add helper methods to process guardrail responses:

```kotlin
/**
 * Processes guardrail response and handles interventions.
 * Returns processed content or blocked message based on guardrail action.
 */
private fun processGuardrailResponse(originalContent: String, response: Any?): String
{
    // Implementation to handle guardrail interventions
    // - Extract blocked messages
    // - Apply content masking
    // - Log guardrail actions
    // - Return appropriate content
}

/**
 * Extracts guardrail assessment information for tracing and debugging.
 */
private fun extractGuardrailAssessments(response: Any?): Map<String, Any>
{
    // Implementation to extract assessment details
    // - Content policy violations
    // - Topic policy blocks
    // - Sensitive information detections
    // - Word filter matches
}
```

### Task 8 - Add Comprehensive Error Handling
**Location:** Throughout guardrail methods

Handle all AWS guardrail-specific errors:
- AccessDeniedException → Log permission error
- ResourceNotFoundException → Log guardrail not found
- ValidationException → Log configuration error
- ThrottlingException → Log rate limiting
- ServiceUnavailableException → Log service unavailable

### Task 9 - Create Comprehensive Unit Tests
**File:** `TPipe-Bedrock/src/test/kotlin/bedrockPipe/GuardrailsTest.kt`

**Test Coverage:**
1. **Configuration Tests**
   - setGuardrail() method chaining
   - clearGuardrail() functionality
   - enableFullGuardrailTrace() setting

2. **Standalone ApplyGuardrail Tests**
   - Successful content evaluation
   - Content blocking scenarios
   - Content masking scenarios
   - Error handling for all AWS error types
   - Full output mode testing

3. **Inline Guardrail Tests**
   - InvokeModel with guardrails
   - Converse API with guardrails
   - Streaming with guardrails
   - Guardrail intervention handling

4. **Response Processing Tests**
   - Blocked content handling
   - Masked content processing
   - Assessment extraction
   - Trace information parsing

**Test Structure:**
```kotlin
class GuardrailsTest 
{
    @Test
    fun testGuardrailConfiguration() = runBlocking {
        val pipe = BedrockPipe()
            .setGuardrail("test-guardrail-id", "1", enableTrace = true)
        
        // Verify configuration is set correctly
    }
    
    @Test
    fun testApplyGuardrailStandalone() = runBlocking {
        // Mock ApplyGuardrailResponse
        // Test successful evaluation
        // Verify response processing
    }
    
    @Test
    fun testInlineGuardrailsWithInvokeModel() = runBlocking {
        // Test guardrail integration with InvokeModel
        // Verify guardrail configuration is passed
    }
    
    @Test
    fun testGuardrailErrorHandling() = runBlocking {
        // Test all AWS error scenarios
        // Verify proper error handling and tracing
    }
    
    // Additional test methods...
}
```

### Task 10 - Update Documentation
**File:** `docs/bedrock/getting-started.md`

Add comprehensive guardrails section:

```markdown
## Using AWS Bedrock Guardrails

TPipe-Bedrock provides full support for AWS Bedrock Guardrails to implement safeguards for your generative AI applications.

### Inline Guardrails (During Model Inference)

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setRegion("us-east-1")
    .setGuardrail("my-guardrail-id", "1", enableTrace = true)

pipe.init()

// Guardrails will automatically evaluate input and output
val result = pipe.generateText("Your prompt here")
```

### Standalone Content Evaluation

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setGuardrail("my-guardrail-id", "DRAFT")

pipe.init()

// Evaluate content without model inference
val response = pipe.applyGuardrailStandalone(
    content = "Content to evaluate",
    source = "INPUT",
    fullOutput = true
)

when (response?.action) {
    "NONE" -> println("Content passed all guardrail policies")
    "GUARDRAIL_INTERVENED" -> {
        println("Guardrail intervened")
        response.outputs.forEach { output ->
            println("Processed content: ${output.text}")
        }
    }
}
```

### Guardrail Policies

AWS Bedrock Guardrails support multiple policy types:

1. **Content Filters** - Detect harmful content (hate, violence, sexual content, etc.)
2. **Denied Topics** - Block specific topics you define
3. **Sensitive Information** - Detect and mask PII (names, addresses, SSN, etc.)
4. **Word Filters** - Block specific words and phrases
5. **Contextual Grounding** - Ensure responses are relevant and grounded

### Advanced Configuration

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail("arn:aws:bedrock:us-east-1:123456789012:guardrail/abc123", "2")
    .enableFullGuardrailTrace() // Enhanced debugging

// Clear guardrail configuration
pipe.clearGuardrail()
```
```

**File:** `docs/api/pipe.md` (or create `docs/api/bedrock-pipe.md`)

Add API reference:
```markdown
### Guardrail Methods

#### setGuardrail()
Configures guardrail for content filtering and safety policies.

**Parameters:**
- `identifier: String` - Guardrail ID or ARN
- `version: String = "DRAFT"` - Guardrail version
- `enableTrace: Boolean = false` - Enable tracing

**Returns:** `BedrockPipe` - For method chaining

#### applyGuardrailStandalone()
Evaluates content against guardrail without model inference.

**Parameters:**
- `content: String` - Content to evaluate
- `source: String = "INPUT"` - Content source ("INPUT" or "OUTPUT")
- `fullOutput: Boolean = false` - Return full assessment

**Returns:** `ApplyGuardrailResponse?` - Guardrail evaluation result

#### clearGuardrail()
Removes guardrail configuration.

**Returns:** `BedrockPipe` - For method chaining
```

### Task 11 - Add IAM Permission Documentation
**File:** `docs/bedrock/getting-started.md`

Add IAM requirements section:
```markdown
## Required IAM Permissions for Guardrails

To use AWS Bedrock Guardrails, ensure your AWS credentials have:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "bedrock:ApplyGuardrail",
                "bedrock:InvokeModel",
                "bedrock:Converse",
                "bedrock:GetGuardrail"
            ],
            "Resource": "*"
        }
    ]
}
```

For specific guardrail enforcement:
```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "bedrock:InvokeModel",
                "bedrock:Converse"
            ],
            "Resource": "*",
            "Condition": {
                "StringEquals": {
                    "bedrock:GuardrailIdentifier": "your-guardrail-id"
                }
            }
        }
    ]
}
```
```

### Task 12 - Code Formatting and Validation
1. **Apply TPipe formatting rules** from `.amazonq/rules/TPipe-Formatting.md`
2. **Run build validation:** `./gradlew build`
3. **Run tests:** `./gradlew :TPipe-Bedrock:test`
4. **Verify no existing functionality broken**

## Guardrail Policy Types Reference

| Policy Type | Description | Actions |
|-------------|-------------|---------|
| Content Filters | Detect harmful content (hate, violence, sexual, misconduct, prompt attacks) | Block |
| Denied Topics | Block user-defined topics | Block |
| Sensitive Information | Detect PII (names, SSN, addresses, etc.) and custom regex | Block or Mask |
| Word Filters | Block specific words, phrases, profanity | Block |
| Image Content | Filter harmful visual content | Block |
| Contextual Grounding | Ensure responses are grounded and relevant | Block |

## Error Handling Strategy

1. **Configuration Validation:** Check guardrail settings before API calls
2. **AWS Error Handling:** Handle all AWS-specific error types gracefully
3. **Fallback Behavior:** Continue operation without guardrails on non-critical errors
4. **Comprehensive Logging:** Log all guardrail actions and interventions
5. **Tracing Integration:** Emit detailed trace events for debugging

## Testing Strategy

1. **Unit Tests:** Mock all AWS API calls for consistent testing
2. **Integration Tests:** Optional real AWS API tests with test guardrails
3. **Error Simulation:** Test all AWS error response types
4. **Policy Testing:** Test each guardrail policy type individually
5. **Response Processing:** Verify correct handling of all response types

## Rollout Plan

1. **Phase 1:** Implement basic guardrail configuration and inline integration
2. **Phase 2:** Add ApplyGuardrail standalone API support
3. **Phase 3:** Implement comprehensive response processing and error handling
4. **Phase 4:** Complete documentation, examples, and testing
5. **Phase 5:** Final validation and code review

## Validation Checklist

- [ ] Guardrail configuration methods implemented with fluent API
- [ ] Inline guardrails integrated with InvokeModel and Converse APIs
- [ ] ApplyGuardrail standalone API implemented
- [ ] Comprehensive error handling for all AWS error types
- [ ] Response processing for blocked and masked content
- [ ] Complete unit test coverage for all guardrail functionality
- [ ] Documentation updated with examples and IAM requirements
- [ ] Code follows TPipe formatting standards
- [ ] Build passes without errors
- [ ] Integration with existing BedrockPipe patterns maintained

## Dependencies & Risks

**Dependencies:**
- AWS SDK Kotlin BedrockRuntime client with guardrail support
- Existing BedrockPipe request/response handling
- TPipe tracing system

**Risks:**
- Guardrail policy changes (mitigated by following AWS documentation)
- Performance impact of guardrail evaluation (documented in usage guidelines)
- IAM permission complexity (mitigated by clear documentation)

**Mitigation Strategies:**
- Follow AWS official documentation exactly
- Implement comprehensive error handling
- Provide clear configuration examples
- Test with multiple guardrail configurations

## Success Metrics

1. **Functional:** All guardrail policies work correctly with proper interventions
2. **Integration:** Seamless integration with existing InvokeModel and Converse APIs
3. **Standalone:** ApplyGuardrail API provides independent content evaluation
4. **Reliability:** Handles all error conditions gracefully
5. **Documentation:** Clear usage examples and best practices
6. **Testing:** 100% unit test coverage for new functionality

This implementation provides comprehensive AWS Bedrock Guardrails support while maintaining all existing TPipe-Bedrock functionality and following established TPipe design patterns.
