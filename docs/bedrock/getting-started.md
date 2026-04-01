# Getting Started with TPipe-Bedrock

## Table of Contents
- [What is TPipe-Bedrock?](#what-is-tpipe-bedrock)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [AWS Configuration](#aws-configuration)
- [Your First Bedrock Pipe](#your-first-bedrock-pipe)
- [Available Models](#available-models)
- [Basic Configuration](#basic-configuration)
- [Advanced Features](#advanced-features)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

TPipe-Bedrock is the AWS Bedrock integration module for TPipe, providing access to Amazon's managed AI models including Claude, Titan, and other foundation models through a unified TPipe interface.

## What is TPipe-Bedrock?

TPipe-Bedrock enables you to:
- **Access AWS Bedrock models** through TPipe's unified interface
- **Use Claude, Titan, and other foundation models** with TPipe's advanced features
- **Leverage AWS infrastructure** for scalable AI applications
- **Integrate with AWS services** seamlessly
- **Benefit from AWS security and compliance** features

## Prerequisites

### AWS Account Setup
- Active AWS account with Bedrock access
- Appropriate IAM permissions for Bedrock
- AWS CLI configured (optional but recommended)

### Model Access
- Request access to desired Bedrock models in AWS Console
- Models require explicit access requests before use
- Access approval may take time depending on the model

### Development Environment
- Kotlin/JVM development environment
- Gradle with Kotlin DSL build system (Maven and Groovy DSL not supported)
- Java 24 or higher (GraalVM CE 24 recommended for native compilation)
- Kotlin 1.9.0 or higher

## Installation

### Add TPipe-Bedrock Dependency

#### Gradle (Kotlin DSL)
```kotlin
dependencies {
    implementation("com.TTT:TPipe-Core:1.0.0")
    implementation("com.TTT:TPipe-Bedrock:1.0.0")
}
```

**Note**: TPipe only supports Gradle with Kotlin DSL. Maven and Groovy DSL are not supported.

## AWS Configuration

### Method 1: Programmatic Credentials (Recommended for Desktop/Electron Apps)
Set credentials programmatically, ideal for applications that fetch credentials from remote servers:

```kotlin
import env.bedrockEnv
import bedrockPipe.BedrockPipe

// Set credentials before creating pipe
bedrockEnv.setKeys(
    public = "AKIAIOSFODNN7EXAMPLE",
    secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
)

val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")

pipe.init() // Uses credentials from bedrockEnv
```

**Use Case:** Electron apps, desktop applications, or any scenario where credentials need to be fetched at runtime from a secure remote source.

### Method 2: AWS Credentials File
Create `~/.aws/credentials`:
```ini
[default]
aws_access_key_id = YOUR_ACCESS_KEY
aws_secret_access_key = YOUR_SECRET_KEY
region = us-east-1
```

### Method 3: Environment Variables
```bash
export AWS_ACCESS_KEY_ID=your_access_key
export AWS_SECRET_ACCESS_KEY=your_secret_key
export AWS_DEFAULT_REGION=us-east-1
```

### Method 4: IAM Roles (Recommended for Production)
Use IAM roles when running on AWS infrastructure:
- EC2 instance roles
- ECS task roles
- Lambda execution roles

**Credential Priority:** TPipe-Bedrock checks credentials in this order:
1. `bedrockEnv.setKeys()` (if set and not empty)
2. Environment variables
3. AWS credentials file
4. IAM roles

### Required IAM Permissions
```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "bedrock:InvokeModel",
                "bedrock:InvokeModelWithResponseStream"
            ],
            "Resource": "*"
        }
    ]
}
```

## Your First Bedrock Pipe

### Simple Text Generation
```kotlin
import bedrockPipe.BedrockPipe

fun main() {
    val pipe = BedrockPipe()
        .setRegion("us-east-1")
        .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
        .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
        .setTemperature(0.7)
        .setMaxTokens(1000)
    
    val result = runBlocking { pipe.execute("What is artificial intelligence?") }
    println(result.text)
}
```

### With Error Handling
```kotlin
import bedrockPipe.BedrockPipe

fun main() {
    try {
        val pipe = BedrockPipe()
            .setRegion("us-east-1")
            .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
            .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
            .setTemperature(0.7)
            .setMaxTokens(1000)
        
        val result = runBlocking { pipe.execute("Explain quantum computing in simple terms.") }
        println("Response: ${result.text}")
        
    } catch (e: Exception) {
        println("Error: ${e.message}")
        // Handle specific AWS/Bedrock errors
    }
}
```

## Available Models

### Claude Models (Anthropic)
```kotlin
// Claude 3 Sonnet (Recommended for most use cases)
.setModel("anthropic.claude-3-sonnet-20240229-v1:0")

// Claude 3 Haiku (Fast and cost-effective)
.setModel("anthropic.claude-3-haiku-20240307-v1:0")

// Claude 3 Opus (Most capable, higher cost)
.setModel("anthropic.claude-3-opus-20240229-v1:0")

// Claude 3.5 Sonnet (Latest version)
.setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
```

### Amazon Titan Models
```kotlin
// Titan Text G1 - Express
.setModel("amazon.titan-text-express-v1")

// Titan Text G1 - Lite
.setModel("amazon.titan-text-lite-v1")

// Titan Embeddings
.setModel("amazon.titan-embed-text-v1")
```

### Other Foundation Models
```kotlin
// AI21 Jurassic
.setModel("ai21.j2-ultra-v1")
.setModel("ai21.j2-mid-v1")

// Cohere Command
.setModel("cohere.command-text-v14")
.setModel("cohere.command-light-text-v14")

// Meta Llama
.setModel("meta.llama2-13b-chat-v1")
.setModel("meta.llama2-70b-chat-v1")
```

## Basic Configuration

### Essential Settings
```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")                    // AWS region
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")  // Model ID
    .setSystemPrompt("You are a helpful AI assistant.")   // System prompt
    .setTemperature(0.7)                       // Creativity (0.0-1.0)
    .setMaxTokens(1000)                        // Max response length
    .setTopP(0.9)                             // Nucleus sampling
```

### Advanced Configuration
```kotlin
val pipe = BedrockPipe()
    .setRegion("us-west-2")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("You are an expert technical writer.")
    .setTemperature(0.3)                       // Lower for more focused responses
    .setMaxTokens(2000)
    .setTopP(0.8)
    .setTopK(40)                              // Top-K sampling
    .setStopSequences(listOf("END", "STOP"))   // Stop generation at these tokens
    .setContextWindowSize(100000)              // Context window size
```

## Advanced Features

### Service Tier Configuration

AWS Bedrock offers four service tiers to optimize performance and cost: Reserved, Priority, Standard, and Flex. TPipe-Bedrock provides full support for service tier selection.

#### Service Tier Options

```kotlin
import bedrockPipe.BedrockPriorityTier

// Standard tier (default) - Consistent performance for everyday tasks
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setServiceTier(BedrockPriorityTier.Standard)

// Priority tier - Fastest response times for mission-critical applications
val priorityPipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setServiceTier(BedrockPriorityTier.Priority)

// Flex tier - Cost-effective processing for workloads with flexible timing
val flexPipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setServiceTier(BedrockPriorityTier.Flex)

// Reserved tier - Prioritized compute with 99.5% uptime (requires AWS account team access)
val reservedPipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setServiceTier(BedrockPriorityTier.Reserved)
```

#### When to Use Each Tier

**Standard (Default)**
- Everyday AI tasks
- Content generation
- Text analysis
- Routine document processing

**Priority**
- Customer-facing autonomous systems
- Real-time language translation
- Mission-critical applications
- Time-sensitive workflows

**Flex**
- Model evaluations
- Content summarization
- Agentic workflows
- Batch processing tasks

**Reserved**
- 24/7 mission-critical applications
- Applications that cannot tolerate downtime
- Predictable workloads requiring guaranteed capacity
- Requires contacting AWS account team for access

#### Service Tier Notes

- On-demand quota is shared across Priority, Standard, and Flex tiers
- Reserved tier capacity is separate from on-demand quota
- Service tier configuration is visible in API responses and CloudTrail events
- CloudWatch metrics available under ModelId, ServiceTier, and ResolvedServiceTier
- Not all models support all service tiers - check AWS documentation for availability

### JSON Schema Support
```kotlin
data class TechnicalAnalysis(
    val summary: String,
    val keyPoints: List<String>,
    val recommendations: List<String>,
    val riskLevel: String
)

val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("Analyze technical documents and provide structured analysis.")
    .setJsonOutput(TechnicalAnalysis("", emptyList(), emptyList(), ""))
    .requireJsonPromptInjection()

val result = runBlocking { pipe.execute("Analyze this technical specification...") }
val analysis = Json.decodeFromString<TechnicalAnalysis>(result.text)
```

### Context Management
```kotlin
import com.TTT.Context.ContextWindow

val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("You are a Manuscript Orchestrator responsible for cross-referencing archival data.")
    .pullGlobalContext()
    .autoInjectContext("Use the provided context to answer questions accurately.")

// Add context
val context = ContextWindow()
context.addLoreBookEntry("company", "ACME Corp is a technology company founded in 2020", weight = 10)
context.contextElements.add("Current year: 2024")

// Store in global context
ContextBank.emplaceWithMutex("companyInfo", context)

val result = runBlocking { pipe.execute("Tell me about ACME Corp") }
```

### Content Safety with Guardrails

AWS Bedrock Guardrails provide content moderation and safety controls for AI applications. Guardrails automatically filter both user inputs and model outputs against configured policies.

#### Basic Guardrail Configuration

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail(
        identifier = "abc123def456",  // Your guardrail ID from AWS Console
        version = "1"                  // Version number or "DRAFT"
    )
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")

runBlocking {
    pipe.init()
    
    // Guardrail automatically applied to all interactions
    val result = pipe.execute("User message")
    println(result.text)
}
```

#### Standalone Content Evaluation

Validate content without invoking foundation models:

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setGuardrail("abc123def456", "1")

runBlocking {
    pipe.init()
    
    // Pre-validate user input
    val assessment = pipe.applyGuardrailStandalone(
        content = userInput,
        source = "INPUT"
    )
    
    when (assessment?.action) {
        "GUARDRAIL_INTERVENED" -> println("Content blocked by guardrail")
        "NONE" -> println("Content passed guardrail checks")
    }
}
```

**See Also:** [AWS Bedrock Guardrails Guide](guardrails.md) for comprehensive documentation including IAM requirements, trace debugging, pipeline integration, and best practices.

### Developer-in-the-Loop Functions
```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("Generate marketing content.")
    .setValidatorFunction { content ->
        // Validate content meets requirements
        val text = content.text
        text.length > 100 && text.contains("benefits") && !text.contains("guaranteed")
    }
    .setTransformationFunction { content ->
        // Add disclaimer to marketing content
        content.text = "${content.text}\n\n*Results may vary. Individual experiences may differ."
        content
    }
    .setOnFailure { original, processed ->
        // Fallback content if validation fails
        MultimodalContent("Please contact our sales team for more information.")
    }
```

### Pipeline Integration
```kotlin
import com.TTT.Pipeline.Pipeline

val analysisPipeline = Pipeline()
    .add(BedrockPipe()
        .setRegion("us-east-1")
        .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
        .setSystemPrompt("Analyze the input and extract key themes.")
        .updatePipelineContextOnExit()
    )
    .add(BedrockPipe()
        .setRegion("us-east-1")
        .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
        .setSystemPrompt("Generate a summary based on the analysis.")
        .pullPipelineContext()
        .autoInjectContext("Use the analysis results to create a comprehensive summary.")
    )

val result = runBlocking { analysisPipeline.execute("Large document content here...") }
```

## Best Practices

### 1. Model Selection
```kotlin
// For general tasks - balanced performance and cost
.setModel("anthropic.claude-3-sonnet-20240229-v1:0")

// For simple tasks - fast and cost-effective
.setModel("anthropic.claude-3-haiku-20240307-v1:0")

// For complex reasoning - highest capability
.setModel("anthropic.claude-3-opus-20240229-v1:0")
```

### 2. Temperature Settings
```kotlin
// For factual, consistent responses
.setTemperature(0.1)

// For balanced creativity and consistency
.setTemperature(0.7)

// For creative, varied responses
.setTemperature(0.9)
```

### 3. Token Management
```kotlin
val pipe = BedrockPipe()
    .setMaxTokens(2000)                    // Reasonable limit
    .setContextWindowSize(100000)          // Match model capacity
    .setTokenBudget(TokenBudgetSettings(   // Automatically handles truncation
        maxTokens = 2000,
        contextWindowSize = 100000,
        userPromptSize = 1000
    ))
```

### 4. Error Handling
```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setValidatorFunction { content ->
        // Validate response quality
        content.text.isNotBlank() && content.text.length > 10
    }
    .setOnFailure { original, processed ->
        // Provide fallback response
        MultimodalContent("I apologize, but I couldn't generate a proper response. Please try rephrasing your question.")
    }
```

### 5. Security Considerations
```kotlin
// Use IAM roles instead of hardcoded credentials
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    // Don't hardcode credentials in code
    
// Validate and sanitize inputs
.setPreValidationFunction { contextWindow, content ->
    val sanitizedContent = sanitizeInput(content?.text ?: "")
    content?.text = sanitizedContent
    contextWindow
}
```

## Troubleshooting

### Common Issues

#### 1. Model Access Denied
```
Error: You don't have access to the model with the specified model ID
```
**Solution**: Request model access in AWS Bedrock console

#### 2. Invalid Region
```
Error: The model ID is not supported in the specified region
```
**Solution**: Check model availability by region in AWS documentation

#### 3. Token Limit Exceeded
```
Error: Input is too long for the model
```
**Solution**: Enable auto-truncation or reduce input size
```kotlin
.autoTruncateContext()
.setContextWindowSize(100000)
```

#### 4. Authentication Issues
```
Error: Unable to load AWS credentials
```
**Solution**: Verify AWS credentials configuration
```bash
aws configure list
aws sts get-caller-identity
```

#### 5. Rate Limiting
```
Error: Rate exceeded
```
**Solution**: Implement retry logic or reduce request frequency
```kotlin
.setValidatorFunction { content ->
    // Add delay between requests if needed
    Thread.sleep(100)
    true
}
```

### Debug Mode
```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .enableTracing()  // Enable detailed logging
    .setSystemPrompt("Debug mode enabled")

// Check logs for detailed execution information
```

### Testing Configuration
```kotlin
fun testBedrockConnection() {
    try {
        val pipe = BedrockPipe()
            .setRegion("us-east-1")
            .setModel("anthropic.claude-3-haiku-20240307-v1:0")  // Use fast model for testing
            .setMaxTokens(10)
        
        val result = runBlocking { pipe.execute("Hello") }
        println("Connection successful: ${result.text}")
        
    } catch (e: Exception) {
        println("Connection failed: ${e.message}")
        e.printStackTrace()
    }
}
```

## Debugging and Error Handling

TPipe-Bedrock provides comprehensive debugging capabilities to help troubleshoot issues during development and production.

### Exception Handling

Use the `setExceptionFunction()` method to capture and handle exceptions during AI execution:

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setExceptionFunction { content, exception ->
        println("Exception occurred during AI execution:")
        println("Content state: ${content.text}")
        println("Exception: ${exception.message}")
        
        // Log to external system
        logger.error("BedrockPipe exception", exception)
        
        // Custom error recovery logic
        when (exception) {
            is aws.sdk.kotlin.services.bedrockruntime.model.ThrottlingException -> {
                println("Rate limited - consider implementing retry logic")
            }
            is aws.sdk.kotlin.services.bedrockruntime.model.ValidationException -> {
                println("Invalid request - check model parameters")
            }
        }
    }

pipe.init()
val result = pipe.execute("Your prompt here")
```

### Token Usage Debugging

Monitor token consumption with detailed breakdowns:

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .enableComprehensiveTokenTracking()

pipe.init()
val result = pipe.execute("Your prompt here")

// Get detailed token usage breakdown
val usage = pipe.getTokenUsage()
println(usage.getUsageBreakdown())
// Output:
// Parent Pipe: 150 input, 75 output
// Total: 150 input, 75 output
```

### Common AWS Bedrock Errors

**Authentication Issues:**
- Ensure AWS credentials are properly configured
- Verify IAM permissions include `bedrock:InvokeModel`
- Check region availability for your selected model

**Model Access Issues:**
- Request model access in AWS Bedrock console
- Verify model ID is correct for your region
- Some models require special access approval

**Rate Limiting:**
- Implement exponential backoff retry logic
- Consider using different service tiers (Priority, Standard, Flex)
- Monitor your account quotas in AWS console

This comprehensive guide covers everything needed to get started with TPipe-Bedrock, from basic setup to advanced features and troubleshooting. The modular approach allows users to start simple and gradually adopt more sophisticated features as their needs grow.

## Next Steps

Now that you have TPipe-Bedrock set up, learn about advanced configuration:

**→ [AWS Bedrock Inference Binding](inference-binding.md)** - Cross-region model access and configuration
