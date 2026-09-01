package Defaults.providers

import Defaults.CodexConfiguration
import Defaults.ManifoldDefaults
import codexPipe.CodexPipes
import codexPipe.auth.CodexAuthManager
import codexPipe.auth.CodexCredentialStore
import codexPipe.auth.FileCodexCredentialStore
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.Pipeline
import genericOpenAIPipe.GenericOpenAIPipe
import java.nio.file.Paths

/** Internal factory for Codex OAuth-backed GenericOpenAI pipes. */
internal object CodexDefaults
{
    /** Creates a Manifold whose manager pipes share one OAuth manager. */
    fun createManifold(config: CodexConfiguration): Manifold
    {
        val managerPipeline = ManifoldDefaults.buildDefaultManagerPipeline(config)
        return Manifold().apply {
            setManagerPipeline(managerPipeline)
            ManifoldDefaults.applyManifoldMemoryConfiguration(this, config.manifoldMemory)
        }
    }

    /** Creates a manager pipeline with one shared auth manager across all pipes. */
    fun createManagerPipeline(config: CodexConfiguration): Pipeline
    {
        val authManager = createAuthManager(config)
        return Pipeline().apply {
            repeat(config.pipeCount) { add(createCodexPipe(config, authManager)) }
        }
    }

    /** Creates one worker pipe with its own manager when no pipeline is shared. */
    fun createWorkerPipe(config: CodexConfiguration): GenericOpenAIPipe =
        createCodexPipe(config, createAuthManager(config))

    /** Builds a manager from the configured TPipe-owned and optional CLI paths. */
    internal fun createAuthManager(config: CodexConfiguration): CodexAuthManager
    {
        val store: CodexCredentialStore = config.credentialStorePath
            ?.takeIf { it.isNotBlank() }
            ?.let { FileCodexCredentialStore(Paths.get(it)) }
            ?: FileCodexCredentialStore()
        val cliPath = config.cliAuthFile
            ?.takeIf { it.isNotBlank() }
            ?.let { Paths.get(it) }
            ?: codexPipe.auth.CodexPaths.codexCliAuthFile()
        return CodexAuthManager(
            credentialStore = store,
            importCodexCliCredentialsIfMissing = config.importCodexCliCredentialsIfMissing,
            cliAuthFile = cliPath,
        )
    }

    /** Creates a configured Codex pipe using the supplied shared auth manager. */
    internal fun createCodexPipe(
        config: CodexConfiguration,
        authManager: CodexAuthManager,
    ): GenericOpenAIPipe = CodexPipes.create(config.model, authManager)
}
