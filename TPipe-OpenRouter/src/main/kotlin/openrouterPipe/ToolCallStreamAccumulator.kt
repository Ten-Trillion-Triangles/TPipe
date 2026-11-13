package openrouterPipe

import env.ChatToolCall
import env.ToolCallFunction

/**
 * Merges OpenAI-compat streaming tool-call fragments into whole calls.
 *
 * OpenRouter's chat-completions stream (the `ChatStreamToolCall` wire shape)
 * carries an `index` per fragment (spec: `"required": ["index"]`, "Tool call
 * index in the array"). The first fragment of a call carries `id`/`type`/
 * `function.name` + the start of `function.arguments`; subsequent fragments
 * for the same `index` append the rest of `arguments`. A naive `addAll` would
 * hold N partial calls instead of M whole ones.
 *
 * This accumulator groups by `index` (insertion-ordered) and concatenates the
 * `arguments` fragments; calls without an `index` (non-OpenAI providers) pass
 * through as complete.
 */
class ToolCallStreamAccumulator
{
    private val byIndex = LinkedHashMap<Int, ChatToolCall>()
    private val noIndex = mutableListOf<ChatToolCall>()

    /**
     * Adds one streaming tool-call fragment, merging it into the call identified
     * by its `index` (or appending it as a complete call when index is null).
     *
     * @param delta The fragment from a single streaming chunk.
     */
    fun add(delta: ChatToolCall)
    {
        val i = delta.index
        if(i == null)
        {
            noIndex.add(delta)
            return
        }

        val existing = byIndex[i]
        if(existing == null)
        {
            byIndex[i] = delta
            return
        }
        byIndex[i] = merge(existing, delta)
    }

    /**
     * Merges a later [next] fragment into an already-seen [current] fragment of
     * the same call: keeps the first-seen id/type/name, concatenates arguments.
     */
    private fun merge(current: ChatToolCall, next: ChatToolCall): ChatToolCall
    {
        val mergedName = current.function?.name?.takeIf { it.isNotEmpty() } ?: next.function?.name
        val mergedArgs = ((current.function?.arguments ?: "") + (next.function?.arguments ?: "")).takeIf { it.isNotEmpty() }

        return ChatToolCall(
            id = current.id?.takeIf { it.isNotEmpty() } ?: next.id,
            type = current.type ?: next.type,
            function = ToolCallFunction(name = mergedName, arguments = mergedArgs),
            index = current.index
        )
    }

    /**
     * @return The merged whole calls, in first-seen index order, with complete
     *   (index-less) calls appended after them.
     */
    fun result(): List<ChatToolCall> = byIndex.values.toList() + noIndex
}
