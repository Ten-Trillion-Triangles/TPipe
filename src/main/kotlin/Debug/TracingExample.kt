package com.TTT.Debug

import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking

/**
 * Example demonstrating how to use the TPipe tracing system.
 */
class TracingExample
{
    
    /**
     * Mock pipe for demonstration purposes.
     */
    class MockPipe(private val name: String, private val shouldFail: Boolean = false) : Pipe()
    {
        
        override suspend fun init(): Pipe = this
        
        override fun truncateModuleContext(): Pipe = this
        
        override suspend fun generateText(promptInjector: String): String {
            if(shouldFail)
            {
                throw RuntimeException("Mock failure in $name")
            }
            return "Generated text from $name: $promptInjector"
        }
        
        override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
            if(shouldFail)
            {
                throw RuntimeException("Mock failure in $name")
            }
            val result = generateText(content.text)
            return MultimodalContent(text = result)
        }
    }
    
    companion object {
        @JvmStatic
        fun main(args: Array<String>)
        {
            runBlocking {
                demonstrateBasicTracing()
                demonstrateFailureTracing()
                demonstrateAdvancedTracing()
            }
        }
        
        private suspend fun demonstrateBasicTracing() {
            println("=== Basic Tracing Example ===")
            
            val pipeline = Pipeline()
                .enableTracing()
                .add(MockPipe("Pipe1"))
                .add(MockPipe("Pipe2"))
            
            val result = pipeline.execute("Hello World")
            
            println("Result: $result")
            println("\nTrace Report:")
            println(pipeline.getTraceReport(TraceFormat.CONSOLE))
        }
        
        private suspend fun demonstrateFailureTracing() {
            println("\n=== Failure Tracing Example ===")
            
            val pipeline = Pipeline()
                .enableTracing()
                .add(MockPipe("SuccessfulPipe"))
                .add(MockPipe("FailingPipe", shouldFail = true))
                .add(MockPipe("UnreachablePipe"))
            
            val result = pipeline.execute("Test Input")
            
            println("Result: $result")
            println("\nFailure Analysis:")
            val analysis = pipeline.getFailureAnalysis()
            analysis?.let {
                println("Last Successful Pipe: ${it.lastSuccessfulPipe}")
                println("Failure Reason: ${it.failureReason}")
                println("Suggested Fixes: ${it.suggestedFixes}")
            }
        }
        
        private suspend fun demonstrateAdvancedTracing() {
            println("\n=== Advanced Tracing Example ===")
            
            val traceConfig = TracingBuilder()
                .enabled()
                .detailLevel(TraceDetailLevel.VERBOSE)
                .outputFormat(TraceFormat.HTML)
                .includeContext(true)
                .includeMetadata(true)
                .build()
            
            val pipeline = Pipeline()
                .enableTracing(traceConfig)
                .add(MockPipe("VerbosePipe1"))
                .add(MockPipe("VerbosePipe2"))
            
            val result = pipeline.execute("Advanced Test")
            
            println("Result: $result")
            println("\nTrace ID: ${pipeline.getTraceId()}")
            println("\nHTML Report Generated (first 500 chars):")
            val htmlReport = pipeline.getTraceReport(TraceFormat.HTML)
            println(htmlReport.take(500) + "...")
            
            demonstrateVerbosityLevels()
        }
        
        private suspend fun demonstrateVerbosityLevels() {
            println("\n=== Verbosity Levels Example ===")
            
            // MINIMAL - Only failures
            val minimalConfig = TracingBuilder()
                .enabled()
                .detailLevel(TraceDetailLevel.MINIMAL)
                .outputFormat(TraceFormat.CONSOLE)
                .build()
            
            // NORMAL - Standard pipeline flow
            val normalConfig = TracingBuilder()
                .enabled()
                .detailLevel(TraceDetailLevel.NORMAL)
                .outputFormat(TraceFormat.CONSOLE)
                .build()
            
            // VERBOSE - Detailed tracking
            val verboseConfig = TracingBuilder()
                .enabled()
                .detailLevel(TraceDetailLevel.VERBOSE)
                .outputFormat(TraceFormat.CONSOLE)
                .includeContext(true)
                .includeMetadata(true)
                .build()
            
            // DEBUG - Everything including reasoning
            val debugConfig = TracingBuilder()
                .enabled()
                .detailLevel(TraceDetailLevel.DEBUG)
                .outputFormat(TraceFormat.CONSOLE)
                .includeContext(true)
                .includeMetadata(true)
                .build()
            
            println("MINIMAL: Only critical failures and pipeline termination")
            println("NORMAL: Standard pipeline flow and major operations (default)")
            println("VERBOSE: Detailed operation tracking including validation and transformation")
            println("DEBUG: Everything including internal state and model reasoning content")
            
            // Demonstrate with actual pipes
            println("\n--- MINIMAL Level Demo ---")
            val minimalPipeline = Pipeline()
                .enableTracing(minimalConfig)
                .add(MockPipe("MinimalPipe", shouldFail = true))
            
            minimalPipeline.execute("Test")
            println("Trace report generated for MINIMAL level")
            
            println("\n--- DEBUG Level Demo ---")
            val debugPipeline = Pipeline()
                .enableTracing(debugConfig)
                .add(MockPipe("DebugPipe"))
            
            debugPipeline.execute("Test")
            println("Trace report generated for DEBUG level")
        }
    }
}