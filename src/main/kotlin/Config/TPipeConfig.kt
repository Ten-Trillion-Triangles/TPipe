package com.TTT.Config

import com.TTT.Config.AuthRegistry
import com.TTT.Util.getHomeFolder
import java.io.File

object TPipeConfig
{
    var configDir = "${getHomeFolder()}/.tpipe" //Defines where TPipe looks for persisting lorebooks, and other settings.
    var instanceID = "TPipe-Default" //Defines unique name to avoid multiple instances of TPipe crashing.

    /**
     * Get the defined config directory for TPipe. Which combines assigned config dir path + the instance id which guards
     * against multiple instances of a TPipe application running at once from colliding with each other. This acts
     * as a safe area to support various native persistence features of the TPipe library.
     */
    fun getTPipeConfigDir() : String
    {
        return "${configDir}/$instanceID"
    }

    /**
     * Return the directory where TPipe stores all persisting memory data. This includes lorebook stubs, and todo
     * lists. However, more persisting storage systems will likely be supported in the future.
     */
    fun getMemoryDir() : String
    {
        return "${getTPipeConfigDir()}/memory"
    }

    /**
     * Return the directory where TPipe stores persisting lorebook
     */
    fun getLorebookDir() : String
    {
        return "${getMemoryDir()}/lorebook"
    }

    /**
     * Get the directory where todo lists are stored.
     */
    fun getTodoListDir() : String
    {
        return "${getMemoryDir()}/todo"
    }

    fun getDebugDir() : String
    {
        return "${configDir}/debug"
    }

    fun getTraceDir() : String
    {
        return "${getDebugDir()}/trace"
    }

    fun getTodoDir() : String
    {
        return "${getMemoryDir()}/todo"
    }

    /**
     * Settings for remote memory hosting and access.
     */
    var remoteMemoryEnabled = false
    var remoteMemoryUrl = "http://localhost:8080"
    var remoteMemoryAuthToken = ""
    var useRemoteMemoryGlobally = false
    var enforceMemoryVersioning = false

    /**
     * Helper to register a remote service authentication token.
     * Delegates to [AuthRegistry.registerToken].
     *
     * @param address Service URL, program path, or agent name.
     * @param token Authentication token or secret.
     */
    fun addRemoteAuth(address: String, token: String)
    {
        AuthRegistry.registerToken(address, token)
    }
}
