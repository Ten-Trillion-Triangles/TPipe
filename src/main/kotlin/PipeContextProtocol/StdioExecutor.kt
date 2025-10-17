package com.TTT.PipeContextProtocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.io.File

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
    private val sessionManager = StdioSessionManager
    private val bufferManager = StdioBufferManager()
    private val securityManager = CommandSecurityManager()
    
    /**
     * Execute PCP request with context validation and security enforcement.
     * 
     * @param request The PCP request to execute
     * @param context The security context defining allowed operations
     * @return PcpRequestResult with execution results or validation errors
     */
    override suspend fun execute(request: PcPRequest, context: PcpContext): PcpRequestResult
    {
        val startTime = System.currentTimeMillis()
        
        // Check if security is active - if context has any restrictions
        if (context.stdioOptions.isNotEmpty() || context.allowedDirectoryPaths.isNotEmpty() || 
            context.forbiddenDirectoryPaths.isNotEmpty() || context.allowedFiles.isNotEmpty() || 
            context.forbiddenFiles.isNotEmpty())
        {
            // Find matching command in context whitelist
            val matchingOption = context.stdioOptions.find { 
                it.command == request.stdioContextOptions.command 
            }
            
            if (matchingOption == null)
            {
                return PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.Stdio,
                    error = "Command '${request.stdioContextOptions.command}' not in security whitelist"
                )
            }
            
            // Validate filesystem restrictions
            val pathValidation = validateFilesystemAccess(request.stdioContextOptions, context)
            if (!pathValidation.isValid)
            {
                return PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.Stdio,
                    error = pathValidation.error
                )
            }
        }
        
        // Optional session access validation (if enabled)
        if (context.enableSessionAccessControl)
        {
            val sessionId = request.stdioContextOptions.sessionId ?: ""
            
            if (sessionId.isNotEmpty() && !securityManager.validateSessionAccess(sessionId, context.currentUserId))
            {
                return PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.Stdio,
                    error = "Session access denied for user: ${context.currentUserId}"
                )
            }
        }
        
        // Execute with merged context options (security enforced)
        val contextOption = if (context.stdioOptions.isNotEmpty()) {
            context.stdioOptions.find { it.command == request.stdioContextOptions.command } ?: StdioContextOptions()
        } else {
            StdioContextOptions()
        }
        val mergedOptions = mergeContextOptions(request.stdioContextOptions, contextOption)
        val secureRequest = request.copy(stdioContextOptions = mergedOptions)

        return when (secureRequest.stdioContextOptions.executionMode)
        {
            StdioExecutionMode.ONE_SHOT ->
            {
                if (secureRequest.stdioContextOptions.keepSessionAlive)
                {
                    executePersistentOneShot(secureRequest, context)
                }
                else
                {
                    executeSecure(secureRequest)
                }
            }

            StdioExecutionMode.INTERACTIVE ->
            {
                try
                {
                    val output = executeInteractive(secureRequest.stdioContextOptions, context)
                    PcpRequestResult(
                        success = true,
                        output = output,
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
                        error = e.message
                    )
                }
            }

            StdioExecutionMode.CONNECT ->
            {
                try
                {
                    val output = connectToSession(secureRequest.stdioContextOptions, context)
                    PcpRequestResult(
                        success = true,
                        output = output,
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
                        error = e.message
                    )
                }
            }

            StdioExecutionMode.BUFFER_REPLAY ->
            {
                try
                {
                    val output = replayBuffer(secureRequest.stdioContextOptions, context)
                    PcpRequestResult(
                        success = true,
                        output = output,
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
                        error = e.message
                    )
                }
            }
        }
    }
    
    /**
     * Validate filesystem access against context restrictions.
     * Checks working directory and command arguments for path/file violations.
     */
    private fun validateFilesystemAccess(options: StdioContextOptions, context: PcpContext): ValidationResult
    {
        val pathsToCheck = mutableListOf<String>()
        
        // Add working directory
        options.workingDirectory?.let { pathsToCheck.add(it) }
        
        // Add command arguments that look like paths
        options.args.forEach { arg ->
            if (isLikelyPath(arg)) pathsToCheck.add(arg)
        }
        
        // Check each path against restrictions
        for (path in pathsToCheck)
        {
            val resolvedPath = resolvePath(path)
            
            // 1. Check forbidden lists first (deny takes precedence)
            if (isForbiddenPath(resolvedPath, context) || isForbiddenFile(resolvedPath, context))
            {
                return ValidationResult(false, "Access denied to forbidden path: $path")
            }
            
            // 2. Check allowed lists (if they exist)
            if (!isAllowedPath(resolvedPath, context) || !isAllowedFile(resolvedPath, context))
            {
                return ValidationResult(false, "Access denied - path not in allowed list: $path")
            }
        }
        
        return ValidationResult(true, null)
    }
    
    /**
     * Merge context stdio options with request options.
     * Context options take precedence for security settings.
     */
    private fun mergeContextOptions(requestOptions: StdioContextOptions, contextOptions: StdioContextOptions): StdioContextOptions
    {
        return StdioContextOptions().apply {
            // Use context command if specified, otherwise request command
            command = if (contextOptions.command.isNotEmpty()) 
                contextOptions.command else requestOptions.command
            
            // Context arguments override request arguments
            args.addAll(contextOptions.args)
            if (args.isEmpty())
            {
                args.addAll(requestOptions.args)
            }
            
            // Context working directory overrides request
            workingDirectory = if (contextOptions.workingDirectory?.isNotEmpty() == true) 
                contextOptions.workingDirectory else requestOptions.workingDirectory
                
            // Context timeout overrides request
            timeoutMs = if (contextOptions.timeoutMs > 0) 
                contextOptions.timeoutMs else requestOptions.timeoutMs
                
            // Context permissions override request permissions
            permissions.addAll(contextOptions.permissions)
            if (permissions.isEmpty())
            {
                permissions.addAll(requestOptions.permissions)
            }
            
            // Merge environment variables (context takes precedence)
            environmentVariables.putAll(requestOptions.environmentVariables)
            environmentVariables.putAll(contextOptions.environmentVariables)
            
            // Context execution mode overrides request
            executionMode = if (contextOptions.executionMode != StdioExecutionMode.ONE_SHOT)
            {
                contextOptions.executionMode
            }
            else
            {
                requestOptions.executionMode
            }
            
            // Context session/buffer IDs override request
            sessionId = contextOptions.sessionId ?: requestOptions.sessionId
            bufferId = contextOptions.bufferId ?: requestOptions.bufferId
            
            // Context description overrides request
            description = if (contextOptions.description.isNotEmpty())
                contextOptions.description else requestOptions.description

            keepSessionAlive = contextOptions.keepSessionAlive || requestOptions.keepSessionAlive
            bufferPersistence = contextOptions.bufferPersistence || requestOptions.bufferPersistence

            maxBufferSize = when {
                contextOptions.maxBufferSize > 0 -> contextOptions.maxBufferSize
                requestOptions.maxBufferSize > 0 -> requestOptions.maxBufferSize
                else -> maxBufferSize
            }
        }
    }
    
    /**
     * Execute command with merged security options.
     */
    private suspend fun executeSecure(request: PcPRequest): PcpRequestResult
    {
        val startTime = System.currentTimeMillis()
        val options = request.stdioContextOptions
        
        // Validate permissions before execution
        val validation = validatePermissions(options)
        if (!validation.isValid)
        {
            return PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Stdio,
                error = "Validation failed: ${validation.errors.joinToString("; ")}"
            )
        }
        
        return try
        {
            // Build command with arguments
            val command = buildList<String> {
                add(options.command)
                addAll(options.args)
            }
            
            // Execute command
            val processBuilder = ProcessBuilder(command)
            
            // Set working directory if specified
            if (options.workingDirectory?.isNotEmpty() == true)
            {
                processBuilder.directory(File(options.workingDirectory!!))
            }
            
            // Set environment variables
            val environment = processBuilder.environment()
            options.environmentVariables.forEach { (key, value) ->
                environment[key] = value
            }
            
            val process = processBuilder.start()
            
            // Handle timeout
            val completed = if (options.timeoutMs > 0)
            {
                process.waitFor(options.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            }
            else
            {
                process.waitFor()
                true
            }
            
            if (!completed)
            {
                process.destroyForcibly()
                return PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.Stdio,
                    error = "Command timed out after ${options.timeoutMs}ms"
                )
            }
            
            // Capture output
            val output = process.inputStream.bufferedReader().readText()
            val errorOutput = process.errorStream.bufferedReader().readText()
            
            val finalOutput = if (errorOutput.isNotEmpty())
            {
                "$output\nSTDERR: $errorOutput"
            }
            else
            {
                output
            }
            
            PcpRequestResult(
                success = process.exitValue() == 0,
                output = finalOutput,
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Stdio,
                error = if (process.exitValue() != 0) "Command failed with exit code: ${process.exitValue()}" else null
            )
        }
        catch (e: Exception)
        {
            PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Stdio,
                error = "Execution failed: ${e.message}"
            )
        }
    }
    
    /**
     * Check if argument looks like a file/directory path.
     */
    private fun isLikelyPath(arg: String): Boolean
    {
        return arg.contains("/") || arg.contains("\\") || arg.startsWith("./") || arg.startsWith("../")
    }
    
    /**
     * Resolve path to canonical form, handling symlinks and relative paths.
     */
    private fun resolvePath(path: String): String
    {
        return try
        {
            java.io.File(path).canonicalPath
        }
        catch (e: Exception)
        {
            path // Fallback to original if resolution fails
        }
    }
    
    /**
     * Check if path is in forbidden directory list.
     */
    private fun isForbiddenPath(path: String, context: PcpContext): Boolean
    {
        return context.forbiddenDirectoryPaths.any { forbiddenPath ->
            val resolvedForbidden = resolvePath(forbiddenPath)
            path.startsWith(resolvedForbidden)
        }
    }
    
    /**
     * Check if file is in forbidden file list.
     */
    private fun isForbiddenFile(path: String, context: PcpContext): Boolean
    {
        return context.forbiddenFiles.any { forbiddenFile ->
            val resolvedForbidden = resolvePath(forbiddenFile)
            path == resolvedForbidden
        }
    }
    
    /**
     * Check if path is in allowed directory list (if list exists).
     */
    private fun isAllowedPath(path: String, context: PcpContext): Boolean
    {
        if (context.allowedDirectoryPaths.isEmpty()) return true
        
        return context.allowedDirectoryPaths.any { allowedPath ->
            val resolvedAllowed = resolvePath(allowedPath)
            path.startsWith(resolvedAllowed)
        }
    }
    
    /**
     * Check if file is in allowed file list (if list exists).
     */
    private fun isAllowedFile(path: String, context: PcpContext): Boolean
    {
        if (context.allowedFiles.isEmpty()) return true
        
        return context.allowedFiles.any { allowedFile ->
            val resolvedAllowed = resolvePath(allowedFile)
            path == resolvedAllowed
        }
    }
    
    /**
     * Simple validation result for filesystem checks.
     */
    private data class ValidationResult(val isValid: Boolean, val error: String?)
    
    /**
     * Create interactive session.
     */
    private suspend fun executeInteractive(options: StdioContextOptions, context: PcpContext): String
    {
        val session = sessionManager.createSession(
            command = options.command,
            args = options.args,
            ownerId = context.currentUserId,
            workingDir = options.workingDirectory
        )

        val result = InteractiveResult(
            sessionId = session.sessionId,
            bufferId = if (options.bufferPersistence) session.bufferId else "",
            initialOutput = "Interactive session created: ${session.sessionId}",
            isSessionActive = session.isActive
        )

        setupPersistentBuffer(session, options, context)

        return buildString {
            append("Session created: ${result.sessionId}")
            if (options.bufferPersistence)
            {
                append("\nBuffer: ${session.bufferId}")
            }
            append("\n${result.initialOutput}")
        }
    }
    
    /**
     * Connect to existing session and send input.
     */
    private suspend fun connectToSession(options: StdioContextOptions, context: PcpContext): String
    {
        val sessionId = options.sessionId
            ?: throw IllegalArgumentException("Session ID required for CONNECT mode")
        
        val input = options.args.joinToString(" ")
        if (input.isEmpty())
        {
            throw IllegalArgumentException("Input required for session communication")
        }
        
        val session = sessionManager.getSession(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        if (context.enableSessionAccessControl && session.ownerId != context.currentUserId)
        {
            throw SecurityException("Session access denied for user ${context.currentUserId}")
        }

        setupPersistentBuffer(session, options, context, logCreation = false)

        // Sanitize input
        val sanitizedInput = securityManager.sanitizeSessionInput(input)

        val response = sessionManager.sendInput(sessionId, sanitizedInput)

        bufferManager.appendToBuffer(session.bufferId, sanitizedInput, BufferDirection.INPUT, context = context)
        bufferManager.appendToBuffer(session.bufferId, response.output, BufferDirection.OUTPUT, context = context)
        if (response.error.isNotEmpty())
        {
            bufferManager.appendToBuffer(session.bufferId, response.error, BufferDirection.ERROR, context = context)
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
    private fun replayBuffer(options: StdioContextOptions, context: PcpContext): String
    {
        val bufferId = options.bufferId
            ?: throw IllegalArgumentException("Buffer ID required for BUFFER_REPLAY mode")
        
        val buffer = bufferManager.getBuffer(bufferId, context, listOf(Permissions.Read))
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

    private suspend fun executePersistentOneShot(request: PcPRequest, context: PcpContext): PcpRequestResult
    {
        val startTime = System.currentTimeMillis()
        val options = request.stdioContextOptions

        val validation = validatePermissions(options)
        if (!validation.isValid)
        {
            return PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Stdio,
                error = "Validation failed: ${validation.errors.joinToString("; ")}"
            )
        }

        var session: StdioSession? = null

        return try
        {
            session = sessionManager.createSession(
                command = options.command,
                args = options.args,
                ownerId = context.currentUserId,
                workingDir = options.workingDirectory
            )

            val persistentSession = session!!

            setupPersistentBuffer(persistentSession, options, context)

            val initialReadTimeout = when
            {
                options.timeoutMs <= 0 -> 0L
                options.timeoutMs < 1000L -> options.timeoutMs
                else -> 1000L
            }

            val initialOutput = if (initialReadTimeout > 0)
            {
                sessionManager.readOutput(persistentSession.sessionId, initialReadTimeout)
            }
            else ""

            val output = buildString {
                append("Session created: ${persistentSession.sessionId}")
                if (options.bufferPersistence)
                {
                    append("\nBuffer: ${persistentSession.bufferId}")
                }
                if (initialOutput.isNotBlank())
                {
                    append("\n")
                    append(initialOutput.trimEnd())
                }
            }.ifBlank { "Session created: ${persistentSession.sessionId}" }

            PcpRequestResult(
                success = true,
                output = output,
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Stdio,
                error = null
            )
        }
        catch (e: Exception)
        {
            session?.let { sessionManager.closeSession(it.sessionId) }

            PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Stdio,
                error = "Failed to create persistent session: ${e.message}"
            )
        }
    }

    private fun setupPersistentBuffer(
        session: StdioSession,
        options: StdioContextOptions,
        context: PcpContext,
        logCreation: Boolean = true
    ): StdioBuffer?
    {
        if (!options.bufferPersistence)
        {
            return null
        }

        val existing = bufferManager.getBuffer(session.bufferId)
        val buffer = bufferManager.ensureBuffer(session.bufferId, session.sessionId, options.maxBufferSize)

        if (logCreation && existing == null)
        {
            bufferManager.appendToBuffer(
                buffer.bufferId,
                "Session created: ${options.command} ${options.args.joinToString(" ")}",
                BufferDirection.OUTPUT,
                mapOf("event" to "session_created"),
                context
            )
        }

        return buffer
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
            errors.add("Command '${options.command}' is not allowed (level: $levelName exceeds maximum $maxLevelName)")
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
