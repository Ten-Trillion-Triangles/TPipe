package com.TTT.Debug

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipeTracerTest {
    
    @BeforeTest
    fun setup() {
        PipeTracer.enable()
        RemoteTraceConfig.remoteServerUrl = null
        RemoteTraceConfig.authHeader = null
        RemoteTraceConfig.dispatchAutomatically = false
    }

    @AfterTest
    fun tearDown() {
        RemoteTraceConfig.remoteServerUrl = null
        RemoteTraceConfig.authHeader = null
        RemoteTraceConfig.dispatchAutomatically = false
    }
    
    @Test
    fun testStartTrace() {
        val pipelineId = "test-pipeline"
        PipeTracer.startTrace(pipelineId)
        
        val trace = PipeTracer.getTrace(pipelineId)
        assertTrue(trace.isEmpty())
    }
    
    @Test
    fun testAddEvent() {
        val pipelineId = "test-pipeline"
        PipeTracer.startTrace(pipelineId)
        
        val event = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "test-pipe",
            pipeName = "TestPipe",
            eventType = TraceEventType.PIPE_START,
            phase = TracePhase.INITIALIZATION,
            content = null,
            contextSnapshot = null
        )
        
        PipeTracer.addEvent(pipelineId, event)
        
        val trace = PipeTracer.getTrace(pipelineId)
        assertEquals(1, trace.size)
        assertEquals(event, trace.first())
    }
    
    @Test
    fun testExportTrace() {
        val pipelineId = "test-pipeline"
        PipeTracer.startTrace(pipelineId)
        
        val event = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "test-pipe",
            pipeName = "TestPipe",
            eventType = TraceEventType.PIPE_SUCCESS,
            phase = TracePhase.CLEANUP,
            content = null,
            contextSnapshot = null
        )
        
        PipeTracer.addEvent(pipelineId, event)
        
        val consoleOutput = PipeTracer.exportTrace(pipelineId, TraceFormat.CONSOLE)
        assertTrue(consoleOutput.contains("TestPipe"))
        assertTrue(consoleOutput.contains("PIPE_SUCCESS"))
    }
    
    @Test
    fun testFailureAnalysis() {
        val pipelineId = "test-pipeline"
        PipeTracer.startTrace(pipelineId)
        
        val successEvent = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "pipe1",
            pipeName = "SuccessfulPipe",
            eventType = TraceEventType.PIPE_SUCCESS,
            phase = TracePhase.CLEANUP,
            content = null,
            contextSnapshot = null
        )
        
        val failureEvent = TraceEvent(
            timestamp = System.currentTimeMillis() + 100,
            pipeId = "pipe2",
            pipeName = "FailedPipe",
            eventType = TraceEventType.PIPE_FAILURE,
            phase = TracePhase.EXECUTION,
            content = null,
            contextSnapshot = null,
            error = RuntimeException("Test failure")
        )
        
        PipeTracer.addEvent(pipelineId, successEvent)
        PipeTracer.addEvent(pipelineId, failureEvent)
        
        val analysis = PipeTracer.getFailureAnalysis(pipelineId)
        assertEquals("SuccessfulPipe", analysis.lastSuccessfulPipe)
        assertEquals(failureEvent, analysis.failurePoint)
        assertEquals("Test failure", analysis.failureReason)
        assertTrue(analysis.suggestedFixes.isNotEmpty())
    }

    @Test
    fun testExportTraceAutoDispatchPostsOnceWithoutRecursion() {
        val pipelineId = "remote-dispatch-pipeline"
        PipeTracer.startTrace(pipelineId)
        PipeTracer.addEvent(pipelineId, TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "test-pipe",
            pipeName = "RemoteDispatchPipe",
            eventType = TraceEventType.PIPE_SUCCESS,
            phase = TracePhase.CLEANUP,
            content = null,
            contextSnapshot = null
        ))

        val postLatch = CountDownLatch(1)
        var requestCount = 0
        var authHeader: String? = null
        var requestBody = ""
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/api/traces") { exchange ->
                requestCount++
                authHeader = exchange.requestHeaders.getFirst("Authorization")
                requestBody = exchange.requestBody.bufferedReader().readText()
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(ByteArray(0)) }
                postLatch.countDown()
            }
            start()
        }

        try {
            RemoteTraceConfig.remoteServerUrl = "http://127.0.0.1:${server.address.port}"
            RemoteTraceConfig.authHeader = "Bearer test-token"
            RemoteTraceConfig.dispatchAutomatically = true

            val consoleOutput = PipeTracer.exportTrace(pipelineId, TraceFormat.CONSOLE)

            assertTrue(consoleOutput.contains("RemoteDispatchPipe"))
            assertTrue(postLatch.await(5, TimeUnit.SECONDS), "Expected one trace POST to be dispatched")
            assertEquals(1, requestCount)
            assertEquals("Bearer test-token", authHeader)
            assertTrue(requestBody.contains("\"pipelineId\":\"$pipelineId\""))
            assertTrue(requestBody.contains("\"htmlContent\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testExportTraceWithAutoDispatchAndNoRemoteUrlStillReturnsLocalTrace() {
        val pipelineId = "no-remote-url-pipeline"
        PipeTracer.startTrace(pipelineId)
        PipeTracer.addEvent(pipelineId, TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "test-pipe",
            pipeName = "LocalOnlyPipe",
            eventType = TraceEventType.PIPE_SUCCESS,
            phase = TracePhase.CLEANUP,
            content = null,
            contextSnapshot = null
        ))

        RemoteTraceConfig.dispatchAutomatically = true

        val consoleOutput = PipeTracer.exportTrace(pipelineId, TraceFormat.CONSOLE)

        assertTrue(consoleOutput.contains("LocalOnlyPipe"))
        assertTrue(consoleOutput.contains("PIPE_SUCCESS"))
    }
}
