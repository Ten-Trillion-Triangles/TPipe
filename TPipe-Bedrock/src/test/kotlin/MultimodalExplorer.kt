import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ImageBlock
import aws.sdk.kotlin.services.bedrockruntime.model.DocumentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ImageSource
import aws.sdk.kotlin.services.bedrockruntime.model.DocumentSource
import aws.sdk.kotlin.services.bedrockruntime.model.ImageFormat
import aws.sdk.kotlin.services.bedrockruntime.model.DocumentFormat
import kotlin.test.Test
import java.util.Base64

class MultimodalExplorer {
    
    @Test
    fun exploreMultimodalCapabilities() {
        println("=== EXPLORING AWS BEDROCK MULTIMODAL CAPABILITIES ===")
        
        // Test Image ContentBlock
        val imageBytes = "fake-image-data".toByteArray()
        val base64Image = Base64.getEncoder().encodeToString(imageBytes)
        
        val imageSource = ImageSource.Bytes(imageBytes)
        val imageBlock = ImageBlock {
            format = ImageFormat.Png
            source = imageSource
        }
        val imageContentBlock = ContentBlock.Image(imageBlock)
        
        println("Image ContentBlock created: ${imageContentBlock.javaClass.simpleName}")
        println("Image format: ${imageBlock.format}")
        println("Image source type: ${imageSource.javaClass.simpleName}")
        
        // Test Document ContentBlock  
        val documentBytes = "fake-pdf-data".toByteArray()
        val documentSource = DocumentSource.Bytes(documentBytes)
        val documentBlock = DocumentBlock {
            format = DocumentFormat.Pdf
            name = "test-document.pdf"
            source = documentSource
        }
        val documentContentBlock = ContentBlock.Document(documentBlock)
        
        println("Document ContentBlock created: ${documentContentBlock.javaClass.simpleName}")
        println("Document format: ${documentBlock.format}")
        println("Document name: ${documentBlock.name}")
        println("Document source type: ${documentSource.javaClass.simpleName}")
        
        // Explore ImageSource types
        println("\n=== IMAGE SOURCE TYPES ===")
        val imageSourceClass = ImageSource::class.java
        val imageSourceNestedClasses = imageSourceClass.declaredClasses
        println("ImageSource nested classes:")
        imageSourceNestedClasses.forEach { nestedClass ->
            println("  - ${nestedClass.simpleName}")
        }
        
        // Explore DocumentSource types
        println("\n=== DOCUMENT SOURCE TYPES ===")
        val documentSourceClass = DocumentSource::class.java
        val documentSourceNestedClasses = documentSourceClass.declaredClasses
        println("DocumentSource nested classes:")
        documentSourceNestedClasses.forEach { nestedClass ->
            println("  - ${nestedClass.simpleName}")
        }
        
        // Test different image formats
        println("\n=== SUPPORTED FORMATS ===")
        val supportedImageFormats = listOf(ImageFormat.Png, ImageFormat.Jpeg, ImageFormat.Gif, ImageFormat.Webp)
        supportedImageFormats.forEach { format ->
            try {
                val testImageBlock = ImageBlock {
                    this.format = format
                    source = ImageSource.Bytes("test".toByteArray())
                }
                println("✓ Image format supported: $format")
            } catch (e: Exception) {
                println("✗ Image format not supported: $format - ${e.message}")
            }
        }
        
        val supportedDocumentFormats = listOf(DocumentFormat.Pdf, DocumentFormat.Csv, DocumentFormat.Doc, DocumentFormat.Docx, DocumentFormat.Xls, DocumentFormat.Xlsx, DocumentFormat.Html, DocumentFormat.Txt, DocumentFormat.Md)
        supportedDocumentFormats.forEach { format ->
            try {
                val testDocumentBlock = DocumentBlock {
                    this.format = format
                    name = "test.${format.value}"
                    source = DocumentSource.Bytes("test".toByteArray())
                }
                println("✓ Document format supported: $format")
            } catch (e: Exception) {
                println("✗ Document format not supported: $format - ${e.message}")
            }
        }
    }
}