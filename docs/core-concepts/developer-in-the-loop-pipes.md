# Developer-in-the-Loop Pipes

## Table of Contents
- [What are Developer-in-the-Loop Pipes?](#what-are-developer-in-the-loop-pipes)
- [Validator Pipe](#validator-pipe)
- [Transformation Pipe](#transformation-pipe)
- [Branch Pipe](#branch-pipe)
- [Pipe Execution Order](#pipe-execution-order)
- [Combined DITL Pipes and Functions](#combined-ditl-pipes-and-functions)
- [Practical Examples](#practical-examples)
- [Best Practices](#best-practices)

TPipe provides developer-in-the-loop pipes that use AI models instead of code functions for validation, transformation, and error handling. These pipes enable AI-powered processing chains where complex logic that's difficult to code can be handled by specialized AI models.

## What are Developer-in-the-Loop Pipes?

Developer-in-the-loop pipes are AI-powered alternatives to code-based DITL functions:

- **Validator Pipe**: AI model validates output instead of validator function
- **Transformation Pipe**: AI model transforms output instead of transformation function  
- **Branch Pipe**: AI model handles failures instead of onFailure function

These pipes are invoked automatically when their corresponding functions are not assigned, or can be used alongside functions for enhanced processing.

## Validator Pipe

### Purpose
Uses an AI model to validate the output of the main pipe when validation logic is too complex for code or requires subjective judgment.

### ⚠️ Important: Validator Pipe Output Behavior

**Validator pipes generate AI output for validation analysis, but this output is discarded by the parent pipe.** The validator pipe's output is only checked for termination status (`shouldTerminate()`). All subsequent operations (validatorFunction, transformationPipe, branchPipe) receive the original generated content from the main pipe, not the validator pipe's output.

This means:
- The validator pipe performs AI-based analysis of the content
- The validator pipe can signal termination if critical issues are found
- The validator pipe's text output and modifications are not passed forward
- The original generated content flows to validatorFunction and downstream operations

### When to Use
- Complex content quality assessment requiring AI analysis
- Subjective validation (tone, style, appropriateness)
- Multi-criteria validation that's hard to code
- Domain-specific validation requiring expertise

### Implementation
```kotlin
val validatorPipe = BedrockPipe()
    .setSystemPrompt("""
        You are a content validator. Analyze the provided content and determine if it meets quality standards.
        
        Validation criteria:
        - Content must be factually accurate
        - Tone must be professional and helpful
        - Information must be complete and relevant
        
        Respond with "VALID" if content passes all criteria, or "INVALID: [reason]" if it fails.
    """)

val mainPipe = BedrockPipe()
    .setSystemPrompt("Generate helpful content based on user input.")
    .setValidatorPipe(validatorPipe)
    .setValidatorFunction { content ->
        // Receives ORIGINAL generated content, not validator pipe output
        // Implement your validation logic here
        content.text.length > 50
    }
```

**Execution flow**:
1. Main pipe generates content
2. Validator pipe receives and analyzes the generated content
3. Validator pipe output is checked for termination only
4. ValidatorFunction receives the ORIGINAL generated content
5. If validation passes, continue pipeline
6. If validation fails, trigger branch pipe or failure handling

### Advanced Validator Pipe
```kotlin
val contentValidator = BedrockPipe()
    .setSystemPrompt("""
        Validate content for publication readiness:
        
        Check for:
        1. Grammar and spelling errors
        2. Factual accuracy and consistency
        3. Appropriate tone for target audience
        4. Completeness of information
        5. Compliance with content guidelines
        
        Return JSON: {"valid": true/false, "issues": ["list of issues"], "score": 0-100}
    """)
    .setJsonOutput(ValidationResult("", false, emptyList(), 0))

val publishingPipe = BedrockPipe()
    .setSystemPrompt("Create publication-ready content.")
    .setValidatorPipe(contentValidator)
    .setValidatorFunction { content ->
        // Parse AI validation result from validator pipe
        // Content here is the ORIGINAL generated content
        val validation = Json.decodeFromString<ValidationResult>(content.text)
        validation.valid && validation.score >= 80
    }
```

## Transformation Pipe

### Purpose
Uses an AI model to transform or enhance the output when transformation logic is complex or requires creative processing.

### When to Use
- Complex text restructuring or reformatting
- Creative enhancement or style adaptation
- Multi-step transformations that are hard to code
- Context-aware content modification

### Implementation
```kotlin
val transformationPipe = BedrockPipe()
    .setSystemPrompt("""
        Transform the provided content to make it more engaging and accessible:
        
        - Simplify complex technical language
        - Add relevant examples and analogies
        - Improve readability and flow
        - Maintain accuracy while enhancing clarity
        
        Return only the transformed content.
    """)

val technicalPipe = BedrockPipe()
    .setSystemPrompt("Generate technical documentation.")
    .setTransformationPipe(transformationPipe)  // AI transforms output
```

### Multi-Stage Transformation
```kotlin
val styleTransformer = BedrockPipe()
    .setSystemPrompt("Adapt content style for the target audience.")
    .pullPipelineContext()  // Access audience info from pipeline

val formatTransformer = BedrockPipe()
    .setSystemPrompt("Format content for the specified output format.")

val contentPipe = BedrockPipe()
    .setSystemPrompt("Generate initial content.")
    .setTransformationPipe(styleTransformer)  // First transformation
    .setTransformationFunction { content ->
        // Additional transformation after AI processing
        val formatted = runBlocking { formatTransformer.execute(content.text) }
        content.text = formatted.text
        content
    }
```

## Branch Pipe

### Purpose
Uses an AI model to handle validation failures and attempt error correction when automated recovery is complex.

### When to Use
- Intelligent error correction and content repair
- Context-aware failure recovery
- Creative problem-solving for failed outputs
- Multi-attempt correction strategies

> ℹ️ **Note:** For consistency with `onFailure` handlers, the output of a branch pipe is processed by any configured transformation pipe or function before becoming the final result.

### Implementation
```kotlin
val errorCorrectionPipe = BedrockPipe()
    .setSystemPrompt("""
        The previous content failed validation. Analyze the issues and provide a corrected version.
        
        Common issues to fix:
        - Factual inaccuracies or inconsistencies
        - Inappropriate tone or style
        - Missing or incomplete information
        - Format or structure problems
        
        Provide the corrected content that addresses these issues.
    """)

val contentPipe = BedrockPipe()
    .setSystemPrompt("Generate content based on user requirements.")
    .setValidatorFunction { content ->
        // Validation logic that might fail
        validateContent(content.text)
    }
    .setBranchPipe(errorCorrectionPipe)  // AI handles failures
```

### Intelligent Error Recovery
```kotlin
val smartRecoveryPipe = BedrockPipe()
    .setSystemPrompt("""
        The content failed validation. You have access to the original input and the failed output.
        
        Analyze what went wrong and create a better version that:
        1. Addresses the validation failure reasons
        2. Maintains the original intent
        3. Improves upon the failed attempt
        
        Be creative in finding solutions to the identified problems.
    """)
    .pullPipelineContext()  // Access original input and context

val robustPipe = BedrockPipe()
    .setSystemPrompt("Generate high-quality content.")
    .setValidatorFunction { content ->
        val quality = assessQuality(content.text)
        quality > 0.8  // High quality threshold
    }
    .setBranchPipe(smartRecoveryPipe)
```

## Pipe Execution Order

When multiple DITL pipes are configured, they execute in this order:

1. **Main Pipe** - Primary AI processing
2. **Validator Pipe** (if set and no validator function) - AI validation
3. **Validator Function** (if set) - Code validation
4. **Transformation Pipe** (if set and no transformation function) - AI transformation
5. **Transformation Function** (if set) - Code transformation
6. **Branch Pipe** (if validation fails and no onFailure function) - AI error handling
7. **OnFailure Function** (if validation fails and set) - Code error handling

## Combined DITL Pipes and Functions

### Pipes with Function Fallbacks
```kotlin
val comprehensivePipe = BedrockPipe()
    .setSystemPrompt("Generate comprehensive content.")
    .setValidatorPipe(validatorPipe)        // AI validation
    .setValidatorFunction { content ->      // Code validation as backup
        content.text.length > 100  // Minimum length check
    }
    .setTransformationPipe(transformerPipe) // AI transformation
    .setTransformationFunction { content -> // Code transformation as enhancement
        content.text = addMetadata(content.text)
        content
    }
    .setBranchPipe(recoveryPipe)           // AI error recovery
    .setOnFailure { original, processed -> // Code fallback
        MultimodalContent("Fallback content when AI recovery fails")
    }
```

### Conditional DITL Pipe Usage
```kotlin
val adaptivePipe = BedrockPipe()
    .setSystemPrompt("Generate content with adaptive processing.")
    .setValidatorFunction { content ->
        val complexity = assessComplexity(content.text)
        
        if (complexity > 0.8) {
            // Use AI validator for complex content
            setValidatorPipe(complexValidatorPipe)
            true  // Let AI validator handle it
        } else {
            // Use simple code validation for simple content
            validateSimpleContent(content.text)
        }
    }
```

## Practical Examples

### Content Publishing Pipeline
```kotlin
val contentPipeline = Pipeline()
    .add(BedrockPipe()
        .setSystemPrompt("Generate initial content draft.")
        .setTransformationPipe(BedrockPipe()
            .setSystemPrompt("Enhance content for readability and engagement.")
        )
        .setValidatorPipe(BedrockPipe()
            .setSystemPrompt("Validate content meets publication standards.")
        )
        .setBranchPipe(BedrockPipe()
            .setSystemPrompt("Fix content issues identified during validation.")
        )
    )
```

### Technical Documentation System
```kotlin
val docGenerationPipe = BedrockPipe()
    .setSystemPrompt("Generate technical documentation.")
    .setTransformationPipe(BedrockPipe()
        .setSystemPrompt("Format documentation with proper structure and examples.")
    )
    .setValidatorPipe(BedrockPipe()
        .setSystemPrompt("Validate technical accuracy and completeness.")
    )
```

### Creative Writing Assistant
```kotlin
val creativeWritingPipe = BedrockPipe()
    .setSystemPrompt("Generate creative content based on prompts.")
    .setTransformationPipe(BedrockPipe()
        .setSystemPrompt("Enhance prose style and narrative flow.")
    )
    .setValidatorPipe(BedrockPipe()
        .setSystemPrompt("Evaluate creative quality and narrative consistency.")
    )
    .setBranchPipe(BedrockPipe()
        .setSystemPrompt("Revise content to improve creative elements.")
    )
```

## Best Practices

### 1. Validator Pipe Setup
```kotlin
// Correct: Always provide both required functions
val properValidatorPipe = BedrockPipe()
    .setSystemPrompt("Validate content quality and accuracy.")
## Best Practices

### 1. Validator Pipe Usage
```kotlin
// Validator pipe for AI-based validation analysis
val validatorPipe = BedrockPipe()
    .setSystemPrompt("Validate content quality and accuracy.")

val properPipe = BedrockPipe()
    .setValidatorPipe(validatorPipe)
    .setValidatorFunction { content ->
        // Receives original generated content
        // Implement validation logic here
        content.text.contains("VALID")
    }
```

### 2. Clear Pipe Purposes
```kotlin
// Good: Specific, focused pipe responsibilities
val grammarValidator = BedrockPipe()
    .setSystemPrompt("Check grammar and spelling only.")

val styleTransformer = BedrockPipe()
    .setSystemPrompt("Adapt writing style for target audience.")

// Avoid: Overly broad or unclear purposes
val genericPipe = BedrockPipe()
    .setSystemPrompt("Fix everything that's wrong.")
```

### 3. Appropriate Model Selection
```kotlin
// Use appropriate models for different pipe types
val validatorPipe = BedrockPipe()
    .setModel("gpt-4")  // Strong analytical capabilities
    
val transformerPipe = BedrockPipe()
    .setModel("claude-3-haiku")  // Fast for simple transformations
```

### 4. Error Handling
```kotlin
// Always provide fallbacks for DITL pipes
val robustPipe = BedrockPipe()
    .setValidatorPipe(aiValidator)
    .setValidatorFunction { content ->  // Fallback validation
        basicValidation(content.text)
    }
    .setBranchPipe(aiRecovery)
    .setOnFailure { original, processed ->  // Final fallback
        generateFallbackContent(original.text)
    }
```

### 5. Context Sharing
```kotlin
// Share context between DITL pipes
val contextAwarePipe = BedrockPipe()
    .pullPipelineContext()
    .setValidatorPipe(BedrockPipe()
        .pullPipelineContext()  // Validator has same context
        .setSystemPrompt("Validate using pipeline context.")
    )
    .setTransformationPipe(BedrockPipe()
        .pullPipelineContext()  // Transformer has same context
        .setSystemPrompt("Transform using pipeline context.")
    )
```

Human-in-the-loop pipes enable sophisticated AI-powered processing chains where complex validation, transformation, and error handling logic can be implemented using AI models instead of traditional code, providing flexibility and intelligence that goes beyond rule-based approaches.

## Next Steps

Now that you understand AI-powered DITL processing, learn about advanced reasoning capabilities:

**→ [Reasoning Pipes](reasoning-pipes.md)** - Chain-of-thought reasoning capabilities
