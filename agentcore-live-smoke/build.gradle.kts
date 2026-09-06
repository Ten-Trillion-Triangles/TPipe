import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    application
}

group = "com.TTT"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
}

kotlin {
    jvmToolchain(24)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)
    }
}

repositories {
    mavenCentral()
}

application {
    mainClass = "com.TTT.AgentCore.LiveSmoke.LiveSmokeRunnerKt"
}

dependencies {
    // The AgentCore module intentionally keeps its TPipe/MCP implementation
    // dependencies non-transitive. The harness uses those APIs directly.
    implementation(project(":"))
    implementation(project(":TPipe-MCP"))
    implementation(project(":TPipe-AgentCore"))
    implementation(project(":TPipe-Bedrock"))
    implementation("aws.sdk.kotlin:bedrockagentcore:1.6.107")
    implementation("aws.sdk.kotlin:bedrockagentcorecontrol:1.6.107")
    implementation("aws.sdk.kotlin:aws-core:1.6.107")
    implementation("aws.sdk.kotlin:bedrockruntime:1.6.107")
    implementation("io.ktor:ktor-client-websockets:3.3.3")
    implementation("io.ktor:ktor-client-cio:3.3.3")
    implementation("io.ktor:ktor-server-core:3.3.3")
    implementation("io.ktor:ktor-server-cio:3.3.3")
    implementation("io.opentelemetry:opentelemetry-sdk:1.54.1")
    implementation("io.opentelemetry:opentelemetry-sdk-common:1.54.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    useJUnitPlatform()
    onlyIf {
        providers.gradleProperty("agentcoreLiveSmokeTests")
            .map(String::toBoolean)
            .orElse(false)
            .get()
    }
}

tasks.register("liveSmoke") {
    group = "verification"
    description = "Runs the explicitly configured AgentCore live smoke harness."
    dependsOn(tasks.named("run"))
}

tasks.named<JavaExec>("run") {
    // TPipe-MCP's published jar is intentionally an all-in-one application
    // jar. Use its compiled classes here so its bundled older coroutine
    // classes cannot shadow the AWS/Ktor runtime selected for this harness.
    dependsOn(":TPipe-MCP:classes")
    val mcpClasses = project(":TPipe-MCP").layout.buildDirectory.dir("classes/kotlin/main")
    val mcpResources = project(":TPipe-MCP").layout.buildDirectory.dir("resources/main")
    classpath = classpath.filter { !it.name.endsWith("-all.jar") } +
        files(mcpClasses, mcpResources)
}

tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set("agentcore-live-smoke-all.jar")
    mergeServiceFiles()
    exclude("module-info.class")
    exclude("META-INF/versions/**/module-info.class")
    manifest {
        attributes["Main-Class"] = "com.TTT.AgentCore.LiveSmoke.LiveSmokeRunnerKt"
    }
}
