package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Simple example pipe for demonstrating pause/resume functionality
 */
class ExamplePipe(private val name: String) : Pipe() {
    init {
        pipeName = name
    }
    
    override fun truncateModuleContext(): Pipe = this
    
    override suspend fun generateText(promptInjector: String): String {
        println("Executing $name...")
        delay(100) // Simulate some processing time
        return "Output from $name"
    }
}

/**
 * Example demonstrating Pipeline pause/resume functionality
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
