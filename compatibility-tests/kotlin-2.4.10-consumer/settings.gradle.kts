pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("../../build/compatibility-maven") }
        mavenCentral()
    }
}

rootProject.name = "tpipe-kotlin-2-4-consumer"
