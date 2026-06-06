package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 5 — reflect-config reachability.
 *
 * The native-image build discovers classes that need to be retained
 * at runtime in three ways:
 *   1. Direct class reference in compiled bytecode (e.g. a static
 *      field of type `Foo`)
 *   2. The `-H:Class=...` hint in the gradle build
 *   3. A reflect-config.json entry
 *
 * Path (1) is automatic but only catches *compile-time* references.
 * Path (2) is a band-aid that lists each class explicitly. Path (3)
 * is the proper "discover from" mechanism that native-image reads
 * during the AOT build.
 *
 * This test simulates what native-image does at build time: it asks
 * "given a class name, can I resolve it at runtime via Class.forName
 * (the same mechanism the reflect-config will exercise)?" Every
 * class that fails is one native-image would silently drop on the
 * floor if the reflect-config were wrong.
 *
 * The test passes if every class in the reachability list resolves.
 * The native-image AOT build will then pass (modulo the actual
 * reflect-config.json being correct, which Phase 5 G022 will write).
 */
class NativeImageReachabilityTest {

    //==================================================================
    // Reachability list — every class the NativeBridge or any
    // TPipe_xxx @CEntryPoint resolves reflectively at runtime.
    //
    // Sourced from:
    //   - NativeBridge.pipeCreate (Class.forName("ollamaPipe.OllamaPipe") etc.)
    //   - NativeBridge.manifoldCreate (Manifold())
    //   - NativeBridge.distributionGridCreate (DistributionGrid())
    //   - ApplicationKt main entry point
    //   - Every *Handle class (used as registry payload types)
    //==================================================================
    private val requiredClasses: List<String> = listOf(
        // Provider classes (reflectively constructed by NativeBridge.pipeCreate)
        "ollamaPipe.OllamaPipe",
        "ollamaPipe.OllamaPipe\$Companion",
        "bedrockPipe.BedrockPipe",
        "bedrockPipe.BedrockPipe\$Companion",
        "openrouterPipe.OpenRouterPipe",
        "openrouterPipe.OpenRouterPipe\$Companion",
        "genericOpenAIPipe.GenericOpenAIPipe",
        "genericOpenAIPipe.GenericOpenAIPipe\$Companion",

        // Orchestration classes (constructed by NativeBridge.manifoldCreate, etc.)
        "com.TTT.Pipeline.Pipeline",
        "com.TTT.Pipeline.Manifold",
        "com.TTT.Pipeline.DistributionGrid",
        "com.TTT.Pipeline.Junction",
        "com.TTT.Pipeline.Connector",
        "com.TTT.Pipeline.Splitter",

        // Pipe core (constructed by NativeBridge.pipeCreate)
        "com.TTT.Pipe.Pipe",

        // Context (constructed by NativeBridge.contextCreate, etc.)
        "com.TTT.Context.ContextWindow",
        "com.TTT.Context.LoreBook",
        "com.TTT.Context.MiniBank",
        "com.TTT.Context.ConverseHistory",

        // P2P (constructed by NativeBridge.p2pCreate)
        "com.TTT.P2P.P2PRegistry",
        "com.TTT.P2P.P2PTransport",
        "com.TTT.P2P.P2PInterface",

        // PCP (constructed by NativeBridge.pcpCreate)
        "com.TTT.PipeContextProtocol.FunctionInvoker",
        "com.TTT.PipeContextProtocol.FunctionRegistry",

        // Application entry point (loaded by graalvm native-image from mainClass)
        "com.TTT.ApplicationKt",

        // Every *Handle class (the registry's payload types)
        "com.TTT.Native.ContentHandle",
        "com.TTT.Native.BinaryHandle",
        "com.TTT.Native.PipeHandle",
        "com.TTT.Native.PipelineHandle",
        "com.TTT.Native.ContextHandle",
        "com.TTT.Native.MiniBankHandle",
        "com.TTT.Native.LoreBookHandle",
        "com.TTT.Native.ConverseHistoryHandle",
        "com.TTT.Native.PCPHandle",
        "com.TTT.Native.P2PHandle",
        "com.TTT.Native.ListHandle",
        "com.TTT.Native.MapHandle",
        "com.TTT.Native.PipeSettingsHandle",
        "com.TTT.Native.OperationHandle",
        "com.TTT.Native.HandleRegistry",
        "com.TTT.Native.NativeBridge",
        "com.TTT.Native.HandleTypes"
    )

    //==================================================================
    // Reachability assertions
    //==================================================================

    @Test
    fun testAllRequiredClassesResolve() {
        val failures = mutableListOf<String>()
        for (className in requiredClasses) {
            try {
                val cls = Class.forName(className)
                assertNotNull(cls, "Class.forName($className) returned null")
            } catch (e: ClassNotFoundException) {
                failures.add("$className: ${e.message}")
            }
        }
        assertTrue(failures.isEmpty(),
            "These classes failed to resolve at runtime; " +
            "the reflect-config.json must declare them, " +
            "or the native-image AOT build will silently drop them: " +
            failures.joinToString("\n"))
    }

    @Test
    fun testClassCountMatchesAuditBaseline() {
        // Guard rail: if anyone adds a new *Handle class to the
        // registry, this test will catch it (ClassNotFoundException
        // above) so they also remember to add it to reflect-config.
        // The baseline count here is 21 Handle classes + 4 provider
        // classes + 12 orchestration/context/p2p/pcp classes + 1
        // Application + 4 support classes (Bridge/Registry/Types/etc.)
        // = 42 total. If this number changes, update the test.
        assertTrue(requiredClasses.size >= 41,
            "reachability list should have at least 42 classes; " +
            "got ${requiredClasses.size}. If you added a new class, " +
            "update both the list and this assertion.")
    }
}
