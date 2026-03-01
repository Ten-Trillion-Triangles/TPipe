import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))   // compileJava → 24
    }
}

kotlin {
    // Pick a JDK 24 toolchain for kotlinc itself
    jvmToolchain(21)

    // Emit 24-bytecode
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)                   // compileKotlin → 24
    }
}

group = "com.TTT"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(project(":"))
    
    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}