package com.TTT.PipeContextProtocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Represents a persistent stdio session.
 */
@Serializable
data class StdioSession(
    val sessionId: String,
    @kotlinx.serialization.Transient val process: Process? = null,
    val command: String,
    val args: List<String>,
    val workingDirectory: String?,
    val createdAt: Long,
    val bufferId: String,
    val ownerId: String,
    var isActive: Boolean = true
)

/**
 * Response from session communication.
 */
@Serializable
data class SessionResponse(
    val output: String,
    val error: String,
    val isActive: Boolean
)

/**
 * Result of session operations.
 */
@Serializable
data class SessionResult(
    val sessionId: String,
    val response: String,
    val isSessionActive: Boolean,
    val bufferId: String
)

/**
 * Manages persistent stdio sessions for long-form communication.
 * Handles session lifecycle, buffer management, and cleanup.
 */
object StdioSessionManager
{
    private val activeSessions = ConcurrentHashMap<String, StdioSession>()
    private val sessionReaders = ConcurrentHashMap<String, BufferedReader>()
    private val sessionWriters = ConcurrentHashMap<String, BufferedWriter>()
    
    /**
     * Create a new persistent session.
     */
    suspend fun createSession(
        command: String, 
        args: List<String>, 
        ownerId: String,
        workingDir: String? = null
    ): StdioSession = withContext(Dispatchers.IO)
    {
        val sessionId = generateSessionId()
        val bufferId = "buffer_$sessionId"
        
        // Always check resource limits before creating session (hard enforced)
        val securityManager = CommandSecurityManager()
        val resourceCheck = securityManager.checkResourceLimits(sessionId)
        if(!resourceCheck.isValid)
        {
            throw SecurityException("Resource limit exceeded: ${resourceCheck.warnings.joinToString(", ")}")
        }
        
        try
        {
            val processBuilder = ProcessBuilder(listOf(command) + args)
            workingDir?.let { processBuilder.directory(java.io.File(it)) }
            
            val process = processBuilder.start()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
            
            sessionReaders[sessionId] = reader
            sessionWriters[sessionId] = writer
            
            val session = StdioSession(
                sessionId = sessionId,
                process = process,
                command = command,
                args = args,
                workingDirectory = workingDir,
                createdAt = System.currentTimeMillis(),
                bufferId = bufferId,
                ownerId = ownerId,
                isActive = true
            )
            
            activeSessions[sessionId] = session
            session
        }
        catch(e: Exception)
        {
            throw RuntimeException("Failed to create session: ${e.message}", e)
        }
    }
    
    /**
     * Get existing session by ID.
     */
    fun getSession(sessionId: String): StdioSession?
    {
        return activeSessions[sessionId]
    }
    
    /**
     * Get all active sessions.
     */
    fun getActiveSessions(): Collection<StdioSession>
    {
        return activeSessions.values.filter { it.isActive }
    }
    
    /**
     * Send input to session and get response.
     */
    suspend fun sendInput(sessionId: String, input: String): SessionResponse = withContext(Dispatchers.IO)
    {
        val session = activeSessions[sessionId]
            ?: return@withContext SessionResponse("", "Session not found", false)
        
        val writer = sessionWriters[sessionId]
            ?: return@withContext SessionResponse("", "Session writer not available", false)
        
        val reader = sessionReaders[sessionId]
            ?: return@withContext SessionResponse("", "Session reader not available", false)
        
        try
        {
            // Send input
            writer.write(input)
            writer.newLine()
            writer.flush()
            
            // Read response with timeout and a short settle window so slow stdio hosts can finish writing
            // their protocol payload after any startup or debug noise.
            val output = StringBuilder()
            val timeoutMs = 30_000L
            val initialWaitMs = 5_000L
            val settleWindowMs = 250L
            val settlePollMs = 50L
            val startTime = System.currentTimeMillis()
            var sawOutput = false
            var settledAt: Long? = null

            while(System.currentTimeMillis() - startTime < timeoutMs)
            {
                if(reader.ready())
                {
                    val line = reader.readLine()
                    if(line != null)
                    {
                        output.appendLine(line)
                        sawOutput = true
                        settledAt = null
                    }
                    else
                    {
                        break
                    }
                }
                else if(sawOutput)
                {
                    val settledAtValue = settledAt
                    if(settledAtValue == null)
                    {
                        settledAt = System.currentTimeMillis()
                    }
                    else if(System.currentTimeMillis() - settledAtValue >= settleWindowMs)
                    {
                        break
                    }
                }
                else if(System.currentTimeMillis() - startTime >= initialWaitMs)
                {
                    break
                }

                Thread.sleep(settlePollMs)
            }
            
            SessionResponse(
                output = output.toString(),
                error = "",
                isActive = session.process?.isAlive ?: false
            )
        }
        catch(e: Exception)
        {
            SessionResponse("", "Communication error: ${e.message}", false)
        }
    }
    
    /**
     * Read output from session with timeout.
     */
    suspend fun readOutput(sessionId: String, timeoutMs: Long = 5000): String = withContext(Dispatchers.IO)
    {
        val reader = sessionReaders[sessionId] ?: return@withContext ""
        
        try
        {
            val output = StringBuilder()
            val startTime = System.currentTimeMillis()
            
            while(System.currentTimeMillis() - startTime < timeoutMs)
            {
                if(reader.ready())
                {
                    val line = reader.readLine()
                    if(line != null)
                    {
                        output.appendLine(line)
                    }
                }
                else
                {
                    Thread.sleep(50)
                }
            }
            
            output.toString()
        }
        catch(e: Exception)
        {
            "Error reading output: ${e.message}"
        }
    }
    
    /**
     * Close session and cleanup resources.
     */
    fun closeSession(sessionId: String): Boolean
    {
        val session = activeSessions[sessionId] ?: return false
        
        try
        {
            // Close streams
            sessionReaders[sessionId]?.close()
            sessionWriters[sessionId]?.close()
            
            // Terminate process
            session.process?.let { process ->
                if(process.isAlive)
                {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
            
            // Remove from maps
            activeSessions.remove(sessionId)
            sessionReaders.remove(sessionId)
            sessionWriters.remove(sessionId)
            
            return true
        }
        catch(e: Exception)
        {
            return false
        }
    }
    
    /**
     * List all active session IDs.
     */
    fun listActiveSessions(): List<String>
    {
        return activeSessions.keys.toList()
    }
    
    /**
     * Generate unique session ID.
     */
    private fun generateSessionId(): String
    {
        return "session_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}
