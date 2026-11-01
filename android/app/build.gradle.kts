import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android") version "2.0.20"
}

// SDL's Java classes (zlib licence) are copied in from an SDL3 source checkout when the build is told where it is:
//   ./gradlew assembleRelease -Pshank1.sdl3Java=/path/to/SDL/android-project/app/src/main/java
// or with SHANK1_SDL3_JAVA in the environment. GameActivity extends org.libsdl.app.SDLActivity, so the build without
// them fails with the message below rather than half-working.
val sdl3Java: String? = (project.findProperty("shank1.sdl3Java") as String?) ?: System.getenv("SHANK1_SDL3_JAVA")

// The SDL3 tree itself (built as a CMake subproject and linked into libmain.so) and Renut's translated shader cache
// (built on the PC: a cross build cannot run DXC). Both are passed straight to CMake; see docs/android.md, steps 2-3.
val sdl3Dir: String? = (project.findProperty("shank1.sdl3Dir") as String?) ?: System.getenv("SHANK1_SDL3_DIR")
val shaderCache: String? =
    (project.findProperty("shank1.shaderCache") as String?) ?: System.getenv("SHANK1_SHADER_CACHE")
val sdlPackageDir = layout.projectDirectory.dir("src/main/java/org/libsdl/app")

val syncSdlJava by tasks.registering(Copy::class) {
    description = "Copies SDL3's Java classes into the app so GameActivity can extend SDLActivity."
    val source = sdl3Java?.let { file("$it/org/libsdl/app") }
    if (source == null || !source.isDirectory) {
        doFirst {
            throw GradleException(
                "SDL3's Java classes are not in the tree: pass -Pshank1.sdl3Java=<SDL3>/android-project/app/src/main/java " +
                    "(or set SHANK1_SDL3_JAVA). See docs/android.md, step 2.")
        }
    } else {
        from(source)
        into(sdlPackageDir)
    }
}

// The game's own data (the user's copy of Shank) is never in this repository and never in the APK: the launcher
// imports it on the device. What is packaged is the port's program and the touch layouts.

android {
    namespace = "com.shank1.port"
    compileSdk = 34
    ndkVersion = project.findProperty("shank1.ndkVersion") as String? ?: "26.3.11579264"

    defaultConfig {
        applicationId = "com.shank1.port"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // Only 64-bit ARM: the recompiled game is a 64-bit AArch64 binary with NEON in its hot paths.
        ndk {
            abiFilters += (project.findProperty("shank1.abis") as String? ?: "arm64-v8a").split(",")
        }
        externalNativeBuild {
            cmake {
                // The same sources as the console build (port/CMakeLists.txt). Nothing here is a second fork of the
                // renderer: the Android differences are the window (backend/native_window.cpp), the video and audio
                // device of the SDK and src/android.
                arguments += listOfNotNull(
                    "-DCMAKE_BUILD_TYPE=${project.findProperty("shank1.buildType") ?: "Release"}",
                    "-DREXGLUE_PLATFORM_ANDROID=ON",
                    "-DSHANK1_RENUT_RENDERER=ON",
                    "-DSHANK1_ANDROID_ETC2=${if (project.findProperty("shank1.etc2") == "off") "OFF" else "ON"}",
                    sdl3Dir?.let { "-DSHANK1_SDL3_DIR=$it" },
                    shaderCache?.let { "-DSHANK1_RENUT_SHADER_CACHE_CPP=$it" },
                )
                cppFlags += listOf("-O3", "-fno-exceptions", "-fno-rtti", "-fvisibility=hidden", "-ffunction-sections",
                                   "-fdata-sections")
                abiFilters += (project.findProperty("shank1.abis") as String? ?: "arm64-v8a").split(",")
            }
        }
        // The library keeps its data in filesDir; the launcher imports the game there.
        manifestPlaceholders["shank1Version"] = versionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false // the game is native: R8 has nothing to strip and would only break reflection
            isShrinkResources = false
            ndk {
                // strip the native library: 20-30 MB of debug info is not needed on the device
                debugSymbolLevel = "NONE"
            }
        }
        debug {
            isJniDebuggable = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../../port/CMakeLists.txt")
            version = "3.25.2+"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    packaging {
        // The port's assets are read from the APK's own (uncompressed) entries, so the shader table can be mapped.
        jniLibs.useLegacyPackaging = false
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named("preBuild") { dependsOn(syncSdlJava) }

dependencies {
    // Nothing. The launcher, the settings and the touch controls are framework views and Canvas drawing.
}
