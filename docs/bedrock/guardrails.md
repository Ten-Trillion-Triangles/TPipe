# AWS Bedrock Guardrails

## Table of Contents
- [Overview](#overview)
- [IAM Requirements](#iam-requirements)
- [Configuration Methods](#configuration-methods)
- [Standalone Evaluation](#standalone-evaluation)
- [Integration with Pipes](#integration-with-pipes)
- [Integration with Pipelines](#integration-with-pipelines)
- [Trace Debugging](#trace-debugging)
- [Best Practices](#best-practices)

## Overview

AWS Bedrock Guardrails provide content moderation and safety controls for AI applications. Guardrails evaluate both user inputs and model responses against configured policies including:

- **Content Filters**: Block harmful content categories (hate, insults, sexual, violence, misconduct)
- **Denied Topics**: Prevent discussions of specific topics
- **Sensitive Information Filters**: Detect and redact PII (emails, phone numbers, addresses, etc.)
- **Word Filters**: Block or redact custom word lists
- **Contextual Grounding**: Validate responses against source documents

TPipe integrates Guardrails at the pipe level, automatically applying policies to all model interactions or enabling standalone content evaluation at any point in your application flow.

## IAM Requirements

### Required Permissions

Your AWS IAM role or user must have the `bedrock:ApplyGuardrail` permission:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "bedrock:InvokeModel",
        "bedrock:InvokeModelWithResponseStream",
        "bedrock:ApplyGuardrail"
      ],
      "Resource": [
        "arn:aws:bedrock:*::foundation-model/*",
        "arn:aws:bedrock:*:*:guardrail/*"
      ]
    }
  ]
}
```

### Creating a Guardrail

Before using Guardrails in TPipe, create and configure a guardrail in the AWS Bedrock console:

1. Navigate to AWS Bedrock Console → Guardrails
2. Click "Create guardrail"
3. Configure content filters, denied topics, and sensitive information filters
4. Note the **Guardrail ID** and **Version** (or use "DRAFT" for testing)

## Configuration Methods

### Basic Configuration

Configure a guardrail using its identifier and version:

```kotlin
import bedrockPipe.BedrockPipe
import kotlinx.coroutines.runBlocking

val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail(
        identifier = "abc123def456",  // Your guardrail ID
        version = "1"                  // Version number or "DRAFT"
    )
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")

runBlocking {
    pipe.init()
    
    // Guardrail automatically applied to all interactions
    val result = pipe.execute("Tell me about quantum computing")
    println(result.text)
}
```

### Using Guardrail ARN

You can also use the full ARN as the identifier:

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail(
        identifier = "arn:aws:bedrock:us-east-1:123456789012:guardrail/abc123def456",
        version = "1"
    )
```

### Enabling Trace for Debugging

Enable guardrail tracing to see which policies triggered:

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail(
        identifier = "abc123def456",
        version = "DRAFT",
        enableTrace = true  // Enable basic tracing
    )
```

### Full Trace Mode

For comprehensive debugging including non-detected content:

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail("abc123def456", "DRAFT")
    .enableFullGuardrailTrace()  // Enhanced debugging
```

### Clearing Guardrail Configuration

Remove guardrail configuration to disable content filtering:

```kotlin
pipe.clearGuardrail()
```

## Standalone Evaluation

Evaluate content against guardrails without invoking foundation models. This is useful for:
- Pre-validating user input before processing
- Checking content at multiple pipeline stages
- Implementing custom content moderation workflows

### Basic Standalone Evaluation

```kotlin
import bedrockPipe.BedrockPipe
import kotlinx.coroutines.runBlocking

val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setGuardrail("abc123def456", "1")

runBlocking {
    pipe.init()
    
    val userInput = "User's message here"
    
    // Evaluate input before processing
    val assessment = pipe.applyGuardrailStandalone(
        content = userInput,
        source = "INPUT"
    )
    
    when (assessment?.action) {
        "GUARDRAIL_INTERVENED" -> {
            println("Content blocked by guardrail")
            // Handle blocked content
        }
        "NONE" -> {
            println("Content passed guardrail checks")
            // Proceed with processing
        }
    }
}
```

### Evaluating Model Output

Check model responses before returning to users:

```kotlin
val modelResponse = pipe.execute("Generate a story").text

// Evaluate output
val assessment = pipe.applyGuardrailStandalone(
    content = modelResponse,
    source = "OUTPUT"
)

if (assessment?.action == "GUARDRAIL_INTERVENED") {
    println("Model output blocked by guardrail")
    // Regenerate or use fallback response
}
```

### Full Assessment Details

Get detailed assessment information for debugging:

```kotlin
val assessment = pipe.applyGuardrailStandalone(
    content = userInput,
    source = "INPUT",
    fullOutput = true  // Include non-detected content
)

// Access detailed assessments
assessment?.assessments?.forEach { assessment ->
    println("Policy: ${assessment}")
}
```

## Integration with Pipes

### Automatic Input/Output Filtering

Guardrails automatically filter both user inputs and model outputs:

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail("abc123def456", "1")
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")

runBlocking {
    pipe.init()
    
    // Both input and output automatically filtered
    val result = pipe.execute("User message")
    
    // If guardrail intervenes, execution may fail or return filtered content
    println(result.text)
}
```

### Combining with Validation Functions

Use guardrails alongside custom validation:

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail("abc123def456", "1")
    .setValidatorFunction { content ->
        // Custom validation logic
        val isValid = content.text.length > 10
        
        if (!isValid) {
            println("Custom validation failed")
        }
        
        isValid
    }
```

### Multi-Stage Content Validation

Validate at multiple stages using standalone evaluation:

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail("abc123def456", "1")
    .setPreValidationFunction { contextWindow, content ->
        // Validate context before merging
        val contextText = contextWindow.toString()
        val assessment = pipe.applyGuardrailStandalone(contextText, "INPUT")
        
        if (assessment?.action == "GUARDRAIL_INTERVENED") {
            println("Context blocked by guardrail")
            contextWindow.clear()  // Clear problematic context
        }
        
        contextWindow
    }
    .setTransformationFunction { content ->
        // Validate output after generation
        val assessment = pipe.applyGuardrailStandalone(content.text, "OUTPUT")
        
        if (assessment?.action == "GUARDRAIL_INTERVENED") {
            content.text = "I cannot provide that information."
        }
        
        content
    }
```

## Integration with Pipelines

### Pipeline-Wide Guardrails

Apply guardrails to all pipes in a pipeline:

```kotlin
import com.TTT.Pipeline.Pipeline

val analysisPipe = BedrockPipe()
    .setPipeName("Analyzer")
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail("abc123def456", "1")
    .setSystemPrompt("Analyze the input.")

val summaryPipe = BedrockPipe()
    .setPipeName("Summarizer")
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail("abc123def456", "1")
    .setSystemPrompt("Summarize the analysis.")

val pipeline = Pipeline()
    .add(analysisPipe)
    .add(summaryPipe)

runBlocking {
    pipeline.init()
    val result = pipeline.execute("User input")
    println(result.text)
}
```

### Different Guardrails per Stage

Use different guardrail configurations for different pipeline stages:

```kotlin
val inputPipe = BedrockPipe()
    .setPipeName("InputValidator")
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail("strict-input-guardrail", "1")  // Strict input filtering

val processingPipe = BedrockPipe()
    .setPipeName("Processor")
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail("moderate-guardrail", "1")  // Moderate filtering

val outputPipe = BedrockPipe()
    .setPipeName("OutputValidator")
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail("strict-output-guardrail", "1")  // Strict output filtering

val pipeline = Pipeline()
    .add(inputPipe)
    .add(processingPipe)
    .add(outputPipe)
```

### Conditional Guardrail Application

Apply guardrails conditionally based on content or context:

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setPreInvokeFunction { content ->
        // Apply guardrail only for sensitive topics
        if (content.text.contains("sensitive", ignoreCase = true)) {
            setGuardrail("strict-guardrail", "1")
        } else {
            clearGuardrail()
        }
        
        false  // Continue execution
    }
```

## Trace Debugging

### Enabling Tracing

Combine guardrail tracing with TPipe's tracing system:

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail("abc123def456", "DRAFT", enableTrace = true)
    .enableTracing()  // Enable TPipe tracing

runBlocking {
    pipe.init()
    val result = pipe.execute("Test input")
    
    // View trace report
    val traceReport = pipe.getTraceReport()
    println(traceReport)
}
```

### Full Trace Mode

Enable comprehensive guardrail debugging:

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail("abc123def456", "DRAFT")
    .enableFullGuardrailTrace()  // Enhanced debugging
    .enableTracing()

// Full trace includes:
// - Content filters (detected and non-detected)
// - Denied topics (detected and non-detected)
// - PII detection results
// - Contextual grounding assessments
```

### Analyzing Guardrail Interventions

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail("abc123def456", "1", enableTrace = true)
    .setExceptionFunction { content, exception ->
        println("Guardrail intervention detected:")
        println("Content: ${content.text}")
        println("Exception: ${exception.message}")
        
        // Log for analysis
        logGuardrailIntervention(content, exception)
    }
```

## Best Practices

### 1. Use Versioned Guardrails in Production

```kotlin
// Development: Use DRAFT for testing
val devPipe = BedrockPipe()
    .setGuardrail("abc123def456", "DRAFT")

// Production: Use specific versions
val prodPipe = BedrockPipe()
    .setGuardrail("abc123def456", "1")
```

### 2. Validate Input Before Processing

```kotlin
runBlocking {
    pipe.init()
    
    // Pre-validate user input
    val assessment = pipe.applyGuardrailStandalone(userInput, "INPUT")
    
    if (assessment?.action == "GUARDRAIL_INTERVENED") {
        return@runBlocking "Your message contains content that violates our policies."
    }
    
    // Proceed with processing
    val result = pipe.execute(userInput)
}
```

### 3. Handle Guardrail Interventions Gracefully

```kotlin
val pipe = BedrockPipe()
    .setGuardrail("abc123def456", "1")
    .setOnFailure { original, failed ->
        // Check if failure was due to guardrail
        val assessment = pipe.applyGuardrailStandalone(original.text, "INPUT")
        
        if (assessment?.action == "GUARDRAIL_INTERVENED") {
            // Return user-friendly message
            MultimodalContent("I cannot process that request due to content policy restrictions.")
        } else {
            // Other failure - retry or handle differently
            failed
        }
    }
```

### 4. Use Different Guardrails for Different Use Cases

```kotlin
// Strict guardrail for public-facing autonomous system
val publicPipe = BedrockPipe()
    .setGuardrail("strict-public-guardrail", "1")

// Moderate guardrail for internal tools
val internalPipe = BedrockPipe()
    .setGuardrail("moderate-internal-guardrail", "1")

// Minimal guardrail for content analysis
val analysisPipe = BedrockPipe()
    .setGuardrail("minimal-analysis-guardrail", "1")
```

### 5. Monitor Guardrail Performance

```kotlin
val pipe = BedrockPipe()
    .setGuardrail("abc123def456", "1", enableTrace = true)
    .enableTracing()

runBlocking {
    pipe.init()
    
    val startTime = System.currentTimeMillis()
    val result = pipe.execute(userInput)
    val duration = System.currentTimeMillis() - startTime
    
    // Log metrics
    logMetrics(
        guardrailId = "abc123def456",
        duration = duration,
        intervened = result.text.isEmpty()
    )
}
```

### 6. Test Guardrail Configurations

```kotlin
suspend fun testGuardrailConfiguration(guardrailId: String, version: String) {
    val pipe = BedrockPipe()
        .setRegion("us-east-1")
        .setGuardrail(guardrailId, version)
    
    pipe.init()
    
    val testCases = listOf(
        "Normal content",
        "Potentially harmful content",
        "PII: john.doe@example.com",
        "Denied topic content"
    )
    
    testCases.forEach { testCase ->
        val assessment = pipe.applyGuardrailStandalone(testCase, "INPUT")
        println("Test: $testCase")
        println("Action: ${assessment?.action}")
        println("---")
    }
}
```

### 7. Combine with Context Window Filtering

```kotlin
val pipe = BedrockPipe()
    .setGuardrail("abc123def456", "1")
    .pullGlobalContext()
    .setPageKey("user-context")
    .setPreValidationFunction { contextWindow, content ->
        // Validate context content
        val contextText = contextWindow.toString()
        val assessment = pipe.applyGuardrailStandalone(contextText, "INPUT")
        
        if (assessment?.action == "GUARDRAIL_INTERVENED") {
            // Remove problematic context entries
            contextWindow.contextElements.clear()
        }
        
        contextWindow
    }
```

### 8. Use Standalone Evaluation for Custom Workflows

```kotlin
suspend fun moderateUserContent(content: String, pipe: BedrockPipe): Boolean {
    val assessment = pipe.applyGuardrailStandalone(content, "INPUT")
    
    return when (assessment?.action) {
        "GUARDRAIL_INTERVENED" -> {
            // Log intervention
            logContentViolation(content, assessment)
            false
        }
        "NONE" -> true
        else -> false
    }
}

// Use in custom workflow
runBlocking {
    pipe.init()
    
    if (moderateUserContent(userInput, pipe)) {
        val result = pipe.execute(userInput)
        println(result.text)
    } else {
        println("Content moderation failed")
    }
}
```

---

AWS Bedrock Guardrails provide robust content safety controls for TPipe applications. By combining automatic filtering with standalone evaluation, you can build secure, policy-compliant AI systems that protect users and maintain content quality standards.
