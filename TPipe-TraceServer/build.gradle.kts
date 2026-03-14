plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.TTT.TraceServer.TraceServerDemoKt")
}

dependencies {
    implementation(project(":"))

    implementation("io.ktor:ktor-server-core:2.3.11")
    implementation("io.ktor:ktor-server-netty:2.3.11")
    implementation("io.ktor:ktor-server-websockets:2.3.11")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.11")
    implementation("io.ktor:ktor-server-cors:2.3.11")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.11")

    // Explicitly align coroutines version with what Ktor 2.3.11 expects to avoid LockFreeLinkedListHead runtime exception
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("io.ktor:ktor-server-tests:2.3.11")
    testImplementation("com.microsoft.playwright:playwright:1.42.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()

    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1") // We already have it from node script or we'll bypass validation
    environment("PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS", "true")

    // Download Playwright browsers automatically on test run
    doFirst {
        javaexec {
            mainClass.set("com.microsoft.playwright.CLI")
            classpath = sourceSets["test"].runtimeClasspath
            args = listOf("install", "chromium")
        }
    }
}
