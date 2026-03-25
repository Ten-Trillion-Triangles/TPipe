import com.TTT.Util.semanticCompress
import com.TTT.Util.SemanticCompressionResult

fun assert(condition: Boolean, message: String) {
    if (!condition) {
        throw Exception("Assertion Failed: $message")
    }
}

println("Starting Empirical Validation of Semantic Compression Fixes (Final Spec)...")

// 1. Validate Quote Preservation (Smart & Straight)
println("Checking Quote Preservation...")
val quoteInput = "He said “Smart Quote” and \"Straight Quote\"."
val quoteResult = semanticCompress(quoteInput)
assert(quoteResult.compressedText.contains("“Smart Quote”"), "Smart quotes and their content should be preserved verbatim")
assert(quoteResult.compressedText.contains("\"Straight Quote\""), "Straight quotes and their content should be preserved verbatim")

// 2. Validate Universal Pronoun Protection (Retention in text)
println("Checking Pronoun Protection...")
val pronounInput = "I and we should see if he and she are there."
val pronounResult = semanticCompress(pronounInput)
val pronounWords = pronounResult.compressedText.lowercase().split(" ")
assert(pronounWords.contains("i"), "Pronoun 'I' should be preserved")
assert(pronounWords.contains("we"), "Pronoun 'we' should be preserved")
assert(pronounWords.contains("he"), "Pronoun 'he' should be preserved")
assert(pronounWords.contains("she"), "Pronoun 'she' should be preserved")

// 3. Validate Legend Integrity (No Pronouns in Legend)
println("Checking Legend Integrity...")
// "He" appears many times capitalized at start of sentence.
val legendInput = (0 until 20).joinToString(". ") { "He repeated himself" } + " Benjamin Mendelson Benjamin Mendelson"
val legendResult = semanticCompress(legendInput)
assert(!legendResult.legend.lowercase().contains("he"), "Pronouns should never be keyed in the legend even if frequent")
// Benjamin Mendelson is 2 tokens. Needs 6 to key.
val bm_input = (0 until 6).joinToString(". ") { "I saw Benjamin Mendelson" }
val bm_result = semanticCompress(bm_input)
assert(bm_result.legend.contains("Benjamin Mendelson"), "Proper nouns (2 tokens) should key at 6 occurrences")

// 4. Validate Punctuation Rules (Preserve : and ", strip others)
println("Checking Punctuation Rules...")
val punctInput = "Header: \"The message!\" (Extra)!"
val punctResult = semanticCompress(punctInput)
assert(punctResult.compressedText.contains(":"), "Colons should be preserved")
assert(punctResult.compressedText.contains("\""), "Quotes should be preserved")
assert(punctResult.compressedText.contains("!\""), "Exclamation INSIDE quotes should be preserved")
assert(!punctResult.compressedText.endsWith("!"), "Exclamation OUTSIDE quotes should be stripped")
assert(!punctResult.compressedText.contains("("), "Parentheses should be stripped")

// 5. Validate Refined Legend Thresholds (Final Spec Table)
println("Checking Legend Thresholds...")
// 1 token: No replace.
val t1_input = (0 until 20).joinToString(". ") { "I saw Alice" }
val t1 = semanticCompress(t1_input)
assert(t1.legend.isEmpty(), "1-token nouns should never be keyed")

// 2 tokens: 6 times
val t2_fail = (0 until 5).joinToString(". ") { "I saw Alice Johnson" }
assert(semanticCompress(t2_fail).legend.isEmpty(), "2-token nouns should not key at 5 occurrences")
val t2_pass = (0 until 6).joinToString(". ") { "I saw Alice Johnson" }
assert(semanticCompress(t2_pass).legend.contains("Alice Johnson"), "2-token nouns should key at 6 occurrences")

// 3 tokens: 4 times
val t3_fail = (0 until 3).joinToString(". ") { "I saw Alpha Beta Gamma" }
assert(semanticCompress(t3_fail).legend.isEmpty(), "3-token nouns should not key at 3 occurrences")
val t3_pass = (0 until 4).joinToString(". ") { "I saw Alpha Beta Gamma" }
assert(semanticCompress(t3_pass).legend.contains("Alpha Beta Gamma"), "3-token nouns should key at 4 occurrences")

// 4-5 tokens: 3 times
val t4_fail = (0 until 2).joinToString(". ") { "I saw Alpha Beta Gamma Delta" }
assert(semanticCompress(t4_fail).legend.isEmpty(), "4-token nouns should not key at 2 occurrences")
val t4_pass = (0 until 3).joinToString(". ") { "I saw Alpha Beta Gamma Delta" }
assert(semanticCompress(t4_pass).legend.contains("Alpha Beta Gamma Delta"), "4-token nouns should key at 3 occurrences")

// 6+ tokens: 2 times
val t6_pass = (0 until 2).joinToString(". ") { "I saw One Two Three Four Five Six" }
assert(semanticCompress(t6_pass).legend.contains("One Two Three Four Five Six"), "6-token nouns should key at 2 occurrences")

// 6. Validate Sentence Start Safeguard
println("Checking Sentence Start Safeguard...")
// "In July" appears 10 times, but ONLY at start of sentence.
val ss_input = (0 until 10).joinToString(". ") { "In July it was hot" }
val ss_result = semanticCompress(ss_input)
assert(!ss_result.legend.contains("In July"), "'In July' should not be keyed if it only appears at start of sentence")

// 7. Validate Lexicon Update (Check a word from the new list)
println("Checking Lexicon Updates...")
val lexiconInput = "He went atop the building albeit safely."
val lexiconResult = semanticCompress(lexiconInput)
assert(!lexiconResult.compressedText.contains("atop"), "New stop word 'atop' should be stripped")
assert(!lexiconResult.compressedText.contains("albeit"), "New stop word 'albeit' should be stripped")

println("Validation COMPLETE. All systems performing within spec.")
