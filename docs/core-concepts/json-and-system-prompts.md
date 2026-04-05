# JSON Prompt Injection and System Prompt Configuration

## Table of Contents
- [JSON Prompt Injection](#json-prompt-injection)
- [System Prompt Configuration](#system-prompt-configuration)
- [Complete JSON Workflow Example](#complete-json-workflow-example)
- [Error Handling and Validation](#error-handling-and-validation)
- [Best Practices](#best-practices)

TPipe provides sophisticated JSON handling through automatic schema generation and prompt injection, plus advanced system prompt management for complex AI interactions.

## JSON Prompt Injection

### The Problem
Most AI models don't natively support structured JSON input/output. You need to force models to understand JSON schemas and produce valid JSON responses through prompt engineering.

### Helper Categories

There are two very different kinds of helpers on this page:

- **Safe composition helpers**: `setMiddlePrompt()` and `setFooterPrompt()` only place extra text at a specific point in the system prompt. They do not replace schema generation or prompt injection behavior.
- **Normal schema helpers**: `setJsonInput(T/KClass)` and `setJsonOutput(T/KClass)` are the standard way to describe structured input and output. They keep TPipe's generated schema path intact.
- **Advanced override helpers**: `setJsonInput(String)`, `setJsonOutput(String)`, `setJsonInputInstructions(...)`, `setJsonOutputInstructions(...)`, `setMergedPcpJsonInstructions(...)`, and `requireJsonPromptInjection(stripExternalText = true)` hand more responsibility back to the caller. Use them only when you specifically want to replace or bypass TPipe's generated guidance.

### How JSON Injection Works

#### Step 1: Define Input/Output Schemas
```kotlin
@Serializable
data class AnalysisRequest(
    val document: String,
    val analysisType: String = "summary",
    val includeMetadata: Boolean = true
)

@Serializable  
data class AnalysisResponse(
    val summary: String,
    val keyPoints: List<String>,
    val confidence: Double,
    val metadata: Map<String, String> = emptyMap()
)

pipe.setJsonInput(AnalysisRequest("", "summary"))
    .setJsonOutput(AnalysisResponse("", emptyList(), 0.0))
```

**What this does**: 
- TPipe automatically generates complete JSON schemas from your Kotlin classes
- Includes all properties, default values, and type information
- Creates example JSON that gets injected into the system prompt
- Using the JSON injector helpers automatically disables native JSON mode under the hood
- Call `requireJsonPromptInjection(stripExternalText = true)` only when you want the explicit strip mode

**Recommended use**: Prefer the typed/KClass overloads unless you intentionally need to hand-write the JSON shape yourself.

### Automatic Schema Generation

#### How Schema Generation Works
```kotlin
// Your data class
@Serializable
data class UserRequest(
    val task: String,
    val priority: Int = 1,
    val tags: List<String> = emptyList(),
    val metadata: RequestMetadata = RequestMetadata()
)

// TPipe automatically generates this JSON schema:
{
    "task": "",
    "priority": 1,
    "tags": [],
    "metadata": {
        "source": "",
        "timestamp": 0
    }
}
```

**Key features**:
- **Complete schemas**: All properties included, even nested objects
- **Default values**: Shows expected values and types
- **Type safety**: Compile-time validation of your schemas

#### Schema Generation with Complex Types
```kotlin
@Serializable
data class ProcessingConfig(
    val steps: List<ProcessingStep>,
    val options: Map<String, String> = emptyMap(),
    val validation: ValidationRules? = null
)

@Serializable
data class ProcessingStep(
    val name: String,
    val enabled: Boolean = true,
    val parameters: StepParameters = StepParameters()
)

pipe.setJsonInput(ProcessingConfig(emptyList()))
```

TPipe generates nested schemas automatically, handling:
- Lists and arrays
- Maps and dictionaries  
- Nullable types
- Nested data classes
- Enums and sealed classes

### Alternative Schema Setting Methods

#### Using KClass for Primitives and Special Cases
```kotlin
// For primitive types
pipe.setJsonInput(String::class)
    .setJsonOutput(Int::class)

// For classes with private constructors
pipe.setJsonInput(MyClassWithPrivateConstructor::class)
    .setJsonOutput(DatabaseEntity::class)

// When you can't instantiate the class
pipe.setJsonInput(SealedClass::class)
```

**When to use KClass overloads:**
- Working with Kotlin primitives (`String::class`, `Int::class`, etc.)
- Classes with private constructors that prevent instantiation
- Sealed classes or abstract classes
- When you only have the KClass reference available

#### Direct String Schema (Not Recommended)
```kotlin
pipe.setJsonInput("""{"name": "string", "age": 0}""")
    .setJsonOutput("""{"result": "string", "success": true}""")
```

### Controlling JSON Injection Behavior

#### Strip Non-JSON Text
```kotlin
pipe.requireJsonPromptInjection(stripExternalText = true)
```

This is the explicit manual mode. It is not needed for normal schema configuration, only when you want TPipe to strip non-JSON text from the response after generation.

**stripExternalText = false** (default): Accepts responses like:
```
Here's my analysis:
{"summary": "Document discusses...", "confidence": 0.85}
I hope this helps!
```

**stripExternalText = true**: Automatically extracts only the JSON:
```
{"summary": "Document discusses...", "confidence": 0.85}
```

#### Custom JSON Instructions
```kotlin
pipe.setJsonInputInstructions("""
    The user provides structured data in JSON format.
    All fields are required unless marked as optional.
    Parse the data carefully and use all provided information.
""")

pipe.setMiddlePrompt("""
    Process the input data according to your core instructions.
    Apply domain-specific knowledge and reasoning.
    Ensure your analysis is thorough and accurate.
""")

pipe.setJsonOutputInstructions("""
    Respond ONLY with valid JSON matching the specified schema.
    Do not include explanations, comments, or additional text.
    Ensure all required fields have appropriate values.
""")
```

`setJsonInputInstructions()` and `setJsonOutputInstructions()` are advanced overrides. They replace TPipe's default JSON input/output guidance, so you are taking responsibility for the wording that normally gets generated for you.

Use them when you need:
- model-specific wording that the defaults don't express well
- a stricter contract than the generated text provides
- custom validation or formatting rules

`setMiddlePrompt()` is different. It is a safe insertion point, not an override. It simply places extra text between the input and output schema blocks.

**Use cases for `setMiddlePrompt()`**:
- processing instructions that apply after understanding the input format
- domain-specific guidance that should come before output formatting rules
- reasoning or analysis instructions that bridge input and output

### What Gets Injected Into System Prompt

When you enable JSON injection, TPipe automatically adds instructions in this order:

```
1. Your base system prompt
2. JSON input instructions + input schema
3. Middle prompt instructions (if set)
4. JSON output instructions + output schema
5. Footer prompt (if set)
```

**Example of complete injected prompt**:
```
You are a document analysis assistant.

The user will provide input in the form of JSON.
The JSON input is as follows: {"document": "", "analysisType": "summary"}

Process the input data according to your core instructions.
Apply domain-specific knowledge and ensure thorough analysis.

You must respond only in valid JSON format matching this structure:
{"summary": "", "confidence": 0.0, "keyPoints": []}

Always validate your JSON output before responding.
```

This happens automatically when you call `setSystemPrompt()` or `applySystemPrompt()`.

`setFooterPrompt()` is part of that safe composition flow. It appends extra text after all the generated JSON and protocol blocks have already been assembled.

## System Prompt Configuration

### Basic System Prompt Setup
```kotlin
pipe.setSystemPrompt("""
    You are a document analysis assistant.
    Analyze provided documents and extract key information.
    Focus on accuracy and completeness.
""")
```

**Important**: Set JSON input/output BEFORE calling `setSystemPrompt()` so JSON instructions get properly injected. The JSON setter helpers now turn on prompt injection automatically, so you only need `requireJsonPromptInjection()` when you want the explicit strip mode.

### System Prompt Composition Order
TPipe builds system prompts in this order:

1. **Your base system prompt**
2. **JSON input instructions + schema** (if JSON injection enabled)
3. **Middle prompt instructions** (if set)
4. **JSON output instructions + schema** (if JSON injection enabled)
5. **Footer prompt** (if set)

```kotlin
pipe.setJsonInput(inputSchema)           // 1. Configure JSON input
    .setJsonInputInstructions("Input rules")  // 2. Input instructions
    .setMiddlePrompt("Processing guidance")   // 3. Middle instructions
    .setJsonOutput(outputSchema)         // 4. Configure JSON output
    .setJsonOutputInstructions("Output rules") // 5. Output instructions
    .setSystemPrompt("Base instructions") // 6. Set system prompt (includes all JSON)
    .setFooterPrompt("Final notes")      // 7. Add footer
```

### Footer Prompts
```kotlin
pipe.setFooterPrompt("""
    Important: Always validate your JSON output before responding.
    If you cannot provide a complete response, indicate this in the appropriate fields.
""")
```

**Purpose**: Instructions that must appear at the very end of the system prompt, after all other injections.

### Dynamic System Prompt Updates
```kotlin
// Initial setup
pipe.setSystemPrompt("You are an assistant")

// Later, update JSON schemas and rebuild
pipe.setJsonInput(newInputSchema)
    .setJsonOutput(newOutputSchema)
    .applySystemPrompt()  // Rebuilds system prompt with new JSON instructions
```

**applySystemPrompt()**: Reconstructs the complete system prompt with current JSON schemas and other components.

### Advanced Overrides

The following helpers are powerful because they replace the normal generated path instead of just adding to it:

- `setJsonInput(String)` and `setJsonOutput(String)` let you provide the schema text yourself.
- `setJsonInputInstructions(...)` and `setJsonOutputInstructions(...)` replace the default input/output guidance text.
- `setMergedPcpJsonInstructions(...)` replaces the merged PCP + JSON instruction block.
- `requireJsonPromptInjection(stripExternalText = true)` turns on explicit prompt injection and response stripping.

These are fine when you know exactly why you need them. They are not the default path for ordinary schema-driven prompting.

## Complete JSON Workflow Example

### 1. Define Your Data Structures
```kotlin
@Serializable
data class DocumentRequest(
    val content: String,
    val analysisType: String = "comprehensive",
    val language: String = "en",
    val extractEntities: Boolean = true
)

@Serializable
data class DocumentAnalysis(
    val summary: String,
    val entities: List<Entity>,
    val sentiment: String,
    val confidence: Double,
    val processingTime: Long = 0
)

@Serializable
data class Entity(
    val text: String,
    val type: String,
    val confidence: Double
)
```

### 2. Configure the Pipe
```kotlin
val analysisPipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setRegion("us-west-2")
    .setJsonInput(DocumentRequest(""))
    .setJsonInputInstructions("""
        Parse the document content and analysis parameters carefully.
        Use the specified language and analysis type to guide your approach.
    """)
    .setMiddlePrompt("""
        Apply professional document analysis techniques.
        Focus on accuracy and provide confidence scores for all findings.
        Extract entities using standard NLP categories.
    """)
    .setJsonOutput(DocumentAnalysis("", emptyList(), "", 0.0))
    .setJsonOutputInstructions("""
        Provide complete analysis results in valid JSON format.
        Ensure all confidence scores are between 0.0 and 1.0.
    """)
    .setSystemPrompt("""
        You are a professional document analysis system.
        Analyze documents according to the provided parameters.
        Extract entities, determine sentiment, and provide confidence scores.
    """)
    .setFooterPrompt("""
        Use standard entity types: PERSON, ORGANIZATION, LOCATION, etc.
        If analysis cannot be completed, indicate this in appropriate fields.
    """)
```

### 3. Use the Pipe
```kotlin
val request = DocumentRequest(
    content = "Apple Inc. announced record profits...",
    analysisType = "comprehensive",
    extractEntities = true
)

val jsonInput = Json.encodeToString(request)
val response = runBlocking { analysisPipe.generateText(jsonInput) }
val result = Json.decodeFromString<DocumentAnalysis>(response)
```

## Error Handling and Validation

### JSON Schema Validation
```kotlin
// Validate schemas at compile time
@Serializable
data class ValidatedSchema(
    val required: String,                    // Required field
    val optional: String = "",               // Optional with default
    val list: List<String> = emptyList(),    // Typed collections
    val enum: Status = Status.PENDING       // Enum types
)

enum class Status { PENDING, PROCESSING, COMPLETE }
```

### Runtime JSON Handling
```kotlin
fun safeJsonProcessing(pipe: BedrockPipe, input: Any): MyResponse? {
    return try {
        val jsonInput = Json.encodeToString(input)
        val response = runBlocking { pipe.generateText(jsonInput) }
        Json.decodeFromString<MyResponse>(response)
    } catch (e: SerializationException) {
        // Handle malformed JSON from AI
        null
    } catch (e: Exception) {
        // Handle other errors
        null
    }
}
```

## Best Practices

### 1. Schema Design
```kotlin
// Good: Complete schema with defaults
@Serializable
data class GoodSchema(
    val required: String,
    val optional: String = "",
    val list: List<Item> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

// Avoid: Incomplete schemas
@Serializable
data class IncompleteSchema(
    val field: String  // No defaults, AI might not understand expected format
)
```

### 2. JSON Injection Setup Order
```kotlin
// Correct order
pipe.setJsonInput(inputSchema)           // 1. Set input schema
    .setJsonOutput(outputSchema)         // 2. Set output schema  
    .setSystemPrompt("Instructions")     // 3. Set system prompt (includes JSON)

// Wrong order - JSON instructions won't be included
pipe.setSystemPrompt("Instructions")     // System prompt set too early
    .setJsonOutput(outputSchema)         // JSON injection enabled after
```

### 3. Model-Specific Considerations
```kotlin
// Models that add explanations despite instructions
pipe.requireJsonPromptInjection(stripExternalText = true)
    .setJsonOutputInstructions("Output ONLY valid JSON. No explanations.")
```

### 4. Testing JSON Schemas
```kotlin
// Test schema generation
val testInput = MyInputClass()
val testOutput = MyOutputClass()

// Verify schemas are complete
val inputJson = Json.encodeToString(testInput)
val outputJson = Json.encodeToString(testOutput)

println("Input schema: $inputJson")
println("Output schema: $outputJson")
```

This JSON injection system enables reliable structured interactions with any AI model, regardless of native JSON support, through automatic schema generation and intelligent prompt engineering.

## Next Steps

Now that you understand structured AI interactions, learn about TPipe's memory system:

**→ [Context Window - Memory Storage and Retrieval](context-window.md)** - TPipe's memory system
