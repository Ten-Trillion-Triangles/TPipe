import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.authentication.http.BasicAuthentication

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `maven-publish`
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
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("io.ktor:ktor-client-mock:3.1.3")
    implementation(project(":"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    implementation("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-cio:3.1.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.github.ten-trillion-triangles"
            artifactId = "TPipe-GenericOpenAI"
            version = (project.findProperty("publishVersion") as String?) ?: project.version.toString()
            from(components["java"])
            pom.withXml {
                val dependencies = asNode().children().filterIsInstance<groovy.util.Node>()
                    .firstOrNull { node -> node.name().toString() == "dependencies" }
                dependencies?.children()?.filterIsInstance<groovy.util.Node>()?.forEach { dependency ->
                    val groupId = dependency.children().filterIsInstance<groovy.util.Node>()
                        .firstOrNull { node -> node.name().toString() == "groupId" }
                    if(groupId?.text() == "com.TTT")
                    {
                        groupId.setValue("com.github.ten-trillion-triangles")
                        dependency.children().filterIsInstance<groovy.util.Node>()
                            .firstOrNull { node -> node.name().toString() == "version" }
                            ?.setValue(version)
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "CodeArtifact"
            val repositoryUrl = (project.findProperty("codeArtifactRepoUrl") as String?)
                ?: "https://tpipe-521369004927.d.codeartifact.us-east-1.amazonaws.com/maven/tpipe-startup-maven/"
            url = uri(repositoryUrl)
            authentication {
                create<BasicAuthentication>("basic")
            }
            credentials {
                username = "aws"
                password = providers.environmentVariable("CODEARTIFACT_AUTH_TOKEN")
                    .orElse(providers.gradleProperty("codeArtifactAuthToken"))
                    .getOrElse("")
            }
        }
        maven {
            name = "CompatibilityRepository"
            url = uri(rootProject.layout.buildDirectory.dir("compatibility-maven"))
        }
    }
}
