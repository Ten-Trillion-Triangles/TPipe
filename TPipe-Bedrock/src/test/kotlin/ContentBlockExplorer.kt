import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.SystemContentBlock
import kotlin.test.Test

class ContentBlockExplorer {
    
    @Test
    fun exploreContentBlockTypes() {
        println("=== EXPLORING AWS BEDROCK CONTENTBLOCK TYPES ===")
        
        // Check what ContentBlock types are available
        val textBlock = ContentBlock.Text("Hello world")
        println("Text ContentBlock: ${textBlock.javaClass.simpleName}")
        
        // Try to find other ContentBlock types through reflection
        val contentBlockClass = ContentBlock::class.java
        println("ContentBlock class: ${contentBlockClass.name}")
        
        // Look at nested classes/sealed classes
        val nestedClasses = contentBlockClass.declaredClasses
        println("Nested classes in ContentBlock:")
        nestedClasses.forEach { nestedClass ->
            println("  - ${nestedClass.simpleName}")
        }
        
        // Check if there are Image or Document types
        try {
            // These might exist as sealed class variants
            val methods = contentBlockClass.methods
            println("ContentBlock methods:")
            methods.filter { it.name.contains("Image") || it.name.contains("Document") || it.name.contains("Binary") }
                .forEach { method ->
                    println("  - ${method.name}: ${method.returnType}")
                }
        } catch (e: Exception) {
            println("Error exploring methods: ${e.message}")
        }
        
        // Check SystemContentBlock too
        val systemTextBlock = SystemContentBlock.Text("System prompt")
        println("SystemContentBlock: ${systemTextBlock.javaClass.simpleName}")
        
        val systemContentBlockClass = SystemContentBlock::class.java
        val systemNestedClasses = systemContentBlockClass.declaredClasses
        println("Nested classes in SystemContentBlock:")
        systemNestedClasses.forEach { nestedClass ->
            println("  - ${nestedClass.simpleName}")
        }
    }
}