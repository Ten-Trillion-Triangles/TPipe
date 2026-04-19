import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

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

group = "com.TTT"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.modelcontextprotocol:kotlin-sdk:0.11.1")
    implementation("io.ktor:ktor-server-cio:3.3.3")
    implementation("io.ktor:ktor-server-auth:3.3.3")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.20")
}

// Ensure proper source set configuration
sourceSets {
    main {
        kotlin.srcDirs("src/main/kotlin")
        resources.srcDirs("src/main/resources")
    }
    test {
        kotlin.srcDirs("src/test/kotlin")
    }
}

// Handle duplicate resources
tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}