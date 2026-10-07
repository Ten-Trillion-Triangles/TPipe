package com.TTT.Context

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TruncationSettings
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.Util.serialize

/**
 * Estimates the tokens a [ContextWindow] / [MiniBank] bills when it is sent to the model, WITHOUT
 * calling the kotlinx `serialize()` codec on the common path.
 *
 * The send path (Pipe.kt fullPrompt construction) appends the SERIALIZED window to the prompt, so
 * the model bills the JSON framing (braces, quotes, keys, pretty-print whitespace, nested object
 * skeletons) on top of the raw content. Raw-content truncation budgeting ignored that framing,
 * which is the gap this estimator closes.
 *
 * How it works:
 *  - [estimateTokens] reproduces the exact serialized string the send path produces — but builds it
 *    with a [StringBuilder] rather than the kotlinx codec — and runs the shared [Dictionary]
 *    tokenizer over it. For the common text-centric schema this is byte-identical to
 *    `serialize(window)`, so the estimate is EXACT (no approximation, no hand-tuned constants).
 *  - Windows carrying the rare deep fields (non-default PCP `tools`, non-empty `binaryContent`, or a
 *    nested non-empty `miniBankContext` on a converse entry) fall back to a single exact
 *    `serialize()` call. That is one call for a rare window, not a per-element / per-page / per-loop
 *    serialization — the "spamming serialize()" cost is what this class exists to remove.
 *
 * [contentTokens] counts only the variable user data (element strings, history role + text, lorebook
 * key/value/list strings) so that `frameCost = estimateTokens - contentTokens` is the exact
 * non-content overhead to reserve against the content budget.
 *
 * @see com.TTT.Pipe.Pipe.countContextWindowTokens The exact (expensive) reference counter.
 */
object SerializedFrameEstimator
{

    //==== Estimation (entry points) =================================================//

    /**
     * Exact tokens this [window] bills when sent: framing + content, reproduced without the kotlinx
     * codec on the common path. Zero serialize() calls unless the window carries the rare deep
     * fields (see [hasRareContent]).
     */
    fun estimateTokens(window: ContextWindow, settings: TruncationSettings): Int
    {
        return if(hasRareContent(window))
        {
            Dictionary.countTokens(serialize(window), settings)
        }
        else
        {
            Dictionary.countTokens(renderWindow(window, 0), settings)
        }
    }

    /**
     * Exact tokens this [bank] bills when sent via `serialize(miniContextBank)`.
     */
    fun estimateMiniBankTokens(bank: MiniBank, settings: TruncationSettings): Int
    {
        val anyPageRare = bank.contextMap.values.any { hasRareContent(it) }
        return if(anyPageRare)
        {
            Dictionary.countTokens(serialize(bank), settings)
        }
        else
        {
            Dictionary.countTokens(renderMiniBank(bank, 0), settings)
        }
    }

    /**
     * The exact non-content overhead of [window] (serialized framing + content quoting) — the amount
     * to reserve from a content budget so that `content + frame` fits. Exact for the common schema.
     */
    fun frameCost(window: ContextWindow, settings: TruncationSettings): Int
    {
        return estimateTokens(window, settings) - contentTokens(window, settings)
    }

    /**
     * The exact non-content overhead of a whole [bank] (bank-level framing plus every page's window
     * framing) — the amount to reserve when budgeting a multi-page context.
     */
    fun miniBankFrameCost(bank: MiniBank, settings: TruncationSettings): Int
    {
        return estimateMiniBankTokens(bank, settings) - miniBankContentTokens(bank, settings)
    }

    /**
     * Raw content tokens of [window]: the variable user data that sits inside the frame —
     * context element strings, converse history role + text, and lorebook key/value/list strings.
     * Counted with the same [Dictionary] tokenizer the budgeting uses, so content and frame sums
     * are in the same units.
     */
    fun contentTokens(window: ContextWindow, settings: TruncationSettings): Int
    {
        var total = 0

        for(element in window.contextElements)
        {
            total += Dictionary.countTokens(element, settings)
        }

        for(entry in window.converseHistory.history)
        {
            total += Dictionary.countTokens(entry.role.name, settings)
            total += Dictionary.countTokens(entry.content.text, settings)
        }

        for((mapKey, lore) in window.loreBookKeys)
        {
            //The key string appears twice in the serialized form: as the map key and as the
            //LoreBook.key field (ALWAYS emitted).
            total += Dictionary.countTokens(mapKey, settings)
            total += Dictionary.countTokens(lore.key, settings)
            total += Dictionary.countTokens(lore.value, settings)
            for(aliasKey in lore.aliasKeys)
            {
                total += Dictionary.countTokens(aliasKey, settings)
            }
            for(linkedKey in lore.linkedKeys)
            {
                total += Dictionary.countTokens(linkedKey, settings)
            }
            for(requiredKey in lore.requiredKeys)
            {
                total += Dictionary.countTokens(requiredKey, settings)
            }
        }

        return total
    }

    /**
     * Raw content tokens of a whole [bank]: page-key names plus each page's window content.
     */
    fun miniBankContentTokens(bank: MiniBank, settings: TruncationSettings): Int
    {
        var total = 0

        for((pageKey, pageWindow) in bank.contextMap)
        {
            total += Dictionary.countTokens(pageKey, settings)
            total += contentTokens(pageWindow, settings)
        }

        return total
    }

    //==== Rare-content detection (triggers the one exact serialize fallback) ========//

    /**
     * The reconstructed serialized string for [window] at root level. Exposed for golden tests that
     * pin the reconstruction against the real `serialize()` output byte-for-byte.
     */
    internal fun renderWindowString(window: ContextWindow): String
    {
        return renderWindow(window, 0)
    }

    /**
     * The reconstructed serialized string for [bank] at root level. Exposed for golden tests.
     */
    internal fun renderMiniBankString(bank: MiniBank): String
    {
        return renderMiniBank(bank, 0)
    }

    /**
     * True when [window] carries any of the deep `MultimodalContent` sub-fields that the fast
     * renderer does not model: a non-default PCP `tools` request, non-empty `binaryContent`, or a
     * nested non-empty `miniBankContext` on a converse entry. Note `MultimodalContent.context` is
     * never serialized (see class KDoc), so it is intentionally not a rare-content trigger.
     */
    private fun hasRareContent(window: ContextWindow): Boolean
    {
        for(entry in window.converseHistory.history)
        {
            val content = entry.content
            if(content.tools != PcPRequest() ||
                content.binaryContent.isNotEmpty() ||
                content.miniBankContext.contextMap.isNotEmpty())
            {
                return true
            }
        }
        return false
    }

    //==== Fast reconstruction (matches serialize() for the common schema) ==========//

    /** Pretty-print indent unit (kotlinx `prettyPrint` uses 4 spaces per level). */
    private fun indent(level: Int): String
    {
        return "    ".repeat(level)
    }

    /**
     * Renders [s] as a JSON string literal: wrapped in double quotes with control characters and
     * backslashes escaped per [appendJsonEscaped] — the same escaping kotlinx `prettyPrint` applies.
     *
     * @param s The raw string value to encode.
     */
    private fun jsonString(s: String): String
    {
        val sb = StringBuilder()
        sb.append('"')
        sb.appendJsonEscaped(s)
        sb.append('"')
        return sb.toString()
    }

    /**
     * Appends [s] to this [StringBuilder] with JSON string escaping: `"` and `\` are backslashed,
     * `\n` / `\r` / `\t` become short escapes, and any other control char (code < 0x20) becomes a
     * `\uXXXX` escape — matching kotlinx `prettyPrint` output byte-for-byte.
     *
     * @param s The raw characters to escape and append.
     */
    private fun StringBuilder.appendJsonEscaped(s: String)
    {
        for(c in s)
        {
            when(c)
            {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                {
                    if(c.code < 0x20)
                    {
                        append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    }
                    else
                    {
                        append(c)
                    }
                }
            }
        }
    }

    /**
     * Renders a JSON object. `level` is the level of the key this object is the value of (0 for the
     * root). The opening brace follows the key (unindented within the value), body fields sit at
     * `level + 1`, and the closing brace at `level` — exactly kotlinx's pretty-print layout.
     */
    private fun objectValue(level: Int, fields: List<Pair<String, (Int) -> String>>): String
    {
        if(fields.isEmpty()) return "{}"

        val sb = StringBuilder()
        sb.append('{')
        fields.forEachIndexed { i, (key, valueFn) ->
            sb.append('\n').append(indent(level + 1)).append(jsonString(key)).append(": ")
            sb.append(valueFn(level + 1))
            if(i < fields.size - 1) sb.append(',')
        }
        sb.append('\n').append(indent(level)).append('}')
        return sb.toString()
    }

    /**
     * Renders a JSON array. Empty arrays render as `[]`. `level` is the level of the key this array
     * is the value of (0 for the root): each element sits at `level + 1` and the closing bracket at
     * `level` — exactly kotlinx's pretty-print layout.
     *
     * @param level The nesting level of the key this array is the value of.
     * @param items One renderer per array element; each is invoked with the element's own level.
     */
    private fun arrayValue(level: Int, items: List<(Int) -> String>): String
    {
        if(items.isEmpty()) return "[]"

        val sb = StringBuilder()
        sb.append('[')
        items.forEachIndexed { i, itemFn ->
            sb.append('\n').append(indent(level + 1)).append(itemFn(level + 1))
            if(i < items.size - 1) sb.append(',')
        }
        sb.append('\n').append(indent(level)).append(']')
        return sb.toString()
    }

    /**
     * Renders a JSON array of string literals (e.g. `contextElements`, `linkedKeys`), each element
     * quoted and escaped via [jsonString].
     *
     * @param level The nesting level of the key this array is the value of.
     * @param list The raw strings to encode.
     */
    private fun stringArrayValue(level: Int, list: List<String>): String
    {
        return arrayValue(level, list.map { s -> { _ -> jsonString(s) } })
    }

    /**
     * Reconstructs the exact `serialize()` JSON for [window] at nesting [level]. Emits `loreBookKeys`
     * always; `contextElements` / `converseHistory` only when non-empty; `version` only when
     * non-zero — matching kotlinx `encodeDefaults=false` for this schema.
     *
     * @param window The context window to serialize.
     * @param level The nesting level this object sits at in the enclosing document.
     */
    private fun renderWindow(window: ContextWindow, level: Int): String
    {
        val fields = ArrayList<Pair<String, (Int) -> String>>()

        //ALWAYS emitted, even when empty.
        fields.add("loreBookKeys" to { l -> loreMapValue(l, window.loreBookKeys) })

        if(window.contextElements.isNotEmpty())
        {
            fields.add("contextElements" to { l -> stringArrayValue(l, window.contextElements) })
        }

        if(window.converseHistory.history.isNotEmpty())
        {
            fields.add("converseHistory" to { l -> converseHistoryValue(l, window.converseHistory) })
        }

        if(window.version != 0L)
        {
            fields.add("version" to { _ -> window.version.toString() })
        }

        return objectValue(level, fields)
    }

    /**
     * Reconstructs the exact `serialize()` JSON for [bank] at nesting [level]. Emits `contextMap`
     * only when it holds at least one page; an empty bank renders as `{}`.
     *
     * @param bank The mini bank to serialize.
     * @param level The nesting level this object sits at in the enclosing document.
     */
    private fun renderMiniBank(bank: MiniBank, level: Int): String
    {
        val fields = ArrayList<Pair<String, (Int) -> String>>()

        if(bank.contextMap.isNotEmpty())
        {
            fields.add("contextMap" to { l -> bankMapValue(l, bank.contextMap) })
        }

        return objectValue(level, fields)
    }

    /**
     * Renders a `contextMap` object whose values are full nested [ContextWindow] pages: each entry
     * key maps to a reconstructed [renderWindow].
     *
     * @param level The nesting level this object sits at in the enclosing document.
     * @param map The page-key → page-window entries to serialize.
     */
    private fun bankMapValue(level: Int, map: MutableMap<String, ContextWindow>): String
    {
        val fields = map.entries.map { (pageKey, pageWindow) ->
            pageKey to { l: Int -> renderWindow(pageWindow, l) }
        }
        return objectValue(level, fields)
    }

    /**
     * Renders a `loreBookKeys` object: each map key maps to a reconstructed [LoreBook] object via
     * [loreValue]. An empty map renders as `{}`.
     *
     * @param level The nesting level this object sits at in the enclosing document.
     * @param map The lore-key → [LoreBook] entries to serialize.
     */
    private fun loreMapValue(level: Int, map: MutableMap<String, LoreBook>): String
    {
        val fields = map.entries.map { (key, lore) ->
            key to { l: Int -> loreValue(l, lore) }
        }
        return objectValue(level, fields)
    }

    /**
     * Reconstructs a single [LoreBook] object: `key` and `value` always, `weight` only when
     * non-zero, then the `linkedKeys` / `aliasKeys` / `requiredKeys` string arrays.
     *
     * @param level The nesting level this object sits at in the enclosing document.
     * @param lore The lore book to serialize.
     */
    private fun loreValue(level: Int, lore: LoreBook): String
    {
        val fields = ArrayList<Pair<String, (Int) -> String>>()
        fields.add("key" to { _ -> jsonString(lore.key) })
        fields.add("value" to { _ -> jsonString(lore.value) })
        if(lore.weight != 0)
        {
            fields.add("weight" to { _ -> lore.weight.toString() })
        }
        fields.add("linkedKeys" to { l -> stringArrayValue(l, lore.linkedKeys) })
        fields.add("aliasKeys" to { l -> stringArrayValue(l, lore.aliasKeys) })
        fields.add("requiredKeys" to { l -> stringArrayValue(l, lore.requiredKeys) })
        return objectValue(level, fields)
    }

    /**
     * Reconstructs a `converseHistory` object wrapping its `history` array of [ConverseData] entries.
     *
     * @param level The nesting level this object sits at in the enclosing document.
     * @param history The converse history to serialize.
     */
    private fun converseHistoryValue(level: Int, history: ConverseHistory): String
    {
        val fields = listOf(
            "history" to { l: Int ->
                arrayValue(l, history.history.map { entry -> { ll: Int -> converseDataValue(ll, entry) } })
            }
        )
        return objectValue(level, fields)
    }

    /**
     * Reconstructs a single [ConverseData] entry: `role`, `content`, and `uuid` (only when a uuid was
     * assigned, i.e. non-empty).
     *
     * @param level The nesting level this object sits at in the enclosing document.
     * @param entry The converse entry to serialize.
     */
    private fun converseDataValue(level: Int, entry: ConverseData): String
    {
        val fields = ArrayList<Pair<String, (Int) -> String>>()
        fields.add("role" to { _ -> jsonString(entry.role.name) })
        fields.add("content" to { l -> multimodalContentValue(l, entry.content) })
        //ALWAYS emitted only when the uuid was assigned (non-empty).
        if(entry.getUUID().isNotEmpty())
        {
            fields.add("uuid" to { _ -> jsonString(entry.getUUID()) })
        }
        return objectValue(level, fields)
    }

    /**
     * Reconstructs a `MultimodalContent` object for the fast path: `text` when non-empty and
     * `useSnapshot` when set. The rare deep fields (`tools`, `binaryContent`, `terminatePipeline`,
     * nested `miniBankContext`) are deliberately not modeled here — their presence routes the whole
     * window to the exact `serialize()` fallback (see [hasRareContent]).
     *
     * @param level The nesting level this object sits at in the enclosing document.
     * @param content The multimodal content to serialize.
     */
    private fun multimodalContentValue(level: Int, content: MultimodalContent): String
    {
        val fields = ArrayList<Pair<String, (Int) -> String>>()

        if(content.text.isNotEmpty())
        {
            fields.add("text" to { _ -> jsonString(content.text) })
        }

        //terminatePipeline / tools are the rare deep fields: when present they route this window to
        //the exact serialize() fallback (see hasRareContent), so they are not rendered here.
        if(content.useSnapshot)
        {
            fields.add("useSnapshot" to { _ -> "true" })
        }

        return objectValue(level, fields)
    }
}
