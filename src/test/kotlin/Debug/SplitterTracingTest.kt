package com.TTT.Debug

import com.TTT.Pipeline.Splitter
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.io.File

class SplitterTracingTest {
    
    @Test
    fun testSplitterTracingSystem() = runBlocking {
        // Enable global tracer
        PipeTracer.enable()
        
        try {
            val traceConfig = TraceConfig(
                enabled = true,
                detailLevel = TraceDetailLevel.DEBUG,
                outputFormat = TraceFormat.HTML,
                includeContext = true,
                includeMetadata = true
            )
            
            val splitter = Splitter().enableTracing(traceConfig)
            
            // Create pipelines with multiple pipes each to show internal pipeline execution
            val analysisP1 = Pipeline()
                .add(MockPipe("AnalysisPreprocessor", "Preprocessed analysis data"))
                .add(MockPipe("AnalysisEngine", "Analysis result from engine"))
                .setPipelineName("AnalysisPipeline1")
            
            val analysisP2 = Pipeline()
                .add(MockPipe("AnalysisValidator", "Validated analysis input"))
                .setPipelineName("AnalysisPipeline2")
            
            val summaryP1 = Pipeline()
                .add(MockPipe("SummaryExtractor", "Extracted key points"))
                .setPipelineName("SummaryPipeline1")
            
            // Add multiple keys with different content and pipelines
            splitter.addContent("analysis", MultimodalContent("Raw data for analysis processing"))
                .addPipeline("analysis", analysisP1)
                .addPipeline("analysis", analysisP2)
            
            splitter.addContent("summary", MultimodalContent("Content to be summarized"))
                .addPipeline("summary", summaryP1)
            
            // Add callbacks to generate callback trace events
            splitter.setOnPipelineFinish { splitter, pipeline, content ->
                println("✅ Pipeline ${pipeline.pipelineName} completed: ${content.text.take(30)}...")
            }
            
            splitter.setOnSplitterFinish { splitter ->
                println("🎯 Splitter completed with ${splitter.results.contents.size} results")
            }
            
            // Initialize and execute
            splitter.init(traceConfig)
            val jobs = splitter.executePipelines()
            jobs.forEach { it.await() }
            
            // Wait for completion callbacks
            delay(100)
            
            // Debug: Print actual results
            println("📊 Actual results count: ${splitter.results.contents.size}")
            
            // Get comprehensive trace report
            val traceReport = splitter.getTraceReport(TraceFormat.HTML)
            
            // Write comprehensive HTML report
            val outputFile = File("splitter_comprehensive_trace.html")
            outputFile.writeText(traceReport)
            println("🔍 Comprehensive trace report: ${outputFile.absolutePath}")
            println("📊 Shows ${splitter.results.contents.size} pipeline results across multiple keys")
            
            // Basic verifications
            assertTrue(traceReport.isNotEmpty())
            assertTrue(splitter.getTraceId().isNotEmpty())
            
        } finally {
            PipeTracer.disable()
        }
    }
}

// Mock pipe class for testing
class MockPipe(
    private val mockName: String,
    private val mockResult: String
) : Pipe() {
    
    init {
        pipeName = mockName
    }
    
    override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
        delay(10) // Simulate processing
        return MultimodalContent(mockResult)
    }
    
    override fun truncateModuleContext(): Pipe = this
    
    override suspend fun generateText(promptInjector: String): String {
        delay(10)
        return mockResult
    }
}
