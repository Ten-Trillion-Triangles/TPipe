# TPipe-Bedrock

AWS Bedrock provider implementation for TPipe framework.

## Features

- Support for Claude 3 models (Sonnet, Haiku, Opus)
- Configurable AWS regions
- JSON input/output with prompt injection fallback
- Context window management
- Temperature, top_p, and stop sequence controls
- Native AWS SDK integration

## Usage

### Text-Only Processing
```kotlin
val pipe = BedrockPipe()
    .setProvider(ProviderName.Aws)
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setRegion("us-east-1")
    .setSystemPrompt("You are a helpful assistant")
    .setTemperature(0.7)
    .setMaxTokens(1000)

pipe.init()
val result = pipe.execute("Your prompt here")
```

### Multimodal Processing
```kotlin
val pipe = BedrockMultimodalPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setRegion("us-east-1")
    .setSystemPrompt("Analyze the provided image and document")
    .setMaxTokens(2000)

pipe.init()

val content = MultimodalContent(
    text = "What do you see in this image?",
    binaryContent = listOf(
        BinaryContent.Bytes(imageBytes, "image/jpeg"),
        BinaryContent.Base64String(base64Document, "application/pdf")
    )
)

val result = pipe.execute(content)
// result.text contains the analysis
// result.binaryContent may contain generated files
```

### GPT-OSS Processing
```kotlin
val pipe = BedrockPipe()
    .setProvider(ProviderName.Aws)
    .setModel("openai.gpt-oss-120b-1:0")
    .setRegion("us-west-2") // Required for GPT-OSS
    .setSystemPrompt("You are a helpful assistant")
    .setTemperature(0.7)
    .setMaxTokens(2000)
    .setReasoning() // Enable reasoning mode

pipe.init()
val result = pipe.execute("Explain quantum computing")
```

## Supported Models

- `anthropic.claude-3-sonnet-20240229-v1:0` (default)
- `anthropic.claude-3-haiku-20240307-v1:0`
- `anthropic.claude-3-opus-20240229-v1:0`
- `openai.gpt-oss-20b-1:0` (us-west-2 only)
- `openai.gpt-oss-120b-1:0` (us-west-2 only)

## Configuration

Ensure AWS credentials are configured via:
- Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
- AWS credentials file
- IAM roles (for EC2/Lambda)

Required permissions:
- `bedrock:InvokeModel`