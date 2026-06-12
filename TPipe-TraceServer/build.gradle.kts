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

    implementation("io.ktor:ktor-server-core:${libs.versions.ktor.version.get()}")
    implementation("io.ktor:ktor-server-netty:${libs.versions.ktor.version.get()}")
    implementation("io.ktor:ktor-server-websockets:${libs.versions.ktor.version.get()}")
    implementation("io.ktor:ktor-server-content-negotiation:${libs.versions.ktor.version.get()}")
    implementation("io.ktor:ktor-server-cors:${libs.versions.ktor.version.get()}")
    implementation("io.ktor:ktor-server-status-pages:${libs.versions.ktor.version.get()}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${libs.versions.ktor.version.get()}")
    // v2 cross-cutting plugins. Ktor 3.x ships these as separate artifacts
    // that are not transitively pulled in by ktor-server-core. All of them
    // are built-in Ktor plugins (no third-party code) and stay
    // GraalVM native-image compatible.
    implementation("io.ktor:ktor-server-compression:${libs.versions.ktor.version.get()}")
    implementation("io.ktor:ktor-server-rate-limit:${libs.versions.ktor.version.get()}")
    implementation("io.ktor:ktor-server-metrics-micrometer:${libs.versions.ktor.version.get()}")
    implementation("io.micrometer:micrometer-registry-prometheus:1.12.13")

    // kotlinx-coroutines and kotlinx-serialization are pinned explicitly so the
    // TraceServer module can reason about GraalVM native-image compatibility
    // (the broadcaster in TraceServer uses Dispatchers.IO from coroutines-core).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("io.ktor:ktor-server-test-host:${libs.versions.ktor.version.get()}")
    testImplementation("io.ktor:ktor-client-core:${libs.versions.ktor.version.get()}")
    testImplementation("io.ktor:ktor-client-cio:${libs.versions.ktor.version.get()}")
    testImplementation("io.ktor:ktor-server-status-pages:${libs.versions.ktor.version.get()}")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
