package com.TTT.Config

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceEvent
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TraceFormat
import com.TTT.Debug.TracePhase
import com.TTT.Debug.TracingBuilder
import com.TTT.Pipeline.Pipeline
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Regression coverage for the instance-owned TPipe filesystem namespace.
 */
class TPipeConfigIsolationTest
{
    @TempDir
    lateinit var tempDir: Path

    private lateinit var originalConfigDir: String
    private lateinit var originalInstanceId: String

    @BeforeEach
    fun captureConfiguration()
    {
        originalConfigDir = TPipeConfig.configDir
        originalInstanceId = TPipeConfig.instanceID
        PipeTracer.enable()
    }

    @AfterEach
    fun restoreConfiguration()
    {
        TPipeConfig.configDir = originalConfigDir
        TPipeConfig.instanceID = originalInstanceId
        PipeTracer.disable()
    }

    @Test
    fun allDirectoryHelpersUseTheInstanceRoot()
    {
        TPipeConfig.configDir = tempDir.toString()
        TPipeConfig.instanceID = "Apex"

        val instanceRoot = tempDir.resolve("Apex").toString()
        val memoryDir = "$instanceRoot/memory"
        val lorebookDir = "$memoryDir/lorebook"
        val todoDir = "$memoryDir/todo"

        assertEquals(instanceRoot, TPipeConfig.getTPipeConfigDir())
        assertEquals(memoryDir, TPipeConfig.getMemoryDir())
        assertEquals(lorebookDir, TPipeConfig.getLorebookDir())
        assertEquals(todoDir, TPipeConfig.getTodoListDir())
        assertEquals("$instanceRoot/debug", TPipeConfig.getDebugDir())
        assertEquals("$instanceRoot/debug/trace", TPipeConfig.getTraceDir())
        assertEquals(TPipeConfig.getTodoListDir(), TPipeConfig.getTodoDir())

        TPipeConfig.instanceID = "OtherApp"

        assertNotEquals(instanceRoot, TPipeConfig.getTPipeConfigDir())
        assertNotEquals(memoryDir, TPipeConfig.getMemoryDir())
        assertNotEquals(lorebookDir, TPipeConfig.getLorebookDir())
        assertNotEquals(todoDir, TPipeConfig.getTodoListDir())
        assertNotEquals("$instanceRoot/debug", TPipeConfig.getDebugDir())
        assertNotEquals("$instanceRoot/debug/trace", TPipeConfig.getTraceDir())
    }

    @Test
    fun defaultTracePathsUseTheActiveInstance()
    {
        TPipeConfig.configDir = tempDir.toString()
        TPipeConfig.instanceID = "Apex"
        val apexTraceDir = TPipeConfig.getTraceDir()

        assertEquals(apexTraceDir, TraceConfig().exportPath)
        assertEquals(apexTraceDir, TracingBuilder().build().exportPath)
        assertEquals(apexTraceDir, TracingBuilder().autoExport().build().exportPath)
        assertEquals(apexTraceDir, com.TTT.Pipeline.PumpStationTracingDsl().build().exportPath)
        assertEquals(apexTraceDir, com.TTT.Pipeline.PumpStationTracingDsl().autoExport().build().exportPath)

        TPipeConfig.instanceID = "OtherApp"
        val otherTraceDir = TPipeConfig.getTraceDir()

        assertEquals(otherTraceDir, TraceConfig().exportPath)
        assertNotEquals(apexTraceDir, otherTraceDir)
    }

    @Test
    fun defaultAutoExportWritesUnderTheInstanceTraceDirectory()
    {
        TPipeConfig.configDir = tempDir.toString()
        TPipeConfig.instanceID = "Apex"

        val pipeline = Pipeline()
        pipeline.enableTracing(TraceConfig(autoExport = true))
        val traceId = pipeline.getTraceId()
        PipeTracer.startTrace(traceId)
        PipeTracer.addEvent(
            traceId,
            TraceEvent(
                timestamp = System.currentTimeMillis(),
                pipeId = "pipe",
                pipeName = "Pipe",
                eventType = TraceEventType.PIPE_SUCCESS,
                phase = TracePhase.CLEANUP,
                content = null,
                contextSnapshot = null
            )
        )

        pipeline.getTraceReport(TraceFormat.CONSOLE)

        val instanceTraceDir = File(TPipeConfig.getTraceDir())
        assertTrue(
            instanceTraceDir.listFiles()?.any { it.isFile } == true,
            "Default auto-export must write under ${instanceTraceDir.absolutePath}"
        )
        assertFalse(
            File(tempDir.toFile(), "debug/trace").exists(),
            "Default auto-export must not use the unscoped configDir/debug/trace path"
        )
    }

    @Test
    fun explicitTraceExportPathRemainsUnchanged()
    {
        TPipeConfig.configDir = tempDir.toString()
        TPipeConfig.instanceID = "Apex"
        val explicitPath = tempDir.resolve("caller-owned-traces").toString()

        assertEquals(explicitPath, TraceConfig(autoExport = true, exportPath = explicitPath).exportPath)
        assertEquals(explicitPath, TracingBuilder().autoExport(path = explicitPath).build().exportPath)
        assertEquals(
            explicitPath,
            com.TTT.Pipeline.PumpStationTracingDsl().autoExport(path = explicitPath).build().exportPath
        )
    }
}
