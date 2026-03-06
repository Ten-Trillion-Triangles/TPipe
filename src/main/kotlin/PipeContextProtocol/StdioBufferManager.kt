package com.TTT.PipeContextProtocol

import com.TTT.Util.serialize
import com.TTT.Util.deserialize
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Direction of buffer data flow.
 */
enum class BufferDirection
{
    INPUT,    // Data sent to process
    OUTPUT,   // Data received from process stdout
    ERROR     // Data received from process stderr
}

/**
 * Single entry in communication buffer.
 */
@Serializable
data class BufferEntry(
    val timestamp: Long,
    val direction: BufferDirection,
    val content: String,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Buffer for storing session communication history.
 */
@Serializable
data class StdioBuffer(
    val bufferId: String,
    val sessionId: String,
    val entries: MutableList<BufferEntry> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Result of buffer search operations.
 */
@Serializable
data class BufferMatch(
    val entryIndex: Int,
    val entry: BufferEntry,
    val matchText: String
)

/**
 * Manages stdio buffers for capturing and replaying communication.
 * Supports buffer persistence, search, and interaction history.
 */
class StdioBufferManager
{
    private val buffers = ConcurrentHashMap<String, StdioBuffer>()
    private val bufferLimits = ConcurrentHashMap<String, Int>()
    
    /**
     * Create new buffer for session.
     */
    fun createBuffer(sessionId: String): StdioBuffer
    {
        val bufferId = "buffer_${sessionId}_${System.currentTimeMillis()}"
        val buffer = StdioBuffer(
            bufferId = bufferId,
            sessionId = sessionId
        )
        
        buffers[bufferId] = buffer
        return buffer
    }

    /**
     * Ensure buffer exists for session, optionally applying a size limit.
     */
    fun ensureBuffer(bufferId: String, sessionId: String, maxSizeBytes: Int? = null): StdioBuffer
    {
        val buffer = buffers[bufferId] ?: StdioBuffer(
            bufferId = bufferId,
            sessionId = sessionId
        ).also { buffers[bufferId] = it }

        maxSizeBytes?.takeIf { it > 0 }?.let { limit ->
            bufferLimits[bufferId] = limit
            enforceBufferLimit(bufferId, buffer)
        }

        return buffer
    }
    
    /**
     * Append data to buffer with optional access control.
     */
    fun appendToBuffer(
        bufferId: String,
        data: String,
        direction: BufferDirection,
        metadata: Map<String, String> = emptyMap(),
        context: PcpContext? = null,
        requiredPermissions: List<Permissions> = emptyList()
    )
    {
        val buffer = buffers[bufferId] ?: return

        if(context?.enableBufferAccessControl == true)
        {
            val permissions = if(requiredPermissions.isEmpty()) listOf(Permissions.Write) else requiredPermissions
            ensureBufferAccess(bufferId, context, permissions)
        }

        val entry = BufferEntry(
            timestamp = System.currentTimeMillis(),
            direction = direction,
            content = data,
            metadata = metadata
        )

        synchronized(buffer.entries)
        {
            buffer.entries.add(entry)

            // Limit buffer size to prevent memory issues
            if(buffer.entries.size > 10000)
            {
                buffer.entries.removeAt(0)
            }

            enforceBufferLimit(bufferId, buffer)
        }
    }
    
    /**
     * Legacy method for backward compatibility.
     */
    fun appendToBuffer(
        bufferId: String, 
        data: String, 
        direction: BufferDirection,
        metadata: Map<String, String> = emptyMap()
    )
    {
        appendToBuffer(bufferId, data, direction, metadata, null)
    }
    
    /**
     * Get buffer by ID.
     */
    /**
     * Get buffer with optional access control validation.
     */
    fun getBuffer(bufferId: String, context: PcpContext? = null, requiredPermissions: List<Permissions> = emptyList()): StdioBuffer?
    {
        val buffer = buffers[bufferId] ?: return null

        if(context?.enableBufferAccessControl == true)
        {
            val permissions = if(requiredPermissions.isEmpty()) listOf(Permissions.Read) else requiredPermissions
            ensureBufferAccess(bufferId, context, permissions)
        }

        return buffer
    }

    /**
     * Legacy method for backward compatibility.
     */
    fun getBuffer(bufferId: String): StdioBuffer?
    {
        return getBuffer(bufferId, null)
    }
    
    /**
     * Search buffer for pattern with optional access control.
     */
    fun searchBuffer(bufferId: String, pattern: String, context: PcpContext? = null): List<BufferMatch>
    {
        val buffer = buffers[bufferId] ?: return emptyList()

        if(context?.enableBufferAccessControl == true)
        {
            ensureBufferAccess(bufferId, context, listOf(Permissions.Read))
        }

        val matches = mutableListOf<BufferMatch>()
        
        synchronized(buffer.entries)
        {
            buffer.entries.forEachIndexed { index, entry ->
                if(entry.content.contains(pattern, ignoreCase = true))
                {
                    matches.add(
                        BufferMatch(
                            entryIndex = index,
                            entry = entry,
                            matchText = pattern
                        )
                    )
                }
            }
        }
        
        return matches
    }

    /**
     * Legacy method for backward compatibility.
     */
    fun searchBuffer(bufferId: String, pattern: String): List<BufferMatch>
    {
        return searchBuffer(bufferId, pattern, null)
    }
    
    /**
     * Save buffer to file.
     */
    fun saveBuffer(bufferId: String, filePath: String): Boolean
    {
        val buffer = buffers[bufferId] ?: return false
        
        return try
        {
            val json = serialize(buffer)
            File(filePath).writeText(json)
            true
        }
        catch(e: Exception)
        {
            false
        }
    }
    
    /**
     * Load buffer from file.
     */
    fun loadBuffer(filePath: String): StdioBuffer?
    {
        return try
        {
            val json = File(filePath).readText()
            val buffer = deserialize<StdioBuffer>(json)
            
            buffer?.let {
                buffers[it.bufferId] = it
            }
            
            buffer
        }
        catch(e: Exception)
        {
            null
        }
    }
    
    /**
     * Clear buffer contents.
     */
    fun clearBuffer(bufferId: String): Boolean
    {
        val buffer = buffers[bufferId] ?: return false
        
        synchronized(buffer.entries)
        {
            buffer.entries.clear()
        }
        
        return true
    }
    
    /**
     * Remove buffer completely.
     */
    fun removeBuffer(bufferId: String): Boolean
    {
        bufferLimits.remove(bufferId)
        return buffers.remove(bufferId) != null
    }
    
    /**
     * Get buffer statistics.
     */
    fun getBufferStats(bufferId: String): Map<String, Any>?
    {
        val buffer = buffers[bufferId] ?: return null
        
        synchronized(buffer.entries)
        {
            val inputCount = buffer.entries.count { it.direction == BufferDirection.INPUT }
            val outputCount = buffer.entries.count { it.direction == BufferDirection.OUTPUT }
            val errorCount = buffer.entries.count { it.direction == BufferDirection.ERROR }
            val totalSize = buffer.entries.sumOf { it.content.length }
            
            return mapOf(
                "totalEntries" to buffer.entries.size,
                "inputEntries" to inputCount,
                "outputEntries" to outputCount,
                "errorEntries" to errorCount,
                "totalSizeBytes" to totalSize,
                "createdAt" to buffer.createdAt,
                "sessionId" to buffer.sessionId
            )
        }
    }

    private fun enforceBufferLimit(bufferId: String, buffer: StdioBuffer)
    {
        val limit = bufferLimits[bufferId] ?: return

        if(limit <= 0) return

        var currentSize = buffer.entries.sumOf { it.content.length }

        while(currentSize > limit && buffer.entries.isNotEmpty())
        {
            val removed = buffer.entries.removeAt(0)
            currentSize -= removed.content.length
        }
    }

    private fun ensureBufferAccess(bufferId: String, context: PcpContext, requiredPermissions: List<Permissions>)
    {
        val buffer = buffers[bufferId] ?: throw SecurityException("Buffer access denied: unknown buffer $bufferId")

        val session = StdioSessionManager.getSession(buffer.sessionId)
            ?: throw SecurityException("Buffer access denied: session not found for $bufferId")

        if(session.ownerId != context.currentUserId)
        {
            throw SecurityException("Buffer access denied for user ${context.currentUserId}")
        }

        if(requiredPermissions.isNotEmpty())
        {
            if(context.stdioOptions.isEmpty())
            {
                throw SecurityException("Buffer access denied: no stdio options configured for access control")
            }

            val allowedPermissions = context.stdioOptions
                .find { it.command == session.command }
                ?.permissions
                ?.takeIf { it.isNotEmpty() }
                ?: throw SecurityException("Buffer access denied: command '${session.command}' missing from context")

            val hasPermission = requiredPermissions.all { allowedPermissions.contains(it) }

            if(!hasPermission)
            {
                val missing = requiredPermissions.joinToString(", ")
                throw SecurityException("Buffer access denied: missing permissions [$missing]")
            }
        }
    }

}
