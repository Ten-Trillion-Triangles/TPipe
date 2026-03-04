# Reasoning Pipes

> 💡 **Tip:** **Reasoning Pipes** are specialized processing plants. They isolate the LLM's "thinking" (like `<think>` tags) from its final output, sending clean water down the line.


## Table of Contents
- [What are Reasoning Pipes?](#what-are-reasoning-pipes)
- [Core Concepts](#core-concepts)
- [Building Reasoning Pipes with TPipe-Defaults](#building-reasoning-pipes-with-tpipe-defaults)
- [Reasoning Methods](#reasoning-methods)
- [Reasoning Injection Methods](#reasoning-injection-methods)
- [Multi-Round Reasoning with Focus Points](#multi-round-reasoning-with-focus-points)
- [Cross-Provider Reasoning](#cross-provider-reasoning)
- [Reasoning Depth and Duration Settings](#reasoning-depth-and-duration-settings)
- [Nested Reasoning Pipes](#nested-reasoning-pipes)
- [Practical Examples](#practical-examples)
- [Best Practices](#best-practices)

Reasoning pipes provide chain-of-thought reasoning capabilities to any AI model, enabling complex problem-solving and step-by-step analysis even on models that don't natively support reasoning modes. TPipe-Defaults provides proven reasoning builders that create optimized reasoning pipes for different problem types.

## What are Reasoning Pipes?

Reasoning pipes are specialized AI models that execute before your main pipe to provide structured thinking and analysis:

- **Chain-of-thought reasoning** for step-by-step problem solving
- **Multi-round reasoning** for complex analysis
- **Structured reasoning strategies** for different problem types
- **Automatic reasoning injection** into main pipe processing

The reasoning pipe generates detailed analysis that enhances the main pipe's decision-making capabilities.

## Core Concepts

### Reasoning Execution Flow
1. **Input received** by main pipe
2. **Reasoning pipe executes** first with the input
3. **Reasoning content generated** through structured analysis
4. **Reasoning injected** into main pipe processing
5. **Main pipe processes** with enhanced reasoning context
6. **Final result** combines reasoning and main processing

### Reasoning Budget and Rounds
```kotlin
import com.TTT.Pipe.TokenBudgetSettings

val mainPipe = BedrockPipe()
    .setSystemPrompt("Solve problems using provided reasoning.")
    .setReasoningPipe(reasoningPipe)
    .setTokenBudget(TokenBudgetSettings(
        reasoningBudget = 2000  // Token budget for reasoning
    ))
```

**Note**: Reasoning rounds are configured in the `ReasoningSettings` when building the reasoning pipe, not on the main pipe.

## Building Reasoning Pipes with TPipe-Defaults

TPipe-Defaults provides the `ReasoningBuilder` with proven reasoning strategies:

### Structured Chain-of-Thought Reasoning
```kotlin
import Defaults.reasoning.ReasoningBuilder.reasonWithBedrock
import Defaults.reasoning.*
import Defaults.BedrockConfiguration
import com.TTT.Structs.PipeSettings

val bedrockConfig = BedrockConfiguration(
    region = "us-east-1",
    model = "anthropic.claude-3-sonnet-20240229-v1:0"
)

val pipeSettings = PipeSettings(
    temperature = 0.7,
    topP = 0.8,
    maxTokens = 2000
)

val reasoningSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    depth = ReasoningDepth.Med,
    duration = ReasoningDuration.Med,
    reasoningInjector = ReasoningInjector.SystemPrompt,
    numberOfRounds = 2
)

val structuredReasoningPipe = reasonWithBedrock(
    bedrockConfig,
    reasoningSettings,
    pipeSettings
)

val problemSolvingPipe = BedrockPipe()
    .setSystemPrompt("Solve problems systematically using step-by-step analysis.")
    .setReasoningPipe(structuredReasoningPipe)
    .setTokenBudget(TokenBudgetSettings(reasoningBudget = 1500))
```

### Explicit Chain-of-Thought Reasoning
```kotlin
val explicitReasoningSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.ExplicitCot,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Long,
    reasoningInjector = ReasoningInjector.BeforeUserPrompt,
    numberOfRounds = 1
)

val explicitReasoningPipe = reasonWithBedrock(
    bedrockConfig,
    explicitReasoningSettings,
    pipeSettings
)

val analyticalPipe = BedrockPipe()
    .setSystemPrompt("Analyze problems with explicit step-by-step reasoning.")
    .setReasoningPipe(explicitReasoningPipe)
```

### Process-Focused Chain-of-Thought
```kotlin
val processReasoningSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.processFocusedCot,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Med,
    reasoningInjector = ReasoningInjector.AfterUserPrompt,
    numberOfRounds = 3
)

val processReasoningPipe = reasonWithBedrock(
    bedrockConfig,
    processReasoningSettings,
    pipeSettings
)

val methodicalPipe = BedrockPipe()
    .setSystemPrompt("Apply methodical reasoning with process focus.")
    .setReasoningPipe(processReasoningPipe)
```

## Reasoning Methods

### Structured Chain-of-Thought (StructuredCot)
```kotlin
val structuredSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    depth = ReasoningDepth.Med,
    duration = ReasoningDuration.Med
)
```

**What it does**: Uses a formal phase-based framework (analyze→plan→execute→validate) for systematic problem-solving. Provides structured, step-by-step reasoning with clear transitions between logical phases.

**Best for**: General problem solving, systematic analysis, structured decision making

### Explicit Chain-of-Thought (ExplicitCot)
```kotlin
val explicitSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.ExplicitCot,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Long
)
```

**What it does**: Shows detailed step-by-step reasoning with clear transitions between each logical step. Makes the thinking process completely transparent and traceable.

**Best for**: Complex logical problems, mathematical reasoning, detailed analysis where transparency is crucial

### Process-Focused Chain-of-Thought (processFocusedCot)
```kotlin
val processSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.processFocusedCot,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Med
)
```

**What it does**: Focuses on methodological justification and adaptive thinking strategies. Emphasizes the reasoning process itself and how to adapt approaches based on the problem type.

**Best for**: Methodological problems, process optimization, adaptive problem-solving scenarios

### Best Idea Strategy
```kotlin
val bestIdeaSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.BestIdea,
    depth = ReasoningDepth.Med,
    duration = ReasoningDuration.Short,
    reasoningInjector = ReasoningInjector.SystemPrompt
)

val bestIdeaReasoningPipe = reasonWithBedrock(
    bedrockConfig,
    bestIdeaSettings,
    pipeSettings
)
```

**What it does**: Asks the AI to come up with what it thinks is the single best idea to solve the problem. Focuses on generating one high-quality solution rather than exploring multiple options.

**Use cases**: Quick decision making, brainstorming, creative problem solving, time-sensitive decisions

### Comprehensive Plan Strategy
```kotlin
val comprehensiveSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.ComprehensivePlan,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Long,
    reasoningInjector = ReasoningInjector.BeforeUserPrompt
)

val planningReasoningPipe = reasonWithBedrock(
    bedrockConfig,
    comprehensiveSettings,
    pipeSettings
)
```

**What it does**: Asks the AI to develop a substantial, comprehensive plan on how it would solve the problem. Creates detailed, multi-step strategies with thorough planning.

**Use cases**: Strategic planning, project management, complex problem decomposition, long-term planning

### Role-Play Reasoning
```kotlin
val rolePlaySettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.RolePlay,
    roleCharacter = "You are an experienced business consultant with 20 years of strategic planning experience.",
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Med,
    reasoningInjector = ReasoningInjector.SystemPrompt
)

val consultantReasoningPipe = reasonWithBedrock(
    bedrockConfig,
    rolePlaySettings,
    pipeSettings
)
```

**What it does**: Asks the AI to play as a specific character and act as that character trying to reason through the given task. Applies domain expertise and perspective from the specified role.

**Use cases**: Domain expertise simulation, specialized knowledge application, perspective-based analysis

### Chain of Draft Reasoning
```kotlin
val chainOfDraftSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.ChainOfDraft,
    depth = ReasoningDepth.Med,
    duration = ReasoningDuration.Short,
    reasoningInjector = ReasoningInjector.SystemPrompt
)

val chainOfDraftReasoningPipe = reasonWithBedrock(
    bedrockConfig,
    chainOfDraftSettings,
    pipeSettings
)
```

**What it does**: Chain of Draft (CoD) is an innovative prompting technique that revolutionizes model reasoning by using concise, high-signal thinking steps rather than verbose explanations. Each reasoning step is limited to **5 words or less**, forcing the model to focus on essential logical components while minimizing unnecessary verbosity. This mirrors how humans solve problems with brief mental notes rather than detailed explanations.

**Key Innovation**: CoD recognizes that most reasoning chains contain high redundancy. By distilling steps to their semantic core, it helps models focus on logical structure rather than language fluency, achieving the same reasoning quality with dramatically fewer tokens.

**Performance Benefits**:
- Up to **75% reduction in token usage** compared to Chain-of-Thought
- Over **78% decrease in latency** while maintaining accuracy
- Cleaner output for downstream parsing and automation
- Significant cost savings in production environments

**Example Comparison**:
- **Chain-of-Thought**: "Jason had 20 lollipops. He gave some to Denny and now has 12 left. So he gave away 8."
- **Chain-of-Draft**: "Start: 20, End: 12, 20 – 12 = 8."

**Best for**: Mathematical calculations, logical puzzles, structured reasoning tasks, cost-sensitive applications, real-time systems where latency matters, scenarios requiring minimal token usage

**When to avoid**: Zero-shot scenarios (works best with few-shot examples), tasks requiring high interpretability (legal/medical), small language models (<3B parameters), creative or open-ended tasks where elaboration adds value

## Reasoning Injection Methods

Configure how reasoning content is injected into the main pipe:

### System Prompt Injection
```kotlin
val systemPromptSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    reasoningInjector = ReasoningInjector.SystemPrompt
)
```

**How it works**: Reasoning is injected at the end of the system prompt

### Before User Prompt Injection
```kotlin
val beforeUserSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.ExplicitCot,
    reasoningInjector = ReasoningInjector.BeforeUserPrompt
)
```

**How it works**: Reasoning appears before the user's input

### After User Prompt Injection
```kotlin
val afterUserSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.processFocusedCot,
    reasoningInjector = ReasoningInjector.AfterUserPrompt
)
```

**How it works**: Reasoning appears after the user's input

### Converse History Injection
```kotlin
val converseSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    reasoningInjector = ReasoningInjector.BeforeUserPromptWithConverse
)
```

**How it works**: Reasoning is injected into a ConverseHistory block

### Context Injection
```kotlin
val contextSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.ComprehensivePlan,
    reasoningInjector = ReasoningInjector.AsContext
)
```

**How it works**: Reasoning is injected as context to a designated page key

## Multi-Round Reasoning with Focus Points

### What are Focus Points?
Focus points are specific instructions that guide the reasoning pipe's attention during each round of multi-round reasoning. They allow you to direct the AI's reasoning toward particular aspects of a problem, ensuring comprehensive coverage of important areas.

**How focus points work**:
- Each round gets a specific focus area to concentrate on
- The AI dedicates reasoning time to that particular aspect
- Enables systematic exploration of complex problems
- Ensures important considerations aren't overlooked

### Progressive Multi-Round Analysis
```kotlin
val focusPoints = mutableMapOf<Int, String>()
focusPoints[1] = "risk assessment and potential challenges"
focusPoints[2] = "cost analysis and resource requirements"  
focusPoints[3] = "timeline and implementation strategy"

val multiRoundSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Long,
    reasoningInjector = ReasoningInjector.SystemPrompt,
    numberOfRounds = 3,
    focusPoints = focusPoints
)
```

**What happens in each round**:
- **Round 1**: AI focuses specifically on "risk assessment and potential challenges"
- **Round 2**: AI focuses on "cost analysis and resource requirements" while building on Round 1
- **Round 3**: AI focuses on "timeline and implementation strategy" while incorporating insights from previous rounds

### Focus Point Examples by Domain

#### Business Strategy Focus Points
```kotlin
val businessFocusPoints = mutableMapOf<Int, String>()
businessFocusPoints[1] = "market analysis and competitive landscape"
businessFocusPoints[2] = "financial impact and ROI projections"
businessFocusPoints[3] = "implementation risks and mitigation strategies"
businessFocusPoints[4] = "organizational change management requirements"
```

#### Technical Architecture Focus Points
```kotlin
val technicalFocusPoints = mutableMapOf<Int, String>()
technicalFocusPoints[1] = "scalability and performance requirements"
technicalFocusPoints[2] = "security considerations and compliance"
technicalFocusPoints[3] = "integration challenges and dependencies"
technicalFocusPoints[4] = "maintenance and operational complexity"
```

#### Creative Innovation Focus Points
```kotlin
val creativeFocusPoints = mutableMapOf<Int, String>()
creativeFocusPoints[1] = "user experience and interface design"
creativeFocusPoints[2] = "market differentiation and unique value proposition"
creativeFocusPoints[3] = "technical feasibility and implementation approach"
creativeFocusPoints[4] = "scalability and future enhancement possibilities"
```

### Focus Points vs Single-Round Reasoning

#### Without Focus Points (Single Round)
```kotlin
val singleRoundSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    numberOfRounds = 1  // No focus points needed
)
```
**Result**: AI provides general reasoning covering all aspects but may not dive deep into specific areas.

#### With Focus Points (Multi-Round)
```kotlin
val focusedSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    numberOfRounds = 3,
    focusPoints = mapOf(
        1 to "technical feasibility analysis",
        2 to "business impact assessment", 
        3 to "implementation roadmap planning"
    )
)
```
**Result**: AI provides dedicated, deep analysis of each focus area while building comprehensive understanding across rounds.

```kotlin
val strategicReasoningPipe = reasonWithBedrock(
    bedrockConfig,
    multiRoundSettings,
    pipeSettings
)

val strategicPipe = BedrockPipe()
    .setSystemPrompt("Make strategic decisions with focused multi-round analysis.")
    .setReasoningPipe(strategicReasoningPipe)
    .setTokenBudget(TokenBudgetSettings(reasoningBudget = 4000))
```

## Cross-Provider Reasoning

### Ollama-Based Reasoning
```kotlin
import Defaults.reasoning.ReasoningBuilder.reasonWithOllama
import Defaults.OllamaConfiguration

val ollamaConfig = OllamaConfiguration(
    host = "localhost:11434",
    model = "llama3.1:70b"
)

val ollamaReasoningSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.StructuredCot,
    depth = ReasoningDepth.Med,
    duration = ReasoningDuration.Med,
    reasoningInjector = ReasoningInjector.SystemPrompt
)

val ollamaReasoningPipe = reasonWithOllama(
    ollamaConfig,
    ollamaReasoningSettings,
    pipeSettings
)

val ollamaProblemSolver = OllamaPipe()
    .setSystemPrompt("Solve problems using systematic reasoning.")
    .setReasoningPipe(ollamaReasoningPipe)
```

## Reasoning Depth and Duration Settings

### Reasoning Depth
```kotlin
// Light reasoning for simple problems
ReasoningDepth.Low

// Moderate reasoning for standard problems  
ReasoningDepth.Med

// Deep reasoning for complex problems
ReasoningDepth.High
```

### Reasoning Duration
```kotlin
// Quick reasoning for time-sensitive decisions
ReasoningDuration.Short

// Standard reasoning duration
ReasoningDuration.Med

// Extended reasoning for thorough analysis
ReasoningDuration.Long
```

## Practical Examples

### Business Decision Making
```kotlin
val businessFocusPoints = mutableMapOf<Int, String>()
businessFocusPoints[1] = "market analysis and competitive landscape"
businessFocusPoints[2] = "financial impact and ROI projections"
businessFocusPoints[3] = "implementation risks and mitigation strategies"

val businessReasoningSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.ComprehensivePlan,
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Long,
    reasoningInjector = ReasoningInjector.BeforeUserPrompt,
    numberOfRounds = 3,
    focusPoints = businessFocusPoints
)

val businessReasoningPipe = reasonWithBedrock(
    bedrockConfig,
    businessReasoningSettings,
    pipeSettings
)

val businessDecisionPipe = BedrockPipe()
    .setSystemPrompt("Make informed business decisions based on comprehensive analysis.")
    .setReasoningPipe(businessReasoningPipe)
    .pullPipelineContext()

// Usage
val decision = runBlocking { businessDecisionPipe.execute("Should we expand into the European market?") }
```

### Technical Problem Solving
```kotlin
val engineerReasoningSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.RolePlay,
    roleCharacter = "You are a senior software architect with expertise in scalable system design.",
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Med,
    reasoningInjector = ReasoningInjector.SystemPrompt,
    numberOfRounds = 2
)

val engineerReasoningPipe = reasonWithBedrock(
    bedrockConfig,
    engineerReasoningSettings,
    pipeSettings
)

val systemDesignPipe = BedrockPipe()
    .setSystemPrompt("Design robust technical systems with expert analysis.")
    .setReasoningPipe(engineerReasoningPipe)

// Usage
val design = runBlocking { systemDesignPipe.execute("Design a microservices architecture for our e-commerce platform") }
```

### Creative Innovation
```kotlin
val creativeFocusPoints = mutableMapOf<Int, String>()
creativeFocusPoints[1] = "user experience and interface design"
creativeFocusPoints[2] = "market differentiation and unique value proposition"
creativeFocusPoints[3] = "technical feasibility and implementation approach"

val creativeReasoningSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.BestIdea,
    depth = ReasoningDepth.Med,
    duration = ReasoningDuration.Med,
    reasoningInjector = ReasoningInjector.AfterUserPrompt,
    numberOfRounds = 3,
    focusPoints = creativeFocusPoints
)

val creativeReasoningPipe = reasonWithBedrock(
    bedrockConfig,
    creativeReasoningSettings,
    pipeSettings
)

val innovationPipe = BedrockPipe()
    .setSystemPrompt("Develop innovative solutions with creative analysis.")
    .setReasoningPipe(creativeReasoningPipe)

// Usage
val innovation = runBlocking { innovationPipe.execute("Create a new mobile app concept for remote team collaboration") }
```

### Mathematical Problem Solving with Chain of Draft
```kotlin
val mathReasoningSettings = ReasoningSettings(
    reasoningMethod = ReasoningMethod.ChainOfDraft,
    depth = ReasoningDepth.Med,
    duration = ReasoningDuration.Short,
    reasoningInjector = ReasoningInjector.SystemPrompt,
    numberOfRounds = 1
)

val mathReasoningPipe = reasonWithBedrock(
    bedrockConfig,
    mathReasoningSettings,
    pipeSettings
)

val mathSolverPipe = BedrockPipe()
    .setSystemPrompt("Solve mathematical problems with concise, step-by-step reasoning using minimal drafts.")
    .setReasoningPipe(mathReasoningPipe)
    .setTokenBudget(TokenBudgetSettings(reasoningBudget = 500)) // Lower budget due to CoD efficiency

// Usage - demonstrates the efficiency gains
val solution = runBlocking { 
    mathSolverPipe.execute("Jason had 20 lollipops and gave Denny some lollipops. Now Jason has 12 lollipops. How many lollipops did Jason give to Denny?") 
}

// Expected CoD reasoning output:
// "Start: 20 lollipops
//  End: 12 lollipops  
//  20 - 12 = 8
//  Answer: 8 lollipops"
```

**Why Chain of Draft excels here**: Mathematical problems benefit greatly from CoD's concise approach. Instead of verbose explanations, the model focuses on essential operations and calculations, reducing token usage by up to 75% while maintaining accuracy. This makes it ideal for applications requiring frequent mathematical reasoning at scale.

## Nested Reasoning Pipes

TPipe supports unlimited nesting of reasoning pipes, enabling sophisticated multi-layered reasoning architectures for complex problem-solving scenarios.

### Creating Nested Reasoning Structures

```kotlin
// Level 3: Deep analysis reasoning
val deepAnalysis = ReasoningBuilder()
    .setReasoningMethod(ReasoningMethod.StructuredCot)
    .setReasoningRounds(2)
    .build()

// Level 2: Strategic reasoning with nested deep analysis
val strategicReasoning = ReasoningBuilder()
    .setReasoningMethod(ReasoningMethod.ComprehensivePlan)
    .setReasoningRounds(3)
    .build()
    .setReasoningPipe(deepAnalysis)  // Nested reasoning

// Level 1: Main reasoning with nested strategic layer
val mainReasoning = ReasoningBuilder()
    .setReasoningMethod(ReasoningMethod.BestIdea)
    .setReasoningRounds(2)
    .build()
    .setReasoningPipe(strategicReasoning)  // Nested reasoning

// Root pipe with multi-layered reasoning
val rootPipe = BedrockPipe()
    .setSystemPrompt("Solve complex problems using layered reasoning.")
    .setReasoningPipe(mainReasoning)
    .enableTracing()  // Traces all nested levels automatically
```

### Nested Reasoning Execution Flow

1. **Root pipe** receives input
2. **Main reasoning** executes first
3. **Strategic reasoning** executes within main reasoning
4. **Deep analysis** executes within strategic reasoning
5. **Results bubble up** through each layer
6. **Final output** incorporates all reasoning levels

### Use Cases for Nested Reasoning

#### Complex Problem Decomposition
```kotlin
// Research analysis with nested reasoning
val detailAnalysis = ReasoningBuilder()
    .setReasoningMethod(ReasoningMethod.StructuredCot)
    .setReasoningRounds(4)
    .build()

val researchReasoning = ReasoningBuilder()
    .setReasoningMethod(ReasoningMethod.ComprehensivePlan)
    .setReasoningRounds(2)
    .build()
    .setReasoningPipe(detailAnalysis)

val researchPipe = BedrockPipe()
    .setSystemPrompt("Conduct thorough research analysis.")
    .setReasoningPipe(researchReasoning)
```

#### Multi-Perspective Analysis
```kotlin
// Different reasoning approaches at each level
val criticalAnalysis = ReasoningBuilder()
    .setReasoningMethod(ReasoningMethod.StructuredCot)
    .setReasoningRounds(3)
    .build()

val creativeThinking = ReasoningBuilder()
    .setReasoningMethod(ReasoningMethod.BestIdea)
    .setReasoningRounds(2)
    .build()
    .setReasoningPipe(criticalAnalysis)

val balancedReasoning = BedrockPipe()
    .setSystemPrompt("Provide balanced analysis combining creativity and critical thinking.")
    .setReasoningPipe(creativeThinking)
```

### Tracing Nested Reasoning

TPipe automatically propagates tracing through all nested reasoning levels:

```kotlin
val pipeline = Pipeline()
    .enableTracing()  // Traces all nested reasoning automatically
    .add(pipeWithNestedReasoning)

val traceReport = pipeline.getTraceReport(TraceFormat.HTML)
// Contains chronological events from all reasoning levels
```

### Performance Considerations

- **Token budgets** apply to each reasoning level independently
- **Nested depth** should be balanced against performance needs
- **Reasoning rounds** multiply across nesting levels
- **Tracing overhead** increases with nesting depth

### Best Practices for Nested Reasoning

1. **Limit nesting depth** to 3-4 levels for optimal performance
2. **Use different reasoning methods** at each level for diverse perspectives
3. **Configure appropriate token budgets** for each reasoning layer
4. **Enable tracing** to monitor nested execution flow
5. **Test thoroughly** with representative problem complexity

## Best Practices

### 1. Choose Appropriate Reasoning Method
```kotlin
// Use StructuredCot for systematic problem solving
ReasoningMethod.StructuredCot

// Use BestIdea for quick creative solutions
ReasoningMethod.BestIdea

// Use ComprehensivePlan for strategic planning
ReasoningMethod.ComprehensivePlan

// Use RolePlay for domain expertise
ReasoningMethod.RolePlay

// Use ChainOfDraft for concise mathematical reasoning
ReasoningMethod.ChainOfDraft
```

### 2. Match Depth and Duration to Problem Complexity
```kotlin
// Simple problems
ReasoningSettings(
    depth = ReasoningDepth.Low,
    duration = ReasoningDuration.Short
)

// Complex problems
ReasoningSettings(
    depth = ReasoningDepth.High,
    duration = ReasoningDuration.Long
)
```

### 3. Use Focus Points for Multi-Round Reasoning
```kotlin
val focusPoints = mutableMapOf<Int, String>()
focusPoints[1] = "problem analysis"
focusPoints[2] = "solution generation"
focusPoints[3] = "implementation planning"

ReasoningSettings(
    numberOfRounds = 3,
    focusPoints = focusPoints
)
```

### 4. Select Appropriate Injection Method
```kotlin
// SystemPrompt: For instruction-like reasoning
ReasoningInjector.SystemPrompt

// BeforeUserPrompt: For context-like reasoning
ReasoningInjector.BeforeUserPrompt

// AsContext: For structured context integration
ReasoningInjector.AsContext
```

Reasoning pipes built with TPipe-Defaults ReasoningBuilder provide proven, structured approaches to complex problem-solving that enhance any AI model's analytical and decision-making capabilities through systematic chain-of-thought processing.

## Next Steps

Now that you understand chain-of-thought reasoning, learn about dynamic pipeline control:

**→ [Pipeline Flow Control](pipeline-flow-control.md)** - Dynamic routing and conditional execution
