package com.TTT.Pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [parsePathSafetyVerdict] (the path-safety agent JSON verdict parser
 * added in commit 65ebee36). The parser is the only gate that lets a real path-safety
 * LLM's `{"safe": false}` response actually reject a path — previously the harness
 * only checked [MultimodalContent.terminatePipeline] / [MultimodalContent.passPipeline]
 * flags, which is a degenerate always-approve (LLMs don't set those flags on safety
 * verdicts).
 */
class PathSafetyVerdictParserTest
{
    @Test
    fun parsesTrueVerdict()
    {
        val result = parsePathSafetyVerdict("""{"safe": true, "reason": "approved"}""")
        assertEquals(true, result)
    }

    @Test
    fun parsesFalseVerdict()
    {
        val result = parsePathSafetyVerdict("""{"safe": false, "reason": "rejected by safety policy"}""")
        assertEquals(false, result)
    }

    @Test
    fun handlesSurroundingWhitespace()
    {
        val result = parsePathSafetyVerdict("""
            {"safe": false, "reason": "trim me"}
        """.trimIndent())
        assertEquals(false, result)
    }

    @Test
    fun rejectsStringBoolean()
    {
        // The string "true" is NOT a JSON boolean — it must be a literal.
        // The parser falls back to null so the legacy flag check is used.
        val result = parsePathSafetyVerdict("""{"safe": "true"}""")
        assertNull(result)
    }

    @Test
    fun rejectsNumericBoolean()
    {
        // 1 is not a JSON boolean either.
        val result = parsePathSafetyVerdict("""{"safe": 1}""")
        assertNull(result)
    }

    @Test
    fun rejectsNullSafeField()
    {
        val result = parsePathSafetyVerdict("""{"safe": null}""")
        assertNull(result)
    }

    @Test
    fun rejectsMissingSafeField()
    {
        val result = parsePathSafetyVerdict("""{"reason": "no safe field here"}""")
        assertNull(result)
    }

    @Test
    fun rejectsEmptyObject()
    {
        val result = parsePathSafetyVerdict("{}")
        assertNull(result)
    }

    @Test
    fun rejectsNonObjectJson()
    {
        val result = parsePathSafetyVerdict("""[1, 2, 3]""")
        assertNull(result)
    }

    @Test
    fun rejectsNonJsonText()
    {
        val result = parsePathSafetyVerdict("this is not JSON at all")
        assertNull(result)
    }

    @Test
    fun rejectsBlankText()
    {
        assertNull(parsePathSafetyVerdict(""))
        assertNull(parsePathSafetyVerdict("   "))
        assertNull(parsePathSafetyVerdict("\n\t  \n"))
    }

    @Test
    fun ignoresExtraFields()
    {
        // Extra fields are fine — we only care about `safe`.
        val result = parsePathSafetyVerdict(
            """{"safe": true, "reason": "approved", "extra": "ignored", "nested": {"x": 1}}"""
        )
        assertEquals(true, result)
    }

    @Test
    fun rejectsMarkdownFencedResponse()
    {
        // Real LLMs frequently wrap their JSON in ```json ... ``` fences.
        // The strict parser rejects the fenced form (returns null) and the
        // caller falls back to the legacy flag check. This is intentional:
        // a strict parse keeps the failure mode obvious. A real production
        // agent that consistently fences its response would need to call
        // [com.TTT.Util.repairJsonString] upstream or strip the fences in
        // its system prompt.
        val result = parsePathSafetyVerdict("```json\n{\"safe\": false}\n```")
        assertNull(result)
    }
}
