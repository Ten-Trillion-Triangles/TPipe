package bedrockPipe

import com.TTT.Util.constructPipeFromTemplate
import com.TTT.Pipe.Pipe
import kotlin.test.*

class ConstructPipeTest {

    @Test
    fun `test constructPipeFromTemplate with BedrockMultimodalPipe`() {
        val original = BedrockMultimodalPipe()
        original.pipeName = "OriginalBedrockPipe"
        
        // 1. Basic property preservation
        val copy = constructPipeFromTemplate<BedrockMultimodalPipe>(original)
        
        assertNotNull(copy, "Copy should not be null")
        assertTrue(copy is BedrockMultimodalPipe, "Copy should be instance of BedrockMultimodalPipe")
        assertEquals("OriginalBedrockPipe", copy.pipeName, "Copy should preserve properties")
        
        // 2. Verify independence of basic properties
        original.pipeName = "ModifiedName"
        assertEquals("OriginalBedrockPipe", copy.pipeName, "Copy should be independent of original")

        // 3. Test metadata copying and independence
        original.pipeMetadata["testKey"] = "testValue"
        val copyWithMetadata = constructPipeFromTemplate<BedrockMultimodalPipe>(original, copyMetadata = true)
        assertNotNull(copyWithMetadata)
        assertEquals("testValue", copyWithMetadata.pipeMetadata["testKey"], "Metadata should be copied")
        
        // Change original metadata
        original.pipeMetadata["testKey"] = "newValue"
        assertEquals("testValue", copyWithMetadata.pipeMetadata["testKey"], "Copied metadata should be independent")
        
        // 4. Test function preservation (if requested)
        var functionCalled = false
        original.validatorFunction = { _ -> 
            functionCalled = true
            true 
        }
        
        val copyWithFunctions = constructPipeFromTemplate<BedrockMultimodalPipe>(original, copyFunctions = true)
        assertNotNull(copyWithFunctions)
        assertNotNull(copyWithFunctions.validatorFunction, "Function should be copied")
        
        // Verify the function is actually the same logic
        // (Since they are carried by reference, they are the same instance)
    }
}
