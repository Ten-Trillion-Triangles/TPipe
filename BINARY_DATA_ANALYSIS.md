# Binary Data Handling in LLM Providers - TPipe Analysis

## Executive Summary

After investigating AWS Bedrock SDK and researching industry patterns, here's how binary data (images, documents, PDFs, etc.) is handled across major LLM providers:

## AWS Bedrock Approach

### Supported Binary Formats
- **Images**: PNG, JPEG, GIF, WebP
- **Documents**: PDF, CSV, DOC, DOCX, XLS, XLSX, HTML, TXT, MD
- **Video**: Supported via ContentBlock.Video (newer models)

### Data Transport Methods
1. **Direct Bytes**: `ImageSource.Bytes(byteArray)` / `DocumentSource.Bytes(byteArray)`
2. **S3 References**: `ImageSource.S3Location` / `DocumentSource.S3Location`
3. **Text Content**: `DocumentSource.Text` for text documents

### ContentBlock Types Available
- `ContentBlock.Text` - Text content
- `ContentBlock.Image` - Image content with format specification
- `ContentBlock.Document` - Document content with format specification
- `ContentBlock.Video` - Video content (newer models)
- `ContentBlock.ToolUse` - Tool execution requests
- `ContentBlock.ToolResult` - Tool execution results

## Industry Standard Patterns

### 1. Base64 Encoding (Most Common)
**Used by**: OpenAI, Anthropic Direct API, Google Gemini, Cohere
- Binary data converted to base64 strings
- Embedded directly in JSON requests
- Simple but increases payload size by ~33%

```json
{
  "messages": [
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "What's in this image?"},
        {
          "type": "image_url",
          "image_url": {
            "url": "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQ..."
          }
        }
      ]
    }
  ]
}
```

### 2. Direct Byte Arrays
**Used by**: AWS Bedrock, Google Vertex AI, Azure OpenAI
- Raw binary data passed directly
- More efficient for large files
- Requires SDK support

### 3. Cloud Storage References
**Used by**: Most enterprise providers
- Upload to S3/GCS/Azure Blob first
- Pass URL/URI reference to model
- Best for large files and production systems

### 4. Multipart Form Data
**Used by**: Some REST APIs
- HTTP multipart uploads
- Good for web applications
- Standard HTTP approach

## TPipe Implementation Strategy

### Core Classes Created

#### 1. `BinaryContent` (Sealed Class)
```kotlin
sealed class BinaryContent {
    data class Bytes(val data: ByteArray, val mimeType: String, val filename: String?)
    data class Base64String(val data: String, val mimeType: String, val filename: String?)
    data class CloudReference(val uri: String, val mimeType: String, val filename: String?)
    data class TextDocument(val content: String, val mimeType: String, val filename: String?)
}
```

#### 2. `MultimodalContent`
```kotlin
data class MultimodalContent(
    val text: String = "",
    val binaryContent: List<BinaryContent> = emptyList()
)
```

#### 3. `BedrockMultimodalPipe`
Enhanced BedrockPipe with full multimodal support:
- Automatic format detection and conversion
- Support for both Converse API and Invoke API
- Handles images, documents, and mixed content

### Key Features

1. **Format Flexibility**: Automatic conversion between bytes, base64, and cloud references
2. **Provider Abstraction**: Same interface works across different LLM providers
3. **Backward Compatibility**: Existing text-only pipes continue to work
4. **Type Safety**: Kotlin sealed classes prevent invalid combinations
5. **Efficient Transport**: Choose optimal transport method per provider

## Usage Examples

### Image Analysis
```kotlin
val pipe = BedrockMultimodalPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .useConverseApi()

val imageContent = BinaryContent.Bytes(
    data = imageBytes,
    mimeType = "image/png",
    filename = "chart.png"
)

val result = pipe.generateContent(
    MultimodalContent(
        text = "Analyze this chart",
        binaryContent = listOf(imageContent)
    )
)
```

### Document Processing
```kotlin
val documentContent = BinaryContent.TextDocument(
    content = "# Report\n\nKey findings...",
    mimeType = "text/markdown",
    filename = "report.md"
)

val result = pipe.generateContent(
    MultimodalContent(
        text = "Summarize this document",
        binaryContent = listOf(documentContent)
    )
)
```

### Mixed Content
```kotlin
val result = pipe.generateContent(
    MultimodalContent(
        text = "Compare the document with the chart",
        binaryContent = listOf(documentContent, imageContent)
    )
)
```

## Provider-Specific Implementations

### AWS Bedrock
- Uses `ContentBlock` system with typed formats
- Supports both Converse API (recommended) and Invoke API
- Direct byte array transport for efficiency
- S3 references for large files

### OpenAI (Future Implementation)
- Base64 encoding in JSON messages
- `image_url` with data URLs
- File uploads for assistants API

### Anthropic Direct API (Future Implementation)
- Base64 encoding in message content
- Similar to OpenAI format
- Supports Claude's vision capabilities

### Google Vertex AI (Future Implementation)
- Direct byte arrays via gRPC
- Base64 for REST API
- Supports Gemini multimodal models

## Performance Considerations

1. **File Size Limits**: Each provider has different limits
   - AWS Bedrock: Up to 20MB per image, 50MB per document
   - OpenAI: Up to 20MB per file
   - Google: Varies by model

2. **Transport Efficiency**:
   - Direct bytes: Most efficient
   - Base64: +33% overhead but universal
   - Cloud references: Best for large files

3. **Caching**: Cloud references enable better caching

## Security Considerations

1. **Data Privacy**: Binary data may contain sensitive information
2. **Cloud Storage**: S3/GCS references require proper access controls
3. **Transmission**: HTTPS required for base64 transport
4. **Retention**: Consider data retention policies for uploaded files

## Future Enhancements

1. **Automatic Format Detection**: MIME type detection from file headers
2. **Compression**: Automatic compression for large files
3. **Streaming**: Support for streaming large binary content
4. **Caching**: Local caching of processed binary content
5. **Validation**: Content validation and sanitization
6. **Batch Processing**: Multiple files in single request

## Conclusion

The TPipe multimodal system provides a unified interface for handling binary data across different LLM providers while maintaining efficiency and type safety. The implementation supports the most common patterns (bytes, base64, cloud references) and can be extended for additional providers and formats as needed.

Key benefits:
- **Unified API**: Same code works across providers
- **Flexible Transport**: Choose optimal method per use case
- **Type Safety**: Compile-time validation of content types
- **Performance**: Efficient binary handling without unnecessary conversions
- **Extensible**: Easy to add new providers and formats