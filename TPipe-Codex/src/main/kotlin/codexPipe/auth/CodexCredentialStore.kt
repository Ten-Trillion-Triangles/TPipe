package codexPipe.auth

import java.io.IOException

/** Synchronous persistence boundary for TPipe-owned Codex OAuth credentials. */
interface CodexCredentialStore
{
    /** Loads credentials, or null when no profile is saved. */
    @Throws(IOException::class)
    fun load(): CodexOAuthCredentials?

    /** Atomically replaces the saved profile where the filesystem supports it. */
    @Throws(IOException::class)
    fun save(credentials: CodexOAuthCredentials)

    /** Deletes the saved profile and returns whether a file was removed. */
    @Throws(IOException::class)
    fun delete(): Boolean
}
