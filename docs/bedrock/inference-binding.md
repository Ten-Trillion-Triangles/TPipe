# AWS Bedrock Inference Binding

## Table of Contents
- [What is Inference Binding?](#what-is-inference-binding)
- [Why Inference Binding is Required](#why-inference-binding-is-required)
- [Configuration Methods](#configuration-methods)
- [Text Configuration File](#text-configuration-file)
- [CLI Tool Usage](#cli-tool-usage)
- [Programmatic Binding](#programmatic-binding)
- [Cross-Region Inference](#cross-region-inference)
- [Model Availability by Region](#model-availability-by-region)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

AWS Bedrock inference binding is a configuration system that maps foundation models to inference profiles, enabling cross-region access and optimized model routing for applications using TPipe-Bedrock.

## What is Inference Binding?

Inference binding is the process of mapping AWS Bedrock foundation model IDs to inference profile ARNs. This system:

- **Enables cross-region model access** - Use models from different regions through inference profiles
- **Provides consistent model routing** - Automatically route model calls through appropriate profiles
- **Supports regional optimization** - Access models through the most efficient regional endpoints
- **Handles model availability** - Automatically manage models that require specific regional access

## Why Inference Binding is Required

Some AWS Bedrock models have **cross-region inference requirements** that force users to use inference profiles whether they want to or not:

### Models Requiring Inference Profiles
```kotlin
// These models REQUIRE inference profiles for cross-region access:
"amazon.nova-pro-v1:0"           // Nova models
"amazon.nova-lite-v1:0"
"deepseek.r1-v1:0"              // DeepSeek models
"meta.llama4-maverick-17b-instruct-v1:0"  // Latest Llama models
"anthropic.claude-sonnet-4-20250514-v1:0" // Claude 4 models
```

### Direct vs Profile Access
```kotlin
// Direct model access (when available in region)
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")  // Direct access

// Inference profile access (required for cross-region)
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("amazon.nova-pro-v1:0")  // Automatically uses bound profile
```

## Configuration Methods

TPipe-Bedrock provides three ways to configure inference binding:

1. **Text Configuration File** - `~/.aws/inference.txt`
2. **CLI Tool** - Interactive and command-line interface
3. **Programmatic Binding** - Code-based configuration

## Text Configuration File

### Location and Format
The configuration file is located at `~/.aws/inference.txt` with the format:
```
modelId=inferenceProfileId
```

### Example Configuration File
```
# Amazon Nova models (require inference profiles)
amazon.nova-pro-v1:0=arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.amazon.nova-pro-v1:0
amazon.nova-lite-v1:0=arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.amazon.nova-lite-v1:0

# DeepSeek models (require inference profiles)
deepseek.r1-v1:0=arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.deepseek.r1-v1:0

# Claude models (direct access available)
anthropic.claude-3-sonnet-20240229-v1:0=
anthropic.claude-3-haiku-20240307-v1:0=

# Meta Llama models (mixed requirements)
meta.llama4-maverick-17b-instruct-v1:0=arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.meta.llama4-maverick-17b-instruct-v1:0
meta.llama3-70b-instruct-v1:0=
```

### Auto-Generated Default File
When first loaded, TPipe-Bedrock automatically creates a default configuration file with all available models:

```kotlin
import env.bedrockEnv

// This automatically creates ~/.aws/inference.txt if it doesn't exist
bedrockEnv.loadInferenceConfig()
```

The default file includes:
- **150+ foundation models** from all providers
- **Empty inference profile IDs** for user configuration
- **Both direct and profile model variants**
- **Regional availability indicators**

## CLI Tool Usage

TPipe-Bedrock includes a comprehensive CLI tool for managing inference bindings:

### Running the CLI Tool
```bash
# Linux/Unix
./inference-config.sh [command] [args]
./cli-config.sh [command] [args]

# Windows
cli-config.bat [command] [args]

# macOS (double-click or terminal)
./cli-config.command [command] [args]

# Direct Gradle execution (all platforms)
./gradlew :TPipe-Bedrock:run --args="[command] [args]"
```

### Available Commands

#### List Models
```bash
# List all models
./inference-config.sh list

# Filter models by term
./inference-config.sh list claude
```

#### Search Models
```bash
# Search for models containing term
./inference-config.sh search anthropic
./inference-config.sh search nova
```

#### List by Provider
```bash
# List models by provider
./inference-config.sh provider anthropic
./inference-config.sh provider meta
./inference-config.sh provider amazon
```

#### List by Region
```bash
# List models available in specific region
./inference-config.sh region us-east-1
./inference-config.sh region eu-west-1
```

#### Bind Models
```bash
# Bind model to inference profile
./inference-config.sh bind \
  "amazon.nova-pro-v1:0" \
  "arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.amazon.nova-pro-v1:0"

# Bind model for direct access (empty profile)
./inference-config.sh bind \
  "anthropic.claude-3-sonnet-20240229-v1:0" \
  ""
```

### Interactive Mode Example
```bash
# Run with no arguments to enter interactive mode
./inference-config.sh
```

```
AWS Bedrock Inference Profile Configuration Tool
==================================================

Options:
1. List all models
2. Search models
3. List by provider
4. List by region
5. Bind inference profile
6. Show current bindings
7. Exit
Choose option (1-7): 2

Enter search term: nova
Search results for 'nova':
1. amazon.nova-lite-v1:0 -> direct
2. amazon.nova-pro-v1:0 -> arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.amazon.nova-pro-v1:0
3. us.amazon.nova-lite-v1:0 -> direct
Found: 3 models
```

## Programmatic Binding

### Loading Configuration
```kotlin
import env.bedrockEnv

// Load inference configuration from ~/.aws/inference.txt
bedrockEnv.loadInferenceConfig()
```

### Binding Models in Code
```kotlin
// Bind models to inference profiles
bedrockEnv.bindInferenceProfile(
    "amazon.nova-pro-v1:0", 
    "arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.amazon.nova-pro-v1:0"
)

bedrockEnv.bindInferenceProfile(
    "deepseek.r1-v1:0", 
    "arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.deepseek.r1-v1:0"
)

// Bind for direct access (empty profile ID)
bedrockEnv.bindInferenceProfile("anthropic.claude-3-sonnet-20240229-v1:0", "")
```

### Querying Configuration
```kotlin
// Get inference profile for a model
val profileId = bedrockEnv.getInferenceProfileId("amazon.nova-pro-v1:0")

// Search models
val claudeModels = bedrockEnv.searchModels("claude")
val metaModels = bedrockEnv.getModelsByProvider("meta")

// Advanced search with filters
val usEast1Models = bedrockEnv.searchModelsAdvanced(
    region = "us-east-1",
    provider = "anthropic"
)
```

### Complete Setup Example
```kotlin
import env.bedrockEnv
import bedrockPipe.BedrockPipe

fun setupInferenceBinding() {
    // Load configuration
    bedrockEnv.loadInferenceConfig()
    
    // Bind required models
    bedrockEnv.bindInferenceProfile(
        "amazon.nova-pro-v1:0",
        "arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.amazon.nova-pro-v1:0"
    )
    
    bedrockEnv.bindInferenceProfile(
        "deepseek.r1-v1:0",
        "arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.deepseek.r1-v1:0"
    )
    
    // Create pipe - inference binding is automatic
    val pipe = BedrockPipe()
        .setRegion("us-east-1")
        .setModel("amazon.nova-pro-v1:0")  // Uses bound profile automatically
        .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    
    val result = runBlocking { pipe.execute("Hello world") }
}
```

## Cross-Region Inference

### Understanding Cross-Region Access
Some models are only available in specific regions but can be accessed from other regions through inference profiles:

```kotlin
// Model available in us-east-2, accessed from us-east-1
bedrockEnv.bindInferenceProfile(
    "amazon.nova-pro-v1:0",
    "arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.amazon.nova-pro-v1:0"
)

val pipe = BedrockPipe()
    .setRegion("us-east-1")  // Your application region
    .setModel("amazon.nova-pro-v1:0")  // Model from us-east-2 via profile
```

### Regional Inference Profile ARNs
```kotlin
// US East 1 profiles
"arn:aws:bedrock:us-east-1:521369004927:inference-profile/us.anthropic.claude-sonnet-4-20250514-v1:0"

// US East 2 profiles  
"arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.amazon.nova-pro-v1:0"
"arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.deepseek.r1-v1:0"
"arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.meta.llama4-maverick-17b-instruct-v1:0"

// US West 2 profiles
"arn:aws:bedrock:us-west-2:521369004927:inference-profile/us.writer.palmyra-x5-v1:0"
```

## Model Availability by Region

### US East 1 (us-east-1)
- **All Anthropic Claude models** (direct access)
- **Amazon Titan models** (direct access)
- **Most foundation models** (direct access)
- **Cross-region access** to Nova, DeepSeek via profiles

### US East 2 (us-east-2)  
- **Amazon Nova models** (direct access)
- **DeepSeek models** (direct access)
- **Meta Llama 4 models** (direct access)
- **Cross-region access** to other models via profiles

### US West 2 (us-west-2)
- **Writer Palmyra models** (direct access)
- **OpenAI models** (direct access, us-west-2 only)
- **Cross-region access** to other models via profiles

### EU Regions (eu-west-1, eu-central-1)
- **Limited model availability**
- **No OpenAI models**
- **No Llama 405B models**
- **Requires profiles** for most US-exclusive models

### Example Regional Configuration
```kotlin
// Configure for EU region with US model access
bedrockEnv.loadInferenceConfig()

// Bind US models for EU access
bedrockEnv.bindInferenceProfile(
    "amazon.nova-pro-v1:0",
    "arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.amazon.nova-pro-v1:0"
)

val pipe = BedrockPipe()
    .setRegion("eu-west-1")  // EU region
    .setModel("amazon.nova-pro-v1:0")  // US model via profile
```

## Best Practices

### 1. Always Load Configuration First
```kotlin
// Load before using any BedrockPipe
bedrockEnv.loadInferenceConfig()

// Then create pipes
val pipe = BedrockPipe()
    .setModel("amazon.nova-pro-v1:0")
```

### 2. Use CLI Tool for Discovery
```bash
# Find available models in your region
kotlin -cp build/libs/tpipe-bedrock.jar cli.InferenceConfigCli region us-east-1

# Search for specific model types
kotlin -cp build/libs/tpipe-bedrock.jar cli.InferenceConfigCli search claude
```

### 3. Bind Required Models Early
```kotlin
// Bind all required models at application startup
fun initializeInferenceBinding() {
    bedrockEnv.loadInferenceConfig()
    
    // Bind models that require profiles
    val requiredBindings = mapOf(
        "amazon.nova-pro-v1:0" to "arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.amazon.nova-pro-v1:0",
        "deepseek.r1-v1:0" to "arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.deepseek.r1-v1:0"
    )
    
    requiredBindings.forEach { (model, profile) ->
        bedrockEnv.bindInferenceProfile(model, profile)
    }
}
```

### 4. Environment-Specific Configuration
```kotlin
// Different bindings for different environments
val environment = System.getenv("ENVIRONMENT") ?: "development"

bedrockEnv.loadInferenceConfig()

when (environment) {
    "production" -> {
        // Use production inference profiles
        bedrockEnv.bindInferenceProfile(
            "amazon.nova-pro-v1:0",
            "arn:aws:bedrock:us-east-2:PROD_ACCOUNT:inference-profile/us.amazon.nova-pro-v1:0"
        )
    }
    "development" -> {
        // Use development profiles or direct access
        bedrockEnv.bindInferenceProfile("amazon.nova-pro-v1:0", "")
    }
}
```

## Troubleshooting

### Common Issues

#### 1. Model Not Found Error
```
Error: Model ID not supported in region
```
**Solution**: Check if model requires inference profile binding
```bash
kotlin -cp build/libs/tpipe-bedrock.jar cli.InferenceConfigCli search nova
```

#### 2. Configuration File Not Found
```
Error: ~/.aws/inference.txt not found
```
**Solution**: Load configuration to auto-create file
```kotlin
bedrockEnv.loadInferenceConfig()  // Creates default file
```

#### 3. Invalid Inference Profile ARN
```
Error: Invalid inference profile ID
```
**Solution**: Verify ARN format and account ID
```
arn:aws:bedrock:REGION:ACCOUNT_ID:inference-profile/PROFILE_ID
```

#### 4. Cross-Region Access Denied
```
Error: Access denied for cross-region inference
```
**Solution**: Ensure proper IAM permissions for inference profiles
```json
{
    "Effect": "Allow",
    "Action": [
        "bedrock:InvokeModel",
        "bedrock:InvokeModelWithResponseStream"
    ],
    "Resource": [
        "arn:aws:bedrock:*:*:inference-profile/*"
    ]
}
```

### Debug Configuration
```kotlin
// Check current bindings
val allModels = bedrockEnv.getAllModels()
allModels.forEach { model ->
    val profile = bedrockEnv.getInferenceProfileId(model)
    println("$model -> ${profile ?: "direct"}")
}

// Test specific model binding
val novaProfile = bedrockEnv.getInferenceProfileId("amazon.nova-pro-v1:0")
println("Nova Pro profile: $novaProfile")
```

### Validation Script
```kotlin
fun validateInferenceConfiguration() {
    bedrockEnv.loadInferenceConfig()
    
    val requiredModels = listOf(
        "amazon.nova-pro-v1:0",
        "deepseek.r1-v1:0",
        "anthropic.claude-sonnet-4-20250514-v1:0"
    )
    
    requiredModels.forEach { model ->
        val profile = bedrockEnv.getInferenceProfileId(model)
        if (profile.isNullOrEmpty()) {
            println("⚠️  $model not bound to inference profile")
        } else {
            println("✅ $model -> $profile")
        }
    }
}
```

Inference binding is essential for accessing the latest AWS Bedrock models that require cross-region inference profiles. Use the provided tools and configuration methods to ensure your TPipe-Bedrock applications can access all required models seamlessly.
