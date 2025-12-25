package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Simple test pipe for testing pause/resume functionality
 */
class TestPipe(private val name: String) : Pipe() {
    init {
        pipeName = name
    }
    
    override fun truncateModuleContext(): Pipe {
        return this
    }
    
    override suspend fun generateText(promptInjector: String): String {
        return "Generated text from $name"
    }
}

/**
 * Test class for Pipeline pause/resume functionality
 */
class PipelinePauseResumeTest {
    
    @Test
    fun testBasicPauseResume() = runBlocking {
        val pipeline = Pipeline()
            .add(TestPipe("Pipe1"))
            .add(TestPipe("Pipe2"))
            .add(TestPipe("Pipe3"))
            .pauseBeforePipes()
            .setPipelineName("TestPipeline")
        
        val initialContent = MultimodalContent("Start: ")
        
        // Start pipeline in background
        val job = launch {
            pipeline.execute(initialContent)
        }
        
        // Give it a moment to start
        delay(100)
        
        // Verify pausing capability is enabled
        assertTrue(pipeline.canPause())
        
        // Pause the pipeline
        pipeline.pause()
        
        // Give it time to pause
        delay(50)
        
        // Resume the pipeline
        pipeline.resume()
        
        // Wait for completion
        job.join()
        
        // Verify execution completed - check that content was processed
        val result = pipeline.content.text
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }
    
    @Test
    fun testPausePointsNotEnabledByDefault() {
        val pipeline = Pipeline()
            .add(TestPipe("Pipe1"))
            .setPipelineName("TestPipeline")
        
        // Verify pausing is not enabled by default
        assertFalse(pipeline.canPause())
    }
    
    @Test
    fun testConditionalPause() = runBlocking {
        var pauseConditionMet = false
        
        val pipeline = Pipeline()
            .add(TestPipe("Pipe1"))
            .add(TestPipe("Pipe2"))
            .pauseWhen { pipe, content ->
                pipe.pipeName == "Pipe2"
            }
            .onPause { pipe, content ->
                pauseConditionMet = true
            }
            .setPipelineName("TestPipeline")
        
        val initialContent = MultimodalContent("Start: ")
        
        // Start pipeline
        val job = launch {
            pipeline.execute(initialContent)
        }
        
        // Give it time to process
        delay(200)
        
        // Resume if paused
        if (pipeline.isPaused()) {
            pipeline.resume()
        }
        
        job.join()
        
        // Verify pausing capability was enabled
        assertTrue(pipeline.canPause())
    }
}
