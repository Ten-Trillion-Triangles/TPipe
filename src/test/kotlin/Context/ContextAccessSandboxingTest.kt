package com.TTT.Context

import com.TTT.Config.TPipeConfig
import com.TTT.Context.Persistence.ContextPersistenceBackend
import com.TTT.Context.Persistence.ContextResourceMetadataBackend
import com.TTT.Pipe.DummyPipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.ConcurrentHashMap
import com.TTT.Util.serialize

class ContextAccessSandboxingTest
{
    private val privateKey = "sandbox-private-page"
    private val otherKey = "sandbox-other-page"
    private val legacyKey = "sandbox-legacy-page"
    private val todoKey = "sandbox-todo"
    private val remoteKey = "sandbox-remote-page"

    @Before
    fun setup() = runBlocking {
        cleanup()
    }

    @After
    fun tearDown() = runBlocking {
        cleanup()
    }

    private suspend fun cleanup()
    {
        ContextBank.clearRemotePersistenceBackend()
        TPipeConfig.remoteMemoryEnabled = false
        TPipeConfig.useRemoteMemoryGlobally = false
        ContextBank.configureCachePolicySuspend(CacheConfig())
        listOf(privateKey, otherKey, legacyKey, todoKey, remoteKey).forEach { key ->
            ContextBank.deleteContextWindowSuspend(key, skipRemote = true)
            ContextBank.deleteResourceMetadataSuspend(key, ContextResourceKind.CONTEXT_WINDOW)
            ContextBank.deleteTodoListSuspend(key, skipRemote = true)
            ContextBank.deleteResourceMetadataSuspend(key, ContextResourceKind.TODO_LIST)
        }
    }

    private suspend fun seedProtectedPage(key: String, resourceId: String, value: String)
    {
        ContextBank.emplaceSuspend(
            key,
            ContextWindow().apply { contextElements.add(value) },
            StorageMode.MEMORY_ONLY,
            skipRemote = true
        )
        ContextBank.registerResourceMetadataSuspend(
            key,
            ContextResourceMetadata(
                resourceKind = ContextResourceKind.CONTEXT_WINDOW,
                resourceId = resourceId,
                boundaryId = "sandbox-boundary"
            )
        )
    }

    private fun scopeFor(vararg resourceIds: String): ContextAccessScope
    {
        val selectors = resourceIds.map { ContextResourceSelector.Resource(it) }.toSet()
        return ContextAccess.issueRootScope(
            principal = ExecutionPrincipal("sandbox-agent"),
            rights = ContextAccessRights(
                readableResources = selectors,
                writableResources = selectors,
                creatableResources = selectors,
                deletableResources = selectors,
                enumerableResources = selectors,
                delegable = true
            )
        )
    }

    @Test
    fun unscopedCallRetainsLegacyAccessToProtectedPage() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")

        val loaded = ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true)

        assertEquals(listOf("private value"), loaded.contextElements)
    }

    @Test
    fun protectedDirectReadThrowsTypedDenialForUnauthorizedScope() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")

        val exception = assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(scopeFor("other-resource")) {
                ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true)
            }
        }

        assertEquals(ContextAccessOperation.READ, exception.operation)
        assertEquals(ContextResourceKind.CONTEXT_WINDOW, exception.resourceKind)
    }

    @Test
    fun missingMetadataRemainsLegacySharedInsideSecuredScope() = runBlocking {
        ContextBank.emplaceSuspend(
            legacyKey,
            ContextWindow().apply { contextElements.add("legacy value") },
            StorageMode.MEMORY_ONLY,
            skipRemote = true
        )

        val loaded = ContextAccess.withCoroutineScope(scopeFor("unrelated-resource")) {
            ContextBank.getContextFromBankSuspend(legacyKey, skipRemote = true)
        }

        assertEquals(listOf("legacy value"), loaded.contextElements)
    }

    @Test
    fun nestedScopeCannotBroadenAmbientAuthority() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        seedProtectedPage(otherKey, "other-resource", "other value")

        val outerScope = scopeFor("private-resource")
        val broaderScope = scopeFor("other-resource")

        ContextAccess.withCoroutineScope(outerScope) {
            assertEquals(
                listOf("private value"),
                ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true).contextElements
            )

            assertFailsWith<ContextAccessDeniedException> {
                ContextAccess.withCoroutineScope(broaderScope) {
                    ContextBank.getContextFromBankSuspend(otherKey, skipRemote = true)
                }
            }
        }

        Unit
    }

    @Test
    fun scopeRestoresAmbientAuthorityAndRootCopiesRights() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        seedProtectedPage(otherKey, "other-resource", "other value")
        val readableResources = mutableSetOf<ContextResourceSelector>(
            ContextResourceSelector.Resource("private-resource")
        )
        val scope = ContextAccess.issueRootScope(
            ExecutionPrincipal("immutable-root"),
            ContextAccessRights(readableResources = readableResources)
        )
        readableResources.clear()
        readableResources.add(ContextResourceSelector.Resource("other-resource"))

        assertNull(ContextAccess.currentScope())
        ContextAccess.withCoroutineScope(scope) {
            assertEquals("immutable-root", ContextAccess.currentScope()?.principal?.id)
            assertEquals(
                listOf("private value"),
                ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true).contextElements
            )
            assertFailsWith<ContextAccessDeniedException> {
                ContextBank.getContextFromBankSuspend(otherKey, skipRemote = true)
            }
        }
        assertNull(ContextAccess.currentScope())
    }

    @Test
    fun coroutineScopesRemainIsolatedAcrossDispatchers() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        seedProtectedPage(otherKey, "other-resource", "other value")

        val results = coroutineScope {
            listOf(
                async(Dispatchers.Default) {
                    ContextAccess.withCoroutineScope(scopeFor("private-resource")) {
                        val own = ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true)
                        val denied = assertFailsWith<ContextAccessDeniedException> {
                            ContextBank.getContextFromBankSuspend(otherKey, skipRemote = true)
                        }
                        own.contextElements.single() to denied.operation
                    }
                },
                async(Dispatchers.Default) {
                    ContextAccess.withCoroutineScope(scopeFor("other-resource")) {
                        val own = ContextBank.getContextFromBankSuspend(otherKey, skipRemote = true)
                        val denied = assertFailsWith<ContextAccessDeniedException> {
                            ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true)
                        }
                        own.contextElements.single() to denied.operation
                    }
                }
            ).awaitAll()
        }

        assertEquals(
            setOf("private value" to ContextAccessOperation.READ, "other value" to ContextAccessOperation.READ),
            results.toSet()
        )
    }

    @Test
    fun protectedEnumerationHidesUnauthorizedPageKeys() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        seedProtectedPage(otherKey, "other-resource", "other value")

        val visibleKeys = ContextAccess.withCoroutineScope(scopeFor("private-resource")) {
            ContextBank.getPageKeysSuspend(skipRemote = true)
        }

        assertTrue(visibleKeys.contains(privateKey))
        assertFalse(visibleKeys.contains(otherKey))
    }

    @Test
    fun protectedWriteRequiresWritePermission() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")

        assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(
                ContextAccess.issueRootScope(
                    ExecutionPrincipal("reader"),
                    ContextAccessRights(readableResources = setOf(ContextResourceSelector.Resource("private-resource")))
                )
            ) {
                ContextBank.mutateContextWindowSuspend(privateKey, skipRemote = true) { window ->
                    window.contextElements.add("should not be written")
                }
            }
        }

        assertEquals(
            listOf("private value"),
            ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true).contextElements
        )
    }

    @Test
    fun remoteEnumerationRequiresSomeEnumerationAuthorityBeforeListing() = runBlocking {
        val backend = FakeRemoteBackend().apply {
            contextWindows[remoteKey] = ContextWindow()
        }
        ContextBank.setRemotePersistenceBackend(backend)
        TPipeConfig.useRemoteMemoryGlobally = true

        val readOnlyScope = ContextAccess.issueRootScope(
            ExecutionPrincipal("reader"),
            ContextAccessRights(readableResources = setOf(ContextResourceSelector.Resource("remote-resource")))
        )
        val exception = assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(readOnlyScope) {
                ContextBank.getPageKeysSuspend()
            }
        }

        assertEquals(ContextAccessOperation.ENUMERATE, exception.operation)
        assertEquals(ContextAccessDenialReason.MISSING_PERMISSION, exception.reason)
        assertEquals(0, backend.pageKeyListReads)
    }

    @Test
    fun synchronousEmplaceCannotBypassProtectedWriteCheck() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")

        assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(
                ContextAccess.issueRootScope(
                    ExecutionPrincipal("reader"),
                    ContextAccessRights(readableResources = setOf(ContextResourceSelector.Resource("private-resource")))
                )
            ) {
                ContextBank.emplace(privateKey, ContextWindow(), StorageMode.MEMORY_ONLY, skipRemote = true)
            }
        }

        Unit
    }

    @Test
    fun synchronousReadCannotBypassProtectedReadCheck() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")

        assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(scopeFor("other-resource")) {
                ContextBank.getContextFromBank(privateKey, skipRemote = true)
            }
        }

        Unit
    }

    @Test
    fun protectedMemoryEvictionRequiresDeletePermission() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        val readOnlyScope = ContextAccess.issueRootScope(
            ExecutionPrincipal("reader"),
            ContextAccessRights(readableResources = setOf(ContextResourceSelector.Resource("private-resource")))
        )

        assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(readOnlyScope) {
                ContextBank.evictFromMemory(privateKey)
            }
        }
        assertTrue(ContextBank.contextWindowExistsLocallySuspend(privateKey))
    }

    @Test
    fun protectedTodoMemoryEvictionRequiresDeletePermission() = runBlocking {
        ContextBank.emplaceTodoListSuspend(todoKey, TodoList(), StorageMode.MEMORY_ONLY, skipRemote = true)
        ContextBank.registerResourceMetadataSuspend(
            todoKey,
            ContextResourceMetadata(ContextResourceKind.TODO_LIST, "todo-resource", "sandbox-boundary")
        )
        val readOnlyScope = ContextAccess.issueRootScope(
            ExecutionPrincipal("reader"),
            ContextAccessRights(readableResources = setOf(ContextResourceSelector.Resource("todo-resource")))
        )

        assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(readOnlyScope) {
                ContextBank.evictTodoListFromMemory(todoKey)
            }
        }
        assertTrue(ContextBank.todoListExistsLocallySuspend(todoKey))
    }

    @Test
    fun legacyListPageKeysReturnsSafeResultWhenSecuredRemoteMetadataIsUnavailable() = runBlocking {
        val backend = LegacyRemoteBackend().apply {
            contextWindows[remoteKey] = ContextWindow()
        }
        ContextBank.setRemotePersistenceBackend(backend)
        TPipeConfig.useRemoteMemoryGlobally = true

        val result = MemoryIntrospection.withCoroutineScope(
            MemoryIntrospectionConfig(
                allowedPageKeys = mutableSetOf("*"),
                allowRead = true,
                allowWrite = true,
                allowPageCreation = true
            )
        ) {
            ContextAccess.withCoroutineScope(scopeFor("remote-resource")) {
                MemoryIntrospectionTools.listPageKeys()
            }
        }

        assertTrue(result.isEmpty())
        assertEquals(0, backend.pageKeyListReads)
    }

    @Test
    fun securedWriteDoesNotRetainCallerOwnedMutableWindow() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        val suppliedWindow = ContextWindow().apply { contextElements.add("replacement") }

        ContextAccess.withCoroutineScope(scopeFor("private-resource")) {
            ContextBank.emplaceSuspend(
                privateKey,
                suppliedWindow,
                StorageMode.MEMORY_ONLY,
                skipRemote = true
            )
        }
        suppliedWindow.contextElements.add("caller mutation")

        assertEquals(
            listOf("replacement"),
            ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true).contextElements
        )
    }

    @Test
    fun writeOnlyScopeUpdatesKnownPageWithoutEnumerationAuthority() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        val scope = ContextAccess.issueRootScope(
            ExecutionPrincipal("writer"),
            ContextAccessRights(
                writableResources = setOf(ContextResourceSelector.Resource("private-resource"))
            )
        )

        val result = MemoryIntrospection.withCoroutineScope(
            MemoryIntrospectionConfig(
                allowedPageKeys = mutableSetOf(privateKey),
                allowRead = false,
                allowWrite = true,
                allowPageCreation = false
            )
        ) {
            ContextAccess.withCoroutineScope(scope) {
                MemoryIntrospectionTools.updateLorebookEntry(
                    privateKey,
                    LoreBook().apply {
                        key = "write-only"
                        value = "updated"
                    }
                )
            }
        }

        assertTrue(result)
    }

    @Test
    fun scopedAutomaticCacheEvictionDoesNotRemoveUnauthorizedPages() = runBlocking {
        ContextBank.configureCachePolicySuspend(CacheConfig(maxEntries = 100))
        ContextBank.emplaceSuspend(
            privateKey,
            ContextWindow().apply { contextElements.add("private value") },
            StorageMode.DISK_WITH_CACHE,
            skipRemote = true
        )
        ContextBank.registerResourceMetadataSuspend(
            privateKey,
            ContextResourceMetadata(ContextResourceKind.CONTEXT_WINDOW, "private-resource")
        )
        ContextBank.emplaceSuspend(
            otherKey,
            ContextWindow().apply { contextElements.add("other value") },
            StorageMode.DISK_WITH_CACHE,
            skipRemote = true
        )
        ContextBank.registerResourceMetadataSuspend(
            otherKey,
            ContextResourceMetadata(ContextResourceKind.CONTEXT_WINDOW, "other-resource")
        )
        val writeOnlyScope = ContextAccess.issueRootScope(
            ExecutionPrincipal("writer"),
            ContextAccessRights(
                writableResources = setOf(ContextResourceSelector.Resource("private-resource"))
            )
        )
        ContextAccess.withCoroutineScope(writeOnlyScope) {
            ContextBank.configureCachePolicySuspend(CacheConfig(maxEntries = 1))
            ContextBank.mutateContextWindowSuspend(privateKey) { it.contextElements.add("new value") }
        }

        assertTrue(ContextBank.contextWindowExistsLocallySuspend(privateKey))
        assertTrue(ContextBank.contextWindowExistsLocallySuspend(otherKey))
        assertEquals(2, ContextBank.getCacheStatistics().memoryEntries)
    }

    @Test
    fun scopedClearCacheRetainsProtectedEntriesWithoutDeleteAuthority() = runBlocking {
        ContextBank.configureCachePolicySuspend(CacheConfig(maxEntries = 100))
        seedProtectedPage(privateKey, "private-resource", "private value")
        ContextBank.setStorageMode(privateKey, StorageMode.DISK_WITH_CACHE)
        ContextBank.emplaceSuspend(privateKey, ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true), StorageMode.DISK_WITH_CACHE, skipRemote = true)

        val readOnlyScope = ContextAccess.issueRootScope(
            ExecutionPrincipal("reader"),
            ContextAccessRights(readableResources = setOf(ContextResourceSelector.Resource("private-resource")))
        )
        ContextAccess.withCoroutineScope(readOnlyScope) {
            ContextBank.clearCacheSuspend()
        }

        assertTrue(ContextBank.contextWindowExistsLocallySuspend(privateKey))
    }

    @Test
    fun deniedWriteDoesNotInvokeWritebackFunction() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        var invoked = false
        ContextBank.registerWriteBackFunction(privateKey) { _, _ ->
            invoked = true
            true
        }

        try
        {
            assertFailsWith<ContextAccessDeniedException> {
                ContextAccess.withCoroutineScope(
                    ContextAccess.issueRootScope(
                        ExecutionPrincipal("reader"),
                        ContextAccessRights(readableResources = setOf(ContextResourceSelector.Resource("private-resource")))
                    )
                ) {
                    ContextBank.emplaceSuspend(
                        privateKey,
                        ContextWindow(),
                        StorageMode.MEMORY_ONLY,
                        skipRemote = true
                    )
                }
            }
            assertFalse(invoked)
        }
        finally
        {
            ContextBank.removeWriteBackFunction(privateKey)
        }
    }

    @Test
    fun createPermissionCanCreateAnEnrolledPage() = runBlocking {
        ContextBank.registerResourceMetadataSuspend(
            privateKey,
            ContextResourceMetadata(ContextResourceKind.CONTEXT_WINDOW, "private-resource", "sandbox-boundary")
        )

        val scope = ContextAccess.issueRootScope(
            ExecutionPrincipal("creator"),
            ContextAccessRights(
                creatableResources = setOf(ContextResourceSelector.Resource("private-resource"))
            )
        )
        ContextAccess.withCoroutineScope(scope) {
            ContextBank.emplaceSuspend(
                privateKey,
                ContextWindow().apply { contextElements.add("created value") },
                StorageMode.MEMORY_ONLY,
                skipRemote = true
            )
        }

        assertEquals(
            listOf("created value"),
            ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true).contextElements
        )
    }

    @Test
    fun protectedDeleteRequiresDeletePermission() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")

        assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(
                ContextAccess.issueRootScope(
                    ExecutionPrincipal("writer"),
                    ContextAccessRights(
                        readableResources = setOf(ContextResourceSelector.Resource("private-resource")),
                        writableResources = setOf(ContextResourceSelector.Resource("private-resource"))
                    )
                )
            ) {
                ContextBank.deleteContextWindowSuspend(privateKey, skipRemote = true)
            }
        }

        assertEquals(
            listOf("private value"),
            ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true).contextElements
        )
    }

    @Test
    fun protectedTodoReadAndWriteUseTodoMetadata() = runBlocking {
        ContextBank.emplaceTodoListSuspend(todoKey, TodoList(), StorageMode.MEMORY_ONLY, skipRemote = true)
        ContextBank.registerResourceMetadataSuspend(
            todoKey,
            ContextResourceMetadata(ContextResourceKind.TODO_LIST, "todo-resource", "sandbox-boundary")
        )

        val scope = ContextAccess.issueRootScope(
            ExecutionPrincipal("todo-agent"),
            ContextAccessRights(
                readableResources = setOf(ContextResourceSelector.Resource("todo-resource")),
                writableResources = setOf(ContextResourceSelector.Resource("todo-resource"))
            )
        )

        ContextAccess.withCoroutineScope(scope) {
            ContextBank.getPagedTodoListSuspend(todoKey, skipRemote = true)
            ContextBank.emplaceTodoListSuspend(todoKey, TodoList(), StorageMode.MEMORY_ONLY, skipRemote = true)
        }

        assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(
                ContextAccess.issueRootScope(
                    ExecutionPrincipal("wrong-agent"),
                    ContextAccessRights(readableResources = setOf(ContextResourceSelector.Resource("wrong")))
                )
            ) {
                ContextBank.getPagedTodoListSuspend(todoKey, skipRemote = true)
            }
        }

        Unit
    }

    @Test
    fun securedScopeCannotObtainMutableReferences() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")

        val scope = scopeFor("private-resource")
        assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(scope) {
                ContextBank.getContextFromBankSuspend(privateKey, copy = false, skipRemote = true)
            }
        }
        assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(scope) {
                ContextBank.withContextWindowReferenceSuspend(privateKey, skipRemote = true) { }
            }
        }

        Unit
    }

    @Test
    fun deniedRetrievalDoesNotInvokeRetrievalFunction() = runBlocking {
        var invoked = false
        ContextBank.registerRetrievalFunction(remoteKey) {
            invoked = true
            ContextWindow().apply { contextElements.add("retrieved") }
        }
        ContextBank.registerResourceMetadataSuspend(
            remoteKey,
            ContextResourceMetadata(ContextResourceKind.CONTEXT_WINDOW, "remote-resource", "sandbox-boundary")
        )

        assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(scopeFor("other-resource")) {
                ContextBank.getContextFromBankSuspend(remoteKey, skipRemote = true)
            }
        }

        assertFalse(invoked)
        ContextBank.removeRetrievalFunction(remoteKey)
    }

    @Test
    fun legacyIntrospectionQueryHidesProtectedPageOnDenial() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        ContextBank.mutateContextWindowSuspend(privateKey, skipRemote = true) { window ->
            window.loreBookKeys["secret"] = LoreBook().apply {
                key = "secret"
                value = "secret value"
            }
        }

        val results = ContextAccess.withCoroutineScope(scopeFor("other-resource")) {
            MemoryIntrospectionTools.queryLorebook(privateKey, query = "secret")
        }

        assertTrue(results.isEmpty())
    }

    @Test
    fun invalidSidecarFailsClosed() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        File("${TPipeConfig.getLorebookDir()}/$privateKey.bank.access").writeText("not-json")

        val exception = assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(scopeFor("private-resource")) {
                ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true)
            }
        }

        assertEquals(ContextAccessDenialReason.INVALID_METADATA, exception.reason)
    }

    @Test
    fun sidecarWithoutExplicitSchemaVersionFailsClosed() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        File("${TPipeConfig.getLorebookDir()}/$privateKey.bank.access").writeText(
            "{\"resourceKind\":\"CONTEXT_WINDOW\",\"resourceId\":\"private-resource\"}"
        )

        val exception = assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(scopeFor("private-resource")) {
                ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true)
            }
        }

        assertEquals(ContextAccessDenialReason.INVALID_METADATA, exception.reason)
    }

    @Test
    fun sidecarWithNullSchemaVersionFailsClosed() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        File("${TPipeConfig.getLorebookDir()}/$privateKey.bank.access").writeText(
            "{\"schemaVersion\":null,\"resourceKind\":\"CONTEXT_WINDOW\",\"resourceId\":\"private-resource\"}"
        )

        val exception = assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(scopeFor("private-resource")) {
                ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true)
            }
        }

        assertEquals(ContextAccessDenialReason.INVALID_METADATA, exception.reason)
    }

    @Test
    fun orphanedSidecarCannotAuthorizeAResource() = runBlocking {
        val orphanKey = "sandbox-orphan-page"
        val sidecar = File("${TPipeConfig.getLorebookDir()}/$orphanKey.bank.access")
        sidecar.parentFile.mkdirs()
        sidecar.writeText(
            serialize(
                ContextResourceMetadata(
                    ContextResourceKind.CONTEXT_WINDOW,
                    "orphan-resource"
                ),
                encodedefault = true
            )
        )

        try
        {
            val exception = assertFailsWith<ContextAccessDeniedException> {
                ContextAccess.withCoroutineScope(scopeFor("orphan-resource")) {
                    ContextBank.getContextFromBankSuspend(orphanKey, skipRemote = true)
                }
            }
            assertEquals(ContextAccessDenialReason.INVALID_METADATA, exception.reason)
        }
        finally
        {
            sidecar.delete()
        }
    }

    @Test
    fun secureToolVariantSurfacesProtectedDenial() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")

        assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(scopeFor("other-resource")) {
                SecureMemoryIntrospectionTools.getLorebook(privateKey)
            }
        }

        Unit
    }

    @Test
    fun secureToolVariantCanReadWhenScopeAllowsResource() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        ContextBank.mutateContextWindowSuspend(privateKey, skipRemote = true) { window ->
            window.loreBookKeys["secret"] = LoreBook().apply {
                key = "secret"
                value = "secret value"
            }
        }

        val lorebook = ContextAccess.withCoroutineScope(scopeFor("private-resource")) {
            SecureMemoryIntrospectionTools.getLorebook(privateKey)
        }

        assertEquals("secret value", lorebook.getValue("secret").value)
    }

    @Test
    fun secureToolVariantPreservesAnExistingMemoryIntrospectionLeash() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        val scope = scopeFor("private-resource")

        val result = MemoryIntrospection.withCoroutineScope(
            MemoryIntrospectionConfig(
                allowedPageKeys = mutableSetOf(otherKey),
                allowRead = true
            )
        ) {
            ContextAccess.withCoroutineScope(scope) {
                SecureMemoryIntrospectionTools.getLorebook(privateKey)
            }
        }

        assertTrue(result.isEmpty())
    }

    @Test
    fun secureListSurfacesEnumerationDenial() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        val readOnlyScope = ContextAccess.issueRootScope(
            ExecutionPrincipal("reader"),
            ContextAccessRights(readableResources = setOf(ContextResourceSelector.Resource("private-resource")))
        )

        assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(readOnlyScope) {
                SecureMemoryIntrospectionTools.listPageKeys()
            }
        }

        Unit
    }

    @Test
    fun childScopeRequiresDelegationAndCannotRegainRights() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        seedProtectedPage(otherKey, "other-resource", "other value")

        val delegatingScope = scopeFor("private-resource")
        ContextAccess.withCoroutineScope(delegatingScope) {
            assertFailsWith<ContextAccessDeniedException> {
                ContextAccess.withChildCoroutineScope(
                    ExecutionPrincipal("child-agent"),
                    ContextAccessRights(
                        readableResources = setOf(ContextResourceSelector.Resource("other-resource")),
                        delegable = true
                    )
                ) {
                    ContextBank.getContextFromBankSuspend(otherKey, skipRemote = true)
                }
            }
        }

        val nonDelegatingScope = ContextAccess.issueRootScope(
            ExecutionPrincipal("non-delegating"),
            ContextAccessRights(readableResources = setOf(ContextResourceSelector.Resource("private-resource")))
        )
        assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(nonDelegatingScope) {
                ContextAccess.withChildCoroutineScope(
                    ExecutionPrincipal("child-agent"),
                    ContextAccessRights(readableResources = setOf(ContextResourceSelector.Resource("private-resource")))
                ) { }
            }
        }

        Unit
    }

    @Test
    fun childScopeUsesTheDelegatedPrincipal() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")

        val root = scopeFor("private-resource")
        ContextAccess.withCoroutineScope(root) {
            ContextAccess.withChildCoroutineScope(
                ExecutionPrincipal("child-agent"),
                ContextAccessRights(
                    readableResources = setOf(ContextResourceSelector.Resource("private-resource")),
                    delegable = true
                )
            ) {
                assertEquals("child-agent", ContextAccess.currentScope()?.principal?.id)
            }
        }
    }

    @Test
    fun boundarySelectorGrantsAccessToResourcesInThatBoundary() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        seedProtectedPage(otherKey, "other-resource", "other value")

        val scope = ContextAccess.issueRootScope(
            ExecutionPrincipal("boundary-agent"),
            ContextAccessRights(
                readableResources = setOf(ContextResourceSelector.Boundary("sandbox-boundary"))
            )
        )

        val values = ContextAccess.withCoroutineScope(scope) {
            listOf(
                ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true).contextElements.single(),
                ContextBank.getContextFromBankSuspend(otherKey, skipRemote = true).contextElements.single()
            )
        }

        assertEquals(listOf("private value", "other value"), values)
    }

    @Test
    fun pipeGlobalContextPullCannotBypassProtectedPageAuthorization() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        val pipe = DummyPipe()
        pipe.setPageKey(privateKey)
            .pullGlobalContext()
            .allowEmptyUserPrompt()
            .allowEmptyContentObject()

        assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(scopeFor("other-resource")) {
                pipe.execute("hello")
            }
        }

        Unit
    }

    @Test
    fun protectedAccessStillHonorsContextLock() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")
        ContextLock.addLock(privateKey, privateKey, isPageKey = true, skipRemote = true)

        try
        {
            val scope = scopeFor("private-resource")
            val loaded = ContextAccess.withCoroutineScope(scope) {
                ContextBank.getContextFromBankSuspend(privateKey, skipRemote = true)
            }
            assertTrue(loaded.contextElements.isEmpty())
        }
        finally
        {
            ContextLock.removeLock(privateKey, skipRemote = true)
        }
    }

    @Test
    fun securedRemoteAccessRequiresMetadataCapabilityBeforeFetchingValue() = runBlocking {
        val backend = FakeRemoteBackend().apply {
            contextWindows[remoteKey] = ContextWindow().apply { contextElements.add("remote value") }
            resourceMetadata[remoteKey] = ContextResourceMetadata(
                ContextResourceKind.CONTEXT_WINDOW,
                "remote-resource",
                "sandbox-boundary"
            )
        }
        ContextBank.setRemotePersistenceBackend(backend)
        ContextBank.setStorageMode(remoteKey, StorageMode.REMOTE)

        assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(scopeFor("other-resource")) {
                ContextBank.getContextFromBankSuspend(remoteKey)
            }
        }

        assertEquals(0, backend.contextReads)
    }

    @Test
    fun securedRemoteAccessRejectsLegacyBackendWithoutMetadataCapability() = runBlocking {
        val backend = LegacyRemoteBackend().apply {
            contextWindows[remoteKey] = ContextWindow().apply { contextElements.add("remote value") }
        }
        ContextBank.setRemotePersistenceBackend(backend)
        ContextBank.setStorageMode(remoteKey, StorageMode.REMOTE)

        val exception = assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(scopeFor("remote-resource")) {
                ContextBank.getContextFromBankSuspend(remoteKey)
            }
        }

        assertEquals(ContextAccessDenialReason.METADATA_UNAVAILABLE, exception.reason)
        assertEquals(0, backend.contextReads)
    }

    @Test
    fun remoteCreateUsesCreatePermissionAndDoesNotFetchTheValue() = runBlocking {
        val backend = FakeRemoteBackend()
        backend.resourceMetadata[remoteKey] = ContextResourceMetadata(
            ContextResourceKind.CONTEXT_WINDOW,
            "remote-resource",
            "sandbox-boundary"
        )
        ContextBank.setRemotePersistenceBackend(backend)
        ContextBank.setStorageMode(remoteKey, StorageMode.REMOTE)

        val createOnlyScope = ContextAccess.issueRootScope(
            ExecutionPrincipal("creator"),
            ContextAccessRights(
                creatableResources = setOf(ContextResourceSelector.Resource("remote-resource"))
            )
        )

        ContextAccess.withCoroutineScope(createOnlyScope) {
            ContextBank.emplaceSuspend(
                remoteKey,
                ContextWindow().apply { contextElements.add("created remotely") },
                StorageMode.REMOTE
            )
        }

        assertEquals(listOf("created remotely"), backend.contextWindows[remoteKey]!!.contextElements)
        assertEquals(1, backend.presenceReads)
        assertEquals(0, backend.contextReads)
    }

    @Test
    fun remoteCreateFailsClosedWhenPresenceIsUnavailable() = runBlocking {
        val backend = FakeRemoteBackend().apply {
            resourceMetadata[remoteKey] = ContextResourceMetadata(
                ContextResourceKind.CONTEXT_WINDOW,
                "remote-resource",
                "sandbox-boundary"
            )
            reportUnknownPresence = true
        }
        ContextBank.setRemotePersistenceBackend(backend)
        ContextBank.setStorageMode(remoteKey, StorageMode.REMOTE)

        val createOnlyScope = ContextAccess.issueRootScope(
            ExecutionPrincipal("creator"),
            ContextAccessRights(
                creatableResources = setOf(ContextResourceSelector.Resource("remote-resource"))
            )
        )

        val exception = assertFailsWith<ContextAccessDeniedException> {
            ContextAccess.withCoroutineScope(createOnlyScope) {
                ContextBank.emplaceSuspend(
                    remoteKey,
                    ContextWindow().apply { contextElements.add("must not be stored") },
                    StorageMode.REMOTE
                )
            }
        }

        assertEquals(ContextAccessDenialReason.METADATA_UNAVAILABLE, exception.reason)
        assertEquals(ContextAccessOperation.CREATE, exception.operation)
        assertTrue(backend.contextWindows[remoteKey] == null)
        assertEquals(0, backend.contextReads)
    }

    @Test
    fun remoteWriteLeashNormalizesPresenceDenialToWrite() = runBlocking {
        val backend = FakeRemoteBackend().apply {
            resourceMetadata[remoteKey] = ContextResourceMetadata(
                ContextResourceKind.CONTEXT_WINDOW,
                "remote-resource",
                "sandbox-boundary"
            )
            throwPresenceDenial = true
        }
        ContextBank.setRemotePersistenceBackend(backend)
        ContextBank.setStorageMode(remoteKey, StorageMode.REMOTE)

        val exception = assertFailsWith<ContextAccessDeniedException> {
            MemoryIntrospection.withCoroutineScope(
                MemoryIntrospectionConfig(
                    allowedPageKeys = mutableSetOf(remoteKey),
                    allowWrite = true
                )
            ) {
                ContextAccess.withCoroutineScope(scopeFor("remote-resource")) {
                    MemoryIntrospection.canWriteSuspend(remoteKey)
                }
            }
        }

        assertEquals(ContextAccessOperation.WRITE, exception.operation)
    }

    @Test
    fun sidecarUsesTheConfiguredMemoryDirectory() = runBlocking {
        seedProtectedPage(privateKey, "private-resource", "private value")

        val sidecar = File("${TPipeConfig.getLorebookDir()}/$privateKey.bank.access")

        assertTrue(sidecar.exists())
    }

    @Test
    fun concurrentMetadataUpdatesLeaveOneValidSidecar() = runBlocking {
        seedProtectedPage(privateKey, "initial-resource", "private value")
        val resourceIds = (0 until 20).map { "concurrent-resource-$it" }.toSet()

        coroutineScope {
            resourceIds.map { resourceId ->
                async(Dispatchers.Default) {
                    ContextBank.registerResourceMetadataSuspend(
                        privateKey,
                        ContextResourceMetadata(
                            ContextResourceKind.CONTEXT_WINDOW,
                            resourceId,
                            "sandbox-boundary"
                        )
                    )
                }
            }.awaitAll()
        }

        val metadata = ContextBank.getResourceMetadataSuspend(privateKey, ContextResourceKind.CONTEXT_WINDOW)
        assertTrue(metadata?.resourceId in resourceIds)
    }

    private open class LegacyRemoteBackend : ContextPersistenceBackend
    {
        val contextWindows = ConcurrentHashMap<String, ContextWindow>()
        var contextReads: Int = 0
        var pageKeyListReads: Int = 0

        override open val id: String = "legacy-test-remote"

        override suspend fun getContextWindow(key: String): ContextWindow?
        {
            contextReads++
            return contextWindows[key]
        }

        override suspend fun putContextWindow(key: String, window: ContextWindow)
        {
            contextWindows[key] = window
        }

        override suspend fun deleteContextWindow(key: String): Boolean = contextWindows.remove(key) != null

        override suspend fun listContextWindowKeys(): List<String>
        {
            pageKeyListReads++
            return contextWindows.keys.toList()
        }

        override suspend fun getTodoList(key: String): TodoList? = null

        override suspend fun putTodoList(key: String, todoList: TodoList) = Unit

        override suspend fun deleteTodoList(key: String): Boolean = false

        override suspend fun listTodoListKeys(): List<String> = emptyList()
    }

    private class FakeRemoteBackend : LegacyRemoteBackend(), ContextResourceMetadataBackend
    {
        val resourceMetadata = ConcurrentHashMap<String, ContextResourceMetadata>()
        var presenceReads: Int = 0
        var reportUnknownPresence: Boolean = false
        var throwPresenceDenial: Boolean = false

        override val id: String = "metadata-test-remote"

        override suspend fun getResourceMetadata(
            kind: ContextResourceKind,
            key: String
        ): ContextResourceMetadata? = resourceMetadata[key]?.takeIf { it.resourceKind == kind }

        override suspend fun putResourceMetadata(
            kind: ContextResourceKind,
            key: String,
            metadata: ContextResourceMetadata
        )
        {
            resourceMetadata[key] = metadata
        }

        override suspend fun deleteResourceMetadata(kind: ContextResourceKind, key: String): Boolean =
            resourceMetadata.remove(key) != null

        override suspend fun resourceExists(kind: ContextResourceKind, key: String): Boolean?
        {
            presenceReads++
            if(throwPresenceDenial)
            {
                throw ContextAccessDeniedException(
                    ContextAccessOperation.READ,
                    kind,
                    key,
                    ContextAccessDenialReason.METADATA_UNAVAILABLE
                )
            }
            if(reportUnknownPresence) return null
            return when(kind)
            {
                ContextResourceKind.CONTEXT_WINDOW -> contextWindows.containsKey(key)
                ContextResourceKind.TODO_LIST -> false
                ContextResourceKind.BANKED_CONTEXT -> true
            }
        }
    }
}
