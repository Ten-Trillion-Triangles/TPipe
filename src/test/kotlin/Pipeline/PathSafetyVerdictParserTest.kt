package com.TTT.Pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [parsePathSafetyVerdict] (the path-safety agent JSON verdict parser
 * added in commit 65ebee36). The parser is the only gate that lets a real path-safety
 * LLM's `{"safe": false}` response actually reject a path — previously the harness
 * only checked [MultimodalContent.terminatePipeline] / [MultimodalContent.passPipeline]
 * flags, which is a degenerate always-approve (LLMs don't set those flags on safety
 * verdicts).
 *
 * Updated 2026-07-08 (F3 fix): the parser now returns a [PathSafetyVerdict] data
 * class carrying both the boolean verdict AND the optional `reason` string, so the
 * path-safety → dispatch hint can include the actual rejection reason instead of
 * the hardcoded "Rejected by path safety check" fallback. The boolean check at
 * callers uses `verdict.approved`; the reason is best-effort (null if missing).
 */
class PathSafetyVerdictParserTest
{
    @Test
    fun parsesTrueVerdict()
    {
        val result = parsePathSafetyVerdict("""{"safe": true, "reason": "approved"}""")
        assertEquals(true, result!!.approved)
    }

    @Test
    fun parsesFalseVerdict()
    {
        val result = parsePathSafetyVerdict("""{"safe": false, "reason": "rejected by safety policy"}""")
        assertEquals(false, result!!.approved)
    }

    @Test
    fun parsesFalseVerdictWithReason()
    {
        val result = parsePathSafetyVerdict(
            """{"safe": false, "reason": "schema invalid: missing required field 'topic'"}"""
        )
        assertEquals(false, result!!.approved)
        assertEquals("schema invalid: missing required field 'topic'", result.reason)
    }

    @Test
    fun parsesTrueVerdictWithReason()
    {
        val result = parsePathSafetyVerdict("""{"safe": true, "reason": "all checks passed"}""")
        assertEquals(true, result!!.approved)
        assertEquals("all checks passed", result.reason)
    }

    @Test
    fun parsesVerdictWithMissingReason()
    {
        val result = parsePathSafetyVerdict("""{"safe": false}""")
        assertEquals(false, result!!.approved)
        assertEquals(null, result.reason)
    }

    @Test
    fun handlesSurroundingWhitespace()
    {
        val result = parsePathSafetyVerdict(
            """
            {"safe": false, "reason": "trim me"}
        """.trimIndent()
        )
        assertEquals(false, result!!.approved)
        assertEquals("trim me", result.reason)
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
        // Extra fields are fine — we only care about `safe` and `reason`.
        val result = parsePathSafetyVerdict(
            """{"safe": true, "reason": "approved", "extra": "ignored", "nested": {"x": 1}}"""
        )
        assertEquals(true, result!!.approved)
        assertEquals("approved", result.reason)
    }

    @Test
    fun extractsVerdictFromMarkdownFencedResponse()
    {
        // Real LLMs frequently wrap their JSON in ```json ... ``` fences.
        // extractAllJsonObjects is designed to recover the inner object, so the
        // fenced form DOES yield a verdict. The test asserts the unwrapped verdict
        // is returned (the parser is lenient about fences, strict about field types).
        val result = parsePathSafetyVerdict("```json\n{\"safe\": false, \"reason\": \"fenced\"}\n```")
        assertEquals(false, result!!.approved)
        assertEquals("fenced", result.reason)
    }

    @Test
    fun rejectsNonStringReasonField()
    {
        // Non-string reason (number, object, etc.) is rejected — the field must
        // be a string literal or omitted entirely. Anything else returns null.
        val result = parsePathSafetyVerdict("""{"safe": false, "reason": 42}""")
        assertEquals(false, result!!.approved)
        assertEquals(null, result.reason)
    }
}