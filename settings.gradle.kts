pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "shadowgps"

// :core is plain Kotlin/JVM and always builds.
include(":core")

// :app needs the Android SDK and the Android Gradle Plugin. Including it on a machine
// without an SDK makes every Gradle invocation fail during configuration, even for
// `:core:test`, so it is opted into automatically when an SDK is visible.
//
//   * Android Studio / IntelliJ  -> writes local.properties with sdk.dir, so it is included.
//   * CI                         -> ANDROID_HOME / ANDROID_SDK_ROOT are set, so it is included.
//   * Headless box with no SDK   -> skipped with a warning; `./gradlew :core:test` still works.
//
// Override either way with -Pshadowgps.android=true|false.
val androidOverride: String? = providers.gradleProperty("shadowgps.android").orNull
val sdkVisible: Boolean = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()

when (androidOverride?.lowercase()) {
    "true" -> include(":app")
    "false" -> logger.lifecycle("shadowgps: :app excluded (-Pshadowgps.android=false).")
    else ->
        if (sdkVisible) {
            include(":app")
        } else {
            logger.warn(
                "shadowgps: no Android SDK found (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties), " +
                    "so the :app module is excluded from this build. The :core module still builds and tests. " +
                    "Force it with -Pshadowgps.android=true once an SDK is installed."
            )
        }
}
