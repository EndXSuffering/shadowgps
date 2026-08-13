// Plugin versions live in gradle/libs.versions.toml and are applied per module via
// `alias(libs.plugins.…)`. Nothing is declared here on purpose: naming the Android
// Gradle Plugin at the root — even with `apply false` — forces Gradle to resolve it
// from Google's Maven repo on every build, including builds where the :app module was
// deliberately excluded because no Android SDK is installed.

tasks.register("cleanAll", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
