import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Pipe.BinaryContent
import com.TTT.Pipe.MultimodalContent
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MultimodalPipeTest {
    
    @Test
    fun testImageAnalysis() {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING IMAGE ANALYSIS ===")
        
        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockMultimodalPipe()
            pipe.setModel("anthropic.claude-3-sonnet-20240229-v1:0")
            pipe.setRegion("us-east-1")
            pipe.useConverseApi()
            pipe.setSystemPrompt("You are an expert image analyst. Describe what you see in images.")
            pipe.setMaxTokens(500)
            pipe.setTemperature(0.3)
            
            pipe.init()
            
            // Create a simple test image (1x1 PNG)
            val pngBytes = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77.toByte(), 0x53.toByte(),
                0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54,
                0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0x0F, 0x00, 0x00, 0x01,
                0x00, 0x01, 0x5C.toByte(), 0xC2.toByte(), 0x8A.toByte(), 0x8E.toByte(), 0x00, 0x00,
                0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()
            )
            
            val imageContent = BinaryContent.Bytes(
                data = pngBytes,
                mimeType = "image/png",
                filename = "test.png"
            )
            
            val multimodalContent = MultimodalContent(
                text = "What do you see in this image?",
                binaryContent = mutableListOf(imageContent)
            )
            
            val result = pipe.generateContent(multimodalContent)
            
            println("=== IMAGE ANALYSIS RESULT ===")
            println("Result: ${result.text}")
            
            assertNotNull(result.text, "Result should not be null")
            assertTrue(result.text.isNotEmpty(), "Result should not be empty")
        }
    }
    
    @Test
    fun testDocumentProcessing() {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING DOCUMENT PROCESSING ===")
        
        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockMultimodalPipe()
            pipe.setModel("anthropic.claude-3-sonnet-20240229-v1:0")
            pipe.setRegion("us-east-1")
            pipe.useConverseApi()
            pipe.setSystemPrompt("You are a document analyst. Summarize and analyze documents.")
            pipe.setMaxTokens(500)
            pipe.setTemperature(0.2)
            
            pipe.init()
            
            val documentContent = BinaryContent.TextDocument(
                content = """
                # Project Report
                
                ## Executive Summary
                This report outlines the key findings from our Q4 analysis.
                
                ## Key Metrics
                - Revenue: $1.2M
                - Growth: 15%
                - Customer Satisfaction: 92%
                
                ## Recommendations
                1. Expand marketing efforts
                2. Improve customer support
                3. Invest in R&D
                """.trimIndent(),
                mimeType = "text/markdown",
                filename = "report.md"
            )
            
            val multimodalContent = MultimodalContent(
                text = "Please analyze this document and provide key insights.",
                binaryContent = mutableListOf(documentContent)
            )
            
            val result = pipe.generateContent(multimodalContent)
            
            println("=== DOCUMENT ANALYSIS RESULT ===")
            println("Result: ${result.text}")
            
            assertNotNull(result.text, "Result should not be null")
            assertTrue(result.text.isNotEmpty(), "Result should not be empty")
            assertTrue(result.text.contains("revenue") || result.text.contains("Revenue"), "Should mention revenue")
        }
    }
    
    @Test
    fun testBase64ImageProcessing() {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING BASE64 IMAGE PROCESSING ===")
        
        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockMultimodalPipe()
            pipe.setModel("anthropic.claude-3-sonnet-20240229-v1:0")
            pipe.setRegion("us-east-1")
            pipe.useConverseApi()
            pipe.setSystemPrompt("Analyze images and describe their content.")
            pipe.setMaxTokens(300)
            
            pipe.init()
            
            // Create base64 encoded image
            val pngBytes = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77.toByte(), 0x53.toByte(),
                0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54,
                0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0x0F, 0x00, 0x00, 0x01,
                0x00, 0x01, 0x5C.toByte(), 0xC2.toByte(), 0x8A.toByte(), 0x8E.toByte(), 0x00, 0x00,
                0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()
            )
            
            val base64Image = Base64.getEncoder().encodeToString(pngBytes)
            
            val imageContent = BinaryContent.Base64String(
                data = base64Image,
                mimeType = "image/png",
                filename = "base64_test.png"
            )
            
            val multimodalContent = MultimodalContent(
                text = "Describe this base64 encoded image.",
                binaryContent = mutableListOf(imageContent)
            )
            
            val result = pipe.generateContent(multimodalContent)
            
            println("=== BASE64 IMAGE RESULT ===")
            println("Result: ${result.text}")
            
            assertNotNull(result.text, "Result should not be null")
            assertTrue(result.text.isNotEmpty(), "Result should not be empty")
        }
    }
    
    @Test
    fun testMixedContentProcessing() {
        TestCredentialUtils.requireAwsCredentials()
        
        println("=== TESTING MIXED CONTENT PROCESSING ===")
        
        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            
            val pipe = BedrockMultimodalPipe()
            pipe.setModel("anthropic.claude-3-sonnet-20240229-v1:0")
            pipe.setRegion("us-east-1")
            pipe.useConverseApi()
            pipe.setSystemPrompt("You can analyze both images and documents. Provide comprehensive analysis.")
            pipe.setMaxTokens(800)
            pipe.setTemperature(0.1)
            
            pipe.init()
            
            // Create test image
            val pngBytes = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77.toByte(), 0x53.toByte(),
                0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54,
                0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0x0F, 0x00, 0x00, 0x01,
                0x00, 0x01, 0x5C.toByte(), 0xC2.toByte(), 0x8A.toByte(), 0x8E.toByte(), 0x00, 0x00,
                0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()
            )
            
            val imageContent = BinaryContent.Bytes(
                data = pngBytes,
                mimeType = "image/png",
                filename = "chart.png"
            )
            
            val documentContent = BinaryContent.TextDocument(
                content = """
                Sales Data Analysis
                
                Q1 Results:
                - Total Sales: $500K
                - New Customers: 150
                - Retention Rate: 85%
                
                The attached chart shows the monthly breakdown.
                """.trimIndent(),
                mimeType = "text/plain",
                filename = "sales_report.txt"
            )
            
            val multimodalContent = MultimodalContent(
                text = "Please analyze both the document and the chart image together. What insights can you provide?",
                binaryContent = mutableListOf(documentContent, imageContent)
            )
            
            val result = pipe.generateContent(multimodalContent)
            
            println("=== MIXED CONTENT RESULT ===")
            println("Result: ${result.text}")
            
            assertNotNull(result.text, "Result should not be null")
            assertTrue(result.text.isNotEmpty(), "Result should not be empty")
            assertTrue(result.text.length > 100, "Should provide comprehensive analysis")
        }
    }
}