# Model Reasoning Usage Guide

The TPipe-Defaults module provides built-in reasoning capabilities for LLMs that don't natively support chain-of-thought reasoning. This enables models to "think" through problems step-by-step before providing final answers.

## Reasoning Methods

TPipe-Defaults supports multiple reasoning strategies:

- **StructuredCot**: Phase-based framework (analyze→plan→execute→validate)
- **ExplicitCot**: Step-by-step reasoning with clear transitions
- **ProcessFocusedCot**: Methodological justification and adaptive thinking
- **BestIdea**: Generate and select the single best solution
- **ComprehensivePlan**: Multi-phase detailed planning approach
- **RolePlay**: Character-based reasoning from specific expertise perspective

## Basic Usage

### Simple Reasoning with Bedrock

```kotlin
import Defaults.BedrockConfiguration
import Defaults.reasoning.ReasoningBuilder
import Defaults.reasoning.ReasoningSettings
import Defaults.reasoning.ReasoningMethod
import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import com.TTT.Structs.PipeSettings

// Configure Bedrock provider
val bedrockConfig = BedrockConfiguration(
    region = "us-east-1",
    model = "anthropic.claude-3-sonnet-20240229-v1:0"
)

// Configure reasoning behavior
val reasoningSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    depth = ReasoningDepth.Med,
    duration = ReasoningDuration.Med
)

// Configure pipe parameters
val pipeSettings = PipeSettings(
    temperature = 0.7,
    maxTokens = 4000,
    contextWindowSize = 100000
)

// Create reasoning pipe

val configuredPipe = ReasoningBuilder.reasonWithBedrock(
    bedrockConfig,
    reasoningSettings,
    pipeSettings)

// Initialize and execute
configuredPipe.init()
val result = configuredPipe.execute("Explain how photosynthesis works")
println(result)
```

### Simple Reasoning with Ollama

```kotlin
import Defaults.OllamaConfiguration
import Defaults.reasoning.ReasoningBuilder

// Configure Ollama provider
val ollamaConfig = OllamaConfiguration(
    model = "llama3.1:8b",
    host = "localhost",
    port = 11434
)

// Configure reasoning
val reasoningSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.ExplicitCot,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Long
)

val pipeSettings = PipeSettings(
    temperature = 0.6,
    maxTokens = 8000
)

// Create reasoning pipe
val configuredPipe = ReasoningBuilder.reasonWithOllama(
    ollamaConfig,
    reasoningSettings,
    pipeSettings)

configuredPipe.init()
val result = configuredPipe.execute("Solve: If a train travels 120 km in 2 hours, what is its average speed?")
```

## Reasoning Depth Control

Control how thorough the reasoning process is:

```kotlin
// Low depth - concise, focused reasoning
val quickSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    depth = ReasoningDepth.Low,
    duration = ReasoningDuration.Short
)

// Medium depth - thorough analysis
val balancedSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    depth = ReasoningDepth.Med,
    duration = ReasoningDuration.Med
)

// High depth - exhaustive examination
val deepSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Long
)
```

## Reasoning Methods in Detail

### Structured Chain-of-Thought

Best for systematic problem-solving with clear phases:

```kotlin
val settings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    depth = ReasoningDepth.Med,
    duration = ReasoningDuration.Med
)

val pipe = ReasoningBuilder.reasonWithBedrock(bedrockConfig, settings, pipeSettings, BedrockPipe())
pipe.init()
val result = pipe.execute("Design a database schema for an e-commerce platform")
```

### Explicit Chain-of-Thought

Best for mathematical or logical problems requiring clear step transitions:

```kotlin
val settings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.ExplicitCot,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Med
)

val pipe = ReasoningBuilder.reasonWithBedrock(bedrockConfig, settings, pipeSettings, BedrockPipe())
pipe.init()
val result = pipe.execute("Calculate compound interest on $10,000 at 5% annually for 3 years")
```

### Process-Focused Chain-of-Thought

Best for methodological analysis and adaptive thinking:

```kotlin
val settings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.processFocusedCot,
    depth = ReasoningDepth.Med,
    duration = ReasoningDuration.Med
)

val pipe = ReasoningBuilder.reasonWithBedrock(bedrockConfig, settings, pipeSettings, BedrockPipe())
pipe.init()
val result = pipe.execute("Analyze the trade-offs between microservices and monolithic architecture")
```

### Best Idea Selection

Best for brainstorming and selecting optimal solutions:

```kotlin
val settings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.BestIdea,
    depth = ReasoningDepth.Med,
    duration = ReasoningDuration.Short
)

val pipe = ReasoningBuilder.reasonWithBedrock(bedrockConfig, settings, pipeSettings, BedrockPipe())
pipe.init()
val result = pipe.execute("What's the best approach to reduce API latency in our system?")
```

### Comprehensive Planning

Best for complex projects requiring detailed multi-phase plans:

```kotlin
val settings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.ComprehensivePlan,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Long
)

val pipe = ReasoningBuilder.reasonWithBedrock(bedrockConfig, settings, pipeSettings, BedrockPipe())
pipe.init()
val result = pipe.execute("Create a migration plan from on-premise to AWS cloud infrastructure")
```

### Role-Play Reasoning

Best for domain-specific expertise and perspective-based analysis:

```kotlin
val settings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.RolePlay,
    roleCharacter = "You are a senior security architect with 15 years of experience in cloud security",
    depth = ReasoningDepth.Med,
    duration = ReasoningDuration.Med
)

val pipe = ReasoningBuilder.reasonWithBedrock(bedrockConfig, settings, pipeSettings, BedrockPipe())
pipe.init()
val result = pipe.execute("Review this API design for security vulnerabilities")
```

## Multi-Round Reasoning

Enable multiple rounds of reasoning with focus points:

```kotlin
val settings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Long,
    numberOfRounds = 3,
    focusPoints = mutableMapOf(
        1 to "Focus on identifying all requirements and constraints",
        2 to "Focus on evaluating different architectural approaches",
        3 to "Focus on implementation details and potential risks"
    )
)

val pipe = ReasoningBuilder.reasonWithBedrock(bedrockConfig, settings, pipeSettings, BedrockPipe())
pipe.init()
val result = pipe.execute("Design a real-time notification system for 1 million users")
```

## Reasoning Injection Methods

Control where reasoning output appears in the final response:

```kotlin
import Defaults.reasoning.ReasoningInjector

// Inject at end of system prompt (default)
val systemPromptSettings = ReasoningSettings(
    reasoningInjector = ReasoningInjector.SystemPrompt
)

// Inject before user prompt
val beforeUserSettings = ReasoningSettings(
    reasoningInjector = ReasoningInjector.BeforeUserPrompt
)

// Inject after user prompt
val afterUserSettings = ReasoningSettings(
    reasoningInjector = ReasoningInjector.AfterUserPrompt
)

// Inject as context to specific page key
val contextSettings = ReasoningSettings(
    reasoningInjector = ReasoningInjector.AsContext
)
```

## Assigning Reasoning to Existing Pipes

Apply reasoning configuration to an already-created pipe:

```kotlin
// Create your pipe first
val myPipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setRegion("us-east-1")

// Configure reasoning settings
val reasoningSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Med
)

val pipeSettings = PipeSettings(
    temperature = 0.7,
    maxTokens = 4000,
    contextWindowSize = 100000
)

// Apply reasoning defaults to your existing pipe
ReasoningBuilder.assignDefaults(reasoningSettings, pipeSettings, myPipe)

// Initialize and use
myPipe.init()
val result = myPipe.execute("Your task here")
```

## Using with Pipelines

Integrate reasoning pipes into larger workflows:

```kotlin
val reasoningPipe = BedrockPipe()
ReasoningBuilder.assignDefaults(reasoningSettings, pipeSettings, reasoningPipe)

val summaryPipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setSystemPrompt("Summarize the reasoning and provide key insights")

val pipeline = Pipeline()
    .add(reasoningPipe)
    .add(summaryPipe)

pipeline.init()
val result = pipeline.execute("Analyze the pros and cons of serverless architecture")
```

## Best Practices

1. **Match Method to Task**: Use StructuredCot for systematic problems, ExplicitCot for math, RolePlay for domain expertise

2. **Adjust Depth and Duration**: Start with Med/Med and adjust based on complexity

3. **Multi-Round for Complex Tasks**: Use multiple rounds with focus points for multi-faceted problems

4. **Stop Sequences**: Reasoning pipes automatically use `##Final Answer##` as stop sequence

5. **Token Budget**: Allocate sufficient maxTokens for reasoning (4000+ recommended)

6. **Temperature Settings**: Lower temperature (0.6-0.7) for more focused reasoning

## Advanced: Manual Configuration

For fine-grained control, manually configure reasoning:

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setRegion("us-east-1")

val settings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Long
)

val pipeSettings = PipeSettings(
    temperature = 0.65,
    topP = 0.9,
    maxTokens = 8000,
    contextWindowSize = 100000
)

// Apply reasoning defaults
ReasoningBuilder.assignDefaults(settings, pipeSettings, pipe)

pipe.init()
val result = pipe.execute("Your complex task here")
```

## Notes

- Reasoning pipes automatically configure stop sequences to detect final answers
- Multi-round reasoning requires ConverseHistory for maintaining context between rounds
- The reasoning process is visible in the model's output before the final answer
- Reasoning significantly increases token usage but improves output quality
- Not all models benefit equally from reasoning prompts - test with your specific model

