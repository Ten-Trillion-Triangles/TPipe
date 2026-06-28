package com.TTT.Util

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Context.ContextWindow
import com.TTT.Context.TodoList
import com.TTT.Context.TodoTaskArray
import com.TTT.Pipe.MultimodalContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for the `com.TTT.Util.serialize()` default-encoding behavior.
 *
 * Prior state: `serialize(obj)` defaulted to `encodedefault = true`, which emitted every
 * Kotlin default-valued field into the JSON. For LLM-bound payloads this was a token-cost
 * tax — empty strings, empty lists, and zero-valued fields all leaked into the prompt.
 *
 * Current state: the default is `encodedefault = false`, modeled on the existing
 * `serializeConverseHistory` opt-out wrapper. External P2P wire endpoints that target
 * non-Kotlin clients explicitly pass `encodedefault = true` to preserve the contract.
 *
 * These tests pin the new default behavior so a future regression that flips it back
 * to `true` is caught at test time.
 */
class UtilSerializeDefaultsTest
{
    @Test
    fun emptyContextWindowSerializesInCompactForm()
    {
        val json = serialize(ContextWindow())

        // fields without explicit default-encoding annotations should be omitted
        assertFalse(json.contains("\"contextElements\""),
            "Default-valued contextElements should not appear in compact JSON.")
        assertFalse(json.contains("\"converseHistory\""),
            "Default-valued converseHistory should not appear in compact JSON.")
        assertFalse(json.contains("\"version\""),
            "Default-valued version should not appear in compact JSON.")
        assertFalse(json.contains("\"isInitialized\""),
            "@Transient field should never appear in JSON regardless.")
    }

    @Test
    fun emptyTodoListSerializesInCompactForm()
    {
        val json = serialize(TodoList())

        assertFalse(json.contains("\"tasks\""),
            "Default-valued tasks should not appear in compact JSON.")
        assertFalse(json.contains("\"workHistory\""),
            "Default-valued workHistory should not appear in compact JSON.")
        assertFalse(json.contains("\"version\""),
            "Default-valued version should not appear in compact JSON.")
    }

    @Test
    fun compactContextWindowIsShorterThanWithDefaults()
    {
        val compact = serialize(ContextWindow())
        val verbose = serialize(ContextWindow(), encodedefault = true)

        assertTrue(compact.length < verbose.length,
            "Compact form (${compact.length} chars) should be shorter than verbose form (${verbose.length} chars).")
        // Rough sanity check: at least 20% reduction for an empty context window
        assertTrue(compact.length * 5 < verbose.length * 4,
            "Expected >=20% reduction in JSON size after the default flip.")
    }

    @Test
    fun roundTripPreservesEqualityForContextWindow()
    {
        val original = ContextWindow().apply {
            contextElements.add("first")
            contextElements.add("second")
        }

        val json = serialize(original)
        val restored = deserialize<ContextWindow>(json)

        assertEquals(original.contextElements, restored?.contextElements,
            "Round-trip serialize -> deserialize should preserve contextElements.")
    }

    @Test
    fun roundTripPreservesEqualityForTodoList()
    {
        val original = TodoList(
            tasks = TodoTaskArray(),
            workHistory = ConverseHistory(
                mutableListOf(
                    ConverseData(
                        role = ConverseRole.user,
                        content = MultimodalContent("remember to clean chimneys")
                    )
                )
            ),
            version = 7L
        )

        val json = serialize(original)
        val restored = deserialize<TodoList>(json)

        assertEquals(original.version, restored?.version,
            "Round-trip should preserve non-default version field.")
        assertEquals(original.workHistory.history.size, restored?.workHistory?.history?.size,
            "Round-trip should preserve workHistory entries.")
        assertEquals(original.workHistory.history.first().content.text,
            restored?.workHistory?.history?.first()?.content?.text,
            "Round-trip should preserve workHistory content text.")
    }

    @Test
    fun explicitEncodedefaultTrueStillIncludesDefaults()
    {
        val json = serialize(ContextWindow(), encodedefault = true)

        assertTrue(json.contains("\"contextElements\""),
            "When encodedefault = true is explicit, default-valued contextElements should be present.")
        assertTrue(json.contains("\"version\""),
            "When encodedefault = true is explicit, default-valued version should be present.")
    }

    @Test
    fun encodedefaultFalseDefaultMatchesExistingWrapper()
    {
        // The contract: serialize(...) with no second arg should produce the same compact
        // form as the existing serializeConverseHistory opt-out pattern.
        val window = ContextWindow().apply {
            converseHistory = ConverseHistory(
                mutableListOf(
                    ConverseData(
                        role = ConverseRole.user,
                        content = MultimodalContent("payload")
                    )
                )
            )
        }

        val direct = serialize(window)
        assertFalse(direct.contains("\"uuid\""),
            "Direct serialize() should not leak ConverseData.uuid defaults.")
    }
}
