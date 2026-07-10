package com.TTT.Util

import com.TTT.Util.ProcessName
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder

/**
 * Verifies that ProcessName.set rewrites the argv[0] string in place so
 * `ps aux | grep TPipe` matches the running JVM. Linux-only — macOS/Windows
 * tests self-skip when /proc is unavailable.
 *
 * Why /proc/self/cmdline and not /proc/self/comm? Linux sets /proc/PID/comm
 * from the kernel task comm field at exec time and refuses to update it
 * from non-leader threads. We rewrite the argv[0] string instead, which
 * DOES update both /proc/self/cmdline and `ps -o args`, and which
 * `pkill -f TPipe` matches even though `killall TPipe` does not.
 *
 * Tests are pinned to a fixed execution order because the rename mutates
 * a shared native buffer — re-ordering can leave stale state visible.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ProcessNameTest {

    @Test
    @Order(1)
    fun rewritesArgvZeroToLabel() {
        if (!File("/proc/self/cmdline").exists()) {
            println("[ProcessNameTest] /proc/self/cmdline unavailable on this OS — skipping")
            return
        }

        val targetLabel = "TPipe-Test"
        val success = ProcessName.set(targetLabel)

        assertTrue(success, "ProcessName.set should return true on Linux")

        val firstArgvEntry = readFirstCmdlineArg()
        assertEquals(targetLabel, firstArgvEntry,
            "Expected first argv entry to equal '$targetLabel', but was: '$firstArgvEntry'")
    }

    @Test
    @Order(2)
    fun currentLabelReturnsAppliedLabel() {
        if (!File("/proc/self/cmdline").exists()) {
            println("[ProcessNameTest] /proc/self/cmdline unavailable on this OS — skipping")
            return
        }

        ProcessName.set("TPipe-Curr")
        val firstArgvEntry = readFirstCmdlineArg()
        assertEquals("TPipe-Curr", firstArgvEntry,
            "Expected first argv entry to equal 'TPipe-Curr' after set, but was: '$firstArgvEntry'")
    }

    @Test
    @Order(3)
    fun sanitizesSpacesAndSlashes() {
        if (!File("/proc/self/cmdline").exists()) {
            println("[ProcessNameTest] /proc/self/cmdline unavailable on this OS — skipping")
            return
        }

        ProcessName.set("TPipe Stdio/Loop")
        val firstArgvEntry = readFirstCmdlineArg()
        assertTrue(!firstArgvEntry.contains(' '),
            "Sanitized label must not contain spaces, was: '$firstArgvEntry'")
        assertTrue(!firstArgvEntry.contains('/'),
            "Sanitized label must not contain slashes, was: '$firstArgvEntry'")
        assertTrue(firstArgvEntry.startsWith("TPipe"),
            "Sanitized label should start with 'TPipe', was: '$firstArgvEntry'")
    }

    private fun readFirstCmdlineArg(): String {
        val bytes = File("/proc/self/cmdline").readBytes()
        val firstNull = bytes.indexOf(0)
        val argvBytes = if (firstNull >= 0) bytes.copyOfRange(0, firstNull) else bytes
        return argvBytes.toString(Charsets.UTF_8)
    }
}