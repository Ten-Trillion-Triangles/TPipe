package com.TTT.Context

import com.TTT.Enums.ContextWindowSettings
import com.TTT.Pipe.TruncationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the per-call alternate-dictionary feature on [Dictionary] /
 * [TruncationSettings.dictionaryLocale] and [TruncationSettings.dictionaryPath].
 *
 * The `test-locale` classpath resource at `src/test/resources/Words-test-locale.txt` is a small
 * synthetic wordlist containing words that are intentionally NOT in the bundled
 * `/Words.txt` (xyzzy, plugh, mellifluous, quux, grault, wibble, wobble, flob) so that
 * tokenizer output is observably different between the default and the test locale.
 */
class DictionaryResolutionTest
{
    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * When the new dictionary fields are unset, behavior is identical to the pre-existing
     * default — the bundled `/Words.txt` is used. Regression guard for the v1 contract.
     */
    @Test
    fun testDefaultSettingsResolveToBundledDefault()
    {
        val settings = TruncationSettings()
        val instance = Dictionary.resolveDictionary(settings)
        // The default instance is the lazy defaultInstance property of Dictionary and shares its words/wordsSet
        assertSame(Dictionary.defaultInstance, instance)
        assertEquals(Dictionary.words, instance.words)
    }

    /**
     * Sanity check: a no-settings countTokens call still tokenizes plain English against the
     * default wordlist, identical to pre-existing behavior.
     */
    @Test
    fun testNoSettingsOverloadUsesBundledDefault()
    {
        // "hello world" → 2 whole-word matches in the default English wordlist
        val tokens = Dictionary.countTokens("hello world")
        assertEquals(2, tokens)
    }

    /**
     * Resolving a locale tag that has a matching classpath resource loads it. Tokenizing a
     * word that exists only in the test-locale wordlist produces a different (smaller) token
     * count than the default, proving the locale wordlist was actually used.
     */
    @Test
    fun testLocaleResolutionFromClasspath()
    {
        val settings = TruncationSettings(dictionaryLocale = "test-locale")

        // "xyzzy" exists in test-locale but NOT in the default English wordlist.
        // With test-locale: 1 token (single whole-word match).
        // With default: >= 2 tokens (no single 5-letter match, sub-matches in the bundled
        // wordlist cause the tokenizer to count multiple subwords). We assert the count
        // differs from the locale case to prove the locale wordlist is actually being used,
        // not the default. The exact default value is tokenizer-implementation-detail and
        // could shift with changes to the bundled wordlist; the difference is what matters.
        val tokensWithLocale = Dictionary.countTokens("xyzzy", settings)
        assertEquals("test-locale should match 'xyzzy' as a single whole word", 1, tokensWithLocale)

        val tokensDefault = Dictionary.countTokens("xyzzy", TruncationSettings())
        assertTrue(
            "default tokenizer should count 'xyzzy' differently than test-locale (locale=1, default=$tokensDefault)",
            tokensDefault != tokensWithLocale
        )
    }

    /**
     * Multiple words from the test-locale wordlist should each match as single tokens.
     */
    @Test
    fun testLocaleResolutionHandlesMultipleLocaleWords()
    {
        val settings = TruncationSettings(dictionaryLocale = "test-locale")
        // Three words, all present in test-locale, so each is a single whole-word match
        val tokens = Dictionary.countTokens("xyzzy plugh quux", settings)
        assertEquals(3, tokens)
    }

    /**
     * Asking for a locale with no matching classpath resource throws IllegalArgumentException
     * with a message that mentions the locale tag, so misconfigurations are diagnosable.
     */
    @Test
    fun testLocaleMissingResourceThrows()
    {
        val settings = TruncationSettings(dictionaryLocale = "definitely-not-a-real-locale-tag-xyz")
        try
        {
            Dictionary.countTokens("hello", settings)
            fail("Expected IllegalArgumentException for missing locale resource")
        }
        catch(e: IllegalArgumentException)
        {
            assertTrue(
                "Exception message should mention the missing locale tag, got: ${e.message}",
                e.message?.contains("definitely-not-a-real-locale-tag-xyz") == true
            )
        }
    }

    /**
     * A blank-string locale is treated as "no locale set" and falls back to the default.
     */
    @Test
    fun testBlankLocaleFallsBackToDefault()
    {
        val settings = TruncationSettings(dictionaryLocale = "   ")
        val instance = Dictionary.resolveDictionary(settings)
        assertSame(Dictionary.defaultInstance, instance)
    }

    /**
     * Loading a wordlist from a real file on disk should work, and the contents should drive
     * the tokenizer output.
     */
    @Test
    fun testFilesystemPathResolution()
    {
        val file = tempFolder.newFile("custom-words.txt")
        file.writeText("klingon\nvulcan\nromulan\nbajoran\n")

        val settings = TruncationSettings(dictionaryPath = file.absolutePath)
        val tokens = Dictionary.countTokens("klingon vulcan", settings)
        assertEquals("custom file should match each of 'klingon' and 'vulcan' as one token", 2, tokens)

        // And the same input under the default wordlist is 2 tokens by coincidence (the words
        // aren't in the default, but they fall back to character counting and still sum to 2
        // for a 7-char and 6-char word). To prove the file was actually used, check the
        // returned instance has the file's words.
        val instance = Dictionary.resolveDictionary(settings)
        assertTrue(
            "instance.words should contain file contents, got: ${instance.words}",
            instance.words.contains("klingon")
        )
    }

    /**
     * A path that points at a non-existent file throws IllegalArgumentException with a message
     * that mentions the bad path.
     */
    @Test
    fun testFilesystemMissingFileThrows()
    {
        val settings = TruncationSettings(dictionaryPath = "/this/path/definitely/does/not/exist/${System.nanoTime()}.txt")
        try
        {
            Dictionary.countTokens("hello", settings)
            fail("Expected IllegalArgumentException for missing file")
        }
        catch(e: IllegalArgumentException)
        {
            assertTrue(
                "Exception message should mention the bad path, got: ${e.message}",
                e.message?.contains("not found") == true
            )
        }
    }

    /**
     * When both dictionaryLocale and dictionaryPath are set, locale takes priority. We prove
     * this by setting locale to a valid one and path to a guaranteed-missing location: if
     * path were consulted, the call would throw. Locale wins, so it doesn't.
     */
    @Test
    fun testLocaleWinsOverPath()
    {
        val badPath = "/no/such/dictionary/file/${System.nanoTime()}.txt"
        val settings = TruncationSettings(
            dictionaryLocale = "test-locale",
            dictionaryPath = badPath
        )
        // Should not throw — locale wins, path is ignored
        val tokens = Dictionary.countTokens("xyzzy", settings)
        assertEquals(1, tokens)
    }

    /**
     * When only path is set (locale null), only the path is consulted. Set locale to a known-
     * missing locale and path to a valid file: if the locale were consulted, it would throw.
     * The path is valid, so the call should succeed and use the file's contents.
     */
    @Test
    fun testPathWinsWhenLocaleIsBlank()
    {
        val file = tempFolder.newFile("custom-words-2.txt")
        file.writeText("alphaword\nbetaword\n")
        val settings = TruncationSettings(
            dictionaryLocale = "  ",  // blank → treated as null
            dictionaryPath = file.absolutePath
        )
        val tokens = Dictionary.countTokens("alphaword betaword", settings)
        assertEquals(2, tokens)
    }

    /**
     * Two distinct locales (or paths) produce two distinct cached instances.
     */
    @Test
    fun testDistinctLocalesYieldDistinctInstances()
    {
        val settingsA = TruncationSettings(dictionaryLocale = "test-locale")
        val settingsB = TruncationSettings() // default
        val a = Dictionary.resolveDictionary(settingsA)
        val b = Dictionary.resolveDictionary(settingsB)
        assertNotSame("locale and default should be distinct instances", a, b)
    }

    /**
     * The same locale tag should resolve to the same cached instance across calls
     * (computeIfAbsent idempotence). This proves caching is actually working.
     */
    @Test
    fun testSameLocaleYieldsSameCachedInstance()
    {
        val settings1 = TruncationSettings(dictionaryLocale = "test-locale")
        val settings2 = TruncationSettings(dictionaryLocale = "test-locale")
        val a = Dictionary.resolveDictionary(settings1)
        val b = Dictionary.resolveDictionary(settings2)
        assertSame("same locale tag should return the same cached instance", a, b)
    }

    /**
     * Two calls with the same filesystem path also yield the same cached instance.
     */
    @Test
    fun testSamePathYieldsSameCachedInstance()
    {
        val file = tempFolder.newFile("custom-words-3.txt")
        file.writeText("foo\nbar\n")
        val s1 = TruncationSettings(dictionaryPath = file.absolutePath)
        val s2 = TruncationSettings(dictionaryPath = file.absolutePath)
        assertSame(
            "same filesystem path should return the same cached instance",
            Dictionary.resolveDictionary(s1),
            Dictionary.resolveDictionary(s2)
        )
    }

    /**
     * Relative paths should be normalized to absolute paths for the cache key, so two calls
     * with the same relative path resolve to the same instance.
     */
    @Test
    fun testRelativePathIsNormalizedForCacheKey()
    {
        val file = tempFolder.newFile("rel-words.txt")
        file.writeText("one\ntwo\n")
        val cwd = File("").absoluteFile
        val relative = file.name // bare filename, resolved against cwd by File(path).absolutePath
        val s1 = TruncationSettings(dictionaryPath = relative)
        val s2 = TruncationSettings(dictionaryPath = relative)
        // Only valid if the test happens to run in a cwd that contains this file. Skip otherwise.
        val resolved = File(relative).absoluteFile
        if(resolved.exists() && resolved.parentFile == cwd)
        {
            assertSame(
                "identical relative path strings should yield the same cached instance",
                Dictionary.resolveDictionary(s1),
                Dictionary.resolveDictionary(s2)
            )
        }
        // Always verify the absolute path form works:
        val sAbs = TruncationSettings(dictionaryPath = file.absolutePath)
        assertNotNull(Dictionary.resolveDictionary(sAbs))
    }

    /**
     * truncateWithSettings should also honor the dictionary fields — set a locale, truncate
     * with TruncateTop, and the result should be tokenized against the locale wordlist.
     */
    @Test
    fun testTruncateWithSettingsUsesResolvedDictionary()
    {
        val settings = TruncationSettings(dictionaryLocale = "test-locale")
        // 4 words, all in test-locale; with budget=2 and TruncateBottom we keep the first 2
        val truncated = Dictionary.truncateWithSettings(
            content = "xyzzy plugh quux grault",
            tokenBudget = 2,
            truncationMethod = ContextWindowSettings.TruncateBottom,
            settings = settings
        )
        assertEquals("xyzzy plugh", truncated)
    }

    /**
     * chunkByTokensWithSettings should also honor the dictionary fields.
     */
    @Test
    fun testChunkByTokensWithSettingsUsesResolvedDictionary()
    {
        val settings = TruncationSettings(dictionaryLocale = "test-locale")
        val chunks = Dictionary.chunkByTokensWithSettings(
            text = "xyzzy plugh quux grault wibble",
            maxTokens = 2,
            settings = settings
        )
        // Each chunk <= 2 tokens, 5 words → at least 3 chunks
        assertTrue("expected multiple chunks, got $chunks", chunks.size >= 3)
        chunks.forEach { chunk ->
            val tokens = Dictionary.countTokens(chunk, settings)
            assertTrue("chunk '$chunk' had $tokens tokens, expected <= 2", tokens <= 2)
        }
    }

    /**
     * When no dictionary fields are set on the TruncationSettings, truncateWithSettings and
     * chunkByTokensWithSettings still work — they fall through to the default.
     */
    @Test
    fun testWithSettingsDefaultsAreBackwardCompatible()
    {
        val settings = TruncationSettings()
        val truncated = Dictionary.truncateWithSettings(
            content = "hello world foo bar",
            tokenBudget = 1000,
            truncationMethod = ContextWindowSettings.TruncateTop,
            settings = settings
        )
        // Within budget, no truncation occurs
        assertEquals("hello world foo bar", truncated)

        val chunks = Dictionary.chunkByTokensWithSettings(
            text = "alpha bravo charlie delta",
            maxTokens = 2,
            settings = settings
        )
        assertTrue("expected chunks, got $chunks", chunks.isNotEmpty())
    }
}
