# shank1 (Shank, XBLA 58410A62) - ReXGlue Recompiled Project
#
# SDK boilerplate lives in generated/rexglue.cmake.
#
# PC:      cmake --preset win-amd64-release -DCMAKE_PREFIX_PATH=<installed sa2-nx SDK>
# Switch:  cmake -S . -B out/nx -G Ninja -DCMAKE_BUILD_TYPE=Release
#            -DCMAKE_TOOLCHAIN_FILE=<sa2-nx>/tools/switch/cmake/switch-devkitA64.cmake
#            -DREXSDK_DIR=<sa2-nx>/sdk -DREXGLUE_SWITCH_NVK_SDK=<nvk-sdk>

cmake_minimum_required(VERSION 3.25)
project(shank1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# -mearly-ra=none for everything compiled for the console (as sa2-nx, peggle2-nx and banjo-nx do): devkitA64 GCC's
# AArch64 early register allocation pass dropped table lookups of a NEON permute helper and produced garbage on the
# console only. Set before the SDK is added so its libraries and the LTO link get it too.
if(REXGLUE_PLATFORM_SWITCH)
    add_compile_options(-mearly-ra=none)
    add_link_options(-mearly-ra=none)
endif()

include(generated/rexglue.cmake)

# The SDK sources the Renut engine includes from (src/core headers); the PC build links the installed SDK package,
# the Switch build adds the source tree itself (REXSDK_DIR).
set(SHANK1_SDK_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../tools/sa2-nx/sdk" CACHE PATH "sa2-nx SDK source tree (headers)")

# Sources
set(SHANK1_SOURCES
    src/main.cpp
    src/shank1_compat.cpp
    src/shank1_nsp.cpp
    src/shank1_timing.cpp
)

# Android: the port's own pieces for the phone - the touch pad's JNI bridge, the launcher's argv, the display and
# thermal pacing, and the texture format decision.
if(REXGLUE_PLATFORM_ANDROID)
    list(APPEND SHANK1_SOURCES
        src/android/android_frame_pacing.cpp
        src/android/android_jni.cpp
        src/android/android_pad.cpp
        src/android/android_paths.cpp
        src/android/android_textures.cpp)
endif()

if(REXGLUE_PLATFORM_ANDROID)
    # One shared library, libmain.so: Android's launcher (through SDL3's Java activity, android:lib_name="main")
    # starts it with android_main. The Kotlin side loads the same library, which is where the touch bridge lives.
    add_library(shank1 SHARED ${SHANK1_SOURCES})
    set_target_properties(shank1 PROPERTIES OUTPUT_NAME main)
    target_compile_definitions(shank1 PRIVATE REXGLUE_PLATFORM_ANDROID=1)
elseif(WIN32)
    add_executable(shank1 WIN32 ${SHANK1_SOURCES})
else()
    add_executable(shank1 ${SHANK1_SOURCES})
endif()
target_include_directories(shank1 PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src" "${SHANK1_SDK_SRC}/src/core")

# Renut's Direct3D-level renderer (sa2-nx's copy of reNut): no PM4 ring, no EDRAM. Chosen at boot with
# sa2_renderer = "renut" (the cvar keeps its sa2-nx name); without that key the generic Xenos GPU emulation runs.
option(SHANK1_RENUT_RENDERER "Link Renut's Direct3D-level renderer (sa2_renderer = renut)" ON)

if(REXGLUE_PLATFORM_ANDROID)
    # The Xenos GPU emulation plugin is linked in, as on the console: no dynamic loading in an APK either.
    rexglue_setup_target(shank1 GPU_PLUGINS xenos)
elseif(REXGLUE_PLATFORM_SWITCH)
    # One static NRO: the Xenos GPU emulation plugin is linked in (no dynamic loading on the console).
    rexglue_setup_target(shank1 GPU_PLUGINS xenos)
    target_compile_definitions(rexcore PRIVATE REX_SWITCH_FALLBACK_EXE="/switch/shank1-nx/shank1.nro")
    # The homebrew menu icon must be a 256x256 JPEG (made from the user's icon.ico, opaque).
    rexglue_switch_add_nro(shank1 NAME "Shank" AUTHOR "github.com/zhtipu" VERSION "0.1.0"
        ICON "${CMAKE_CURRENT_SOURCE_DIR}/assets/icon.jpg")
else()
    rexglue_setup_target(shank1)
endif()

if(SHANK1_RENUT_RENDERER)
    include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/shank1_renut_gpu.cmake")
endif()

# --- Android ---------------------------------------------------------------------------------------------------------
#
# ETC2/EAC transcoding of the game's DXT textures (src/android/android_textures.h, src/android/etc2_encode.h). On by
# default: most phones have no textureCompressionBC, and the port then converts each block as it is uploaded instead of
# decompressing it to four times the memory. Off = the console's BC blocks are used as they are.
option(SHANK1_ANDROID_ETC2 "Transcode the game's DXT textures to ETC2/EAC on Android" ON)

if(REXGLUE_PLATFORM_ANDROID)
    if(SHANK1_ANDROID_ETC2)
        target_compile_definitions(shank1 PRIVATE SHANK1_ANDROID_ETC2=1)
    endif()
    # SDL3: the same library the Java classes come from (docs/android.md, step 2). Android has no system SDL, so the
    # tree is built as a subproject of this one and linked statically into libmain.so.
    set(SHANK1_SDL3_DIR "" CACHE PATH "SDL3 source tree for the Android build (git submodule of the SDK)")
    if(NOT SHANK1_SDL3_DIR)
        message(FATAL_ERROR "SHANK1_SDL3_DIR is not set: pass -DSHANK1_SDL3_DIR=<SDL3 tree> (docs/android.md)")
    endif()
    set(SDL_SHARED OFF CACHE BOOL "" FORCE)
    set(SDL_STATIC ON CACHE BOOL "" FORCE)
    set(SDL_TEST OFF CACHE BOOL "" FORCE)
    set(SDL_TESTS OFF CACHE BOOL "" FORCE)
    add_subdirectory("${SHANK1_SDL3_DIR}" "${CMAKE_BINARY_DIR}/sdl3" EXCLUDE_FROM_ALL)
    target_link_libraries(shank1 PRIVATE SDL3::SDL3-static)
    # SDL3 is static here: its Android glue (ANativeActivity_onCreate -> android_main) is only pulled in if the linker
    # is told to keep it, which is what SDL's own Gradle project passes.
    target_link_options(shank1 PRIVATE -Wl,-u,ANativeActivity_onCreate)
    # The JNI entry points and the ANativeWindow in backend/native_window.cpp.
    target_link_libraries(shank1 PRIVATE android log)
endif()
