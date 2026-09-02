// Root aggregator. Module graph and dependency direction are fixed by
// docs/adr/0001-module-layout-and-dependency-direction.md; versions are pinned once here (and in
// gradle/libs.versions.toml) per docs/adr/0003-platform-and-toolchain-baseline.md rather than
// restated per module.
plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.intellij.platform) apply false
    alias(libs.plugins.kover)
}

tasks.wrapper {
    gradleVersion = "9.6.1"
    distributionType = Wrapper.DistributionType.BIN
}

// Coverage floor (tasks/001 acceptance criterion: "CI and local builds enforce meaningful 80%
// coverage for changed production logic"). Aggregated over the modules that currently hold
// measurable production logic. :intellij is the IntelliJ Platform composition root (wiring,
// ToolWindowFactory, actions) and is exercised through plugin verification / manual runIde rather
// than line-coverage, per intellij-plugin-development's EDT/disposal-discipline framing.
dependencies {
    kover(project(":domain"))
    kover(project(":application"))
    kover(project(":adapters-jvm"))
    kover(project(":adapters-adb"))
}

kover {
    reports {
        verify {
            rule("Minimum 80% line coverage") {
                minBound(80)
            }
        }
    }
}

val architectureCheck = tasks.register<Exec>("architectureCheck") {
    group = "verification"
    description = "Enforces module dependency direction and forbidden imports (docs/adr/0001, docs/adr/0002)."
    commandLine("bash", "gradle/scripts/check-architecture.sh")
}

tasks.named("check") {
    dependsOn(architectureCheck)
}
