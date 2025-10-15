# Pipe Class - Core Concepts

## Table of Contents
- [Basic Structure](#basic-structure)
- [Builder Pattern](#builder-pattern)
- [Basic Settings](#basic-settings)
- [Configuration Examples](#configuration-examples)
- [Key Properties](#key-properties)
- [Method Chaining](#method-chaining)
- [System Prompt Processing](#system-prompt-processing)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

The `Pipe` class is the abstract base class for all TPipe implementations. It provides the foundation for text generation, configuration, and the builder pattern used throughout TPipe.

## Basic Structure

```kotlin
abstract class Pipe : P2PInterface, ProviderInterface {
    // Core properties and methods
}
```

All provider-specific pipes (BedrockPipe, OllamaPipe, etc.) inherit from this base class.

## Builder Pattern

TPipe uses a fluent builder pattern where each configuration method returns the pipe instance, allowing method chaining:

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setRegion("us-west-2")
    .setMaxTokens(1000)
    .setTemperature(0.7)
    .setSystemPrompt("You are a helpful assistant.")
```

## Basic Settings

### Model Configuration

```kotlin
// Set the model to use
pipe.setModel("model-id")

// Set provider (usually handled by specific pipe classes)
pipe.setProvider(ProviderName.BEDROCK)
```

### Generation Parameters

```kotlin
// Maximum tokens to generate
pipe.setMaxTokens(1000)

// Temperature (0.0 to 1.0) - controls randomness
pipe.setTemperature(0.7)

// Top-p sampling (0.0 to 1.0) - controls diversity
pipe.setTopP(0.9)

// Stop sequences - generation stops when these are encountered
pipe.setStopSequences(listOf("END", "STOP"))
```

### System Prompt

The system prompt defines the AI's behavior and role:

```kotlin
// Basic system prompt
pipe.setSystemPrompt("You are a helpful assistant.")

// Multi-line system prompt
pipe.setSystemPrompt("""
    You are a helpful AI assistant.
    
    Guidelines:
    - Be concise and accurate
    - Ask for clarification when needed
    - Provide examples when helpful
""".trimIndent())
```

### Pipe Identification

```kotlin
// Set a name for this pipe instance (useful for debugging/tracing)
pipe.setPipeName("my-chat-bot")
```

## Configuration Examples

### Basic Chat Bot

```kotlin
val chatBot = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setRegion("us-west-2")
    .setMaxTokens(500)
    .setTemperature(0.7)
    .setSystemPrompt("You are a friendly chat bot. Keep responses brief and helpful.")
    .setPipeName("chat-bot")
```

### Creative Writing Assistant

```kotlin
val writer = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setRegion("us-west-2")
    .setMaxTokens(2000)
    .setTemperature(0.9)  // Higher temperature for creativity
    .setSystemPrompt("""
        You are a creative writing assistant.
        Help users with:
        - Story ideas and plot development
        - Character creation
        - Writing style improvements
        - Grammar and structure
    """.trimIndent())
    .setPipeName("creative-writer")
```

### Code Assistant

```kotlin
val codeAssistant = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setRegion("us-west-2")
    .setMaxTokens(1500)
    .setTemperature(0.2)  // Lower temperature for accuracy
    .setStopSequences(listOf("```\n\n", "END_CODE"))
    .setSystemPrompt("""
        You are a programming assistant.
        
        When providing code:
        - Use proper syntax highlighting
        - Include comments explaining complex logic
        - Follow best practices
        - Provide working examples
    """.trimIndent())
    .setPipeName("code-assistant")
```

### Structured Output Assistant

```kotlin
val structuredAssistant = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setRegion("us-west-2")
    .setMaxTokens(800)
    .setTemperature(0.3)
    .setSystemPrompt("""
        You are a structured data assistant.
        Always respond in valid JSON format.
        
        Response structure:
        {
            "answer": "your response here",
            "confidence": 0.95,
            "sources": ["source1", "source2"]
        }
    """.trimIndent())
    .setPipeName("structured-assistant")
```

## Key Properties

### Core Settings
- `model`: String - The model identifier
- `maxTokens`: Int - Maximum tokens to generate
- `temperature`: Double - Randomness control (0.0-1.0)
- `systemPrompt`: String - System/instruction prompt

### Identification
- `pipeName`: String - Human-readable name for this pipe instance
- `provider`: ProviderName - The AI provider (BEDROCK, OLLAMA, etc.)

### Advanced Settings
- `stopSequences`: List<String> - Sequences that stop generation
- `topP`: Double - Nucleus sampling parameter
- `promptMode`: PromptMode - How prompts are processed

## Method Chaining

All configuration methods return the pipe instance, enabling fluent chaining:

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setRegion("us-west-2")
    .setMaxTokens(1000)
    .setTemperature(0.7)
    .setSystemPrompt("You are helpful.")
    .setPipeName("my-pipe")
    // Continue chaining more methods...
```

## System Prompt Processing

The system prompt undergoes several processing steps:

1. **Raw System Prompt**: Your original prompt
2. **PCP Context**: Pipe Context Protocol additions (if enabled)
3. **JSON Requirements**: JSON formatting instructions (if needed)
4. **Middle Prompt**: Prompt that sits between json input, and json output.
5. **Context Instructions**: Auto-injected context (if enabled)
6. **Footer Prompt**: Additional instructions appended at the end

```kotlin
// The final system prompt may include additional instructions
// beyond what you explicitly set
pipe.setSystemPrompt("You are helpful.")
// Final prompt might be: "You are helpful.\n\n[JSON instructions]\n\n[Context instructions]"
```

## Best Practices

### System Prompt Design
- Be specific about the AI's role and behavior
- Include examples of desired output format
- Set clear boundaries and guidelines
- Use consistent formatting

### Parameter Tuning
- **Temperature**: 0.0-0.3 for factual/analytical tasks, 0.7-1.0 for creative tasks
- **Max Tokens**: Set based on expected response length
- **Stop Sequences**: Use to control output format and length

### Pipe Naming
- Use descriptive names for debugging and tracing
- Include the purpose or domain (e.g., "medical-qa", "code-review")

## Next Steps

Now that you understand the Pipe class fundamentals, learn about orchestrating multiple pipes:

**→ [Pipeline Class - Orchestrating Multiple Pipes](pipeline-class.md)** - Chaining pipes together
