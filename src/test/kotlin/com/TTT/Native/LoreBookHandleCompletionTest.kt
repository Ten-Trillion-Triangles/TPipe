package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 7 completion test — enforces that every public method on
 * [LoreBookHandle] has a corresponding `TPipe_LoreBook_*` C symbol declared
 * in `tpipe-abi.h`.
 *
 * This guards the C ABI surface against future drift: if a developer adds a
 * new method to [LoreBookHandle], this test fails until the bridge,
 * bootstrap shim, and header declaration are also added.
 *
 * Excluded from the check (per Phase 7 scope):
 *  - `toJson` (serialization helper, already covered by [LoreBookHandle])
 *  - Constructor (`<init>`, not represented in `declaredMethods` for Kotlin
 *    classes anyway, but listed explicitly for clarity)
 *  - Property accessors for the wrapped `loreBook` var (`getLoreBook`,
 *    `setLoreBook`) — those expose the underlying [com.TTT.Context.LoreBook],
 *    not part of the C ABI surface
 *  - Object / data-class members (`equals`, `hashCode`, `toString`)
 *  - Private helper extensions (e.g. `escapeJson`)
 *
 * @see [LoreBookHandle]
 * @see [NativeBridge]
 * @see [TPipeBootstrap]
 */
class LoreBookHandleCompletionTest {

    /** Methods that should NOT be required to appear in the C ABI header. */
    private val excludedMethods = setOf(
        // toJson is present in the header but excluded from the reflection-based
        // check below.
        "toJson",
        // Property accessors for the wrapped `var loreBook` — internal-only.
        "getLoreBook", "setLoreBook",
        // Object methods that Kotlin may auto-generate or inherit.
        "equals", "hashCode", "toString",
        // Private helper extension declared inside LoreBookHandle for JSON
        // escaping. Kotlin compiles it to a private static-style method;
        // exclude defensively in case it shows up under any class loader.
        "escapeJson"
    )

    /** Cached header source — loaded once per test class instance. */
    private val headerSource: String by lazy {
        val stream = LoreBookHandleCompletionTest::class.java.getResourceAsStream("/tpipe-abi.h")
        assertNotNull(stream, "tpipe-abi.h must be on the classpath under /tpipe-abi.h")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * For every eligible public method on [LoreBookHandle], require that
     * `TPipe_LoreBook_<methodName>` appears somewhere in the header text.
     */
    @Test
    fun everyPublicMethodHasCSymbolDeclaration() {
        val methods = LoreBookHandle::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name }
            .distinct()
            .filterNot { it in excludedMethods }
            .sorted()

        assertTrue(
            methods.isNotEmpty(),
            "Reflection should discover at least one public method on LoreBookHandle"
        )

        val missing = mutableListOf<String>()
        for (methodName in methods) {
            val expectedSymbol = "TPipe_LoreBook_$methodName"
            if (!headerSource.contains(expectedSymbol)) {
                missing.add(expectedSymbol)
            }
        }

        if (missing.isNotEmpty()) {
            fail(
                "tpipe-abi.h is missing C symbol declarations for the following " +
                "LoreBookHandle methods: $missing. " +
                "Add the corresponding `int $expectedSymbolExample(...)` " +
                "declaration to src/main/resources/tpipe-abi.h."
            )
        }
    }

    /**
     * Static cross-check: these Phase 7 symbols must appear in the header
     * regardless of reflection filtering.
     */
    @Test
    fun expectedPhaseSevenSymbolsArePresent() {
        val expected = listOf(
            "TPipe_LoreBook_setKey",
            "TPipe_LoreBook_getKey",
            "TPipe_LoreBook_setValue",
            "TPipe_LoreBook_getValue",
            "TPipe_LoreBook_setWeight",
            "TPipe_LoreBook_getWeight",
            "TPipe_LoreBook_addLinkedKey",
            "TPipe_LoreBook_getLinkedKeys",
            "TPipe_LoreBook_addAliasKey",
            "TPipe_LoreBook_getAliasKeys",
            "TPipe_LoreBook_addRequiredKey",
            "TPipe_LoreBook_getRequiredKeys",
            "TPipe_LoreBook_combine",
            "TPipe_LoreBook_toJson"
        )
        for (symbol in expected) {
            assertTrue(
                headerSource.contains(symbol),
                "tpipe-abi.h should declare $symbol but it was not found"
            )
        }
    }

    private val expectedSymbolExample: String = "TPipe_LoreBook_<methodName>"
}
