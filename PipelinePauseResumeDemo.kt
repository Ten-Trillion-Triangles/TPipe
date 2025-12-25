package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Simple example pipe for demonstrating pause/resume functionality.
 * 
 * @param name The display name for this pipe instance
 */
class ExamplePipe(private val name: String) : Pipe()
{
    init {
        pipeName = name
    }
    
    /**
     * Returns a copy of this pipe with truncated context.
     * 
     * @return This pipe instance (no truncation needed for demo)
     */
    override fun truncateModuleContext(): Pipe = this
    
    /**
     * Generates text output for demonstration purposes.
     * 
     * @param promptInjector The input prompt to process
     * @return Simple output message indicating completion
     */
    override suspend fun generateText(promptInjector: String): String
    {
        println("Executing $name...")
        delay(100) // Simulate some processing time
        return "Output from $name"
    }
}

/**
 * Example demonstrating Pipeline pause/resume functionality.
 * Creates a pipeline with multiple pipes and demonstrates pause/resume control.
 */
fun main() = runBlocking {
    println("=== TPipe Pipeline Pause/Resume Demo ===")
    
    // Create pipeline with pause points
    val pipeline = Pipeline()
        .add(ExamplePipe("DataProcessor"))
        .add(ExamplePipe("Analyzer"))
        .add(ExamplePipe("Reporter"))
        .pauseBeforePipes()
        .pauseOnCompletion()
        .onPause { pipe, content ->
            println("⏸️  Pipeline paused at: ${pipe?.pipeName ?: "completion"}")
        }
        .onResume { pipe, content ->
            println("▶️  Pipeline resumed from: ${pipe?.pipeName ?: "completion"}")
        }
        .setPipelineName("DemoWorkflow")
    
    println("✅ Pipeline configured with pause points")
    println("🔧 Can pause: ${pipeline.canPause()}")
    
    // Start pipeline in background
    val job = launch {
        val result = pipeline.execute("Initial data")
        println("🎯 Final result: ${result.text}")
    }
    
    // Demonstrate pause/resume control
    delay(150)
    println("🛑 Requesting pause...")
    pipeline.pause()
    
    delay(1000)
    println("🚀 Resuming pipeline...")
    pipeline.resume()
    
    // Wait for completion
    job.join()
    println("✨ Demo completed!")
}
