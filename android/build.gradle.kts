// The build needs the Android SDK (platform 34 and build-tools) and the NDK, and points CMake at port/CMakeLists.txt
// with the same options the console build uses. See docs/android.md, "Building the APK".

plugins {
    id("com.android.application") version "8.5.2" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
