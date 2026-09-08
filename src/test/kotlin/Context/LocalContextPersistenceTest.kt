package com.TTT.Context

import com.TTT.Context.Persistence.LocalContextPersistence
import com.TTT.Context.Persistence.SavedPageMutationResult
import com.TTT.Context.Persistence.SavedPageReadResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Behavior tests for the local saved-document administration facade. */
class LocalContextPersistenceTest
{
    private lateinit var root: File
    private lateinit var persistence: LocalContextPersistence

    @Before
    fun setUp()
    {
        root = Files.createTempDirectory("local-context-persistence").toFile()
        persistence = LocalContextPersistence(root.toPath())
    }

    @After
    fun tearDown()
    {
        root.deleteRecursively()
    }

    @Test
    fun inventoryIncludesUnloadedNestedBankFilesAndExcludesSidecars()
    {
        val nested = File(root, "nested/page.bank")
        nested.parentFile.mkdirs()
        nested.writeText("{\"contextElements\":[\"hello\"]}")
        File(root, "nested/page.bank.lck").writeText("")
        File(root, "notes.json").writeText("{}")

        val pages = runBlocking { persistence.listSavedPages() }

        assertEquals(listOf("nested/page.bank"), pages.map { it.relativePath })
        assertTrue(pages.single().sizeBytes > 0)
        assertTrue(pages.single().revision.isNotBlank())
    }

    @Test
    fun inventorySupportsFilteredMetadataPages()
    {
        File(root, "first.bank").writeText("{}")
        File(root, "nested/second.bank").also {
            it.parentFile.mkdirs()
            it.writeText("{}")
        }

        val page = runBlocking { persistence.listSavedPagesPage(query = "second", pageSize = 1) }

        assertEquals(1, page.total)
        assertEquals(listOf("nested/second.bank"), page.pages.map { it.relativePath })
        assertEquals(null, page.nextPage)
    }

    @Test
    fun replacementUsesRevisionAndPreservesOriginalOnConflict()
    {
        val page = File(root, "page.bank")
        page.writeText("{\"contextElements\":[\"before\"]}")
        val original = assertIs<SavedPageReadResult.Success>(runBlocking { persistence.readSavedPage("page.bank") }).document

        val success = runBlocking { persistence.replaceSavedPage("page.bank", original.revision, "{\"contextElements\":[\"after\"]}") }
        assertIs<SavedPageMutationResult.Success>(success)
        assertEquals("{\"contextElements\":[\"after\"]}", page.readText())

        val conflict = runBlocking { persistence.replaceSavedPage("page.bank", original.revision, "{\"contextElements\":[\"stale\"]}") }
        assertIs<SavedPageMutationResult.RevisionConflict>(conflict)
        assertEquals("{\"contextElements\":[\"after\"]}", page.readText())
    }

    @Test
    fun nestedReplacementAndDeletionStayWithinThePinnedRoot()
    {
        val page = File(root, "nested/page.bank")
        page.parentFile.mkdirs()
        page.writeText("{\"contextElements\":[\"before\"]}")
        val original = assertIs<SavedPageReadResult.Success>(runBlocking { persistence.readSavedPage("nested/page.bank") }).document

        val replacement = runBlocking {
            persistence.replaceSavedPage("nested/page.bank", original.revision, "{\"contextElements\":[\"after\"]}")
        }
        assertIs<SavedPageMutationResult.Success>(replacement)
        assertEquals("{\"contextElements\":[\"after\"]}", page.readText())

        val current = assertIs<SavedPageReadResult.Success>(runBlocking { persistence.readSavedPage("nested/page.bank") }).document
        val deletion = runBlocking { persistence.deleteSavedPage("nested/page.bank", current.revision) }
        assertIs<SavedPageMutationResult.Success>(deletion)
        assertTrue(!page.exists())
        assertTrue(!root.resolve("outside.bank").exists())
    }

    @Test
    fun traversalAndMalformedDocumentsAreRejected()
    {
        assertIs<SavedPageReadResult.Invalid>(runBlocking { persistence.readSavedPage("../outside.bank") })
        val alias = File(root, "page.bank")
        alias.writeText("{}")
        assertIs<SavedPageReadResult.Invalid>(runBlocking { persistence.readSavedPage("nested/../page.bank") })
        val result = runBlocking { persistence.replaceSavedPage("../outside.bank", "missing", "{}") }
        assertIs<SavedPageMutationResult.Invalid>(result)
        val aliasReplacement = runBlocking { persistence.replaceSavedPage("nested/../page.bank", "missing", "{}") }
        assertIs<SavedPageMutationResult.Invalid>(aliasReplacement)
        val aliasDeletion = runBlocking { persistence.deleteSavedPage("nested/../page.bank", "missing") }
        assertIs<SavedPageMutationResult.Invalid>(aliasDeletion)

        val page = File(root, "broken.bank")
        page.writeText("not-json")
        val document = assertIs<SavedPageReadResult.Invalid>(runBlocking { persistence.readSavedPage("broken.bank") }).document!!
        assertEquals("not-json", document.rawJson)
        val invalid = runBlocking { persistence.replaceSavedPage("broken.bank", document.revision, "{broken") }
        assertIs<SavedPageMutationResult.Invalid>(invalid)
        assertEquals("not-json", page.readText())
    }

    @Test
    fun deletionRequiresCurrentRevisionAndLeavesNoBackup()
    {
        val page = File(root, "delete-me.bank")
        page.writeText("{}")
        val document = assertIs<SavedPageReadResult.Success>(runBlocking { persistence.readSavedPage("delete-me.bank") }).document
        val deleted = runBlocking { persistence.deleteSavedPage("delete-me.bank", document.revision) }

        assertIs<SavedPageMutationResult.Success>(deleted)
        assertTrue(!page.exists())
        assertTrue(root.walkTopDown().none { it.name.endsWith(".bak") || it.name.endsWith(".tmp") })
    }
}
