package codexPipe.auth

import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/** Private, atomic JSON file store for the single active TPipe Codex profile. */
class FileCodexCredentialStore(
    val path: Path = CodexPaths.tpipeAuthFile(),
) : CodexCredentialStore
{
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun load(): CodexOAuthCredentials?
    {
        if(!Files.exists(path)) return null
        val text = try { Files.readString(path) } catch(e: IOException) {
            throw IOException("Unable to read TPipe Codex credential store", e)
        }
        return try { json.decodeFromString<CodexOAuthCredentials>(text) } catch(e: Exception) {
            throw IOException("TPipe Codex credential store is corrupt", e)
        }
    }

    override fun save(credentials: CodexOAuthCredentials)
    {
        val parent = path.parent ?: throw IOException("Codex credential path must have a parent directory")
        Files.createDirectories(parent)
        setPrivateDirectoryPermissions(parent)

        val temporary = parent.resolve(".${path.fileName}.${UUID.randomUUID()}.tmp")
        try
        {
            val bytes = json.encodeToString(credentials).toByteArray(Charsets.UTF_8)
            FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while(buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            setPrivateFilePermissions(temporary)
            try
            {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            catch(_: AtomicMoveNotSupportedException)
            {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            setPrivateFilePermissions(path)
        }
        catch(e: IOException)
        {
            throw IOException("Unable to save TPipe Codex credentials", e)
        }
        finally
        {
            Files.deleteIfExists(temporary)
        }
    }

    override fun delete(): Boolean = Files.deleteIfExists(path)

    private fun setPrivateDirectoryPermissions(directory: Path)
    {
        runCatching { Files.setPosixFilePermissions(directory, PRIVATE_DIRECTORY_PERMISSIONS) }
    }

    private fun setPrivateFilePermissions(file: Path)
    {
        runCatching { Files.setPosixFilePermissions(file, PRIVATE_FILE_PERMISSIONS) }
    }

    private companion object
    {
        val PRIVATE_DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val PRIVATE_FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}
