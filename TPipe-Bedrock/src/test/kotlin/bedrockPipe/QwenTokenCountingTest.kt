package bedrockPipe

import bedrockPipe.BedrockPipe
import com.TTT.Context.Dictionary
import com.TTT.Pipe.TruncationSettings
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QwenTokenCountingTest {

    private val reversalStory = """
        I need to break this down systematically. The key elements I'm working with are: Reverse the outcome of a story while preserving player actions and intent, Maintain the hero's action (raising sword) and intent (saving kingdom), Change only the result/resolution of events, Preserve all narrative elements except final outcome. My constraints and available information: Player (Sir Alaric) successfully defeated a dragon, Villagers cheered and kingdom was saved, Peace was restored for a thousand years, Everyone lived happily ever after, Must keep original action and intent unchanged, Only reverse the consequence/results. What I'm ultimately trying to solve: Reverse the positive outcome of Sir Alaric's victory over the dragon while maintaining his heroic actions and intentions. I can decompose this into these sub-problems: Identify the positive outcome elements to reverse, Determine the logical negative consequences that would follow from the same actions, Preserve the hero's actions and intentions throughout, Construct a reversed narrative that maintains consistency with original elements, Ensure the reversal makes logical sense within the fictional context. The sequence of operations will be: Extract all positive outcome statements from the story, then Reverse each outcome element logically, then Retain all action and intent elements, then Reconstruct narrative with reversed outcomes, then Verify logical consistency of new ending. Dependencies between steps: Cannot reverse outcomes without first identifying them, Must preserve actions before constructing reversed results, Logical consistency depends on maintaining original character intent, Final narrative construction requires all reversed elements to be ready, Verification depends on complete reversal being implemented. Now executing systematically: For Identify Positive Outcomes: List all elements indicating success and positive resolution. For Reverse Outcome Elements: Replace each success element with its logical opposite. For Preserve Hero Actions: Maintain the same physical actions and stated intentions. For Reconstruct Narrative: Integrate reversed outcomes while keeping original structure. For Validate Consistency: Check that reversed outcomes logically follow from same actions. Extracted 'villagers cheered' as positive outcome (Identifying positive outcomes ensures we know what to reverse). Extracted 'kingdom was saved' as positive outcome (Reversing each element creates the opposite result while keeping actions intact). Extracted 'peace was restored for a thousand years' as positive outcome (Preserving actions maintains the story's core behavior and character). Extracted 'everyone lived happily ever after' as positive outcome (Reconstruction allows us to build the new narrative flow properly). Reversed 'villagers cheered' to 'villagers screamed in terror' (Validation confirms our reversal makes logical sense in context). Reversed 'kingdom was saved' to 'kingdom was destroyed' (). Reversed 'peace was restored' to 'chaos reigned' (). Reversed 'everyone lived happily' to 'everyone suffered greatly' (). Maintained 'Sir Alaric raised his sword' as action (). Maintained 'Sir Alaric intended to save kingdom' as intent (). My proposed approach: Reverse all positive outcome elements while maintaining the hero's actions and intentions exactly as written, creating a tragic ending where Sir Alaric's victory leads to destruction rather than salvation. Approach validation: The approach correctly identifies that the original story has a clear positive outcome structure with multiple success indicators that can be systematically reversed while preserving the core heroic action and intent. By reversing the outcome elements (villagers cheering → screaming, kingdom saved → destroyed, peace restored → chaos, happy ending → suffering) while keeping Sir Alaric's sword-raising and saving intention unchanged, we create a logical counter-narrative that satisfies the requirement to reverse results without altering player actions or intent
    """.trimIndent()

    private val targetTokens = 772
    private val tolerance = 5

    private data class ResultEntry(val settings: TruncationSettings, val tokens: Int, val diff: Int)
    private data class RegimeSummary(
        val name: String,
        val ranked: List<ResultEntry>,
        val best: ResultEntry?,
        val bestAboveTarget: ResultEntry?
    )

    @Test
    fun testQwenTokenCountProximity() {
        val pipe = BedrockPipe().setModel("qwen.qwen3-32b-v1:0")
        pipe.truncateModuleContext()
        val baseSettings = pipe.getTruncationSettings()

        val boolOptions = listOf(true, false)
        val nonWordSplits = (1..8).toList()

        val results = mutableListOf<ResultEntry>()

        boolOptions.forEach { countSubWordsInFirstWord ->
            boolOptions.forEach { favorWholeWords ->
                boolOptions.forEach { countSubWordsIfSplit ->
                    boolOptions.forEach { alwaysSplitIfWholeWordExists ->
                        boolOptions.forEach { countOnlyFirstWordFound ->
                            boolOptions.forEach { splitForNonWordChar ->
                                nonWordSplits.forEach { nonWordSplitCount ->
                                    val candidate = baseSettings.copy(
                                        countSubWordsInFirstWord = countSubWordsInFirstWord,
                                        favorWholeWords = favorWholeWords,
                                        countSubWordsIfSplit = countSubWordsIfSplit,
                                        alwaysSplitIfWholeWordExists = alwaysSplitIfWholeWordExists,
                                        countOnlyFirstWordFound = countOnlyFirstWordFound,
                                        splitForNonWordChar = splitForNonWordChar,
                                        nonWordSplitCount = nonWordSplitCount
                                    )

                                    val tokens = Dictionary.countTokens(reversalStory, candidate)
                                    val diff = abs(tokens - targetTokens)

                                    results += ResultEntry(candidate, tokens, diff)
                                }
                            }
                        }
                    }
                }
            }
        }

        val tightSummary = summarizeRegime(
            name = "Tight",
            filter = { settings ->
                settings.favorWholeWords && settings.alwaysSplitIfWholeWordExists && !settings.countSubWordsIfSplit
            },
            allResults = results
        )

        val wideSummary = summarizeRegime(
            name = "Wide",
            filter = { settings ->
                settings.countSubWordsIfSplit || !settings.favorWholeWords || settings.countOnlyFirstWordFound
            },
            allResults = results
        )

        printSummary(tightSummary)
        printSummary(wideSummary)

        assertTrue(
            (tightSummary.best?.diff ?: Int.MAX_VALUE) <= tolerance,
            "Tight regime closest delta ${tightSummary.best?.diff} exceeds tolerance $tolerance."
        )
        assertTrue(
            (wideSummary.best?.diff ?: Int.MAX_VALUE) <= tolerance,
            "Wide regime closest delta ${wideSummary.best?.diff} exceeds tolerance $tolerance."
        )
    }

    private fun summarizeRegime(
        name: String,
        filter: (TruncationSettings) -> Boolean,
        allResults: List<ResultEntry>
    ): RegimeSummary {
        val filtered = allResults.filter { filter(it.settings) }
        val ranked = filtered.sortedWith(compareBy({ it.diff }, { it.tokens }))
        val best = ranked.firstOrNull()
        val bestAbove = filtered
            .filter { it.tokens > targetTokens }
            .minWithOrNull(compareBy({ it.tokens - targetTokens }, { it.diff }))

        return RegimeSummary(name, ranked, best, bestAbove)
    }

    private fun printSummary(summary: RegimeSummary) {
        println()
        println("${summary.name} regime:")
        if (summary.best != null) {
            println("  Closest count = ${summary.best.tokens} (diff = ${summary.best.diff}) using ${summary.best.settings}")
        } else {
            println("  No entries for this regime.")
        }

        println("  Top 5 ranked configurations:")
        summary.ranked.take(5).forEachIndexed { index, entry ->
            println("    Rank ${index + 1}: diff=${entry.diff}, tokens=${entry.tokens}, settings=${entry.settings}")
        }

        if (summary.bestAboveTarget != null) {
            println(
                "  Closest over $targetTokens: tokens=${summary.bestAboveTarget.tokens}, " +
                    "diff=${summary.bestAboveTarget.diff}, settings=${summary.bestAboveTarget.settings}"
            )
        } else {
            println("  No configuration produces tokens above $targetTokens.")
        }
    }
}
