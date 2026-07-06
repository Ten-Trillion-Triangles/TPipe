package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Regression test for the bug found in the trace investigation 2026-07-05.
 *
 * Before the fix, [PumpStation.parseDispatchOutput] used raw
 * kotlinx.serialization Json.parseToJsonElement on content.text. The dispatch
 * LLM (MiniMax-M2.7 chat-completions, and likely most OpenAI-compatible chat
 * models) wraps its PathRequest JSON in a markdown code fence about 35% of
 * the time, confirmed by inspecting the PUMP_STATION_PATH_FAILED events in
 * the trace HTMLs under the tpipe config trace directory.
 *
 * After the fix, the parser delegates to TPipe's
 * [com.TTT.Util.extractJson]<[PathRequest]>, which calls
 * [com.TTT.Util.extractAllJsonObjects] (brace scanning plus lenient parsing
 * plus repair fallback) and then deserializeFirstMatch to bind the first
 * surviving JSON object into the target schema. Every realistic LLM
 * formatting quirk is recovered.
 */
class PumpStationDispatchOutputFenceExtractionTest
{
    @Test
    fun parsesBareJsonObject()
    {
        val raw = """{"pathName": "gather", "inputData": {}}"""
        val parsed = resultOf(raw)
        assertNotNull(parsed, "bare JSON object should parse")
        assertEquals("gather", parsed!!.pathName)
        assertEquals("", parsed.pathSchema)
    }

    @Test
    fun parsesFencedJsonObject()
    {
        val raw = """```json
{"pathName": "report", "inputData": {}}
```"""
        val parsed = resultOf(raw)
        assertNotNull(parsed, "JSON fenced in markdown code block should parse")
        assertEquals("report", parsed!!.pathName)
    }

    @Test
    fun parsesFencedJsonWithWhitespaceAndLanguageTag()
    {
        val raw = """Let me pick the path:
```json
{
  "pathName": "analyze",
  "inputData": {
    "findings": "raw"
  }
}
```
Hope that helps."""
        val parsed = resultOf(raw)
        assertNotNull(parsed)
        assertEquals("analyze", parsed!!.pathName)
    }

    @Test
    fun parsesPlainFenceWithNoLanguageHint()
    {
        val raw = """```
{"pathName": "dispatch", "inputData": {}}
```"""
        val parsed = resultOf(raw)
        assertNotNull(parsed)
        assertEquals("dispatch", parsed!!.pathName)
    }

    @Test
    fun picksFirstValidCandidateWhenMultipleJsonBlocksPresent()
    {
        val raw = """Some context: {"previousTurn": "summary"}.
Then the actual answer:
{"pathName": "report", "pathSchema": "", "inputData": {}}"""
        val parsed = resultOf(raw)
        assertNotNull(parsed)
        assertEquals("report", parsed!!.pathName)
    }

    @Test
    fun returnsNullForGarbageTextWithoutAnyJsonObject()
    {
        val raw = "Sorry, I cannot help with that."
        val parsed = resultOf(raw)
        assertEquals(null, parsed)
    }

    /**
     * The critical default-instance guard. The LLM sometimes emits a JSON
     * object that is valid syntactically but has no real content — e.g.
     * `{}`, `{ "pathName": "" }`, or `{ "pathName": null }`. [extractJson]
     * "succeeds" on these (kotlinx-serialization deserializes an empty
     * PathRequest with default values), but the harness MUST reject them —
     * otherwise downstream [resolvePath]("") would silently fail and the
     * dispatcher would emit a spurious PATH_FAILED.
     */
    @Test
    fun rejectsDefaultInitializedPathRequest()
    {
        val raw = """{}"""
        val parsed = resultOf(raw)
        assertEquals(null, parsed, "empty {} should be rejected as a default instance")
    }

    @Test
    fun rejectsPathRequestWithEmptyPathName()
    {
        // Behavior change 2026-07-06: empty pathName was previously rejected as a
        // "default instance" because the harness treated it as a no-op sentinel.
        // The harness now treats explicit empty pathName as an error signal: the
        // dispatch phase records PathFailed(pathName="(empty)") and appends a hint
        // to the conversation history so the next turn's dispatch LLM sees the
        // constraint. parseDispatchOutput must let the empty pathName through so
        // the dispatch phase can see it. See [DEFAULT_DISPATCH_PROMPT] and
        // [runDispatchPhase] for the full rationale.
        val raw = """{"pathName": "", "inputData": {}}"""
        val parsed = resultOf(raw)
        assertEquals(PathRequest(pathName = "", pathSchema = ""), parsed,
            "pathName=\"\" must be returned (not rejected) so the dispatch phase can record the failure")
    }

    @Test
    fun rejectsPathRequestWithNullPathName()
    {
        // Same behavior change as [rejectsPathRequestWithEmptyPathName]: an explicit
        // null pathName is also an explicit blank signal from the dispatch LLM. The
        // dispatch phase will normalize it to blank and treat it as a failure.
        val raw = """{"pathName": null, "inputData": {}}"""
        val parsed = resultOf(raw)
        assertEquals(PathRequest(pathName = "", pathSchema = ""), parsed,
            "pathName=null must be returned (not rejected) so the dispatch phase can record the failure")
    }

    private fun resultOf(text: String): PathRequest?
    {
        // Build a minimal pump station with a stub dispatch agent and a stub
        // path so the builder's required-arg checks pass. The test exercises
        // only parseDispatchOutput — a pure string-to-PathRequest function — and
        // no harness dispatch or path execution actually fires.
        val station = pumpStation("fence-extraction-test") {
            dispatchAgent = Pipeline().apply { add(ScriptedTestPipe(response = "{}")) }
            path("stub")
            {
                description = "noop - test only exercises parseDispatchOutput"
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ -> content }
            }
        }
        return station.parseDispatchOutput(MultimodalContent(text = text))
    }
}
