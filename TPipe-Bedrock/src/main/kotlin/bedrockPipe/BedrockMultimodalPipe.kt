package bedrockPipe

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.*
import com.TTT.Debug.*
import com.TTT.Pipe.BinaryContent
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.getMimeType
import com.TTT.Pipe.getFilename

/**
 * Enhanced BedrockPipe with multimodal support for images, documents, and other binary content.
 * Extends the base BedrockPipe to handle binary data through AWS Bedrock's ContentBlock system.
 */
@kotlinx.serialization.Serializable
open class BedrockMultimodalPipe : BedrockPipe() {
    
    /**
     * Sets the read timeout for Bedrock API calls.
     * 
     * @param timeoutSeconds Timeout in seconds (default: 3600 for 60 minutes)
     * @return This pipe instance for method chaining
     */
    override fun setReadTimeout(timeoutSeconds: Long): BedrockMultimodalPipe {
        super.setReadTimeout(timeoutSeconds)
        return this
    }

    /**
     * Generates multimodal content using AWS Bedrock with support for images, documents, and text.
     * 
     * @param content Multimodal content containing text and/or binary data
     * @return Generated multimodal content from the model
     */
    override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              content = content,
              metadata = mapOf(
                  "method" to "generateContent",
                  "hasText" to content.text.isNotEmpty(),
                  "binaryContentCount" to content.binaryContent.size,
                  "binaryContentTypes" to content.binaryContent.map { it.getMimeType() },
                  "useConverseApi" to useConverseApi,
                  "model" to model
              ))
        
        return try {
            val client = bedrockClient ?: run {
                trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                      metadata = mapOf("error" to "Client not initialized"))
                return MultimodalContent("")
            }

            val modelId = model.ifEmpty { "anthropic.claude-3-sonnet-20240229-v1:0" }
            
            val result = if(useConverseApi)
            {
                trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
                      metadata = mapOf("apiType" to "ConverseAPI"))
                generateMultimodalWithConverseApi(client, modelId, content)
            }

            else
            {
                trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
                      metadata = mapOf("apiType" to "InvokeAPI"))
                generateMultimodalWithInvokeApi(client, modelId, content)
            }
            
            trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
                  result,
                  metadata = mapOf(
                      "resultTextLength" to result.text.length,
                      "resultBinaryCount" to result.binaryContent.size
                  ))
            result
            
        }

        catch(e: Exception)
        {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  error = e,
                  metadata = mapOf(
                      "errorType" to (e::class.simpleName ?: "Unknown"),
                      "errorMessage" to (e.message ?: "Unknown error")
                  ))

            exceptionFunction?.invoke(content, e)
            MultimodalContent("")
        }
    }
    
    /**
     * Generates multimodal content using AWS Bedrock's Converse API.
     * 
     * The Converse API is the modern, structured approach that natively supports multimodal content
     * through ContentBlocks. This method converts TPipe's MultimodalContent into AWS ContentBlocks,
     * sends the request, and extracts both text responses and reasoning content from the response.
     */
    private suspend fun generateMultimodalWithConverseApi(
        client: BedrockRuntimeClient, 
        modelId: String, 
        content: MultimodalContent
    ): MultimodalContent {
        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf("internalMethod" to "generateMultimodalWithConverseApi"))
        
        // GPT-OSS models need special handling - delegate to parent class
        if (modelId.contains("openai.gpt-oss")) {
            val (textResult, response) = generateGptOssWithConverseApiAndResponse(client, modelId, content.text)
            
            // Extract reasoning content from the response for tracing
            val reasoningContent = if (response != null) {
                extractReasoningFromConverseResponse(response)
            } else ""
            
            if (reasoningContent.isNotEmpty()) {
                trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
                      metadata = mapOf(
                          "reasoningContent" to reasoningContent,
                          "modelSupportsReasoning" to true,
                          "reasoningEnabled" to useModelReasoning
                      ))
            }
            
            return MultimodalContent(
                text = textResult, 
                binaryContent = content.binaryContent,
                modelReasoning = reasoningContent
            )
        }
        
        // DeepSeek models need special handling - get response and extract reasoning
        if (modelId.contains("deepseek")) {
            val converseRequest = buildDeepSeekConverseRequestObject(modelId, listOf(ContentBlock.Text(content.text)))
            
            // Check for streaming first
            if (streamingEnabled) {
                val streamingResult = executeConverseStream(client, modelId, converseRequest, "DeepSeek ConverseStream")
                if (streamingResult != null) {
                    return MultimodalContent(
                        text = streamingResult,
                        binaryContent = content.binaryContent,
                        modelReasoning = ""
                    )
                }
            }
            
            val response = client.converse(converseRequest)
            
            // Extract text content
            val textResult = response.output?.asMessage()?.content?.mapNotNull { contentBlock ->
                when (contentBlock) {
                    is ContentBlock.Text -> contentBlock.value
                    else -> null
                }
            }?.joinToString("\n") ?: ""
            
            // Extract reasoning content
            val reasoningContent = extractReasoningFromConverseResponse(response)
            if (reasoningContent.isNotEmpty()) {
                trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
                      metadata = mapOf(
                          "reasoningContent" to reasoningContent,
                          "modelSupportsReasoning" to true,
                          "reasoningEnabled" to useModelReasoning
                      ))
            }
            
            return MultimodalContent(
                text = textResult, 
                binaryContent = content.binaryContent,
                modelReasoning = reasoningContent
            )
        }
        
        // Convert multimodal content to ContentBlocks (STAYS in this class - binary expertise)
        val contentBlocks = mutableListOf<ContentBlock>()
        
        // Add text content
        if (content.text.isNotEmpty()) {
            contentBlocks.add(ContentBlock.Text(content.text))
        }
        
        // Add binary content using existing conversion logic (STAYS here - binary expertise)
        content.binaryContent.forEach { binaryContent ->
            trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
                  metadata = mapOf(
                      "step" to "convertBinaryContent",
                      "mimeType" to binaryContent.getMimeType(),
                      "size" to when(binaryContent) {
                          is BinaryContent.Bytes -> binaryContent.data.size
                          is BinaryContent.Base64String -> binaryContent.data.length
                          else -> 0
                      }
                  ))
            
            val contentBlock = convertBinaryToContentBlock(binaryContent)
            if (contentBlock != null) {
                contentBlocks.add(contentBlock)
            }
        }
        
        // Use parent class builders (ELIMINATES duplicate logic)
        val converseRequest = when {
            modelId.contains("qwen") -> buildQwenConverseRequest(contentBlocks)
            modelId.contains("anthropic.claude") -> buildClaudeConverseRequest(contentBlocks)
            modelId.contains("amazon.nova") -> buildNovaConverseRequest(contentBlocks)
            modelId.contains("amazon.titan") -> buildTitanConverseRequest(contentBlocks)
            modelId.contains("ai21.j2") -> buildAI21ConverseRequest(contentBlocks)
            modelId.contains("cohere.command") -> buildCohereConverseRequest(contentBlocks)
            modelId.contains("meta.llama") -> buildLlamaConverseRequest(contentBlocks)
            modelId.contains("mistral") -> buildMistralConverseRequest(contentBlocks)
            modelId.contains("openai.gpt-oss") -> buildGptOssConverseRequest(modelId, contentBlocks)
            else -> buildGenericConverseRequest(contentBlocks)
        }
        
        // Check for streaming first
        if (streamingEnabled) {
            val streamingResult = executeConverseStream(client, modelId, converseRequest, "ConverseStream")
            if (streamingResult != null) {
                return MultimodalContent(
                    text = streamingResult,
                    binaryContent = content.binaryContent,
                    modelReasoning = ""
                )
            }
        }
        
        // Execute API call and process response (existing logic STAYS here - binary expertise)
        val response = client.converse(converseRequest)
        val outputMessage = response.output?.asMessage()
        val responseContent = outputMessage?.content
        
        // Extract response content (existing logic STAYS here - binary expertise)
        val responseText = mutableListOf<String>()
        val responseBinaryContent = mutableListOf<BinaryContent>()
        
        responseContent?.forEach { contentBlock ->
            when (contentBlock) {
                is ContentBlock.Text -> {
                    responseText.add(contentBlock.value)
                }
                is ContentBlock.Image -> {
                    // Convert AWS Image ContentBlock back to TPipe BinaryContent
                    val imageBlock = contentBlock.value
                    when (val source = imageBlock.source) {
                        is ImageSource.Bytes -> {
                            responseBinaryContent.add(BinaryContent.Bytes(
                                data = source.value,
                                mimeType = when (imageBlock.format) {
                                    ImageFormat.Png -> "image/png"
                                    ImageFormat.Jpeg -> "image/jpeg"
                                    ImageFormat.Gif -> "image/gif"
                                    ImageFormat.Webp -> "image/webp"
                                    else -> "image/jpeg"
                                }
                            ))
                        }
                        is ImageSource.S3Location -> {
                            responseBinaryContent.add(BinaryContent.CloudReference(
                                uri = source.value.uri ?: "",
                                mimeType = when (imageBlock.format) {
                                    ImageFormat.Png -> "image/png"
                                    ImageFormat.Jpeg -> "image/jpeg"
                                    ImageFormat.Gif -> "image/gif"
                                    ImageFormat.Webp -> "image/webp"
                                    else -> "image/jpeg"
                                }
                            ))
                        }
                        is ImageSource.SdkUnknown, null -> {
                            // Handle unknown or null image sources
                        }
                    }
                }
                is ContentBlock.Document -> {
                    // Convert AWS Document ContentBlock back to TPipe BinaryContent
                    val documentBlock = contentBlock.value
                    when (val source = documentBlock.source) {
                        is DocumentSource.Bytes -> {
                            responseBinaryContent.add(BinaryContent.Bytes(
                                data = source.value,
                                mimeType = when (documentBlock.format) {
                                    DocumentFormat.Pdf -> "application/pdf"
                                    DocumentFormat.Csv -> "text/csv"
                                    DocumentFormat.Doc -> "application/msword"
                                    DocumentFormat.Docx -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                    DocumentFormat.Xls -> "application/vnd.ms-excel"
                                    DocumentFormat.Xlsx -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                    DocumentFormat.Html -> "text/html"
                                    DocumentFormat.Txt -> "text/plain"
                                    DocumentFormat.Md -> "text/markdown"
                                    else -> "application/octet-stream"
                                },
                                filename = documentBlock.name
                            ))
                        }
                        is DocumentSource.S3Location -> {
                            responseBinaryContent.add(BinaryContent.CloudReference(
                                uri = source.value.uri ?: "",
                                mimeType = when (documentBlock.format) {
                                    DocumentFormat.Pdf -> "application/pdf"
                                    DocumentFormat.Csv -> "text/csv"
                                    DocumentFormat.Doc -> "application/msword"
                                    DocumentFormat.Docx -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                    DocumentFormat.Xls -> "application/vnd.excel"
                                    DocumentFormat.Xlsx -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                    DocumentFormat.Html -> "text/html"
                                    DocumentFormat.Txt -> "text/plain"
                                    DocumentFormat.Md -> "text/markdown"
                                    else -> "application/octet-stream"
                                },
                                filename = documentBlock.name
                            ))
                        }
                        is DocumentSource.Text -> {
                            responseBinaryContent.add(BinaryContent.TextDocument(
                                content = source.value,
                                filename = documentBlock.name ?: "document.txt"
                            ))
                        }
                        is DocumentSource.Content -> {
                            // Handle content-based document sources
                        }
                        is DocumentSource.SdkUnknown, null -> {
                            // Handle unknown or null document sources
                        }
                    }
                }
                // Handle other content block types as they become available
                else -> {
                    // Log unknown content block types for debugging
                    trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
                          metadata = mapOf<String, Any>(
                              "unknownContentBlockType" to (contentBlock::class.simpleName ?: "Unknown")
                          ))
                }
            }
        }
        
        // Extract reasoning content (existing logic STAYS here)
        val reasoningContent = extractReasoningFromConverseResponse(response)
        content.modelReasoning = reasoningContent
        if (reasoningContent.isNotEmpty()) {
            trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
                  metadata = mapOf(
                      "reasoningContent" to reasoningContent,
                      "modelSupportsReasoning" to true,
                      "reasoningEnabled" to useModelReasoning
                  ))
        }
        
        return MultimodalContent(
            text = responseText.joinToString("\n"),
            binaryContent = responseBinaryContent,
            modelReasoning = reasoningContent
        )
    }
    
    /**
     * Generates multimodal content using AWS Bedrock's legacy Invoke API.
     * 
     * The Invoke API is the older, JSON-based approach that doesn't natively support
     * multimodal content. This method converts binary content to text descriptions
     * or base64 embeddings and sends everything as a JSON request. This is a fallback
     * for models that don't support the Converse API or when explicitly requested.
     */
    private suspend fun generateMultimodalWithInvokeApi(
        client: BedrockRuntimeClient,
        modelId: String, 
        content: MultimodalContent
    ): MultimodalContent {
        // Build model-specific JSON request with embedded binary content
        // Since Invoke API doesn't support native multimodal, we convert binary
        // content to text descriptions or base64 strings embedded in the prompt
        val requestJson = buildMultimodalRequest(modelId, content)
        
        // Create the raw Invoke API request with JSON body
        // This is the legacy approach that sends raw JSON to the model
        val invokeRequest = InvokeModelRequest {
            this.modelId = modelId
            body = requestJson.toByteArray()
            contentType = "application/json"
            serviceTier = mapServiceTier()
        }

        // Execute the API call and get raw JSON response
        val response = client.invokeModel(invokeRequest)
        val responseBody = response.body?.let { String(it) } ?: ""
        
        // Parse the model-specific JSON response to extract text
        // Each model family has different response formats
        val responseText = extractTextFromResponse(responseBody, modelId)
        
        // Extract reasoning content from the JSON response if present
        // This captures thinking/reasoning from models like DeepSeek R1 and GPT-OSS
        // regardless of the useModelReasoning flag setting
        val reasoningContent = extractReasoningContent(responseBody, modelId)
        if (reasoningContent.isNotEmpty()) {
            trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
                  metadata = mapOf(
                      "reasoningContent" to reasoningContent,
                      "modelSupportsReasoning" to true,
                      "reasoningEnabled" to useModelReasoning
                  ))
        }
        
        return MultimodalContent(text = responseText)

    }
    
    /**
     * Converts TPipe's BinaryContent to AWS Bedrock's ContentBlock format.
     * 
     * This is the core conversion logic that maps TPipe's unified binary content
     * representation to AWS Bedrock's specific ContentBlock types (Image, Document, etc.).
     * Returns null if the content type is not supported by Bedrock's multimodal capabilities.
     */
    private fun convertBinaryToContentBlock(binaryContent: BinaryContent): ContentBlock? {
        return when (binaryContent) {
            is BinaryContent.Bytes -> {
                when {
                    binaryContent.getMimeType().startsWith("image/") -> {
                        val imageFormat = getImageFormat(binaryContent.getMimeType())
                        if (imageFormat != null) {
                            val imageBlock = ImageBlock {
                                format = imageFormat
                                source = ImageSource.Bytes(binaryContent.data)
                            }
                            ContentBlock.Image(imageBlock)
                        } else null
                    }
                    isDocumentMimeType(binaryContent.getMimeType()) -> {
                        val documentFormat = getDocumentFormat(binaryContent.getMimeType())
                        if (documentFormat != null) {
                            val documentBlock = DocumentBlock {
                                format = documentFormat
                                name = binaryContent.getFilename() ?: "document.${documentFormat.value}"
                                source = DocumentSource.Bytes(binaryContent.data)
                            }
                            ContentBlock.Document(documentBlock)
                        } else null
                    }
                    else -> null
                }
            }
            is BinaryContent.Base64String -> {
                // Convert to bytes first
                convertBinaryToContentBlock(binaryContent.toBytes())
            }
            is BinaryContent.CloudReference -> {
                when {
                    binaryContent.getMimeType().startsWith("image/") -> {
                        val imageFormat = getImageFormat(binaryContent.getMimeType())
                        if (imageFormat != null) {
                            val imageBlock = ImageBlock {
                                format = imageFormat
                                source = ImageSource.S3Location(S3Location {
                                    uri = binaryContent.uri
                                })
                            }
                            ContentBlock.Image(imageBlock)
                        } else null
                    }
                    isDocumentMimeType(binaryContent.getMimeType()) -> {
                        val documentFormat = getDocumentFormat(binaryContent.getMimeType())
                        if (documentFormat != null) {
                            val documentBlock = DocumentBlock {
                                format = documentFormat
                                name = binaryContent.getFilename() ?: "document.${documentFormat.value}"
                                source = DocumentSource.S3Location(S3Location {
                                    uri = binaryContent.uri
                                })
                            }
                            ContentBlock.Document(documentBlock)
                        } else null
                    }
                    else -> null
                }
            }
            is BinaryContent.TextDocument -> {
                val documentBlock = DocumentBlock {
                    format = DocumentFormat.Txt
                    name = binaryContent.getFilename() ?: "document.txt"
                    source = DocumentSource.Text(binaryContent.content)
                }
                ContentBlock.Document(documentBlock)
            }
        }
    }
    
    /**
     * Maps MIME types to AWS Bedrock ImageFormat
     */
    private fun getImageFormat(mimeType: String): ImageFormat? = when (mimeType.lowercase()) {
        "image/png" -> ImageFormat.Png
        "image/jpeg", "image/jpg" -> ImageFormat.Jpeg
        "image/gif" -> ImageFormat.Gif
        "image/webp" -> ImageFormat.Webp
        else -> null
    }
    
    /**
     * Maps MIME types to AWS Bedrock DocumentFormat
     */
    private fun getDocumentFormat(mimeType: String): DocumentFormat? = when (mimeType.lowercase()) {
        "application/pdf" -> DocumentFormat.Pdf
        "text/csv", "application/csv" -> DocumentFormat.Csv
        "application/msword" -> DocumentFormat.Doc
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocumentFormat.Docx
        "application/vnd.ms-excel" -> DocumentFormat.Xls
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> DocumentFormat.Xlsx
        "text/html" -> DocumentFormat.Html
        "text/plain" -> DocumentFormat.Txt
        "text/markdown" -> DocumentFormat.Md
        else -> null
    }
    
    /**
     * Checks if MIME type represents a document
     */
    private fun isDocumentMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("application/") || 
               mimeType.startsWith("text/") ||
               getDocumentFormat(mimeType) != null
    }
    
    /**
     * Builds model-specific JSON request for Invoke API with embedded binary content.
     * 
     * Since the Invoke API doesn't support native multimodal content, this method
     * converts binary content to text descriptions and embeds them in the prompt.
     * This is a fallback approach that allows multimodal-like functionality through
     * text descriptions of the binary content.
     */
    private fun buildMultimodalRequest(modelId: String, content: MultimodalContent): String {
        // Start with the original text content
        val textContent = StringBuilder(content.text)
        
        // Convert each binary content item to a text description
        // This creates a text-based representation of multimodal content
        // since Invoke API only accepts JSON with text prompts
        content.binaryContent.forEach { binaryContent ->
            when (binaryContent) {
                is BinaryContent.Base64String -> {
                    // Add base64 content as text description with preview
                    textContent.append("\n\n[${binaryContent.getMimeType()}] ${binaryContent.filename ?: "file"}: ${binaryContent.data.take(100)}...")
                }
                is BinaryContent.Bytes -> {
                    // Convert bytes to base64 and add as text description
                    val base64 = binaryContent.toBase64()
                    textContent.append("\n\n[${base64.getMimeType()}] ${base64.filename ?: "file"}: ${base64.data.take(100)}...")
                }
                is BinaryContent.CloudReference -> {
                    // Add cloud reference URI as text description
                    textContent.append("\n\n[${binaryContent.getMimeType()}] Reference: ${binaryContent.uri}")
                }
                is BinaryContent.TextDocument -> {
                    // Add full text document content directly
                    textContent.append("\n\n[Document] ${binaryContent.filename ?: "document"}: ${binaryContent.content}")
                }
            }
        }
        
        // Build model-specific JSON request using the combined text content
        // Each model family has different JSON request formats
        return when {
            modelId.contains("openai.gpt-oss") -> buildGptOssRequest(textContent.toString())
            modelId.contains("qwen") -> buildQwenRequest(textContent.toString())
            modelId.contains("anthropic.claude") -> buildClaudeRequest(textContent.toString())
            modelId.contains("amazon.nova") -> buildNovaRequest(textContent.toString())
            else -> buildGenericRequest(textContent.toString())
        }
    }
}