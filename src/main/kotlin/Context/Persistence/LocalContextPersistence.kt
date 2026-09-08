package com.TTT.Context.Persistence

import com.TTT.Config.TPipeConfig
import com.TTT.Context.MemoryPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.security.MessageDigest
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException

private const val EDITOR_VALIDATION_LIMIT_BYTES = 16L * 1024L * 1024L

/** Metadata for a native saved `.bank` document. */
data class SavedPageMetadata(
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val revision: String,
    val status: String = "ready",
    val diagnostic: String? = null
)

/** One deterministic page of saved-memory metadata without loading other pages. */
data class SavedPageInventory(
    val pages: List<SavedPageMetadata>,
    val total: Int,
    val nextPage: Int?
)

/** Indicates that saved-page inventory could not be read from the configured root. */
class SavedPageInventoryException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Raw saved-page contents and its checked filesystem revision. */
data class SavedPageDocument(
    val relativePath: String,
    val rawJson: String,
    val revision: String
)

/** Typed result for a direct persisted-page read. */
sealed interface SavedPageReadResult
{
    data class Success(val document: SavedPageDocument) : SavedPageReadResult
    data object Missing : SavedPageReadResult
    data object Unreadable : SavedPageReadResult
    data class Invalid(val message: String, val document: SavedPageDocument? = null) : SavedPageReadResult
    data class Failure(val message: String) : SavedPageReadResult
}

/** Result of a checked saved-page mutation. */
sealed interface SavedPageMutationResult
{
    data class Success(val document: SavedPageDocument? = null) : SavedPageMutationResult
    data object Missing : SavedPageMutationResult
    data object Unreadable : SavedPageMutationResult
    data object RevisionConflict : SavedPageMutationResult
    data class Invalid(val message: String) : SavedPageMutationResult
    data class Failure(val message: String) : SavedPageMutationResult
}

/**
 * Provides local persisted-document administration without touching ContextBank
 * caches, retrieval bindings, or remote persistence.
 *
 * The root is fixed at construction and every path is validated again while its
 * sidecar lock is held. Callers should expose opaque identifiers rather than raw
 * relative paths to untrusted clients.
 */
class LocalContextPersistence(root: Path = Path.of(TPipeConfig.getLorebookDir()))
{
    private val rootPath = root.toAbsolutePath().normalize().also { candidate ->
        Files.createDirectories(candidate)
        require(!Files.isSymbolicLink(candidate)) { "Saved-memory root must not be a symbolic link" }
        require(Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) { "Saved-memory root must be a directory" }
    }.toRealPath(LinkOption.NOFOLLOW_LINKS)
    private val json = Json { isLenient = false; ignoreUnknownKeys = true }

    /** Returns the fixed namespace root used for every inventory and mutation. */
    val namespaceRoot: Path
        get() = rootPath

    /** Lists regular native bank files below the pinned root in deterministic order. */
    suspend fun listSavedPages(): List<SavedPageMetadata> = withContext(Dispatchers.IO)
    {
        listSavedPagesPage(pageSize = Int.MAX_VALUE).pages
    }

    /** Lists only one requested metadata page; document contents are loaded for that page on demand. */
    suspend fun listSavedPagesPage(query: String = "", page: Int = 0, pageSize: Int = 50): SavedPageInventory = withContext(Dispatchers.IO)
    {
        if(!Files.exists(rootPath)) return@withContext SavedPageInventory(emptyList(), 0, null)
        if(!Files.isDirectory(rootPath)) throw IllegalStateException("Saved-memory root is not a directory")
        try
        {
            val normalizedQuery = query.trim().lowercase()
            val candidates = Files.walk(rootPath).use { stream ->
                stream
                    .filter { path -> !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.fileName.toString().endsWith(".bank") }
                    .map { path -> path to relativePathFor(path) }
                    .filter { (_, relativePath) -> normalizedQuery.isEmpty() || relativePath.lowercase().contains(normalizedQuery) || relativePath.substringAfterLast('/').removeSuffix(".bank").lowercase().contains(normalizedQuery) }
                    .sorted(Comparator.comparing<Pair<Path, String>, String> { it.second })
                    .toList()
            }
            val safePageSize = if(pageSize == Int.MAX_VALUE) Int.MAX_VALUE else pageSize.coerceIn(1, 100)
            val safePage = page.coerceAtLeast(0)
            val from = (safePage.toLong() * safePageSize.toLong()).coerceAtMost(candidates.size.toLong()).toInt()
            val to = (from.toLong() + safePageSize.toLong()).coerceAtMost(candidates.size.toLong()).toInt()
            val selected = candidates.subList(from, to).map { (path, relativePath) -> metadataFor(path, relativePath) }
            SavedPageInventory(selected, candidates.size, if(to < candidates.size && safePage < Int.MAX_VALUE) safePage + 1 else null)
        }
        catch(exception: Exception)
        {
            throw SavedPageInventoryException("Saved-memory inventory is unavailable", exception)
        }
    }

    /** Returns one page's metadata without scanning or validating other saved documents. */
    suspend fun metadataForSavedPage(relativePath: String): SavedPageMetadata? = withContext(Dispatchers.IO)
    {
        val target = resolveSafe(relativePath) ?: return@withContext null
        if(!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return@withContext null
        metadataFor(target, relativePath)
    }

    /** Reads one saved page as its original JSON text, without loading it into ContextBank. */
    suspend fun readSavedPage(relativePath: String): SavedPageReadResult = withContext(Dispatchers.IO)
    {
        val target = resolveSafe(relativePath) ?: return@withContext SavedPageReadResult.Invalid("Saved-page path is outside the configured root")
        if(!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return@withContext SavedPageReadResult.Missing
        if(!Files.isReadable(target)) return@withContext SavedPageReadResult.Unreadable
        MemoryPersistence.withLock(target, shared = true) {
            val secureResult = runCatching {
                withSecureParent(relativePath) { parent, fileName ->
                    val attributes = secureAttributes(parent, fileName)
                        ?: return@withSecureParent SavedPageReadResult.Missing
                    if(!attributes.isRegularFile) return@withSecureParent SavedPageReadResult.Missing
                    runCatching {
                        val rawJson = java.io.InputStreamReader(
                            Channels.newInputStream(
                                parent.newByteChannel(Path.of(fileName), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)),
                            ),
                            Charsets.UTF_8,
                        ).use { it.readText() }
                        val document = SavedPageDocument(relativePath, rawJson, secureRevision(parent, fileName))
                        validateDocument(document)
                    }.getOrElse { exception ->
                        when(exception)
                        {
                            is java.nio.file.AccessDeniedException -> SavedPageReadResult.Unreadable
                            is NoSuchFileException -> SavedPageReadResult.Missing
                            else -> SavedPageReadResult.Failure(exception.message ?: "Saved page could not be read")
                        }
                    }
                }
            }.getOrNull()
            secureResult ?: SavedPageReadResult.Failure("Secure saved-page reading is unavailable on this filesystem")
        }
    }

    /** Returns the current streaming revision for a page without materializing its contents. */
    suspend fun revisionForSavedPage(relativePath: String): String? = withContext(Dispatchers.IO)
    {
        val target = resolveSafe(relativePath) ?: return@withContext null
        if(!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return@withContext null
        MemoryPersistence.withLock(target, shared = true) {
            runCatching {
                withSecureParent(relativePath) { parent, fileName ->
                    val attributes = secureAttributes(parent, fileName) ?: return@withSecureParent null
                    if(!attributes.isRegularFile) null else secureRevision(parent, fileName)
                }
            }.getOrNull()
        }
    }

    /** Replaces a page only when its expected revision still matches and JSON is valid. */
    suspend fun replaceSavedPage(relativePath: String, expectedRevision: String, rawJson: String): SavedPageMutationResult = withContext(Dispatchers.IO)
    {
        val target = resolveSafe(relativePath) ?: return@withContext SavedPageMutationResult.Invalid("Saved-page path is outside the configured root")
        val validation = runCatching { json.parseToJsonElement(rawJson) }.exceptionOrNull()
        if(validation != null) return@withContext SavedPageMutationResult.Invalid(validation.message ?: "Saved page is not valid JSON")
        if(!rawJson.trimStart().startsWith("{")) return@withContext SavedPageMutationResult.Invalid("Saved page must be a JSON object")
        if(!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return@withContext SavedPageMutationResult.Missing
        if(!Files.isReadable(target)) return@withContext SavedPageMutationResult.Unreadable

        MemoryPersistence.withExclusiveTransaction(target) {
            val lockedTarget = resolveSafe(relativePath) ?: return@withExclusiveTransaction SavedPageMutationResult.Invalid("Saved-page path is outside the configured root")
            if(!Files.isRegularFile(lockedTarget, LinkOption.NOFOLLOW_LINKS)) return@withExclusiveTransaction SavedPageMutationResult.Missing
            if(!Files.isReadable(lockedTarget)) return@withExclusiveTransaction SavedPageMutationResult.Unreadable
            if(revisionFor(lockedTarget) != expectedRevision) return@withExclusiveTransaction SavedPageMutationResult.RevisionConflict
            val secureResult = runCatching {
                withSecureParent(relativePath) { parent, fileName ->
                    val currentRevision = secureRevision(parent, fileName)
                    if(currentRevision != expectedRevision) return@withSecureParent SavedPageMutationResult.RevisionConflict
                    val temporaryName = "${fileName}.${java.util.UUID.randomUUID()}.tmp"
                    try
                    {
                        writeSecureTemporary(parent, temporaryName, rawJson)
                        // SecureDirectoryStream.move keeps both names relative to the held parent handle;
                        // same-directory rename is the atomic replacement primitive on supported providers.
                        parent.move(Path.of(temporaryName), parent, Path.of(fileName))
                        MemoryPersistence.forceDirectorySync(lockedTarget.parent)
                        SavedPageMutationResult.Success(SavedPageDocument(relativePath, rawJson, secureRevision(parent, fileName)))
                    }
                    catch(exception: Exception)
                    {
                        SavedPageMutationResult.Failure(exception.message ?: "Atomic saved-page replacement failed")
                    }
                    finally
                    {
                        runCatching { parent.deleteFile(Path.of(temporaryName)) }
                    }
                }
            }.getOrNull() ?: return@withExclusiveTransaction SavedPageMutationResult.Failure(
                "Secure saved-page replacement is unavailable on this filesystem"
            )
            secureResult
        }
    }

    /** Deletes a page only when its expected revision still matches. */
    suspend fun deleteSavedPage(relativePath: String, expectedRevision: String): SavedPageMutationResult = withContext(Dispatchers.IO)
    {
        val target = resolveSafe(relativePath) ?: return@withContext SavedPageMutationResult.Invalid("Saved-page path is outside the configured root")
        MemoryPersistence.withExclusiveTransaction(target) {
            val lockedTarget = resolveSafe(relativePath) ?: return@withExclusiveTransaction SavedPageMutationResult.Invalid("Saved-page path is outside the configured root")
            if(!Files.isRegularFile(lockedTarget, LinkOption.NOFOLLOW_LINKS)) return@withExclusiveTransaction SavedPageMutationResult.Missing
            if(!Files.isReadable(lockedTarget)) return@withExclusiveTransaction SavedPageMutationResult.Unreadable
            if(revisionFor(lockedTarget) != expectedRevision) return@withExclusiveTransaction SavedPageMutationResult.RevisionConflict
            val secureResult = runCatching {
                withSecureParent(relativePath) { parent, fileName ->
                    if(secureRevision(parent, fileName) != expectedRevision) return@withSecureParent SavedPageMutationResult.RevisionConflict
                    parent.deleteFile(Path.of(fileName))
                    MemoryPersistence.forceDirectorySync(lockedTarget.parent)
                    SavedPageMutationResult.Success()
                }
            }.getOrNull() ?: return@withExclusiveTransaction SavedPageMutationResult.Failure(
                "Secure saved-page deletion is unavailable on this filesystem"
            )
            secureResult
        }
    }

    private fun resolveSafe(relativePath: String): Path?
    {
        if(relativePath.isBlank() || relativePath.contains('\\') || relativePath.contains(':') || relativePath.startsWith("//") || Path.of(relativePath).isAbsolute) return null
        if(relativePath.split('/').any { component -> component == "." || component == ".." }) return null
        val candidate = rootPath.resolve(relativePath).normalize()
        if(!candidate.startsWith(rootPath) || !candidate.fileName.toString().endsWith(".bank")) return null
        if(Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(candidate)) return null
        var current = candidate.parent
        while(current != null && current != rootPath)
        {
            if(Files.isSymbolicLink(current)) return null
            current = current.parent
        }
        if(!parentIsContained(candidate)) return null
        return candidate
    }

    /** Rechecks the physical parent after lexical and symbolic-link validation. */
    private fun parentIsContained(candidate: Path): Boolean
    {
        val parent = candidate.parent ?: return false
        if(!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) return true
        val rootReal = runCatching { rootPath.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: return false
        val parentReal = runCatching { parent.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: return false
        return parentReal.startsWith(rootReal)
    }

    /** Walks to a page parent through directory handles that do not follow symbolic links. */
    private fun <T> withSecureParent(relativePath: String, block: (SecureDirectoryStream<Path>, String) -> T): T?
    {
        val components = relativePath.split('/')
        Files.newDirectoryStream(rootPath).use { rootStream ->
            val secureRoot = rootStream as? SecureDirectoryStream<Path> ?: return null
            fun descend(directory: SecureDirectoryStream<Path>, index: Int): T?
            {
                if(index == components.lastIndex) return block(directory, components[index])
                val childStream = directory.newDirectoryStream(Path.of(components[index]), LinkOption.NOFOLLOW_LINKS)
                try
                {
                    val secureChild = childStream as? SecureDirectoryStream<Path> ?: return null
                    return descend(secureChild, index + 1)
                }
                finally
                {
                    childStream.close()
                }
            }
            return descend(secureRoot, 0)
        }
    }

    private fun writeSecureTemporary(parent: SecureDirectoryStream<Path>, fileName: String, content: String)
    {
        parent.newByteChannel(
            Path.of(fileName),
            setOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        ).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
            while(buffer.hasRemaining()) channel.write(buffer)
            (channel as? FileChannel)?.force(true)
        }
    }

    private fun secureRevision(parent: SecureDirectoryStream<Path>, fileName: String): String
    {
        val attributes = parent.getFileAttributeView(
            Path.of(fileName),
            java.nio.file.attribute.BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS
        )?.readAttributes() ?: error("Saved page attributes are unavailable")
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("fileKey=${attributes.fileKey()}\ncreated=${attributes.creationTime().toMillis()}\nmodified=${attributes.lastModifiedTime().toMillis()}\nsize=${attributes.size()}\n".toByteArray())
        parent.newByteChannel(Path.of(fileName), setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val buffer = java.nio.ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
            while(true)
            {
                buffer.clear()
                val read = channel.read(buffer)
                if(read < 0) break
                buffer.flip()
                val bytes = ByteArray(read)
                buffer.get(bytes)
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun secureAttributes(parent: SecureDirectoryStream<Path>, fileName: String): BasicFileAttributes?
    {
        return parent.getFileAttributeView(
            Path.of(fileName),
            java.nio.file.attribute.BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )?.readAttributes()
    }

    private fun relativePathFor(path: Path): String = rootPath.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')

    private fun metadataFor(path: Path, relativePath: String): SavedPageMetadata
    {
        return runCatching {
            MemoryPersistence.withLock(path, shared = true) { metadataForLocked(path, relativePath) }
        }.getOrElse { exception ->
            SavedPageMetadata(relativePath, 0L, 0L, "", "unreadable", exception.message ?: "Saved page metadata is unavailable")
        }
    }

    private fun metadataForLocked(path: Path, relativePath: String): SavedPageMetadata
    {
        val attributes = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrElse {
            return SavedPageMetadata(relativePath, 0L, 0L, "", "unreadable", it.message ?: "Saved page metadata is unavailable")
        }
        if(!attributes.isRegularFile || Files.isSymbolicLink(path)) return SavedPageMetadata(relativePath, attributes.size(), attributes.lastModifiedTime().toMillis(), "", "unreadable", "Saved page is not a regular file")
        val size = attributes.size()
        val modifiedAt = attributes.lastModifiedTime().toMillis()
        if(!Files.isReadable(path)) return SavedPageMetadata(relativePath, size, modifiedAt, "", "unreadable", "Saved page is not readable")
        if(size > EDITOR_VALIDATION_LIMIT_BYTES) return SavedPageMetadata(relativePath, size, modifiedAt, "", "too-large", "Saved page exceeds the editor size limit")
        val validation = runCatching {
            val root = json.parseToJsonElement(Files.readString(path))
            require(root is JsonObject) { "Saved page must be a JSON object" }
        }
        if(validation.isFailure)
        {
            val diagnostic = validation.exceptionOrNull()?.message ?: "Saved page is not valid JSON"
            return runCatching { SavedPageMetadata(relativePath, size, modifiedAt, revisionFor(path), "invalid", diagnostic) }
                .getOrElse { SavedPageMetadata(relativePath, size, modifiedAt, "", "unreadable", it.message ?: "Saved page revision is unavailable") }
        }
        return runCatching { SavedPageMetadata(relativePath, size, modifiedAt, revisionFor(path)) }
            .getOrElse { SavedPageMetadata(relativePath, size, modifiedAt, "", "unreadable", it.message ?: "Saved page revision is unavailable") }
    }

    private fun validateDocument(document: SavedPageDocument): SavedPageReadResult
    {
        return runCatching {
            val root = json.parseToJsonElement(document.rawJson)
            require(root is JsonObject) { "Saved page must be a JSON object" }
        }.fold(
            onSuccess = { SavedPageReadResult.Success(document) },
            onFailure = { SavedPageReadResult.Invalid(it.message ?: "Saved page is not valid JSON", document) }
        )
    }

    private fun revisionFor(path: Path): String
    {
        val digest = MessageDigest.getInstance("SHA-256")
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        digest.update("fileKey=${attributes.fileKey()}\ncreated=${attributes.creationTime().toMillis()}\nmodified=${attributes.lastModifiedTime().toMillis()}\nsize=${attributes.size()}\n".toByteArray())
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while(true)
            {
                val read = input.read(buffer)
                if(read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
