// shank1-nx on Android (docs/android.md).
//
// A plain framework project: no AndroidX, no Compose, nothing to download but the Android platform. The launcher, the
// settings screen and the touch controls draw themselves, which keeps the APK small and the build offline-capable.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "shank1-nx"
include(":app")
