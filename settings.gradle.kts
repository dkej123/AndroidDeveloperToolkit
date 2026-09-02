rootProject.name = "adb-toolbox"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Module graph and dependency direction fixed by docs/adr/0001-module-layout-and-dependency-direction.md:
// :intellij -> :application -> :domain, with :adapters-jvm/:adapters-adb depending on :domain only
// and wired in by :intellij at the composition root.
include(":domain", ":application", ":adapters-jvm", ":adapters-adb", ":intellij")
