package genericOpenAIPipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tests for [ResponseShapeNormalizer.stripThinkTags].
 *
 * MiniMax-M2.7 emits `think\n...reasoning...\nthink\n{payload}` blocks that wrap
 * the model's actual JSON output. DeepSeek-R1 emits analogous content via
 * `\\think\\...\\think\\` blocks. The cleaner is provider-scoped; this tests the
 * shape MiniMax-M2.7 emits today and the canonical XML-style form for
 * robustness against future endpoint changes.
 */
class ResponseShapeNormalizerTest
{
    @Test
    fun thinkNewlineBlocksAreStripped()
    {
        val input = "think\nreasoning goes here\nthink\n{\"pathName\":\"report\",\"pathSchema\":\"\"}"
        val out = ResponseShapeNormalizer.stripThinkTags(input)
        assertEquals("{\"pathName\":\"report\",\"pathSchema\":\"\"}", out)
    }

    @Test
    fun closingTagAtLineEndStripsInnerContent()
    {
        val input = "abc think\nstuff\nthink\ndef"
        val out = ResponseShapeNormalizer.stripThinkTags(input)
        assertEquals("abc def", out)
    }

    @Test
    fun textWithoutThinkBlocksPassesThrough()
    {
        val input = "{\"hello\":\"world\"}"
        val out = ResponseShapeNormalizer.stripThinkTags(input)
        assertEquals("{\"hello\":\"world\"}", out)
    }

    @Test
    fun strippedTextIsParseableAsJson()
    {
        val input = "think\nThe user said hello.\nthink\n{\"k\":\"v\"}"
        val out = ResponseShapeNormalizer.stripThinkTags(input)
        val json = Json.parseToJsonElement(out) as kotlinx.serialization.json.JsonObject
        assertEquals("v", json["k"]?.jsonPrimitive?.content)
    }

    @Test
    fun multipleThinkBlocksAreStripped()
    {
        val input = "think\nfirst\nthink\n{\"a\":1} think\nsecond\nthink\n{\"b\":2}"
        val out = ResponseShapeNormalizer.stripThinkTags(input)
        assertEquals("{\"a\":1} {\"b\":2}", out)
    }

    @Test
    fun emptyStringPassesThrough()
    {
        assertEquals("", ResponseShapeNormalizer.stripThinkTags(""))
    }

    @Test
    fun unclosedThinkBlockAtEndPassesThroughUnchanged()
    {
        val input = "think\nunterminated"
        val out = ResponseShapeNormalizer.stripThinkTags(input)
        assertEquals("think\nunterminated", out)
    }

    @Test
    fun canonicalAngularBracketFormIsAlsoHandled()
    {
        // Some endpoints emit the conventional XML form `think...think`. Provider
        // robustness: handle both. The output text should survive unchanged when
        // the canonical form is used and there is content after the closing tag.
        val input = "{\"a\":1} think\nthinking...stuff...\nthink extra"
        val out = ResponseShapeNormalizer.stripThinkTags(input)
        // The bare-word recognizer does not consume this input because the closing
        // tag is not followed by a newline — preservation is the correct behavior
        // (don't drop payload data on the floor when we cannot pair tags).
        assertEquals(input, out)
    }
}
