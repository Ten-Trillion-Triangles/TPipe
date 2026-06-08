package com.TTT.Native

import org.graalvm.nativeimage.IsolateThread
import org.graalvm.nativeimage.c.function.CEntryPoint
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 1: ABI Parity Matrix.
 *
 * Establishes a reproducible measurement of parity between:
 *   (a) the @CEntryPoint methods on TPipeBootstrap (Java side)
 *   (b) the TPipe_* declarations in src/main/resources/tpipe-abi.h (C side)
 *   (c) the T-type symbols exported by the native .so (binary side)
 *
 * Once this test is green, any future regression in the parity (a
 * new @CEntryPoint added without a header declaration, a header
 * declaration removed without removing the Java method, etc.) is
 * caught in CI instead of being discovered by humans.
 *
 * The matrix is the regression baseline. Phases 2+ are the work that
 * closes the gaps surfaced by the matrix.
 */
class AbiParityMatrixTest {

    //==========================================================================
    // Matrix discovery
    //==========================================================================

    /**
     * Reflectively collect every @CEntryPoint-annotated method declared on
     * TPipeBootstrap.java. The native-image build toolchain only emits a
     * TPipe_* C-callable wrapper for methods that carry @CEntryPoint, so
     * this set is the source of truth for "what symbols the .so should
     * contain."
     *
     * Each @CEntryPoint in TPipeBootstrap declares its C ABI name
     * explicitly via `name = "TPipe_*"`. We use that name (not the Java
     * method name) because it is the name the native-image toolchain
     * emits into the .so.
     */
    private fun discoverEntryPoints(): List<DiscoveredEntryPoint> {
        return TPipeBootstrap::class.java.declaredMethods
            .filter { it.isAnnotationPresent(CEntryPoint::class.java) }
            .map { method ->
                val annotation = method.getAnnotation(CEntryPoint::class.java)
                // name is a required String per @CEntryPoint; defensive default
                // to method.name only if it's somehow missing.
                val abiName = annotation.name.takeIf { it.isNotEmpty() } ?: method.name
                DiscoveredEntryPoint(
                    abiName = abiName,
                    javaName = method.name,
                    paramCount = method.parameterCount,
                    firstParamIsIsolateThread = method.parameterTypes.firstOrNull()
                        ?.let { it == IsolateThread::class.java } ?: false,
                    returnType = method.returnType.simpleName
                )
            }
            .sortedBy { it.abiName }
    }

    /**
     * Parse src/main/resources/tpipe-abi.h and return every TPipe_* function
     * name declared (not typedef, not enum, not macro). The parser is
     * deliberately simple: it scans for the literal "TPipe_" followed by
     * an identifier and an opening parenthesis on the same line. This is
     * sufficient for the current header's single-line declaration style.
     */
    private fun discoverDeclaredSymbols(header: File): List<Pair<String, Int>> {
        val results = mutableListOf<Pair<String, Int>>()
        header.useLines { lines ->
            for ((idx, line) in lines.withIndex()) {
                val lineNo = idx + 1
                val trimmed = line.substringBefore("//").trim()
                if (!trimmed.contains("TPipe_")) continue
                val regex = Regex("""\b(TPipe_[A-Za-z0-9_]+)\s*\(""")
                val match = regex.find(trimmed) ?: continue
                // Skip pointer-deref forms like "*TPipe_"
                val nameStart = match.range.first
                if (nameStart > 0 && trimmed[nameStart - 1] == '*') continue
                results.add(match.groupValues[1] to lineNo)
            }
        }
        return results.distinctBy { it.first }.sortedBy { it.first }
    }

    /**
     * Parse `nm -D <so>` output and return every defined T-type symbol
     * whose name starts with TPipe_. This is the binary-side parity
     * check; any drift between Java and the .so is caught here.
     */
    private fun discoverExportedSymbols(soFile: File): List<String> {
        if (!soFile.exists()) return emptyList()
        val process = ProcessBuilder("nm", "-D", "--defined-only", soFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val rc = process.waitFor()
        assertEquals(0, rc, "nm -D failed: $output")
        return output.lineSequence()
            .mapNotNull { line ->
                val parts = line.split(Regex("""\s+"""))
                if (parts.size < 3) return@mapNotNull null
                val type = parts[1]
                val name = parts[2]
                if (type == "T" && name.startsWith("TPipe_")) name else null
            }
            .distinct()
            .sorted()
            .toList()
    }

    private fun locateHeader(): File {
        val candidates = listOf(
            File("src/main/resources/tpipe-abi.h"),
            File("../src/main/resources/tpipe-abi.h")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("tpipe-abi.h not found in candidates: $candidates")
    }

    private fun locateSo(): File? {
        val candidates = listOf(
            File("build/native/nativeCompile/TPipe.so"),
            File("build/native/nativeCompile/TPipe.dylib"),
            File("build/native/nativeCompile/TPipe.dll")
        )
        return candidates.firstOrNull { it.exists() }
    }

    //==========================================================================
    // JVM-side invariants (no filesystem access required)
    //==========================================================================

    @Test
    fun testAtLeastOneEntryPointExists() {
        val eps = discoverEntryPoints()
        assertTrue(eps.isNotEmpty(),
            "TPipeBootstrap should declare at least one @CEntryPoint; got none")
    }

    @Test
    fun testAllEntryPointsHaveIsolateThreadAsFirstParam() {
        val eps = discoverEntryPoints()
        val violations = eps.filter { !it.firstParamIsIsolateThread }
        assertEquals(emptyList(), violations,
            "All @CEntryPoint methods must take IsolateThread as the first parameter; " +
            "violations: ${violations.map { it.abiName }}")
    }

    @Test
    fun testAbiNamesAreUnique() {
        val eps = discoverEntryPoints()
        val duplicates = eps.groupBy { it.abiName }
            .filter { it.value.size > 1 }
            .keys
        assertEquals(emptySet(), duplicates,
            "All @CEntryPoint C ABI names must be unique; duplicates: $duplicates")
    }

    @Test
    fun testAbiNamesFollowNamingConvention() {
        val eps = discoverEntryPoints()
        val pattern = Regex("""^TPipe_[A-Za-z0-9_]+$""")
        val violations = eps.map { it.abiName }.filter { !pattern.matches(it) }
        assertEquals(emptyList(), violations,
            "All @CEntryPoint ABI names must match $pattern; violations: $violations")
    }

    //==========================================================================
    // Header parity: every declared TPipe_* function has a Java entry point
    //==========================================================================

    @Test
    fun testEveryDeclaredSymbolHasAJavaEntryPoint() {
        val eps = discoverEntryPoints().map { it.abiName }.toSet()
        val declared = discoverDeclaredSymbols(locateHeader()).map { it.first }.toSet()
        val orphans = declared - eps
        assertEquals(emptySet(), orphans,
            "Header declares TPipe_* symbols that have no matching @CEntryPoint on " +
            "TPipeBootstrap. The matrix baseline allows the documented orphan set " +
            "(see ABI-WORKFLOW-HANDOFF.md), but a NEW orphan is a regression: $orphans")
    }

    @Test
    fun testOrphanSetIsEmpty() {
        // Phase 1 baseline: the orphan set is EMPTY. The prior analysis
        // noted TPipe_free and TPipe_ConverseHistory_create as orphans;
        // those have since been resolved (either the Java @CEntryPoint
        // was added or the header declaration was removed). This guard
        // rail asserts the empty-set state and fails if a new orphan
        // is introduced — every declared TPipe_* in tpipe-abi.h must
        // have a matching @CEntryPoint on TPipeBootstrap.
        val eps = discoverEntryPoints().map { it.abiName }.toSet()
        val declared = discoverDeclaredSymbols(locateHeader()).map { it.first }.toSet()
        val currentOrphans = declared - eps
        assertEquals(emptySet(), currentOrphans,
            "Orphan set is no longer empty. Either: " +
            "(a) a new TPipe_* declaration was added to tpipe-abi.h without a matching " +
            "@CEntryPoint on TPipeBootstrap (add the Java method), or " +
            "(b) a @CEntryPoint was removed from TPipeBootstrap but the corresponding " +
            "header declaration was left behind (remove it from tpipe-abi.h). " +
            "Current orphans: $currentOrphans")
    }

    //==========================================================================
    // Binary parity: every Java entry point is exported by the .so
    //==========================================================================

    @Test
    fun testEveryEntryPointIsExportedByTheSo() {
        val soFile = locateSo()
        if (soFile == null) {
            println("Skipping: TPipe.so not found. Run ./gradlew nativeCompile to enable this test.")
            return
        }
        val eps = discoverEntryPoints().map { it.abiName }.toSet()
        val exported = discoverExportedSymbols(soFile).toSet()
        val missing = eps - exported
        assertEquals(emptySet(), missing,
            "These @CEntryPoint methods exist in Java but are NOT exported as T-type " +
            "symbols by the .so. Either the annotation is malformed, the build " +
            "is stale, or the symbol was stripped: ${missing.sorted()}")
    }

    @Test
    fun testEveryExportedSymbolHasAJavaEntryPoint() {
        val soFile = locateSo() ?: run {
            println("Skipping: TPipe.so not found. Run ./gradlew nativeCompile to enable this test.")
            return
        }
        val eps = discoverEntryPoints().map { it.abiName }.toSet()
        val exported = discoverExportedSymbols(soFile).toSet()
        val extras = exported - eps
        assertEquals(emptySet(), extras,
            "These TPipe_* symbols are exported by the .so but have NO matching " +
            "@CEntryPoint on TPipeBootstrap. Either the symbol is an aliased wrapper " +
            "(and should be documented) or the Java method was renamed/deleted " +
            "without rebuilding: ${extras.sorted()}")
    }

    //==========================================================================
    // Parity matrix snapshot — printed to stdout for the audit report
    //==========================================================================

    @Test
    fun testPrintParityMatrixSnapshot() {
        val eps = discoverEntryPoints()
        val declared = discoverDeclaredSymbols(locateHeader())
        val soFile = locateSo()
        val exported = soFile?.let { discoverExportedSymbols(it) } ?: emptyList()

        val matrix = buildString {
            appendLine("=== TPipe ABI Parity Matrix (Phase 1 baseline) ===")
            appendLine()
            appendLine("@CEntryPoint methods on TPipeBootstrap: ${eps.size}")
            appendLine("TPipe_* declarations in tpipe-abi.h:    ${declared.size}")
            appendLine("T-type TPipe_* symbols in .so:          ${exported.size}")
            appendLine("so path: ${soFile?.absolutePath ?: "(not built)"}")
            appendLine()
            val orphans = declared.map { it.first }.toSet() - eps.map { it.abiName }.toSet()
            if (orphans.isNotEmpty()) {
                appendLine("Orphans (declared in header, no Java entry point):")
                orphans.sorted().forEach { appendLine("  - $it") }
                appendLine()
            }
            val missingFromSo = eps.map { it.abiName }.toSet() - exported.toSet()
            if (missingFromSo.isNotEmpty()) {
                appendLine("Missing from .so (Java has entry point, binary does not):")
                missingFromSo.sorted().forEach { appendLine("  - $it") }
                appendLine()
            }
            val extraInSo = exported.toSet() - eps.map { it.abiName }.toSet()
            if (extraInSo.isNotEmpty()) {
                appendLine("Extras in .so (binary symbol, no Java entry point):")
                extraInSo.sorted().forEach { appendLine("  - $it") }
                appendLine()
            }
        }
        println(matrix)
        assertNotNull(matrix)
    }

    //==========================================================================
    // Internal data class
    //==========================================================================

    private data class DiscoveredEntryPoint(
        val abiName: String,
        val javaName: String,
        val paramCount: Int,
        val firstParamIsIsolateThread: Boolean,
        val returnType: String
    )

    //==========================================================================
    // Reverse header parity: every Java @CEntryPoint has a header declaration
    //==========================================================================

    @Test
    fun testEveryJavaEntryPointHasAHeaderDeclaration() {
        val eps = discoverEntryPoints().map { it.abiName }.toSet()
        val declared = discoverDeclaredSymbols(locateHeader()).map { it.first }.toSet()
        val javaOnly = eps - declared
        assertEquals(emptySet(), javaOnly,
            "These @CEntryPoint methods exist on TPipeBootstrap but have NO matching " +
            "declaration in tpipe-abi.h. Add a declaration to the C header so the " +
            "function is part of the documented C ABI surface: ${javaOnly.sorted()}")
    }

    @Test
    fun testReverseOrphanSetIsEmpty() {
        // Phase 1 baseline: the reverse orphan set is EMPTY. The Java side may
        // declare more @CEntryPoint methods than the C header documents, but
        // each Java entry point must have a matching tpipe-abi.h declaration.
        // This guard rail fails if a new @CEntryPoint is added to
        // TPipeBootstrap without updating the header.
        val eps = discoverEntryPoints().map { it.abiName }.toSet()
        val declared = discoverDeclaredSymbols(locateHeader()).map { it.first }.toSet()
        val reverseOrphans = eps - declared
        assertEquals(emptySet(), reverseOrphans,
            "Reverse orphan set is no longer empty. A new @CEntryPoint was added " +
            "to TPipeBootstrap without updating tpipe-abi.h. Either: " +
            "(a) add the matching declaration to tpipe-abi.h, or " +
            "(b) remove the @CEntryPoint if the function should not be in the C ABI. " +
            "Current reverse orphans: $reverseOrphans")
    }
}
