package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StdioBufferManagerSecurityTest
{
    private suspend fun createSession(command: String, ownerId: String): StdioSession
    {
        return StdioSessionManager.createSession(
            command = command,
            args = listOf("1"),
            ownerId = ownerId,
            workingDir = null
        )
    }

    @Test
    fun `deny buffer access when no stdio context configured`() = runBlocking<Unit> {
        val manager = StdioBufferManager()
        val owner = "test-user"
        val session = createSession("sleep", owner)
        val buffer = manager.createBuffer(session.sessionId)

        val context = PcpContext().apply {
            enableBufferAccessControl = true
            currentUserId = owner
        }

        try
        {
            assertFailsWith<SecurityException> {
                manager.appendToBuffer(buffer.bufferId, "data", BufferDirection.OUTPUT, context = context)
            }
            Unit
        }
        finally
        {
            session.process?.destroyForcibly()
        }
    }

    @Test
    fun `deny buffer access when command not whitelisted`() = runBlocking<Unit> {
        val manager = StdioBufferManager()
        val owner = "acl-user"
        val session = createSession("sleep", owner)
        val buffer = manager.createBuffer(session.sessionId)

        val context = PcpContext().apply {
            enableBufferAccessControl = true
            currentUserId = owner
            addStdioOption(StdioContextOptions().apply {
                command = "cat"
                permissions = mutableListOf(Permissions.Write)
            })
        }

        try
        {
            assertFailsWith<SecurityException> {
                manager.appendToBuffer(buffer.bufferId, "data", BufferDirection.OUTPUT, context = context)
            }
            Unit
        }
        finally
        {
            session.process?.destroyForcibly()
        }
    }

    @Test
    fun `deny buffer access when missing required permission`() = runBlocking<Unit> {
        val manager = StdioBufferManager()
        val owner = "acl-user"
        val session = createSession("sleep", owner)
        val buffer = manager.createBuffer(session.sessionId)

        val context = PcpContext().apply {
            enableBufferAccessControl = true
            currentUserId = owner
            addStdioOption(StdioContextOptions().apply {
                command = "sleep"
                permissions = mutableListOf(Permissions.Read)
            })
        }

        try
        {
            assertFailsWith<SecurityException> {
                manager.appendToBuffer(buffer.bufferId, "data", BufferDirection.OUTPUT, context = context)
            }
            Unit
        }
        finally
        {
            session.process?.destroyForcibly()
        }
    }

    @Test
    fun `allow buffer access when permissions satisfied`() = runBlocking<Unit> {
        val manager = StdioBufferManager()
        val owner = "acl-user"
        val session = createSession("sleep", owner)
        val buffer = manager.createBuffer(session.sessionId)

        val context = PcpContext().apply {
            enableBufferAccessControl = true
            currentUserId = owner
            addStdioOption(StdioContextOptions().apply {
                command = "sleep"
                permissions = mutableListOf(Permissions.Write, Permissions.Read)
            })
        }

        try
        {
            manager.appendToBuffer(buffer.bufferId, "data", BufferDirection.OUTPUT, context = context)
            val stored = manager.getBuffer(buffer.bufferId, context, listOf(Permissions.Read))
            assertEquals(1, stored?.entries?.size)
        }
        finally
        {
            session.process?.destroyForcibly()
        }
    }

    @Test
    fun `respect max buffer size`()
    {
        val manager = StdioBufferManager()
        val buffer = manager.ensureBuffer("buffer_test_limit", "session-limit", maxSizeBytes = 10)

        manager.appendToBuffer(buffer.bufferId, "1234567890", BufferDirection.OUTPUT)
        manager.appendToBuffer(buffer.bufferId, "abcd", BufferDirection.OUTPUT)

        val stored = manager.getBuffer(buffer.bufferId)
        val totalSize = stored?.entries?.sumOf { it.content.length } ?: 0

        assertTrue(totalSize <= 10, "Buffer should enforce configured size limit")
    }
}
