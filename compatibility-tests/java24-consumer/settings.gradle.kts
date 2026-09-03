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

rootProject.name = "tpipe-java24-consumer"
