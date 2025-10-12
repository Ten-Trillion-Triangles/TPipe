package com.TTT.PipeContextProtocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Result of process execution.
 */
@Serializable
data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val executionTimeMs: Long,
    val sessionId: String? = null,
    val bufferId: String? = null
)

/**
 * Result of interactive session creation.
 */
@Serializable
data class InteractiveResult(
    val sessionId: String,
    val bufferId: String,
    val initialOutput: String,
    val isSessionActive: Boolean
)

/**
 * Executes shell commands with multiple interaction modes and buffer management.
 * Supports one-shot commands, persistent sessions, and direct stdio communication.
 */
class StdioExecutor : PcpExecutor
{
    private val sessionManager = StdioSessionManager()
    private val bufferManager = StdioBufferManager()
    private val securityManager = CommandSecurityManager()
    
    /**
     * Execute PCP request based on stdio context options.
     */
    override suspend fun execute(request: PcPRequest): PcpRequestResult
    {
        val startTime = System.currentTimeMillis()
        val options = request.stdioContextOptions
        
        return try
        {
            // Validate permissions first
            val validation = validatePermissions(options)
            if (!validation.isValid)
            {
                return PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.Stdio,
                    error = "Permission validation failed: ${validation.errors.joinToString(", ")}"
                )
            }
            
            // Route to appropriate execution mode
            val result = when (options.executionMode)
            {
                StdioExecutionMode.ONE_SHOT -> executeOneShot(options)
                StdioExecutionMode.INTERACTIVE -> executeInteractive(options)
                StdioExecutionMode.CONNECT -> connectToSession(options)
                StdioExecutionMode.BUFFER_REPLAY -> replayBuffer(options)
            }
            
            PcpRequestResult(
                success = true,
                output = result,
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Stdio,
                error = null
            )
        }
        catch (e: Exception)
        {
            PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Stdio,
                error = "Stdio execution failed: ${e.message}"
            )
        }
    }
    
    /**
     * Execute one-shot command and return result.
     */
    private suspend fun executeOneShot(options: StdioContextOptions): String = withContext(Dispatchers.IO)
    {
        val processBuilder = ProcessBuilder(listOf(options.command) + options.args)
        
        // Set working directory if specified
        options.workingDirectory?.let { 
            processBuilder.directory(java.io.File(it))
        }
        
        // Set environment variables
        if (options.environmentVariables.isNotEmpty())
        {
            processBuilder.environment().putAll(options.environmentVariables)
        }
        
        val process = processBuilder.start()
        
        // Read output with timeout
        val result = withTimeoutOrNull(options.timeoutMs)
        {
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            
            process.waitFor()
            
            ProcessResult(
                exitCode = process.exitValue(),
                stdout = stdout,
                stderr = stderr,
                executionTimeMs = 0 // Will be calculated by caller
            )
        }
        
        if (result == null)
        {
            process.destroyForcibly()
            throw RuntimeException("Command execution timed out after ${options.timeoutMs}ms")
        }
        
        if (result.exitCode != 0)
        {
            "Command failed with exit code ${result.exitCode}:\n${result.stderr}"
        }
        else
        {
            result.stdout
        }
    }
    
    /**
     * Create interactive session.
     */
    private suspend fun executeInteractive(options: StdioContextOptions): String
    {
        val session = sessionManager.createSession(
            command = options.command,
            args = options.args,
            workingDir = options.workingDirectory
        )
        
        val buffer = if (options.bufferPersistence)
        {
            bufferManager.createBuffer(session.sessionId)
        }
        else null
        
        // Log session creation
        buffer?.let {
            bufferManager.appendToBuffer(
                it.bufferId,
                "Session created: ${options.command} ${options.args.joinToString(" ")}",
                BufferDirection.OUTPUT,
                mapOf("event" to "session_created")
            )
        }
        
        val result = InteractiveResult(
            sessionId = session.sessionId,
            bufferId = buffer?.bufferId ?: "",
            initialOutput = "Interactive session created: ${session.sessionId}",
            isSessionActive = session.isActive
        )
        
        return "Session created: ${result.sessionId}\nBuffer: ${result.bufferId}\n${result.initialOutput}"
    }
    
    /**
     * Connect to existing session and send input.
     */
    private suspend fun connectToSession(options: StdioContextOptions): String
    {
        val sessionId = options.sessionId
            ?: throw IllegalArgumentException("Session ID required for CONNECT mode")
        
        val input = options.args.joinToString(" ")
        if (input.isEmpty())
        {
            throw IllegalArgumentException("Input required for session communication")
        }
        
        // Sanitize input
        val sanitizedInput = securityManager.sanitizeSessionInput(input)
        
        val response = sessionManager.sendInput(sessionId, sanitizedInput)
        
        // Log to buffer if available
        val session = sessionManager.getSession(sessionId)
        session?.let {
            bufferManager.appendToBuffer(it.bufferId, sanitizedInput, BufferDirection.INPUT)
            bufferManager.appendToBuffer(it.bufferId, response.output, BufferDirection.OUTPUT)
            if (response.error.isNotEmpty())
            {
                bufferManager.appendToBuffer(it.bufferId, response.error, BufferDirection.ERROR)
            }
        }
        
        return if (response.error.isNotEmpty())
        {
            "Error: ${response.error}\nOutput: ${response.output}"
        }
        else
        {
            response.output
        }
    }
    
    /**
     * Replay buffer contents.
     */
    private fun replayBuffer(options: StdioContextOptions): String
    {
        val bufferId = options.bufferId
            ?: throw IllegalArgumentException("Buffer ID required for BUFFER_REPLAY mode")
        
        val buffer = bufferManager.getBuffer(bufferId)
            ?: throw IllegalArgumentException("Buffer not found: $bufferId")
        
        val replay = StringBuilder()
        replay.appendLine("=== Buffer Replay: $bufferId ===")
        replay.appendLine("Session: ${buffer.sessionId}")
        replay.appendLine("Created: ${java.time.Instant.ofEpochMilli(buffer.createdAt)}")
        replay.appendLine()
        
        buffer.entries.forEach { entry ->
            val timestamp = java.time.Instant.ofEpochMilli(entry.timestamp)
            val direction = when (entry.direction)
            {
                BufferDirection.INPUT -> ">> "
                BufferDirection.OUTPUT -> "<< "
                BufferDirection.ERROR -> "!! "
            }
            
            replay.appendLine("[$timestamp] $direction${entry.content}")
        }
        
        return replay.toString()
    }
    
    /**
     * Validate permissions for stdio operation.
     */
    private fun validatePermissions(options: StdioContextOptions): PcpValidationResult
    {
        val errors = mutableListOf<String>()
        
        // Determine max security level based on permissions
        // Developer can explicitly allow higher levels by granting appropriate permissions
        val maxSecurityLevel = when
        {
            options.permissions.contains(Permissions.Delete) -> SecurityLevel.FORBIDDEN  // Full access - even rm, format, etc.
            options.permissions.contains(Permissions.Execute) -> SecurityLevel.DANGEROUS // chmod, sudo, kill, etc.
            options.permissions.contains(Permissions.Write) -> SecurityLevel.RESTRICTED  // ps, netstat, find, etc.
            else -> SecurityLevel.SAFE  // Only echo, cat, ls, etc.
        }
        
        // Validate command with security level - now supports FORBIDDEN if developer allows it
        if (!securityManager.validateCommand(options.command, emptyList(), maxSecurityLevel))
        {
            val classification = securityManager.getCommandClassification(options.command)
            val levelName = classification?.level?.name ?: "UNKNOWN"
            val maxLevelName = maxSecurityLevel.name
            errors.add("Command '${options.command}' (level: $levelName) exceeds maximum allowed level: $maxLevelName")
        }
        
        // Check for command injection
        val allInput = listOf(options.command) + options.args
        allInput.forEach { input ->
            if (securityManager.detectCommandInjection(input))
            {
                errors.add("Potential command injection detected in: $input")
            }
        }
        
        // Validate working directory permissions
        options.workingDirectory?.let { workingDir ->
            if (!securityManager.checkPathPermissions(workingDir, options.permissions))
            {
                errors.add("Access denied to working directory: $workingDir")
            }
        }
        
        return PcpValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }
}
