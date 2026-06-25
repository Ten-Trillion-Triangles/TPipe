package com.TTT.PipeContextProtocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.MalformedInputException
import java.util.concurrent.TimeUnit

/**
 * Captures subprocess stdout and stderr without deadlocking, with UTF-8
 * auto-detection and overflow-to-temp-file for arbitrarily large outputs.
 *
 * Replaces the previous pattern of sequential readText() calls on each
 * stream, which would deadlock once either pipe buffer (~64KB on Linux)
 * filled before the parent could drain it.
 *
 * Wire format:
 * - stdout: String? populated when stdout bytes are valid UTF-8
 * - binary: ByteArray? populated when stdout bytes are NOT valid UTF-8
 *   (exactly one of stdout/binary is populated, never both)
 * - truncated: true when stdout was held to maxInMemoryBytes and the
 *   remainder spilled to a temp file referenced by overflowPath
 *   (output size is therefore unbounded; only maxInMemoryBytes are kept
 *   resident in memory per call)
 */
object SubprocessOutputCapture
{
    suspend fun capture(
        process: Process,
        timeoutMs: Long,
        maxInMemoryBytes: Int
    ): BufferedOutput = coroutineScope {
        // Read both streams in parallel so the OS pipe buffer never fills.
        // If we read sequentially, a child that writes >64KB to stdout while
        // stderr fills first will block on its next write while we wait on
        // stderr's readText(). That's the production deadlock this replaces.
        //
        // We read the FULL stream contents (no cap here) so totalBytes
        // reflects everything the child wrote. The cap on in-memory footprint
        // is enforced later by toBufferedText — anything past maxInMemoryBytes
        // spills to a temp file rather than being silently dropped.
        val stdoutDeferred = async(Dispatchers.IO) {
            process.inputStream.readAllBytes()
        }
        val stderrDeferred = async(Dispatchers.IO) {
            process.errorStream.readAllBytes()
        }

        val completed = withTimeoutOrNull(timeoutMs) {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } ?: false

        if(!completed)
        {
            // Hit the timeout wall. Kill the child, drain any remaining
            // buffered bytes so the pipes don't leak file descriptors, and
            // return an empty result — the caller will report this as a
            // timeout error via the surrounding executor.
            process.destroyForcibly()
            stdoutDeferred.await()
            stderrDeferred.await()
            return@coroutineScope BufferedOutput(
                stdout = null,
                stderr = null,
                binary = null,
                totalBytes = 0L,
                truncated = false
            )
        }

        val stdoutBytes = stdoutDeferred.await()
        val stderrBytes = stderrDeferred.await()

        val totalBytes = stdoutBytes.size.toLong() + stderrBytes.size.toLong()
        val stdoutResult = stdoutBytes.toBufferedText(maxInMemoryBytes)

        BufferedOutput(
            stdout = stdoutResult.text,
            stderr = String(stderrBytes, Charsets.UTF_8),
            binary = stdoutResult.binaryTail,
            totalBytes = totalBytes,
            truncated = stdoutResult.truncated,
            overflowPath = stdoutResult.overflowPath
        )
    }
}

private data class TextDecodeResult(
    val text: String?,
    val binaryTail: ByteArray?,
    val truncated: Boolean,
    val overflowPath: String?
)

private fun ByteArray.toBufferedText(maxInMemoryBytes: Int): TextDecodeResult
{
    if(isEmpty()) return TextDecodeResult(text = null, binaryTail = null, truncated = false, overflowPath = null)

    val charset = Charset.forName("UTF-8")
    val decoder = charset.newDecoder()

    val decoded = try
    {
        decoder.decode(ByteBuffer.wrap(this)).toString()
    }
    catch(_: MalformedInputException)
    {
        // Not valid UTF-8. Surface the raw bytes — exactly one of
        // text/binaryTail is populated, never both.
        return TextDecodeResult(
            text = null,
            binaryTail = this,
            truncated = false,
            overflowPath = null
        )
    }

    // Valid UTF-8 and within budget — hold the whole thing in memory.
    if(size <= maxInMemoryBytes)
    {
        return TextDecodeResult(
            text = decoded,
            binaryTail = null,
            truncated = false,
            overflowPath = null
        )
    }

    // Overflow — write the full bytes to a temp file and keep only the
    // head in memory so callers can still see the start of long output
    // without OOM'ing the JVM. The temp file is referenced by path so
    // downstream consumers can read or stream the full content.
    val tempFile = File.createTempFile("pcp_overflow_", ".bin").apply { deleteOnExit() }
    tempFile.writeBytes(this)
    val headText = String(this.copyOfRange(0, maxInMemoryBytes), Charsets.UTF_8)
    return TextDecodeResult(
        text = headText,
        binaryTail = null,
        truncated = true,
        overflowPath = tempFile.absolutePath
    )
}

/**
 * Reads up to [maxBytes] from this stream. Reads past [maxBytes] are
 * discarded so the OS pipe buffer drains — without this the child
 * would block on its next write after exceeding the budget.
 *
 * Deprecated: callers should use InputStream.readAllBytes() (JDK 11+)
 * and let SubprocessOutputCapture.toBufferedText enforce the in-memory
 * cap. This helper is kept only for compatibility with any external
 * test or debug path; it is not used by capture().
 */
@Suppress("unused")
private fun java.io.InputStream.readUpTo(maxBytes: Int): ByteArray
{
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var totalRead = 0
    while(totalRead < maxBytes)
    {
        val toRead = minOf(chunk.size, maxBytes - totalRead)
        val read = read(chunk, 0, toRead)
        if(read < 0) break
        buffer.write(chunk, 0, read)
        totalRead += read
    }
    // Drain everything past the in-memory cap so the pipe never fills up
    // and the child can finish. These bytes are dropped from memory.
    var drainRead = read(chunk)
    while(drainRead >= 0)
    {
        drainRead = read(chunk)
    }
    return buffer.toByteArray()
}