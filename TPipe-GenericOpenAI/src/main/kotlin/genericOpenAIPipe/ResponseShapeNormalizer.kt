package genericOpenAIPipe

/**
 * Provider-side response-shape normalization for GenericOpenAIPipe.
 *
 * Some model endpoints wrap their useful output in auxiliary blocks before the
 * JSON payload itself — most commonly a chain-of-thought block delimited by
 * &lt;think&gt;...&lt;/think&gt; tags. The chat-completions spec gives no way to suppress
 * these blocks server-side, and most providers that emit them do so regardless
 * of [java.lang.Annotation] `response_format`. The cleanest fix lives at the
 * response boundary of the pipe subclass: the harness should never see the
 * block, only the clean payload underneath.
 *
 * Use this from [GenericOpenAIPipe] before returning the response text to the
 * caller. TPipe core stays content-agnostic — it parses what it gets.
 */
object ResponseShapeNormalizer
{
    /**
     * Provider-side wrapper stripping. Removes one or more &lt;think&gt;...&lt;/think&gt;
     * blocks (canonical XML form) or `think\n`...`\nthink\n` blocks (bare-word
     * form) from a model response and returns the residual content. Unclosed
     * leading blocks are preserved verbatim — the model control plane does not
     * contain the closing token, so the rest of the surface is treated as the
     * final response.
     *
     * @param input Raw text returned from the wire before any provider-local
     *              cleanup. May be empty.
     * @return Input with thinking blocks removed. Whitespace trimmed.
     */
    fun stripThinkTags(input: String): String
    {
        if(input.isEmpty()) return input
        val out = StringBuilder(input.length)
        var i = 0
        while(i < input.length)
        {
            val opensAngleBracket = input.startsWith("think", i) || input.startsWith("/think", i)
            val closesHere = opensAngleBracket && run {
                var k = i + if(input.startsWith("/think", i)) 6 else 5
                while(k < input.length && input[k] != '>') k++
                k < input.length
            }
            if(closesHere)
            {
                var k = i + if(input.startsWith("/think", i)) 6 else 5
                while(k < input.length && input[k] != '>') k++
                i = k + 1
                continue
            }
            val opensBareword = input.startsWith("think\n", i)
            if(opensBareword || input.startsWith("think", i) && i + 5 == input.length)
            {
                val afterOpen = if(opensBareword) i + 6 else i + 5
                val closePos = input.indexOf("\nthink\n", afterOpen)
                if(closePos >= 0)
                {
                    i = closePos + 7
                    continue
                }
                if(opensBareword)
                {
                    out.append(input, i, afterOpen)
                    i = afterOpen
                    continue
                }
            }
            out.append(input[i])
            i++
        }
        return out.toString().trim()
    }
}
