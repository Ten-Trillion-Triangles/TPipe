package bedrockPipe

import bedrockPipe.BedrockPipe
import com.TTT.Pipe.StreamingCallbackBuilder
import com.TTT.Pipe.StreamingExecutionMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for multiple streaming callback functionality.
 */
class StreamingCallbackTest
{
    // Helper to access protected emitStreamingChunk for testing
    private class TestBedrockPipe : BedrockPipe()
    {
        public suspend fun testEmit(chunk: String)
        {
            emitStreamingChunk(chunk)
        }
    }

    @Test
    fun testMultipleCallbacksSequential()
    {
        runBlocking {
            val results = mutableListOf<String>()
            val pipe = TestBedrockPipe()
            
            val callback1: suspend (String) -> Unit = { chunk -> results.add("callback1: $chunk") }
            val callback2: suspend (String) -> Unit = { chunk -> results.add("callback2: $chunk") }
            
            pipe.streamingCallbacks {
                add(callback1)
                add(callback2)
                sequential()
            }
            
            // Simulate streaming
            pipe.testEmit("test")
            
            assertEquals(2, results.size)
            assertEquals("callback1: test", results[0])
            assertEquals("callback2: test", results[1])
        }
    }

    @Test
    fun testMultipleCallbacksConcurrent()
    {
        runBlocking {
            val results = mutableListOf<String>()
            val pipe = TestBedrockPipe()
            
            val callback1: suspend (String) -> Unit = { chunk -> 
                delay(10)
                synchronized(results) { results.add("callback1: $chunk") }
            }
            val callback2: suspend (String) -> Unit = { chunk -> 
                delay(10)
                synchronized(results) { results.add("callback2: $chunk") }
            }
            
            pipe.streamingCallbacks {
                add(callback1)
                add(callback2)
                concurrent()
            }
            
            // Simulate streaming
            pipe.testEmit("test")
            delay(50) // Wait for concurrent execution
            
            assertEquals(2, results.size)
            assertTrue(results.contains("callback1: test"))
            assertTrue(results.contains("callback2: test"))
        }
    }

    @Test
    fun testErrorIsolation()
    {
        runBlocking {
            val results = mutableListOf<String>()
            val pipe = TestBedrockPipe()
            
            val callback1: suspend (String) -> Unit = { chunk -> results.add("callback1: $chunk") }
            val callback2: suspend (String) -> Unit = { chunk -> throw RuntimeException("Test error") }
            val callback3: suspend (String) -> Unit = { chunk -> results.add("callback3: $chunk") }
            
            pipe.streamingCallbacks {
                add(callback1)
                add(callback2)
                add(callback3)
                sequential()
            }
            
            // Simulate streaming - should not throw
            pipe.testEmit("test")
            
            // First and third callbacks should execute despite second failing
            assertEquals(2, results.size)
            assertEquals("callback1: test", results[0])
            assertEquals("callback3: test", results[1])
        }
    }

    @Test
    fun testBackwardCompatibilityLegacyCallback()
    {
        runBlocking {
            val results = mutableListOf<String>()
            val pipe = TestBedrockPipe()
            
            // Use legacy API with explicit type
            val legacyCallback: suspend (String) -> Unit = { chunk -> results.add("legacy: $chunk") }
            pipe.setStreamingCallback(legacyCallback)
            
            // Simulate streaming
            pipe.testEmit("test")
            
            assertEquals(1, results.size)
            assertEquals("legacy: test", results[0])
        }
    }

    @Test
    fun testMixedLegacyAndNewCallbacks()
    {
        runBlocking {
            val results = mutableListOf<String>()
            val pipe = TestBedrockPipe()
            
            // Set legacy callback with explicit type
            val legacyCallback: suspend (String) -> Unit = { chunk -> results.add("legacy: $chunk") }
            pipe.setStreamingCallback(legacyCallback)
            
            // Add new callbacks with explicit types
            val newCallback1: suspend (String) -> Unit = { chunk -> results.add("new1: $chunk") }
            val newCallback2: suspend (String) -> Unit = { chunk -> results.add("new2: $chunk") }
            pipe.streamingCallbacks {
                add(newCallback1)
                add(newCallback2)
            }
            
            // Simulate streaming
            pipe.testEmit("test")
            
            // All callbacks should execute
            assertEquals(3, results.size)
            assertEquals("legacy: test", results[0])
            assertTrue(results.contains("new1: test"))
            assertTrue(results.contains("new2: test"))
        }
    }

    @Test
    fun testDisableStreamingClearsAllCallbacks()
    {
        runBlocking {
            val results = mutableListOf<String>()
            val pipe = TestBedrockPipe()
            
            val legacyCallback: suspend (String) -> Unit = { chunk -> results.add("legacy: $chunk") }
            val newCallback: suspend (String) -> Unit = { chunk -> results.add("new: $chunk") }
            
            pipe.setStreamingCallback(legacyCallback)
            pipe.streamingCallbacks {
                add(newCallback)
            }
            
            // Disable streaming
            pipe.disableStreaming()
            
            // Simulate streaming - no callbacks should execute
            pipe.testEmit("test")
            
            assertEquals(0, results.size)
        }
    }

    @Test
    fun testBuilderPatternChaining()
    {
        runBlocking {
            val results = mutableListOf<String>()
            val errors = mutableListOf<String>()
            
            val callback1: suspend (String) -> Unit = { chunk -> results.add("1: $chunk") }
            val callback2: suspend (String) -> Unit = { chunk -> results.add("2: $chunk") }
            
            val builder = StreamingCallbackBuilder()
                .add(callback1)
                .add(callback2)
                .concurrent()
                .onError { e: Exception, chunk: String -> errors.add("Error on $chunk: ${e.message}") }
            
            val manager = builder.build()
            
            assertEquals(StreamingExecutionMode.CONCURRENT, manager.executionMode)
            assertEquals(2, manager.callbackCount())
            assertTrue(manager.hasCallbacks())
            
            manager.emitToAll("test")
            delay(50)
            
            assertEquals(2, results.size)
        }
    }

    @Test
    fun testNonSuspendingCallbackWrapper()
    {
        runBlocking {
            val results = mutableListOf<String>()
            val pipe = TestBedrockPipe()
            
            // Use non-suspending callback with explicit type
            val nonSuspendCallback: (String) -> Unit = { chunk -> results.add("non-suspend: $chunk") }
            pipe.streamingCallbacks {
                add(nonSuspendCallback)
            }
            
            pipe.testEmit("test")
            
            assertEquals(1, results.size)
            assertEquals("non-suspend: test", results[0])
        }
    }

    @Test
    fun testCallbackManager()
    {
        runBlocking {
            val results = mutableListOf<String>()
            val pipe = TestBedrockPipe()
            
            val manager = pipe.obtainStreamingCallbackManager()
            manager.addCallback { chunk -> results.add("direct: $chunk") }
            
            pipe.testEmit("test")
            
            assertEquals(1, results.size)
            assertEquals("direct: test", results[0])
        }
    }
}
