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
        // ddmlib (com.android.tools.ddms:ddmlib), used by :adapters-adb's ddmlib transport
        // (docs/adr/0005), is published to Google's Maven repository, not Maven Central.
        google()
    }
}

// Module graph and dependency direction fixed by docs/adr/0001-module-layout-and-dependency-direction.md:
// :intellij -> :application -> :domain, with :adapters-jvm/:adapters-adb depending on :domain only
// and wired in by :intellij at the composition root.
include(":domain", ":application", ":adapters-jvm", ":adapters-adb", ":intellij")
