package com.TTT.Context

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    private val processLocks = ConcurrentHashMap<Path, ReentrantLock>()

    /**
     * Read a memory file while holding the shared lock for its sidecar lock file.
     *
     * @param filePath Path of the `.bank` or `.todo` file to read.
     * @return File contents, or an empty string when the file does not exist.
     */
    suspend fun readMemoryFile(filePath: String): String
    {
        return withContext(Dispatchers.IO) {
            val targetPath = Path.of(filePath)
            val parentPath = targetPath.parent ?: return@withContext ""
            if(!Files.exists(parentPath))
            {
                return@withContext ""
            }

            withLock(targetPath, shared = true) {
                if(!Files.exists(targetPath) || !Files.isRegularFile(targetPath) || !Files.isReadable(targetPath))
                {
                    return@withLock ""
                }

                Files.readString(targetPath)
            }
        }
    }

    /**
     * Write a memory file using temp-file replacement under an exclusive sidecar lock.
     * The blocking file-lock and filesystem work is isolated onto `Dispatchers.IO`.
     *
     * @param filePath Path of the `.bank` or `.todo` file to write.
     * @param content Serialized file contents.
     */
    suspend fun writeMemoryFile(filePath: String, content: String)
    {
        withContext(Dispatchers.IO) {
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
    }

    /**
     * Delete a memory file while holding its exclusive sidecar lock.
     * The blocking file-lock and filesystem work is isolated onto `Dispatchers.IO`.
     *
     * @param filePath Path of the `.bank` or `.todo` file to delete.
     * @return True when the file existed and was deleted, false otherwise.
     */
    suspend fun deleteMemoryFile(filePath: String): Boolean
    {
        return withContext(Dispatchers.IO) {
            val targetPath = Path.of(filePath)
            val parentPath = targetPath.parent ?: return@withContext false
            if(!Files.exists(parentPath))
            {
                return@withContext false
            }

            withLock(targetPath, shared = false) {
                if(!Files.exists(targetPath))
                {
                    return@withLock false
                }

                Files.delete(targetPath)
                forceDirectorySync(parentPath)
                true
            }
        }
    }

    /**
     * Hold a shared or exclusive lock for a memory file's sidecar lock file while running [block].
     * Callers should invoke this from an IO dispatcher because `FileChannel.lock(...)` blocks the thread.
     *
     * @param targetPath Path of the managed memory file.
     * @param shared True for readers, false for writers and deletes.
     * @param block Action to execute while the lock is held.
     * @return The block result.
     */
    internal fun <T> withLock(targetPath: Path, shared: Boolean, block: () -> T): T
    {
        val normalizedTargetPath = targetPath.toAbsolutePath().normalize()
        val processLock = processLocks.computeIfAbsent(normalizedTargetPath) { ReentrantLock() }
        return processLock.withLock {
            withFileLock(normalizedTargetPath, shared, block)
        }
    }

    /**
     * Hold the sidecar lock without acquiring the in-process lock.
     *
     * This is used by the administrative persistence facade after it has acquired
     * the per-file process lock for a complete checked transaction.
     */
    internal fun <T> withFileLock(targetPath: Path, shared: Boolean, block: () -> T): T
    {
        val lockPath = targetPath.resolveSibling("${targetPath.fileName}${lockSuffix}")
        ensureParentDirectory(lockPath)
        if(Files.isSymbolicLink(lockPath)) throw IllegalStateException("Memory sidecar lock must not be a symbolic link")

        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        ).use { fileChannel ->
            fileChannel.lock(0L, Long.MAX_VALUE, shared).use {
                return block()
            }
        }
    }

    /**
     * Run a complete administrative transaction while sharing the same in-process
     * coordination as ordinary ContextBank persistence.
     */
    internal fun <T> withExclusiveTransaction(targetPath: Path, block: () -> T): T
    {
        val normalizedTargetPath = targetPath.toAbsolutePath().normalize()
        val processLock = processLocks.computeIfAbsent(normalizedTargetPath) { ReentrantLock() }
        return processLock.withLock {
            withFileLock(normalizedTargetPath, shared = false, block)
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
     * Replace a file only when the file system guarantees an atomic move.
     */
    internal fun moveTempFileAtomically(tempPath: Path, targetPath: Path)
    {
        Files.move(
            tempPath,
            targetPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }

    /**
     * Write a uniquely-created temporary sibling and flush its bytes.
     */
    internal fun writeAdministrativeTempFile(targetPath: Path, content: String): Path
    {
        val tempPath = targetPath.resolveSibling("${targetPath.fileName}.${UUID.randomUUID()}.tmp")
        try
        {
            FileChannel.open(
                tempPath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            ).use { fileChannel ->
                val contentBuffer = ByteBuffer.wrap(content.toByteArray())
                while(contentBuffer.hasRemaining()) fileChannel.write(contentBuffer)
                fileChannel.force(true)
            }
            return tempPath
        }
        catch(exception: Exception)
        {
            Files.deleteIfExists(tempPath)
            throw exception
        }
    }

    /** Force the parent directory metadata to durable storage after a namespace mutation. */
    internal fun forceDirectorySync(directoryPath: Path?): Boolean
    {
        if(directoryPath == null || !Files.exists(directoryPath))
        {
            return false
        }
        return runCatching {
            FileChannel.open(directoryPath, StandardOpenOption.READ).use { fileChannel ->
                fileChannel.force(true)
            }
            true
        }.getOrDefault(false)
    }
}
