plugins {
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
}

repositories {
    maven { url = uri("../../build/compatibility-maven") }
    mavenCentral()
}

dependencies {
    implementation("com.github.ten-trillion-triangles:TPipe:1.0.0-compatibility")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

application {
    mainClass.set("compatibility.JavaConsumerMain")
}
