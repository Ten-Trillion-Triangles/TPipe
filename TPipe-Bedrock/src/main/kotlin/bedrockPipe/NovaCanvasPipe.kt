package bedrockPipe

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.InvokeModelRequest
import com.TTT.Pipe.BinaryContent
import com.TTT.Pipe.MultimodalContent
import kotlinx.serialization.json.*
import java.util.*

enum class TaskType(val value: String) {
    TEXT_IMAGE("TEXT_IMAGE"),
    IMAGE_VARIATION("IMAGE_VARIATION"),
    COLOR_GUIDED_GENERATION("COLOR_GUIDED_GENERATION"),
    BACKGROUND_REMOVAL("BACKGROUND_REMOVAL")
}

enum class ControlMode(val value: String) {
    CANNY_EDGE("CANNY_EDGE"),
    SEGMENTATION("SEGMENTATION")
}

/**
 * Nova Canvas implementation for TPipe supporting image generation tasks.
 */
class NovaCanvasPipe : BedrockMultimodalPipe() 
{
    private var taskType: TaskType = TaskType.TEXT_IMAGE
    private var numberOfImages: Int = 1
    private var height: Int = 512
    private var width: Int = 512
    private var cfgScale: Double = 8.0
    private var negativeText: String = ""
    private var similarityStrength: Double = 0.7
    private var controlMode: ControlMode = ControlMode.CANNY_EDGE
    private var colors: List<String> = emptyList()

    /**
     * Sets the task type for Nova Canvas operations.
     * @param type The task type to perform
     * @return This pipe instance for method chaining
     */
    fun setTaskType(type: TaskType): NovaCanvasPipe 
    {
        this.taskType = type
        return this
    }

    /**
     * Sets the dimensions for generated images.
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @return This pipe instance for method chaining
     */
    fun setImageDimensions(width: Int, height: Int): NovaCanvasPipe 
    {
        this.width = width
        this.height = height
        return this
    }

    /**
     * Sets the number of images to generate.
     * @param count Number of images to generate
     * @return This pipe instance for method chaining
     */
    fun setNumberOfImages(count: Int): NovaCanvasPipe 
    {
        this.numberOfImages = count
        return this
    }

    /**
     * Sets the CFG scale for controlling adherence to the prompt.
     * @param scale CFG scale value (higher values = more adherence to prompt)
     * @return This pipe instance for method chaining
     */
    fun setCfgScale(scale: Double): NovaCanvasPipe 
    {
        this.cfgScale = scale
        return this
    }

    /**
     * Sets negative text to avoid in image generation.
     * @param text Text describing what to avoid in the generated image
     * @return This pipe instance for method chaining
     */
    fun setNegativeText(text: String): NovaCanvasPipe 
    {
        this.negativeText = text
        return this
    }

    /**
     * Sets similarity strength for image variation tasks.
     * @param strength Similarity strength (0.0 to 1.0)
     * @return This pipe instance for method chaining
     */
    fun setSimilarityStrength(strength: Double): NovaCanvasPipe 
    {
        this.similarityStrength = strength
        return this
    }

    /**
     * Sets the control mode for conditioning image generation.
     * @param mode Control mode for image conditioning
     * @return This pipe instance for method chaining
     */
    fun setControlMode(mode: ControlMode): NovaCanvasPipe 
    {
        this.controlMode = mode
        return this
    }

    /**
     * Sets color palette for color-guided generation.
     * @param colorList List of color values for guidance
     * @return This pipe instance for method chaining
     */
    fun setColors(colorList: List<String>): NovaCanvasPipe 
    {
        this.colors = colorList
        return this
    }

    /**
     * Generates image content using Amazon Nova Canvas model.
     * 
     * @param content Multimodal content containing text prompts and optional binary images
     * @return Generated multimodal content with image results
     */
    override suspend fun generateContent(content: MultimodalContent): MultimodalContent 
    {
        // Get Bedrock client or return empty content if not available
        val client = bedrockClient ?: return MultimodalContent("")
        val modelId = model.ifEmpty { "amazon.nova-canvas-v1:0" }

        // Build Nova Canvas specific request JSON
        val requestBody = buildNovaCanvasRequest(content)
        
        // Create invoke model request
        val invokeRequest = InvokeModelRequest {
            this.modelId = modelId
            body = requestBody.toByteArray()
            contentType = "application/json"
        }

        // Execute request and get response
        val response = client.invokeModel(invokeRequest)
        val responseBody = response.body?.let { String(it) } ?: ""
        
        // Parse response and return generated images
        return parseNovaCanvasResponse(responseBody)
    }

    /**
     * Builds Nova Canvas API request JSON based on task type and content.
     * 
     * @param content Multimodal content with text and binary data
     * @return JSON request string for Nova Canvas API
     */
    private fun buildNovaCanvasRequest(content: MultimodalContent): String 
    {
        val jsonBuilder = buildJsonObject {
            // Set the task type for Nova Canvas
            put("taskType", taskType.value)
            
            // Build task-specific parameters based on task type
            when(taskType)
            {
                TaskType.TEXT_IMAGE -> {
                    // Text-to-image generation with optional conditioning
                    putJsonObject("textToImageParams") {
                        // Add main text prompt
                        put("text", content.text)
                        
                        // Add negative prompt if specified
                        if(negativeText.isNotEmpty()) put("negativeText", negativeText)
                        
                        // Add conditioning image if provided
                        content.binaryContent.firstOrNull()?.let { binary ->
                            // Convert binary content to appropriate format
                            when(binary)
                            {
                                is BinaryContent.Base64String -> put("conditionImage", binary.data)
                                is BinaryContent.Bytes -> put("conditionImage", Base64.getEncoder().encodeToString(binary.data))
                                is BinaryContent.CloudReference -> put("conditionImage", binary.uri)
                                is BinaryContent.TextDocument -> {} // Skip text documents
                            }
                            // Set control mode for conditioning
                            put("controlMode", controlMode.value)
                        }
                    }
                }
                TaskType.IMAGE_VARIATION -> {
                    // Generate variations of existing images
                    putJsonObject("imageVariationParams") {
                        // Add text guidance for variations
                        put("text", content.text)
                        
                        // Add negative prompt if specified
                        if(negativeText.isNotEmpty()) put("negativeText", negativeText)
                        
                        // Set similarity strength for variations
                        put("similarityStrength", similarityStrength)
                        
                        // Add source images for variation
                        putJsonArray("images") {
                            content.binaryContent.forEach { binary ->
                                // Convert each binary content to appropriate format
                                when(binary)
                                {
                                    is BinaryContent.Base64String -> add(binary.data)
                                    is BinaryContent.Bytes -> add(Base64.getEncoder().encodeToString(binary.data))
                                    is BinaryContent.CloudReference -> add(binary.uri)
                                    is BinaryContent.TextDocument -> {} // Skip text documents
                                }
                            }
                        }
                    }
                }
                TaskType.COLOR_GUIDED_GENERATION -> {
                    // Generate images using color palette guidance
                    putJsonObject("colorGuidedGenerationParams") {
                        // Add main text prompt
                        put("text", content.text)
                        
                        // Add negative prompt if specified
                        if(negativeText.isNotEmpty()) put("negativeText", negativeText)
                        
                        // Add reference image for color extraction
                        content.binaryContent.firstOrNull()?.let { binary ->
                            when(binary)
                            {
                                is BinaryContent.Base64String -> put("referenceImage", binary.data)
                                is BinaryContent.Bytes -> put("referenceImage", Base64.getEncoder().encodeToString(binary.data))
                                is BinaryContent.CloudReference -> put("referenceImage", binary.uri)
                                is BinaryContent.TextDocument -> {} // Skip text documents
                            }
                        }
                        
                        // Add color palette if specified
                        if(colors.isNotEmpty())
                        {
                            putJsonArray("colors") {
                                colors.forEach { add(it) }
                            }
                        }
                    }
                }
                TaskType.BACKGROUND_REMOVAL -> {
                    // Remove background from images
                    putJsonObject("backgroundRemovalParams") {
                        // Add source image for background removal
                        content.binaryContent.firstOrNull()?.let { binary ->
                            when(binary)
                            {
                                is BinaryContent.Base64String -> put("image", binary.data)
                                is BinaryContent.Bytes -> put("image", Base64.getEncoder().encodeToString(binary.data))
                                is BinaryContent.CloudReference -> put("image", binary.uri)
                                is BinaryContent.TextDocument -> {} // Skip text documents
                            }
                        }
                    }
                }
            }
            
            // Add image generation configuration
            putJsonObject("imageGenerationConfig") {
                put("numberOfImages", numberOfImages)
                put("height", height)
                put("width", width)
                put("cfgScale", cfgScale)
            }
        }
        
        return jsonBuilder.toString()
    }

    /**
     * Parses Nova Canvas API response and extracts generated images.
     * @param responseBody Raw JSON response from Nova Canvas API
     * @return MultimodalContent containing generated images as base64 strings
     */
    private fun parseNovaCanvasResponse(responseBody: String): MultimodalContent 
    {
        // Parse JSON response from Nova Canvas API
        val json = Json.parseToJsonElement(responseBody).jsonObject
        
        // Check for errors in response
        json["error"]?.let { error ->
            return MultimodalContent("Error: $error")
        }
        
        // Extract images array from response
        val images = json["images"]?.jsonArray ?: return MultimodalContent("")
        val binaryContent = mutableListOf<BinaryContent>()
        
        // Convert each image to BinaryContent
        images.forEach { imageElement ->
            val base64Image = imageElement.jsonPrimitive.content
            binaryContent.add(BinaryContent.Base64String(base64Image, "image/png"))
        }
        
        // Return multimodal content with generated images
        return MultimodalContent(
            text = "Generated ${images.size} image(s)",
            binaryContent = binaryContent
        )
    }
}

