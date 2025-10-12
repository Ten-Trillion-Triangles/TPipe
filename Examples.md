# TPipe Code Examples

## Basic Pipe Configuration

Create a single AI pipe with model settings and execute it directly.

```kotlin
val pipe = OllamaPipe()
    .setProvider(ProviderName.Ollama)
    .setModel("llama3")
    .setSystemPrompt("You are a helpful assistant that analyzes documents")
    .setTemperature(0.7)
    .setTopP(0.9)
    .setMaxTokens(2048)

val result = runBlocking {
    pipe.execute("Analyze this document: $documentText")
}
```

## Builder Pattern Usage

Chain multiple configuration methods to set up complex pipe parameters.

```kotlin
val documentAnalyzer = OllamaPipe()
    .setModel("llama3:70b")
    .setSystemPrompt("Extract key information from legal documents")
    .setUserPrompt("Document to analyze:")
    .setTemperature(0.3)
    .setTopP(0.8)
    .setTopL(40)
    .setMaxTokens(4096)
    .setContextWindowSize(16000)
    .setContextWindowSettings(ContextWindowSetings.TruncateTop)
    .setStopSequences(listOf("\n\n---", "END_ANALYSIS"))
    .setRepetitionPenalty(1.1)
```

## Native Function Integration

Integrate Kotlin functions directly into pipes for validation and data transformation without JSON serialization overhead.

```kotlin
fun validateJsonStructure(json: String): Boolean
{
    return try
    {
        val parsed = Json.parseToJsonElement(json)
        parsed.jsonObject.containsKey("summary") && 
        parsed.jsonObject.containsKey("key_points")
    }
    catch (e: Exception)
    {
        false
    }
}

fun extractKeyData(json: String): String
{
    val parsed = Json.parseToJsonElement(json)
    val summary = parsed.jsonObject["summary"]?.jsonPrimitive?.content ?: ""
    val keyPoints = parsed.jsonObject["key_points"]?.jsonArray?.map { 
        it.jsonPrimitive.content 
    } ?: emptyList()
    
    return Json.encodeToString(mapOf(
        "extracted_summary" to summary,
        "important_points" to keyPoints,
        "extraction_timestamp" to System.currentTimeMillis()
    ))
}

val pipeWithNativeFunctions = OllamaPipe()
    .setModel("llama3")
    .setSystemPrompt("Analyze documents and return JSON")
    .setValidatorFunction(::validateJsonStructure)
    .setTransformationFunction(::extractKeyData)
```

## JSON Input/Output Configuration

Use structured data classes for type-safe JSON input and output handling.

```kotlin
@Serializable
data class DocumentRequest(
    val content: String,
    val analysisType: String = "summary",
    val maxLength: Int = 500
)

@Serializable
data class AnalysisResult(
    val summary: String = "",
    val keyPoints: List<String> = emptyList(),
    val confidence: Double = 0.0,
    val processingTime: Long = 0L
)

val structuredPipe = OllamaPipe()
    .setModel("llama3")
    .setJsonInput(DocumentRequest("content", "analysis"))
    .setJsonOutput(AnalysisResult())
    .setSystemPrompt("Process documents according to the JSON schema")
```

## Context Management

Add weighted context entries that automatically trigger based on input relevance.

```kotlin
val contextAwarePipe = OllamaPipe()
    .setModel("llama3")
    .setContextWindowSize(12000)
    .setContextWindowSettings(ContextWindowSetings.TruncateMiddle)
    .pullGlobalContext()
    .updatePipelineContextOnExit()
    .setPageKey("legal_analysis")

contextAwarePipe.contextWindow.addLoreBookEntry(
    key = "legal_precedent",
    value = "Important legal precedents and case law...",
    weight = 10
)
```

## Multi-Tier Validation with Recovery

Set up automatic validation with AI-powered error recovery using branch pipes.

```kotlin
val validatorPipe = OllamaPipe()
    .setModel("llama3:8b")
    .setSystemPrompt("Validate if the analysis is complete and accurate. Return 'VALID' or 'INVALID'.")

val recoveryPipe = OllamaPipe()
    .setModel("llama3:70b")
    .setSystemPrompt("Fix and improve the following analysis")
    .setTemperature(0.5)

val robustAnalyzer = OllamaPipe()
    .setModel("llama3")
    .setSystemPrompt("Perform comprehensive document analysis")
    .setValidatorFunction { json ->
        json.contains("summary") && json.length > 100
    }
    .setValidatorPipe(validatorPipe)
    .setBranchPipe(recoveryPipe)
```

## Basic Pipeline Construction

Chain multiple pipes together for sequential processing workflows.

```kotlin
val extractionPipe = OllamaPipe()
    .setModel("llama3:8b")
    .setSystemPrompt("Extract key information from documents")
    .setTemperature(0.3)

val analysisPipe = OllamaPipe()
    .setModel("llama3:70b")
    .setSystemPrompt("Perform detailed analysis")
    .setTemperature(0.7)

val documentPipeline = Pipeline()
    .add(extractionPipe)
    .add(analysisPipe)
    .init()

val result = runBlocking {
    documentPipeline.execute("Process this document: $documentContent")
}
```

## Pipeline with Global Context Sharing

Share context data between pipes and persist it globally across pipeline executions.

```kotlin
val contextSharingPipeline = Pipeline()
    .add(OllamaPipe()
        .setModel("llama3")
        .setSystemPrompt("Extract entities and relationships")
        .updatePipelineContextOnExit()
    )
    .add(OllamaPipe()
        .setModel("llama3")
        .setSystemPrompt("Analyze relationships")
        .pullGlobalContext()
        .setPageKey("entity_analysis")
    )
    .useGlobalContext("shared_analysis")
    .init()
```

## ContextWindow - Basic Usage

Create weighted context entries that trigger based on keyword matching.

```kotlin
val contextWindow = ContextWindow()

contextWindow.addLoreBookEntry(
    key = "legal_precedent",
    value = "Supreme Court ruling in Smith v. Jones established that...",
    weight = 10
)

contextWindow.addLoreBookEntry(
    key = "company_policy", 
    value = "Internal policy requires all contracts to include...",
    weight = 8
)

contextWindow.contextElements.add("Additional context information")
contextWindow.contextSize = 12000
```

## Intelligent Context Selection

Automatically select the most relevant context based on input text analysis.

```kotlin
val inputText = "We need to review the legal precedent for this contract dispute."

val matchingKeys = contextWindow.findMatchingLoreBookKeys(inputText)
val selectedContext = contextWindow.selectLoreBookContext(
    text = inputText,
    maxTokens = 2000,
    favorWholeWords = true,
    countSubWordsInFirstWord = true
)
```

## ContextBank - Global Context Sharing

Store and retrieve context globally with thread-safe operations across different pages.

```kotlin
suspend fun storeGlobalContext() {
    val legalContext = ContextWindow()
    legalContext.addLoreBookEntry("case_law", "Important case law...", 10)
    
    ContextBank.emplaceWithMutex("legal_analysis", legalContext)
    ContextBank.updateBankedContextWithMutex(legalContext)
}

suspend fun retrieveGlobalContext() {
    val legalContext = ContextBank.getContextFromBank("legal_analysis", copy = true)
    val defaultContext = ContextBank.copyBankedContextWindow()
    ContextBank.swapBankWithMutex("technical_analysis")
}
```

## Dictionary-Based Tokenization

Accurately count tokens and truncate text using dictionary-based algorithms.

```kotlin
val text = "The quick brown fox jumps over the lazy dog."

val basicCount = Dictionary.countTokens(text)

val advancedCount = Dictionary.countTokens(
    text = text,
    countSubWordsInFirstWord = true,
    favorWholeWords = true,
    countOnlyFirstWordFound = false,
    splitForNonWordChar = true,
    nonWordSplitCount = 4
)

val truncatedText = Dictionary.truncate(
    text = longText,
    windowSize = 2,
    truncateSettings = ContextWindowSetings.TruncateMiddle,
    favorWholeWords = true
)
```

## Creating New Provider Module

Extend the Pipe class to integrate new AI providers with custom configuration options.

```kotlin
class CustomAIPipe : Pipe() {
    
    private var apiKey: String = ""
    private var endpoint: String = "https://api.customai.com"
    private var customParameter: Float = 1.0f
    
    fun setApiKey(key: String): Pipe
    {
        this.apiKey = key
        return this
    }
    
    fun setEndpoint(url: String): Pipe
    {
        this.endpoint = url
        return this
    }
    
    override suspend fun init(): Pipe
    {
        if (apiKey.isEmpty())
        {
            throw IllegalStateException("API key is required for CustomAI")
        }
        
        val connectionTest = testConnection(endpoint, apiKey)
        if (!connectionTest)
        {
            throw IllegalStateException("Failed to connect to CustomAI service")
        }
        
        return this
    }
    
    override suspend fun generateText(promptInjector: String): String
    {
        val fullPrompt = buildPrompt(promptInjector)
        
        val requestParams = mapOf(
            "model" to model,
            "prompt" to fullPrompt,
            "temperature" to temperature,
            "max_tokens" to maxTokens,
            "top_p" to topP,
            "custom_param" to customParameter
        )
        
        val response = makeApiCall(endpoint, apiKey, requestParams)
        return extractTextFromResponse(response)
    }
}
```

## Provider Module Usage

Use custom provider modules with the same unified interface as built-in providers.

```kotlin
val customPipe = CustomAIPipe()
    .setProvider(ProviderName.Custom)
    .setModel("custom-model-v1")
    .setApiKey("your-api-key-here")
    .setEndpoint("https://api.customai.com/v1/generate")
    .setSystemPrompt("You are a helpful AI assistant")
    .setTemperature(0.7)
    .setMaxTokens(2048)

val result = runBlocking {
    customPipe.init()
    customPipe.execute("Analyze this data: $inputData")
}
```