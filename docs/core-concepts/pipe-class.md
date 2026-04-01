# Pipe Class - Core Concepts

> 💡 **Tip:** A **Pipe** is the fundamental unit of work in TPipe—the main Valve in your AI plumbing. It controls the flow of context to the LLM and the flow of responses back out.


## Table of Contents
- [Basic Structure](#basic-structure)
- [Builder Pattern](#builder-pattern)
- [Basic Settings](#basic-settings)
- [Configuration Examples](#configuration-examples)
- [Key Properties](#key-properties)
- [Method Chaining](#method-chaining)
- [System Prompt Processing](#system-prompt-processing)
- [Conversation History Wrapping](#conversation-history-wrapping)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

The `Pipe` class is the abstract base class for all TPipe implementations. It provides the foundation for text generation, configuration, and the builder pattern used throughout TPipe.

`Pipe` instances are mutable execution objects. Build a fresh pipe for each concurrent top-level run rather than sharing the same instance across simultaneous executions.

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
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
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
pipe.setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")

// Multi-line system prompt
pipe.setSystemPrompt("""
    You are a manuscript orchestrator responsible for maintaining consistency 
    across a multi-chapter technical document.
    
    Guidelines:
    - Ensure technical terms are used consistently
    - Flag contradictions between chapters
    - Maintain a formal, authoritative tone
""".trimIndent())
```

### Pipe Identification

```kotlin
// Set a name for this pipe instance (useful for debugging/tracing)
pipe.setPipeName("security-auditor")
```

## Configuration Examples

### Security Audit Agent

```kotlin
val securityAuditor = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setRegion("us-west-2")
    .setMaxTokens(500)
    .setTemperature(0.7)
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .setPipeName("security-auditor")
```

### Manuscript Orchestrator

```kotlin
val orchestrator = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setRegion("us-west-2")
    .setMaxTokens(2000)
    .setTemperature(0.9)  // Higher temperature for creativity
    .setSystemPrompt("""
        You are a manuscript orchestrator.
        Help users with:
        - Maintaining consistency across chapters
        - Character development tracking
        - Technical terminology alignment
        - Structural integrity of the document
    """.trimIndent())
    .setPipeName("manuscript-orchestrator")
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
pipe.setSystemPrompt("You are an automated security auditor.")
// Final prompt might be: "You are an automated security auditor.\n\n[JSON instructions]\n\n[Context instructions]"
```

## Conversation History Wrapping

Individual pipes can automatically wrap their outputs into conversation history format, enabling seamless conversation flow across pipeline chains. This is particularly useful for multi-turn conversations and agent-based systems.

### Basic Usage

```kotlin
val conversationPipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setRegion("us-west-2")
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .wrapContentWithConverse()  // Enable automatic wrapping
```

### Specifying Conversation Roles

```kotlin
// Pipe acts as an assistant
val assistantPipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .wrapContentWithConverse(ConverseRole.assistant)

// Pipe acts as an agent
val agentPipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .wrapContentWithConverse(ConverseRole.agent)

// Pipe acts as a system component
val systemPipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .wrapContentWithConverse(ConverseRole.system)
```

### How It Works

1. **Input Detection**: When a pipe receives input, it checks if the content is already in `ConverseHistory` format
2. **History Storage**: If conversation history is detected, it's stored in the pipe's internal metadata
3. **Output Wrapping**: The pipe's output is automatically wrapped with the specified role and added to the conversation history
4. **Chain Continuity**: Subsequent pipes in a pipeline can detect and continue building the conversation

### Pipeline Integration

```kotlin
val conversationPipeline = Pipeline()
    .add(BedrockPipe()
        .setModel("anthropic.claude-3-haiku-20240307-v1:0")
        .setSystemPrompt("You are a research assistant.")
        .wrapContentWithConverse(ConverseRole.assistant))
    .add(BedrockPipe()
        .setModel("anthropic.claude-3-haiku-20240307-v1:0")
        .setSystemPrompt("You are a fact checker.")
        .wrapContentWithConverse(ConverseRole.agent))
    .add(BedrockPipe()
        .setModel("anthropic.claude-3-haiku-20240307-v1:0")
        .setSystemPrompt("You are an editor.")
        .wrapContentWithConverse(ConverseRole.assistant))

// Each pipe automatically builds on the conversation history
val result = conversationPipeline.execute("Research the history of AI")
```

### System Prompt to Conversation Conversion

For models that work better with conversation format than system prompts, you can automatically convert system prompts to conversation history:

```kotlin
val conversationPipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .copySystemToUserPrompt()  // Convert system prompt to conversation format
```

This creates a conversation history with:
1. System role entry containing the system prompt
2. User role entry containing the user's input

### Important Notes

- **Chain Continuity**: All pipes in a conversation chain should have `wrapContentWithConverse()` enabled
- **Silent Breaking**: If any pipe in the chain doesn't have wrapping enabled, the conversation chain breaks silently
- **Role Consistency**: Choose appropriate roles for each pipe's function in the conversation
- **JSON Agnostic**: Works regardless of the JSON structure the pipe produces

### Available Conversation Roles

- `ConverseRole.user` - User input
- `ConverseRole.assistant` - AI assistant responses
- `ConverseRole.agent` - Specialized agent responses
- `ConverseRole.system` - System messages and instructions
- `ConverseRole.developer` - Developer/debugging messages

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

### Conversation History
- Enable `wrapContentWithConverse()` on all pipes in a conversation chain
- Choose appropriate conversation roles for each pipe's function
- Use `copySystemToUserPrompt()` for models that prefer conversation format over system prompts
- Consider using Pipeline-level conversation history for complex multi-agent systems

## Next Steps

Now that you understand the Pipe class fundamentals, learn about orchestrating multiple pipes:

**→ [Pipeline Class - Orchestrating Multiple Pipes](pipeline-class.md)** - Chaining pipes together
