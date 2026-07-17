package com.TTT.Util

import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.util.Locale

/**
 * Identifies a TPipe JVM to the OS so it shows as a human-readable label in
 * `ps`, `top`, `htop`, and matches `pkill -f TPipe` instead of generic `java`.
 *
 * Implementation: rewrites the string pointed to by glibc's
 * `program_invocation_name` global. On Linux, glibc keeps a writable pointer
 * to the original argv[0] storage in process memory, and `ps -o args`,
 * `/proc/self/cmdline`, and `ps aux | grep <label>` all read from that
 * storage. Mutating it changes every user-visible view.
 *
 * Limitation: `/proc/self/comm` (the 15-char task name used by `killall` and
 * the `comm` column in `top` / `ps -o comm`) is set by the kernel ONCE at
 * exec time and only updates from the thread-group leader. The JVM's TGID
 * leader is unreachable from user code, so `killall TPipe-HTTP` will NOT work.
 * Use `pkill -f TPipe-HTTP` or `ps -o args | grep TPipe-HTTP` instead — those
 * read argv[0] and WILL match.
 *
 * macOS: leaves the JVM bundle identifier as-is. Windows: no-op.
 *
 * @param rawLabel Human-readable label. Whitespace and path separators are
 *                 replaced with dashes so the label survives shell-pipeline matching.
 */
object ProcessName {

    private val isLinux: Boolean =
        System.getProperty("os.name").lowercase(Locale.ROOT).contains("linux")

    @Volatile
    private var lastAppliedLabel: String? = null

    /**
     * Rewrites the OS-visible process label. Safe to call multiple times — the
     * most recent label wins. Returns true if the OS accepted the rename, false
     * if the platform is unsupported, glibc does not export the lookup symbol,
     * or the call failed.
     */
    fun set(rawLabel: String): Boolean {
        if (!isLinux) {
            return false
        }

        val sanitized = sanitize(rawLabel)

        val result = runCatching {
            val nameSegment = readProgramInvocationNameSegment() ?: return@runCatching false
            val capacity = findContiguousCapacity(nameSegment)
            if (capacity < sanitized.length.toLong()) {
                return@runCatching false
            }
            writeBytes(nameSegment, sanitized)
            nameSegment.set(ValueLayout.JAVA_BYTE, sanitized.length.toLong(), 0)
            for (index in sanitized.length + 1 until capacity.toInt()) {
                nameSegment.set(ValueLayout.JAVA_BYTE, index.toLong(), 0)
            }
            lastAppliedLabel = sanitized
            true
        }
        return result.getOrDefault(false)
    }

    /**
     * Returns the last label passed to [set] that the OS confirmed. Useful for
     * logging and for tests that want to assert the rename actually took effect.
     */
    fun currentLabel(): String? = lastAppliedLabel

    private fun sanitize(rawLabel: String): String {
        return rawLabel
            .replace(Regex("[\\s/\\\\]+"), "-")
            .replace(Regex("[^A-Za-z0-9._-]"), "")
            .ifEmpty { "TPipe" }
    }

    /**
     * Resolves glibc's `program_invocation_name` to a writable native memory
     * segment with a known 4 KB view. Callers must scan for the trailing NUL
     * to find the writable boundary before writing.
     */
    private fun readProgramInvocationNameSegment(): MemorySegment? {
        val libc = Linker.nativeLinker().defaultLookup()
        val namePointerSymbol = libc.find("program_invocation_name").orElse(null) ?: return null
        val namePointer = namePointerSymbol.reinterpret(ValueLayout.ADDRESS.byteSize())
        val namePointerValue = namePointer.get(ValueLayout.ADDRESS, 0L)
        return namePointerValue.reinterpret(NATIVE_VIEW_BYTES)
    }

    private fun writeBytes(target: MemorySegment, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        for ((index, byte) in bytes.withIndex()) {
            target.set(ValueLayout.JAVA_BYTE, index.toLong(), byte.toByte())
        }
    }

    /**
     * Walks the null-terminated string at [start] and returns the byte count
     * available until the next null. Caps the scan at [NATIVE_VIEW_BYTES] to
     * avoid runaway reads on an unbounded segment.
     */
    private fun findContiguousCapacity(start: MemorySegment): Long {
        var index = 0L
        while (index < NATIVE_VIEW_BYTES) {
            val byte = start.get(ValueLayout.JAVA_BYTE, index)
            if (byte.toInt() == 0) {
                return index
            }
            index += 1L
        }
        return NATIVE_VIEW_BYTES
    }

    private const val NATIVE_VIEW_BYTES: Long = 4L * 1024L
}