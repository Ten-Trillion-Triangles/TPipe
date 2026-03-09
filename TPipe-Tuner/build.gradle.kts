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

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}
