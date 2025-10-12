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
     * Append data to buffer.
     */
    fun appendToBuffer(
        bufferId: String, 
        data: String, 
        direction: BufferDirection,
        metadata: Map<String, String> = emptyMap()
    )
    {
        val buffer = buffers[bufferId] ?: return
        
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
            if (buffer.entries.size > 10000)
            {
                buffer.entries.removeAt(0)
            }
        }
    }
    
    /**
     * Get buffer by ID.
     */
    fun getBuffer(bufferId: String): StdioBuffer?
    {
        return buffers[bufferId]
    }
    
    /**
     * Search buffer for pattern.
     */
    fun searchBuffer(bufferId: String, pattern: String): List<BufferMatch>
    {
        val buffer = buffers[bufferId] ?: return emptyList()
        val matches = mutableListOf<BufferMatch>()
        
        synchronized(buffer.entries)
        {
            buffer.entries.forEachIndexed { index, entry ->
                if (entry.content.contains(pattern, ignoreCase = true))
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
        catch (e: Exception)
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
        catch (e: Exception)
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
}
