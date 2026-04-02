package bedrockPipe

import com.TTT.Util.semanticCompress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemanticCompressionLegendKeyingTest
{
    @Test
    fun semanticCompressionTripsLegendAndKeysExpectedProperNouns()
    {
        val input = buildString {
            repeat(6) {
                append("Alice Johnson reviewed the launch notes for Project Atlas with Bob Smith. ")
                append("Project Atlas needed Alice Johnson to confirm the launch notes before Bob Smith sent the update. ")
            }
        }.trim()

        val result = semanticCompress(input)

        assertTrue(result.legend.isNotBlank(), "The compressor should generate a legend for repeated proper nouns")
        assertTrue(result.legend.startsWith("Legend:"), "The legend should use the expected header")
        assertTrue(result.legendMap.isNotEmpty(), "The legend map should contain decoded proper nouns")
        assertEquals("Alice Johnson", result.legendMap["AA"], "AA should decode to the first repeated proper noun")
        assertEquals("Project Atlas", result.legendMap["AB"], "AB should decode to the second repeated proper noun")
        assertTrue(
            result.legend.contains("AA: Alice Johnson"),
            "The formatted legend should include the Alice Johnson mapping"
        )
        assertTrue(
            result.legend.contains("AB: Project Atlas"),
            "The formatted legend should include the Project Atlas mapping"
        )
        assertTrue(
            result.legendMap.values.containsAll(setOf("Alice Johnson", "Project Atlas")),
            "The legend map should contain the expected proper nouns"
        )
    }

    @Test
    fun semanticCompressionKeysOnlyTrueProperNounsNotOrdinaryTitleCasedPhrases()
    {
        val input = buildString {
            repeat(6) {
                append("Ben Mendelson met Alice Johnson near the Dark Blue Suit display at Project Atlas. ")
                append("Alice Johnson reviewed the Dark Blue Suit notes for Project Atlas while Ben Mendelson watched. ")
            }
        }.trim()

        val result = semanticCompress(input)
        val keyedPhrases = result.legendMap.values.toSet()
        val expectedProperNouns = setOf(
            "Alice Johnson",
            "Project Atlas"
        )

        assertTrue(
            expectedProperNouns.all { it in keyedPhrases },
            "The compressor should key the repeated proper nouns"
        )
        assertFalse(
            keyedPhrases.contains("Dark Blue Suit"),
            "Ordinary title-cased phrases should not be treated as proper nouns"
        )
        assertTrue(
            keyedPhrases.all { it in expectedProperNouns },
            "Only true proper nouns should appear in the legend: $keyedPhrases"
        )
    }

    @Test
    fun semanticCompressionDoesNotKeySparseProperNounMentionsInChapterStyleNarrative()
    {
        val input = """
            It was a hot summer day in late July when I first met Ben Mendelson. He had come to the office early, wearing a dark blue suit. He had a briefcase in his hand. He smiled at me and asked if he could borrow a pen. I handed him one. Then he began to write.
                        He wrote the date and time, and then the name of a book, and then he wrote the word 'lover', and then he wrote the words 'love' and 'hate'. Then he wrote the names of his parents and his siblings and his grandparents, and then he wrote the names of his friends. I watched him write the names and then I went to get another pen and paper so he could keep going. But he stopped writing.
                        "Is something wrong?" I asked him.
                        "No, nothing," he said. "Just wanted to make sure I had everyone."

                        We sat together on the couch. I tried to read the book he had written down. It was called The Great American Novel. I thought it might be interesting, but the title was so long that I couldn't find the end of it. I started reading it anyway, and I found it hard to concentrate on the story because the author kept repeating himself. I thought it was strange that he would repeat himself so much, but then I realized he was doing it on purpose. He repeated the same lines three times each in different places in the book. He repeated the same sentences and the same phrases. He repeated the same words.
                        "…he was born in a small town in Maine. He grew up in a house that had no electricity and no running water. His father was a drunk and his mother died giving birth to his youngest brother."
                        Then it took a turn towards insanity.
                        "Benjamin Mendelson was born in a small hospital in Portland Maine on October 12th 1952… Ben was raised by his loving family until he left home at seventeen years old… Ben spent most of his life living in poverty… Ben's father died young… Ben's mother died when she gave birth to her youngest child… Ben has always loved books… Ben loves dogs… Ben has two brothers named Larry and Richard… Ben loves two women named Lila and Jane… Ben has two children named Sam and Hannah… Ben has two cats named Mr. Whiskers and Spot…"

                        There were so many things wrong with this book that I didn't understand what was happening until later that night when I finally finished reading it all. The author had described himself as Benjamin Mendelson but then he also claimed to be Benjamin Mendelson's father. He wrote that his father had died when he was born: "My father died when I was born." However, his father had actually died twenty-nine years earlier when Ben was only six months old: "My father died when I was six months old."
                        He wrote that he had two brothers, but his father had four. And he wrote that he had a mother who had died, but his mother had actually lived until Ben was forty-five years old.
                        And the worst thing of all was that the author had written that he was a writer. That wasn't true at all. He was an accountant, and he worked at the same firm as me. I had been working there for eight years and he'd been there since the year after I graduated college.

                        "What did you think?" Ben asked me when he came back to work the following Monday.
                        I told him I hadn't finished the book yet. He laughed.
                        "You're funny," he said. "You'll finish it tonight. You'll see."
                        When I finished it, I felt sick. I thought I was going to throw up. I couldn't believe that someone would do that to his own father. When Ben came back from lunch an hour later, we sat together on my couch again and talked about what we were going to do next. We decided we should tell someone about this book, but we were afraid to talk about it in front of anyone else at work without knowing exactly what we were talking about first. We worried that maybe someone would accuse us of being crazy. So we waited until we knew we had a witness.

                        "I've been thinking about the names," Ben said. "Why did you ask me to write down all the names of my family members? I can't remember a lot of them."
                        "I don't know," I said. "Maybe because we needed to be sure that everyone in the book was real."
                        Ben nodded and smiled, but then he got serious.
                        "So how are we going to prove this?"
                        I looked at him and then I pointed at myself and then I pointed at Ben and then I pointed at the book.
                        "I think the answer is obvious," I said.
                        Ben nodded, and we both smiled.
        """.trimIndent()

        val result = semanticCompress(input)
        val keyedPhrases = result.legendMap.values.toSet()

        assertTrue(
            keyedPhrases.isEmpty(),
            "This chapter-style narrative does not repeat any full proper noun enough to trigger legend keys: $keyedPhrases"
        )
    }

    @Test
    fun semanticCompressionRejectsOrdinaryTitleCasedPhraseInChapterStyleNarrative()
    {
        val input = """
            It was a hot summer day in late July when I first met Ben Mendelson. He had come to the office early, wearing a dark blue suit. He had a briefcase in his hand. He smiled at me and asked if he could borrow a pen. I handed him one. Then he began to write.
            He wrote the date and time, and then the name of a book, and then he wrote the word 'lover', and then he wrote the words 'love' and 'hate'. Then he wrote the names of his parents and his siblings and his grandparents, and then he wrote the names of his friends. I watched him write the names and then I went to get another pen and paper so he could keep going. But he stopped writing.
            "Is something wrong?" I asked him.
            "No, nothing," he said. "Just wanted to make sure I had everyone."

            We sat together on the couch. I tried to read the book he had written down. It was called The Great American Novel. I thought it might be interesting, but the title was so long that I couldn't find the end of it. I started reading it anyway, and I found it hard to concentrate on the story because the author kept repeating himself. I thought it was strange that he would repeat himself so much, but then I realized he was doing it on purpose. He repeated the same lines three times each in different places in the book. He repeated the same sentences and the same phrases. He repeated the same words.
            He wrote the Dark Blue Suit display into the margins. He wrote the Dark Blue Suit display again in the next paragraph. He wrote the Dark Blue Suit display a third time when he got to the end of the page. He wrote the Dark Blue Suit display four more times because he thought it sounded important.
        """.trimIndent()

        val result = semanticCompress(input)
        val keyedPhrases = result.legendMap.values.toSet()
        assertFalse(
            keyedPhrases.contains("Dark Blue Suit"),
            "Ordinary title-cased phrases should not be treated as proper nouns"
        )
    }
}
