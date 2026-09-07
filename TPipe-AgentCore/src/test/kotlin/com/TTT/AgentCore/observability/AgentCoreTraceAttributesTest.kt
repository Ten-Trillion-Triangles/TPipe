package com.TTT.AgentCore.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentCoreTraceAttributesTest
{
    @Test
    fun defaultMetadataFilterRemovesExecutionAndPrivateData()
    {
        val metadata = mapOf(
            "safe.dimension" to "worker",
            "shellIo" to "stdout",
            "commandText" to "cat credentials.txt",
            "authorization" to "Bearer secret",
            "content" to "user content",
            "reasoningContent" to "private reasoning",
            "paymentProof" to "payment proof"
        )

        assertEquals(
            mapOf("safe.dimension" to "worker"),
            AgentCoreTraceAttributes.filterMetadata(metadata)
        )
    }

    @Test
    fun sensitiveNameMatchingCoversCommonWireSpellings()
    {
        listOf(
            "shell_io",
            "shellOutput",
            "command_text",
            "credentials",
            "prompt",
            "model_reasoning",
            "payment_proof"
        ).forEach { name ->
            assertTrue(AgentCoreTraceAttributes.isSensitiveKey(name), "Expected $name to be sensitive")
        }
        assertFalse(AgentCoreTraceAttributes.isSensitiveKey(AgentCoreTraceAttributes.runtimeId))
    }

    @Test
    fun exposesStableProviderAliasAndAcceptsNullableMetadata()
    {
        assertEquals("tpipe.provider", AgentCoreTraceAttributes.PROVIDER)
        assertTrue(AgentCoreTraceAttributes.isSensitive("oauth_token"))
        assertEquals(
            mapOf("safe" to "value"),
            AgentCoreTraceAttributes.filterMetadata(mapOf("safe" to "value", "secret" to null))
        )
    }
}
