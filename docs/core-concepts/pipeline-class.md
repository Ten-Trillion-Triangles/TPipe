# Pipeline Class - Core Concepts

## Table of Contents
- [What is a Pipeline?](#what-is-a-pipeline)
- [Core Pipeline Concepts](#core-pipeline-concepts)
- [Basic Pipeline Operations](#basic-pipeline-operations)
- [Pipeline Configuration](#pipeline-configuration)
- [Pipeline Execution Flow](#pipeline-execution-flow)
- [Practical Pipeline Examples](#practical-pipeline-examples)
- [Pipeline Benefits](#pipeline-benefits)
- [Best Practices](#best-practices)

The Pipeline class orchestrates multiple AI model interactions by chaining Pipe instances together. Each pipe processes content and passes results to the next pipe, enabling complex multi-stage AI workflows.

## What is a Pipeline?

### The Problem
Single AI model calls are limited:
- One model may not handle complex multi-step tasks
- Different models excel at different tasks (analysis vs generation vs validation)
- You need to process, validate, and refine AI outputs through multiple stages

### The Solution
```kotlin
val pipeline = Pipeline()
    .add(analysisPipe)      // Stage 1: Analyze input
    .add(generationPipe)    // Stage 2: Generate content
    .add(validationPipe)    // Stage 3: Validate output

val result = pipeline.execute("Process this document...")
```

**What this does**: Executes pipes in sequence, where each pipe receives the output of the previous pipe as input.

## Core Pipeline Concepts

### Sequential Execution
```kotlin
val pipeline = Pipeline()
    .add(pipe1)  // Executes first
    .add(pipe2)  // Receives pipe1's output as input
    .add(pipe3)  // Receives pipe2's output as input

// Input → Pipe1 → Pipe2 → Pipe3 → Final Output
```

### Content Flow
Pipelines pass `MultimodalContent` between pipes:
- **Text content**: Main processing content
- **Binary content**: Images, documents, files
- **Model reasoning**: AI thinking/reasoning output
- **Metadata**: Processing information and jump instructions

### Context Sharing
```kotlin
val pipeline = Pipeline()
pipeline.context  // Shared context window across all pipes
```

All pipes in a pipeline can access and modify shared context, enabling:
- Information accumulation across stages
- Cross-pipe communication
- Persistent state management

## Basic Pipeline Operations

### Adding Pipes
```kotlin
val pipeline = Pipeline()

// Add single pipe
pipeline.add(myPipe)

// Add multiple pipes
pipeline.addAll(listOf(pipe1, pipe2, pipe3))

// Method chaining
pipeline.add(pipe1)
    .add(pipe2)
    .add(pipe3)
```

### Executing Pipelines
```kotlin
// Text-only execution
val result: String = pipeline.execute("Input text")

// Multimodal execution
val content = MultimodalContent(
    text = "Analyze this document",
    binaryContent = listOf(BinaryContent.fromFile("document.pdf"))
)
val result: MultimodalContent = pipeline.execute(content)
```

### Pipeline Information
```kotlin
// Get all pipes
val pipes: List<Pipe> = pipeline.getPipes()

// Find pipe by name
val (index, pipe) = pipeline.getPipeByName("analysis-pipe")

// Token usage tracking
val tokenInfo = pipeline.getTokenCount()
// Returns: "Input tokens: 1500 \n Output Tokens: 2300"
```

## Pipeline Configuration

### Pipeline Naming
```kotlin
pipeline.setPipelineName("document-processing-pipeline")
```

**Purpose**: Debugging, monitoring, and tracing identification.

### Tracing and Monitoring
```kotlin
// Enable tracing
pipeline.enableTracing()

// Custom trace configuration
val traceConfig = TraceConfig(
    enabled = true,
    outputFormat = TraceFormat.HTML,
    detailLevel = TraceDetailLevel.VERBOSE
)
pipeline.enableTracing(traceConfig)

// Get trace reports
val traceReport = pipeline.getTraceReport(TraceFormat.HTML)
val failureAnalysis = pipeline.getFailureAnalysis()
val traceId = pipeline.getTraceId()
```

## Pipeline Execution Flow

### Normal Sequential Flow
```kotlin
val pipeline = Pipeline()
    .add(inputProcessor)    // Stage 1: Clean and validate input
    .add(analyzer)         // Stage 2: Analyze content  
    .add(generator)        // Stage 3: Generate response
    .add(validator)        // Stage 4: Validate output

// Execution: Input → Stage1 → Stage2 → Stage3 → Stage4 → Output
```

### Conditional Execution with Pipe Jumping
```kotlin
// Pipes can instruct the pipeline to jump to specific pipes
content.setJumpToPipe("error-handler")  // Jump to named pipe
content.setJumpToPipe(2)                // Jump to pipe at index 2

// Pipeline automatically handles jumps during execution
```

### Error Handling and Branching
```kotlin
val mainPipe = BedrockPipe()
    .setPipeName("main-processor")
    .setValidatorFunction { content ->
        if (content.text.contains("error")) {
            content.setJumpToPipe("error-handler")
            false  // Validation failed, trigger jump
        } else {
            true   // Continue normal flow
        }
    }

val errorPipe = BedrockPipe()
    .setPipeName("error-handler")

pipeline.add(mainPipe)
    .add(errorPipe)
```

## Practical Pipeline Examples

### 1. Document Analysis Pipeline
```kotlin
val documentPipeline = Pipeline()
    .setPipelineName("document-analysis")
    .enableTracing()

// Stage 1: Extract and clean text
val extractorPipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setSystemPrompt("Extract and clean text from the provided document.")
    .setPipeName("text-extractor")

// Stage 2: Analyze content
val analyzerPipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("Analyze the document for key themes, entities, and sentiment.")
    .setPipeName("content-analyzer")

// Stage 3: Generate summary
val summarizerPipe = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setSystemPrompt("Create a concise summary of the analysis results.")
    .setPipeName("summarizer")

documentPipeline.add(extractorPipe)
    .add(analyzerPipe)
    .add(summarizerPipe)

val result = documentPipeline.execute(documentContent)
```

### 2. Quality Assurance Pipeline
```kotlin
val qaPipeline = Pipeline()
    .setPipelineName("quality-assurance")

// Stage 1: Generate content
val generatorPipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("Generate high-quality content based on the request.")
    .setPipeName("content-generator")

// Stage 2: Quality check
val checkerPipe = BedrockPipe()
    .setModel("openai.gpt-oss-20b-1:0")
    .setRegion("us-west-2")
    .requireJsonPromptInjection()
    .setJsonOutput(QualityResult(false, emptyList()))
    .setSystemPrompt("Evaluate content quality and identify issues.")
    .setPipeName("quality-checker")
    .setValidatorFunction { content ->
        val quality = Json.decodeFromString<QualityResult>(content.text)
        if (!quality.passed) {
            content.setJumpToPipe("content-fixer")
            false
        } else {
            true
        }
    }

// Stage 3: Fix issues (conditional)
val fixerPipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("Fix the identified quality issues in the content.")
    .setPipeName("content-fixer")

qaPipeline.add(generatorPipe)
    .add(checkerPipe)
    .add(fixerPipe)
```

### 3. Multi-Model Reasoning Pipeline
```kotlin
val reasoningPipeline = Pipeline()
    .setPipelineName("multi-model-reasoning")

// Stage 1: Initial analysis (fast model)
val quickAnalysis = BedrockPipe()
    .setModel("anthropic.claude-3-haiku-20240307-v1:0")
    .setSystemPrompt("Provide initial analysis and identify key areas for deeper investigation.")
    .setPipeName("quick-analysis")

// Stage 2: Deep reasoning (reasoning model)
val deepReasoning = BedrockPipe()
    .setModel("deepseek.r1-v1:0")
    .setRegion("us-east-2")
    .setSystemPrompt("Perform deep analysis and reasoning on the identified areas.")
    .setReasoning()
    .setPipeName("deep-reasoning")

// Stage 3: Synthesis (balanced model)
val synthesizer = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("Synthesize the quick analysis and deep reasoning into final conclusions.")
    .setPipeName("synthesizer")

reasoningPipeline.add(quickAnalysis)
    .add(deepReasoning)
    .add(synthesizer)
```

## Pipeline Benefits

### 1. Specialization
- Use different models for different tasks
- Optimize each stage for specific requirements
- Leverage model strengths (speed vs accuracy vs reasoning)

### 2. Quality Control
- Multi-stage validation and refinement
- Error detection and correction
- Quality assurance workflows

### 3. Complex Workflows
- Multi-step processing that exceeds single model capabilities
- Conditional logic and branching
- State management across processing stages

### 4. Monitoring and Debugging
- Trace execution through each stage
- Identify bottlenecks and failures
- Token usage tracking across the entire pipeline

## Best Practices

### 1. Pipeline Design
```kotlin
// Clear naming for debugging
pipeline.setPipelineName("descriptive-name")
pipe.setPipeName("stage-description")

// Enable tracing for production monitoring
pipeline.enableTracing()
```

### 2. Error Handling with Branch Pipes
```kotlin
// Use branch pipes for error correction within individual pipes
val mainProcessor = BedrockPipe()
    .setPipeName("main-processor")
    .setValidatorFunction { content ->
        val isValid = validateOutput(content)
        if (!isValid) {
            // Validation failed, branch pipe will handle correction
            false
        } else {
            true  // Continue to next pipe in pipeline
        }
    }
    .setBranchPipe(errorCorrectionPipe)  // Handles validation failures

val errorCorrectionPipe = BedrockPipe()
    .setPipeName("error-correction")
    .setSystemPrompt("Fix the identified issues in the content.")

// Add only the main processor to pipeline - branch pipe is internal
pipeline.add(mainProcessor)
```

### 3. Model Selection
```kotlin
// Fast models for preprocessing
val preprocessor = BedrockPipe().setModel("anthropic.claude-3-haiku-20240307-v1:0")

// Powerful models for main processing  
val processor = BedrockPipe().setModel("anthropic.claude-3-sonnet-20240229-v1:0")

// Specialized models for validation
val validator = BedrockPipe().setModel("openai.gpt-oss-20b-1:0")
```

Pipelines enable sophisticated AI workflows that exceed the capabilities of individual models, providing quality control, specialization, and complex processing logic through coordinated multi-stage execution.

## Next Steps

Now that you understand pipeline orchestration, learn about structured AI interactions:

**→ [JSON Schema and System Prompts](json-and-system-prompts.md)** - Structured AI interactions
