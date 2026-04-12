import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
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

group = "com.TTT.Tuner"
version = "0.0.1"

application {
    mainClass.set("com.TTT.Tuner.TunerAppKt")
}

// Configure run task to pass system properties
tasks.named<JavaExec>("run") {
    // Forward all system properties from Gradle to the application
    systemProperties = System.getProperties().entries.associate { it.key.toString() to it.value }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
