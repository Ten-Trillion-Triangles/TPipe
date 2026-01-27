package com.TTT.Debug

import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.Splitter
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicInteger

class SplitterConcurrencyTest {

    @Test
    fun `test concurrent tracing does not throw exceptions`() = runBlocking {
        // Arrange
        val splitter = Splitter()
        val config = TraceConfig(enabled = true, outputFormat = TraceFormat.JSON)
        splitter.enableTracing(config)
        
        // Use a large number of parallel pipelines to force contention
        val pipeCount = 50
        val eventsPerPipe = 20
        
        // Setup splitter with pipelines containing mock pipes
        for (i in 0 until pipeCount) {
            val pipeline = Pipeline()
            pipeline.pipelineName = "pipeline-$i"
            val mockPipe = MockPipe("pipe-$i", eventsPerPipe)
            
            // Fix: Use add() instead of addPipe()
            pipeline.add(mockPipe)
            
            splitter.addPipeline("key-$i", pipeline)
        }
        
        splitter.init(config)
        
        // Act - Start parallel execution AND parallel reading
        val readErrors = AtomicInteger(0)
        val readCount = AtomicInteger(0)
        
        // Launch reader job that hammers getTrace()
        val readerJob = launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    // This is the critical validaton: getTrace() should be safe to iterate
                    val traceId = splitter.getTraceId()
                    val trace = PipeTracer.getTrace(traceId)
                    
                    // Iterate to provoke ConcurrentModificationException if returned list is not a copy
                    var count = 0
                    trace.forEach { _ -> count++ }
                    
                    readCount.incrementAndGet()
                    delay(1) 
                } catch (e: CancellationException) {
                    // Normal cancellation, ignore or rethrow
                    throw e
                } catch (e: Exception) {
                    println("Reader failed: ${e::class.simpleName} - ${e.message}")
                    e.printStackTrace()
                    readErrors.incrementAndGet()
                    break // Stop on error
                }
            }
        }
        
        // Run the pipelines
        // executePipelines returns a list of Deferred, we await them all
        splitter.executePipelines().awaitAll()
        
        // Give the reader a moment to finish
        delay(50)
        readerJob.cancelAndJoin()
        
        // Assert
        assertEquals(0, readErrors.get(), "Should have 0 read errors. Found ${readErrors.get()}")
        assertTrue(readCount.get() > 0, "Should have performed at least some reads")
        
        val finalTrace = PipeTracer.getTrace(splitter.getTraceId())
        println("Final trace size: ${finalTrace.size}")
    }
    
    // Mock pipe that emits trace events
    // Must implement abstract methods of Pipe and NOT override final execute()
    class MockPipe(name: String, private val eventsToEmit: Int) : Pipe() {
        init {
            this.pipeName = name
        }
        
        override fun truncateModuleContext(): Pipe {
            return this
        }
        
        override suspend fun generateText(promptInjector: String): String {
             // Emulate work and tracing
            repeat(eventsToEmit) { i ->
                val pipeId = currentPipelineId ?: "unknown-pipe-id"
                val event = TraceEvent(
                    timestamp = System.currentTimeMillis(),
                    pipeId = pipeId,
                    pipeName = pipeName,
                    eventType = TraceEventType.PIPE_SUCCESS,
                    phase = TracePhase.EXECUTION,
                    metadata = mapOf("iteration" to i, "thread" to Thread.currentThread().name),
                    content = null,
                    contextSnapshot = null
                )
                PipeTracer.addEvent(pipeId, event)
                delay(1) // yield to allow interleaving
            }
            return "Mock"
        }
    }
}
