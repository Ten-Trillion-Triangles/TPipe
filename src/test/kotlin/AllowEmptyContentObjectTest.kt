package com.TTT

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.fail

class AllowEmptyContentObjectTest {

    @Test
    fun testAllowEmptyContentObjectBypass() {
        runBlocking {
            val pipe = TestTokenPipe("EmptyTestPipe")
            pipe.allowEmptyContentObject()
            
            val emptyContent = MultimodalContent()
            
            try {
                pipe.execute(emptyContent)
                // If it reaches here, the bypass worked!
            } catch (e: Exception) {
                if (e.message == "Empty user prompt, or content object was passed into this pipe.") {
                    fail("allowEmptyContentObject() failed to bypass the empty check even after the fix. Exception: ${e.message}")
                } else {
                    throw e
                }
            }
        }
    }

    @Test
    fun testWithBothFlagsEnabled() {
        runBlocking {
            val pipe = TestTokenPipe("BothFlagsPipe")
            pipe.allowEmptyContentObject()
            pipe.allowEmptyUserPrompt()
            
            val emptyContent = MultimodalContent()
            
            try {
                pipe.execute(emptyContent)
                // This should work
            } catch (e: Exception) {
                if (e.message == "Empty user prompt, or content object was passed into this pipe.") {
                    fail("Even with both flags enabled, the pipe crashed with empty check error.")
                } else {
                    throw e
                }
            }
        }
    }
}
