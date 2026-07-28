import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
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
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Base module dependency
    implementation(project(":"))
    
    // Provider module dependencies
    implementation(project(":TPipe-Bedrock"))
    implementation(project(":TPipe-Ollama"))
    implementation(project(":TPipe-OpenRouter"))
    implementation(project(":TPipe-GenericOpenAI"))
    implementation(libs.ktor.serialization.kotlinx.json)
    
    // Test dependencies
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.test {
    jvmArgs("-Xmx512m")
}
