package com.TTT.Context

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * File-system helpers dedicated to `.bank` and `.todo` persistence.
 *
 * The phase-3 contract for persisted memory is:
 * - temp-file writes plus atomic replace where supported
 * - same-host JVM coordination through sidecar lock files
 * - no promise of correctness for arbitrary external tools that ignore the lock protocol
 */
internal object MemoryPersistence
{
    private const val lockSuffix = ".lck"

    /**
     * Read a memory file while holding the shared lock for its sidecar lock file.
     *
     * @param filePath Path of the `.bank` or `.todo` file to read.
     * @return File contents, or an empty string when the file does not exist.
     */
    fun readMemoryFile(filePath: String): String
    {
        val targetPath = Path.of(filePath)
        val parentPath = targetPath.parent ?: return ""
        if(!Files.exists(parentPath))
        {
            return ""
        }

        return withLock(targetPath, shared = true) {
            if(!Files.exists(targetPath) || !Files.isRegularFile(targetPath) || !Files.isReadable(targetPath))
            {
                return@withLock ""
            }

            Files.readString(targetPath)
        }
    }

    /**
     * Write a memory file using temp-file replacement under an exclusive sidecar lock.
     *
     * @param filePath Path of the `.bank` or `.todo` file to write.
     * @param content Serialized file contents.
     */
    fun writeMemoryFile(filePath: String, content: String)
    {
        val targetPath = Path.of(filePath)
        ensureParentDirectory(targetPath)

        withLock(targetPath, shared = false) {
            val tempPath = createTempPath(targetPath)

            try
            {
                writeTempFile(tempPath, content)
                moveTempFile(tempPath, targetPath)
                forceDirectorySync(targetPath.parent)
            }
            finally
            {
                Files.deleteIfExists(tempPath)
            }
        }
    }

    /**
     * Delete a memory file while holding its exclusive sidecar lock.
     *
     * @param filePath Path of the `.bank` or `.todo` file to delete.
     * @return True when the file existed and was deleted, false otherwise.
     */
    fun deleteMemoryFile(filePath: String): Boolean
    {
        val targetPath = Path.of(filePath)
        val parentPath = targetPath.parent ?: return false
        if(!Files.exists(parentPath))
        {
            return false
        }

        return withLock(targetPath, shared = false) {
            if(!Files.exists(targetPath))
            {
                return@withLock false
            }

            Files.delete(targetPath)
            forceDirectorySync(parentPath)
            true
        }
    }

    /**
     * Hold a shared or exclusive lock for a memory file's sidecar lock file while running [block].
     *
     * @param targetPath Path of the managed memory file.
     * @param shared True for readers, false for writers and deletes.
     * @param block Action to execute while the lock is held.
     * @return The block result.
     */
    internal fun <T> withLock(targetPath: Path, shared: Boolean, block: () -> T): T
    {
        val lockPath = targetPath.resolveSibling("${targetPath.fileName}${lockSuffix}")
        ensureParentDirectory(lockPath)

        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        ).use { fileChannel ->
            fileChannel.lock(0L, Long.MAX_VALUE, shared).use {
                return block()
            }
        }
    }

    /**
     * Create the directory that will contain [targetPath] when it does not already exist.
     *
     * @param targetPath File path whose parent directory must exist.
     */
    private fun ensureParentDirectory(targetPath: Path)
    {
        val parentPath = targetPath.parent ?: return
        Files.createDirectories(parentPath)
    }

    /**
     * Resolve a unique temporary file path beside [targetPath].
     *
     * @param targetPath Final memory-file location.
     * @return Temporary path in the same directory.
     */
    private fun createTempPath(targetPath: Path): Path
    {
        return targetPath.resolveSibling("${targetPath.fileName}.${UUID.randomUUID()}.tmp")
    }

    /**
     * Write [content] to [tempPath] and force it to disk before replacement.
     *
     * @param tempPath Temporary file path.
     * @param content Serialized memory contents.
     */
    private fun writeTempFile(tempPath: Path, content: String)
    {
        FileChannel.open(
            tempPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        ).use { fileChannel ->
            val contentBuffer = ByteBuffer.wrap(content.toByteArray())
            while(contentBuffer.hasRemaining())
            {
                fileChannel.write(contentBuffer)
            }
            fileChannel.force(true)
        }
    }

    /**
     * Move [tempPath] into [targetPath] atomically when the platform supports it.
     *
     * @param tempPath Temporary file path.
     * @param targetPath Final memory-file path.
     */
    private fun moveTempFile(tempPath: Path, targetPath: Path)
    {
        try
        {
            Files.move(
                tempPath,
                targetPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }
        catch(_: AtomicMoveNotSupportedException)
        {
            Files.move(
                tempPath,
                targetPath,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    /**
     * Best-effort directory sync after a write or delete so replacement metadata is flushed when the platform allows it.
     *
     * @param directoryPath Parent directory of the managed file.
     */
    private fun forceDirectorySync(directoryPath: Path?)
    {
        if(directoryPath == null || !Files.exists(directoryPath))
        {
            return
        }

        try
        {
            FileChannel.open(directoryPath, StandardOpenOption.READ).use { fileChannel ->
                fileChannel.force(true)
            }
        }
        catch(_: Exception)
        {
            // Directory fsync support varies by platform; atomic replace and lock discipline remain the hard contract.
        }
    }
}
