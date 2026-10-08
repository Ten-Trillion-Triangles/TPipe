package com.TTT.Context

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TruncationSettings
import com.TTT.Util.serialize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TDD probe (RED-first) against the SerializedFrameEstimator fast path,
 * driven by the 2026-10-08 live "0 chars / stream is closed" validator/railroad
 * crash. The estimator's KDoc claims `MultimodalContent.context` is "never
 * serialized, so it is intentionally not a rare-content trigger." This test
 * measures whether that claim holds against the real global `serialize()`
 * encoder, and whether the fast renderer stays byte-identical on
 * Autogenesis-shaped windows (nested context + terminate flag).
 */
class EstimatorAutogenesisShapeProbeTest
{
    private val settings = TruncationSettings()

    /** A converse entry whose content carries a NON-EMPTY nested context window. */
    private fun windowWithNestedContext(): ContextWindow
    {
        val window = ContextWindow()
        val inner = ContextWindow()
        inner.contextElements.add("injected lore element that Autogenesis actually populates")
        val content = MultimodalContent(text = "history turn").apply {
            this.context = inner
        }
        window.converseHistory.history.add(ConverseData(ConverseRole.user, content))
        return window
    }

    /** A converse entry whose content has terminatePipeline = true. */
    private fun windowWithTerminateFlag(): ContextWindow
    {
        val window = ContextWindow()
        val content = MultimodalContent(text = "history turn").apply {
            terminatePipeline = true
        }
        window.converseHistory.history.add(ConverseData(ConverseRole.user, content))
        return window
    }

    @Test
    fun `REAL encoder DOES serialize a non-empty nested context (disproves KDoc claim)`()
    {
        val window = windowWithNestedContext()
        val serialized = serialize(window)
        val estimatorRender = SerializedFrameEstimator.renderWindowString(window)

        println("PROBE nestedContext: real-serialize has 'injected lore' = " +
            (serialized.contains("injected lore element")))
        println("PROBE nestedContext: estimator-render has 'injected lore' = " +
            (estimatorRender.contains("injected lore element")))
        println("PROBE nestedContext: byte-identical = " + (serialized == estimatorRender))
        println("PROBE nestedContext: hasRareContent = " +
            run {
                val est = SerializedFrameEstimator
                // hasRareContent is private; infer via public estimate path divergence below
                "n/a"
            })

        if(serialized.contains("injected lore element"))
        {
            // The real send-path encoder bills the nested context. The estimator MUST
            // reproduce it. If it does not, the estimate is SHORTER than the real cost.
            assertEquals(
                serialized, estimatorRender,
                "ESTIMATOR UNDERCOUNTS: real serialize() bills a non-empty nested " +
                    "MultimodalContent.context that the fast renderer silently drops. " +
                    "This is the 0-oversized-prompt root cause."
            )
        }
    }

    @Test
    fun `terminatePipeline=true routes to the exact fallback so the estimate matches the real encoder`()
    {
        val window = windowWithTerminateFlag()
        val serialized = serialize(window)
        val exactTokens = Dictionary.countTokens(serialized, settings)
        val estimate = SerializedFrameEstimator.estimateTokens(window, settings)
        val fastRender = SerializedFrameEstimator.renderWindowString(window)
        val fastRenderTokens = Dictionary.countTokens(fastRender, settings)

        println("PROBE terminate: real-serialize has 'terminatePipeline' = " +
            (serialized.contains("terminatePipeline")))
        println("PROBE terminate fallback: exact=$exactTokens estimate=$estimate fastRender=$fastRenderTokens")

        // The estimator's fast renderer does NOT model terminatePipeline, so a terminate-stamped
        // window is rare content: estimateTokens must fall back to the exact serialize() path and
        // therefore match the real encoder's token count. This is the load-bearing invariant that
        // removes the undercount that oversizes the prompt.
        assertEquals(
            exactTokens, estimate,
            "ESTIMATOR UNDERCOUNTS: terminate-flagged windows must be estimated via the exact " +
                "serialize() fallback (hasRareContent must flag terminatePipeline), not the fast path"
        )

        // Prove the fallback is load-bearing: the fast path alone undercounts this window.
        org.junit.jupiter.api.Assertions.assertTrue(
            fastRenderTokens < estimate,
            "fast renderWindowString still undercounts a terminate window (fast=$fastRenderTokens, " +
                "estimate=$estimate) — expected, since the estimate comes from the exact fallback"
        )
    }

    @Test
    fun `estimateTokens equals exact serialized token count on both autogenesis shapes`()
    {
        for((label, window) in listOf(
            "nestedContext" to windowWithNestedContext(),
            "terminateFlag" to windowWithTerminateFlag(),
            "plain" to ContextWindow().apply {
                converseHistory.history.add(ConverseData(ConverseRole.user, MultimodalContent(text = "turn")))
            }
        ))
        {
            val exact = Dictionary.countTokens(serialize(window), settings)
            val est = SerializedFrameEstimator.estimateTokens(window, settings)
            println("PROBE tokens [$label]: exact=$exact estimate=$est delta=$((exact - est)) " +
                "(negative delta = estimator UNDERCOUNTS = oversized prompt)")
            assertEquals(exact, est, "[$label] estimateTokens must equal the exact serialized count")
        }
    }

    @Test
    fun `undercount compounds across N stamped converse entries - decisive magnitude test`()
    {
        // A production validator window carries many converse entries, each stamped by the
        // executor (terminate() -> terminatePipeline=true, which Autogenesis calls on branch
        // paths). If the fast renderer drops the terminatePipeline field on every entry, the
        // undercount COMPOUNDS. Measure estimate vs exact as N grows. If the delta is in the
        // thousands of tokens, the estimator bug is large enough to oversize the prompt and
        // stall the provider; if it stays in the low tens, it is NOT the 35K-prompt root cause.
        val labels = intArrayOf(1, 10, 50, 100, 200, 500)
        for(n in labels)
        {
            val window = ContextWindow()
            // Mirror Autogenesis: each converse entry is a real-ish text + terminate() stamp.
            repeat(n)
            {
                val content = MultimodalContent(
                    text = "history turn $it with a moderate amount of narrative content to simulate a real response"
                ).apply { terminatePipeline = true }
                window.converseHistory.history.add(ConverseData(ConverseRole.assistant, content))
            }

            val exact = Dictionary.countTokens(serialize(window), settings)
            val est = SerializedFrameEstimator.estimateTokens(window, settings)
            val undercount = est - exact // negative = estimator undercounts
            println("MAGNITUDE n=$n exact=$exact estimate=$est undercount=$undercount " +
                "pct=${if(exact > 0) String.format("%.1f", 100.0 * undercount / exact) else "n/a"}%")
        }
    }
}
