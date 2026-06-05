import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Support for GraalVM  Native is planned but not yet implemented. This will allow us to deploy this library
 * as a .so to any platform where a jar can't be used. Enabling deployment to edge devices, iot, phones, and
 * other systems as well as ensuring shims for other languages can be built to enable further direct to codebase
 * integrations of TPipe.
 */

plugins {
    alias(libs.plugins.kotlin.jvm) version "2.2.20"
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
    id("org.graalvm.buildtools.native") version "0.10.0"
    alias(libs.plugins.shadow)
    `maven-publish`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))   // compileJava → 24
    }
    withSourcesJar()
    withJavadocJar()

    // TPipeBootstrap.java lives under src/main/kotlin/ alongside its Kotlin
    // siblings. The Kotlin gradle plugin's default compileJava task only scans
    // src/main/java/, so the .java file would be silently skipped. Add this
    // sourceset so the 108 @CEntryPoint methods are actually compiled and
    // exported by native-image.
    sourceSets {
        named("main") {
            java.srcDir("src/main/kotlin")
        }
    }
}

kotlin {
    // Pick a JDK 24 toolchain for kotlinc itself
    jvmToolchain(24)

    // Emit 24-bytecode
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)                   // compileKotlin → 24
    }
}

// Bundle LICENSE into the main JAR so published artifacts include it
val licenseJar by tasks.registering(Jar::class) {
    archiveClassifier.set("license")
    from(rootProject.file("LICENSE"))
}

// Phase 2: when :TPipe adds :TPipe-Ollama and :TPipe-Bedrock to its
// runtime classpath, those jars transitively appear in :TPipe-MCP's
// runtime classpath (because :TPipe-MCP `implementation(project(":"))`).
// :TPipe-MCP:jar is a custom uber-jar task that does
// `from(configurations.runtimeClasspath.get().files)`, so it
// reads the new provider jars' outputs. Gradle 8.14.3's strict
// `validation_problems` rule then fails the build with
// "Task :TPipe-MCP:jar uses this output of task :TPipe-Ollama:jar
// without declaring an explicit or implicit dependency." Fix it
// by declaring :TPipe-MCP:jar's `dependsOn` here, from the root
// project, so we don't have to touch :TPipe-MCP's own build script.
subprojects {
    tasks.matching { it.name == "jar" && it.project.path == ":TPipe-MCP" }.configureEach {
        dependsOn(
            ":TPipe-Ollama:jar",
            ":TPipe-Bedrock:jar",
            // Phase 4: same Gradle 8.14.3 strict-validation rule applies to the
            // two new runtimeOnly providers — :TPipe-MCP:jar reads them via
            // configurations.runtimeClasspath and needs an explicit task
            // ordering dep declared from the root project.
            ":TPipe-OpenRouter:jar",
            ":TPipe-GenericOpenAI:jar"
        )
    }
}

artifacts {
    add("archives", licenseJar)
}

group = "com.TTT"
version = "1.0.0"

application {
    mainClass = "com.TTT.ApplicationKt"
}

repositories {
    mavenCentral()
}

dependencies {
    // Server
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.netty)
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Client
    implementation("io.ktor:ktor-client-core:${libs.versions.ktor.version.get()}")
    implementation("io.ktor:ktor-client-cio:${libs.versions.ktor.version.get()}")
    implementation("io.ktor:ktor-client-content-negotiation:${libs.versions.ktor.version.get()}")

    // Scripting
    implementation(kotlin("scripting-jsr223"))
    implementation(kotlin("scripting-jvm-host"))

    // Logging
    implementation(libs.logback.classic)

    // TPipe-MCP for server hosting modes
    // Using runtimeOnly to avoid circular dependency at compile time.
    runtimeOnly(project(":TPipe-MCP"))

    // Provider sub-modules — wired in as `runtimeOnly` (not `implementation`)
    // because the sub-modules already declare `implementation(project(":"))`,
    // which would create a compile-time task cycle if :TPipe also depended
    // on them via `implementation`. The existing `runtimeOnly(project(":TPipe-MCP"))`
    // line above uses the same pattern to break that cycle; we mirror it here.
    // The classes are still on the main :TPipe runtime classpath (so GraalVM
    // native-image can resolve them via the `-H:Class=...` reachability hints
    // further down in this file) and on the test classpath via
    // `testImplementation(...)` below.
    runtimeOnly(project(":TPipe-Ollama"))
    runtimeOnly(project(":TPipe-Bedrock"))
    // Phase 4: wire the two pre-existing-but-unwired provider sub-modules so
    // NativeBridge.pipeCreate can construct their Pipe classes via
    // Class.forName + reflective ctor newInstance(). Same reasoning as the
    // Ollama/Bedrock lines above.
    runtimeOnly(project(":TPipe-OpenRouter"))
    runtimeOnly(project(":TPipe-GenericOpenAI"))

    // MCP Server Hosting
    implementation("io.modelcontextprotocol:kotlin-sdk:0.11.1")
    implementation("io.ktor:ktor-server-cio:3.3.3")
    implementation("io.ktor:ktor-server-auth:3.3.3")

    // GraalVM SDK — required at compile time for TPipeBootstrap.java's
    // @CEntryPoint, @CContext, WordBase, CCharPointer, IsolateThread, etc.
    // The native-image plugin auto-adds these to the runtime classpath but
    // NOT to the compileJava classpath, so we declare them explicitly.
    // Note: GraalVM 24.0.2 removed `org.graalvm.word.Word`. The replacement
    // is `org.graalvm.word.WordBase`, which is API-compatible for the
    // operations TPipeBootstrap.java uses (rawValue, equals, hashCode).
    compileOnly("org.graalvm.sdk:graal-sdk:24.0.2")
    compileOnly("org.graalvm.sdk:nativeimage:24.0.2")
    compileOnly("org.graalvm.sdk:word:24.0.2")
    compileOnly("org.graalvm.sdk:collections:24.0.2")

    // Testing
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
    // Phase 2: provider classpath reachability test. The test file
    // (ProviderClasspathTest.kt) references ollamaPipe.OllamaPipe and
    // bedrockPipe.BedrockPipe directly via `::class.java`, which requires
    // the classes to be on the test compile classpath. testImplementation
    // does NOT create a compile-time cycle because the providers are only
    // referenced from :TPipe's test source set — the main :TPipe source
    // set never depends on them at compile time.
    testImplementation(project(":TPipe-Ollama"))
    testImplementation(project(":TPipe-Bedrock"))
    // Phase 4: mirror the runtimeOnly entries above for the test compile
    // classpath so ProviderClasspathTest can reference the new provider
    // classes via `::class.java` and Class.forName.
    testImplementation(project(":TPipe-OpenRouter"))
    testImplementation(project(":TPipe-GenericOpenAI"))
}

tasks.test {
    jvmArgs("-Xmx512m")
}

graalvmNative {
    val mainBinary = binaries.getByName("main")

    // Build args for debuggability and correctness.
    // NOTE: The previous --features=org.graalvm.nativeimage.impl.InternalResourcesFeature
    // line referenced a class that is not part of the public GraalVM API in 24.x
    // (the correct class is com.oracle.svm.hosted.ResourcesFeature and it is
    // auto-registered, so the explicit --features flag is no longer required).
    mainBinary.buildArgs.addAll(listOf(
        "--shared",
        "-H:+ReportExceptionStackTraces",
        "--no-fallback",
        "--enable-https",
        "-H:+AllowVMInternalThreads",
        // Register the C ABI entry-point class as a reachable image class.
        // Without this, native-image does not discover com.TTT.Native.TPipeBootstrap
        // (reachable only via @CEntryPoint annotations), so the TPipe_* symbols are
        // never emitted into the shared object.
        "-H:Class=com.TTT.Native.TPipeBootstrap",
        // Phase 2: register the provider sub-module classes as reachable.
        // NativeBridge.pipeCreate constructs these via Class.forName + ctor
        // newInstance() in Phase 3/4, which is a reflective code path that
        // native-image cannot discover at build time without an explicit
        // hint. The $-suffixed entries are the auto-generated Companion
        // inner classes that hold @JvmStatic factories; native-image
        // reports them as "Companion class not found" if omitted.
        "-H:Class=ollamaPipe.OllamaPipe",
        "-H:Class=bedrockPipe.BedrockPipe",
        "-H:Class=ollamaPipe.OllamaPipe\$Companion",
        "-H:Class=bedrockPipe.BedrockPipe\$Companion",
        // Phase 4: register the two pre-existing-but-unwired provider classes
        // as reachable image classes. NativeBridge.pipeCreate constructs them
        // via Class.forName + ctor newInstance() for the C ABI ids 10 and 11.
        "-H:Class=openrouterPipe.OpenRouterPipe",
        "-H:Class=genericOpenAIPipe.GenericOpenAIPipe",
        "-H:Class=openrouterPipe.OpenRouterPipe\$Companion",
        "-H:Class=genericOpenAIPipe.GenericOpenAIPipe\$Companion",
        // Phase 5: Manifold is constructed via Manifold() in
        // NativeBridge.manifoldCreate. Native-image cannot discover
        // reflective constructors of a class only referenced through the
        // C ABI's @CEntryPoint -> NativeBridge -> ManifoldHandle ->
        // Manifold() chain, so we register it explicitly.
        "-H:Class=com.TTT.Pipeline.Manifold",
        // Phase 11: DistributionGrid is constructed via DistributionGrid()
        // in NativeBridge.distributionGridCreate. Same reasoning as
        // Manifold above — native-image needs an explicit hint to keep
        // the class reachable for the @CEntryPoint code path.
        "-H:Class=com.TTT.Pipeline.DistributionGrid"
    ))

    metadataRepository {
        enabled = true
    }

    // Exclude META-INF/native-image from kotlin-compiler-embeddable JARs.
    // These JARs contain jline native-image configs that reference non-existent
    // reflection-config.json (note: file is named reflect-config.json, not reflection-config.json).
    // jline is used by the Kotlin scripting REPL which we don't use.
    listOf(
        ".*kotlin-compiler-embeddable.*\\.jar",
        ".*kotlin-scripting-compiler-embeddable.*\\.jar",
        ".*kotlin-scripting-compiler-impl-embeddable.*\\.jar",
        ".*jline.*\\.jar"
    ).forEach { pattern ->
        mainBinary.buildArgs.addAll(listOf(
            "--exclude-config", pattern, "^/META-INF/native-image/.*"
        ))
    }
}
