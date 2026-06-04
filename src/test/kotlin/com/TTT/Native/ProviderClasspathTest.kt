package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Classpath reachability test for the provider sub-modules
 * ([ollamaPipe.OllamaPipe] and [bedrockPipe.BedrockPipe]).
 *
 * Phase 2 wires `implementation(project(":TPipe-Ollama"))` and
 * `implementation(project(":TPipe-Bedrock"))` into the root
 * [build.gradle.kts] of `:TPipe` so that:
 *  - the JVM test classpath can resolve these classes,
 *  - the GraalVM native-image build can link them into the .so via
 *    the `-H:Class=...` reachability hints in [build.gradle.kts].
 *
 * This test pins down the JVM-side half of that wiring. It deliberately
 * does NOT call `OllamaPipe()` or `BedrockPipe()` because the providers
 * have `init` blocks that reach into Ktor and the AWS SDK at instance
 * time; a pure class-load / declared-constructor lookup is sufficient
 * to prove the class is on the classpath and keeps the test JVM-isolated
 * (no network, no AWS credentials).
 *
 * Phase 3 / Phase 4 extend [NativeBridge.pipeCreate] to construct these
 * providers via `Class.forName(...).getDeclaredConstructor().newInstance()`,
 * which depends on this test staying green.
 */
class ProviderClasspathTest {

    //==========================================================================
    // Class.forName smoke
    //==========================================================================

    @Test
    fun ollamaPipeClassIsResolvable() {
        // The OllamaPipe class must be loadable from the main :TPipe
        // classpath. If `implementation(project(":TPipe-Ollama"))` is
        // missing, Class.forName throws ClassNotFoundException.
        val resolved = Class.forName("ollamaPipe.OllamaPipe")
        assertEquals("ollamaPipe.OllamaPipe", resolved.name)
    }

    @Test
    fun bedrockPipeClassIsResolvable() {
        // The BedrockPipe class must be loadable from the main :TPipe
        // classpath. If `implementation(project(":TPipe-Bedrock"))` is
        // missing, Class.forName throws ClassNotFoundException.
        val resolved = Class.forName("bedrockPipe.BedrockPipe")
        assertEquals("bedrockPipe.BedrockPipe", resolved.name)
    }

    //==========================================================================
    // No-arg declared constructor
    //==========================================================================

    @Test
    fun ollamaPipeExposesNoArgDeclaredConstructor()
    {
        // OllamaPipe's init block reaches into Ktor, so we only verify the
        // no-arg constructor is declared and do not invoke it.
        val constructor = ollamaPipe.OllamaPipe::class.java.getDeclaredConstructor()
        assertNotNull(constructor, "OllamaPipe must declare a no-arg constructor")
    }

    @Test
    fun bedrockPipeExposesNoArgDeclaredConstructor()
    {
        // BedrockPipe's init block reaches into the AWS SDK, so we only verify
        // the no-arg constructor is declared and do not invoke it.
        val constructor = bedrockPipe.BedrockPipe::class.java.getDeclaredConstructor()
        assertNotNull(constructor, "BedrockPipe must declare a no-arg constructor")
    }

    //==========================================================================
    // Class identity cross-check
    //==========================================================================

    @Test
    fun ollamaPipeClassLookupMatchesKotlinReference()
    {
        // The Class.forName lookup and the Kotlin `::class.java` reference
        // must resolve to the same Class object. If the class is loaded from a
        // different classloader (or shadowed by a duplicate on the classpath)
        // this assertion fails, breaking reflective construction.
        val byName = Class.forName("ollamaPipe.OllamaPipe")
        val byKotlinRef = ollamaPipe.OllamaPipe::class.java
        assertSame(byName, byKotlinRef, "ollamaPipe.OllamaPipe must load from the same classloader as Kotlin references")
    }

    @Test
    fun bedrockPipeClassLookupMatchesKotlinReference()
    {
        // Same identity check as above, for the Bedrock provider.
        val byName = Class.forName("bedrockPipe.BedrockPipe")
        val byKotlinRef = bedrockPipe.BedrockPipe::class.java
        assertSame(byName, byKotlinRef, "bedrockPipe.BedrockPipe must load from the same classloader as Kotlin references")
    }
}
