package com.TTT.Context

import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.serialize
import com.TTT.Util.serializeConverseHistory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConverseHistorySerializationTest
{
    @Test
    fun directConverseHistorySerializationOmitsDefaultFields()
    {
        val history = ConverseHistory(
            mutableListOf(
                ConverseData(
                    role = ConverseRole.user,
                    content = MultimodalContent("hello")
                )
            )
        )

        val json = serializeConverseHistory(history)

        assertFalse(json.contains("\"uuid\""), "ConverseData defaults should not be encoded in compact history JSON.")
        assertTrue(json.contains("\"role\""), "The meaningful conversation content should still serialize.")
    }

    @Test
    fun emptyConverseHistorySerializesWithoutDefaultHistoryPayload()
    {
        val json = serializeConverseHistory(ConverseHistory())

        assertFalse(json.contains("\"history\""), "An empty history should not emit its default list payload.")
    }

    @Test
    fun nestedContextWindowSerializationDoesNotLeakConverseUuidDefaults()
    {
        val window = ContextWindow().apply {
            converseHistory = ConverseHistory(
                mutableListOf(
                    ConverseData(
                        role = ConverseRole.user,
                        content = MultimodalContent("nested payload")
                    )
                )
            )
        }

        val json = serialize(window)

        assertFalse(json.contains("\"uuid\""), "Nested converse history should remain compact inside ContextWindow JSON.")
        assertTrue(json.contains("\"converseHistory\""), "The context window should still include the nested history container.")
    }
}
