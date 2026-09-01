package codexPipe.auth

import codexPipe.CodexConstants
import java.nio.file.Path
import java.nio.file.Paths

/** Resolves TPipe-owned and read-only Codex CLI credential paths. */
object CodexPaths
{
    /** Returns the TPipe credential file, honoring the explicit environment override. */
    fun tpipeAuthFile(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path
    {
        val override = environment[CodexConstants.AUTH_FILE_ENV]?.trim().orEmpty()
        return if(override.isNotEmpty()) Paths.get(override)
        else Paths.get(userHome, ".tpipe", "codex", "auth.json")
    }

    /** Returns the file-backed Codex CLI store; keyring storage is intentionally not inspected. */
    fun codexCliAuthFile(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path
    {
        val codexHome = environment["CODEX_HOME"]?.trim().orEmpty()
        return if(codexHome.isNotEmpty()) Paths.get(codexHome, "auth.json")
        else Paths.get(userHome, ".codex", "auth.json")
    }
}
