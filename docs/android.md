# Shank on Android

The same port as the Switch build - the game's PowerPC code statically recompiled to C++ and drawn by Renut on Vulkan -
packaged as an APK with a launcher, an editable on-screen pad, and the phone's own display and thermal controls.

This page is for building it. For the game data: **the APK contains none.** The launcher imports a copy the player
already owns (the `58410A62` package extracted to a folder with `default.xex`) out of the system's folder picker and
keeps it in the app's private storage. Nothing is uploaded, nothing is downloaded.

```text
android/                     the app: Gradle, Kotlin launcher, overlay, resources
  app/src/main/java/com/shank1/port/
    LauncherActivity.kt      import / play / settings / controls
    LauncherView.kt          the launcher's screen (Canvas)
    GameActivity.kt          SDL3's activity + the touch overlay + the in-game menu
    SettingsActivity.kt      picture, speed, textures, touch, storage
    ControlsActivity.kt      the controls editor
    GameData.kt, PackWriter.kt   import and the asset pack
    Prefs.kt                 options; writes shank1.toml
    Native.kt                the JNI surface (pad, display, thermal, frame stats)
    input/                   TouchLayout, TouchControlsView, VirtualPad
    perf/PerformanceManager.kt   display mode, sustained mode, game mode, thermal, frame pacing
port/src/android/            the native side of all that
  android_jni.cpp            the Kotlin side's door (and the frame-stats call back)
  android_pad.h/.cpp         the virtual pad the game's XInput reads
  android_frame_pacing.h     refresh rate, frame budget, thermal -> render scale
  android_paths.h/.cpp       --game-root / --config, and the launcher's own options
  android_textures.h/.cpp    BC, ETC2 or RGBA8
  etc2_encode.h              the BC1/3/4/5 -> ETC2/EAC transcoders
```

## Does building the APK need the game files?

**Yes - your own copy, at build time, for two steps (and for nothing else):**

1. **The code generator reads `default.xex`.** `rexglue codegen` turns the game's PowerPC code into the ~100 MB of C++
   that `libmain.so` is compiled from. Without your `default.xex` there is no `port/generated/default`, so the native
   library cannot be built and there is no APK. This is the same step as for the Switch build
   ([building.md](building.md), step 3).
2. **The shader cache comes from your copy.** Renut translates the game's Xenos shaders with DXC on the PC; the phone
   only carries the result (step 3 below).

What the game files do **not** affect: the Kotlin side, the launcher, the resources. `./gradlew assembleDebug` gets as
far as configuring CMake without them and then fails in codegen's absence, not in the app.

And what the APK never contains: the game's data. Textures, audio, levels and `default.xex` are imported by the player
at runtime through the launcher, so the APK is the program and the touch layouts - a few tens of megabytes, the same
`SHANK1_RENUT_RELEASE_SHADERS` idea as the NSP (program only). The shader cache *is* game data: a personal build may
bake it in, an APK you hand to someone else may not.

## What you need

- VS2022 (or LLVM/clang 20+) and Python 3 on a PC, as for the console build: [building.md](building.md), steps 1-2.
  The recompiled game (~22,000 functions, ~100 MB of generated C++) is built on the PC and is **not** in this
  repository.
- Android Studio (or the command-line SDK) with **NDK 26.3.11579264**, CMake 3.25+, JDK 17.
- An SDL3 source tree (the SDK's `thirdparty/sdl3` submodule).
- Your own copy of Shank.

## Build

### 1. Generate and build the game's code for the PC, as usual

```powershell
rexglue.exe codegen shank1_manifest.toml --ignore-stamp
```

The generated tree is shared by every platform: the same C++ is compiled for the phone.

### 2. SDL3: the library and its Java classes

The app extends `org.libsdl.app.SDLActivity`, which lives in SDL3's Android project, not in the APK's own sources
(it is zlib-licensed; copying it in at build time keeps the licence obvious and the tree honest):

```powershell
cd android
gradle wrapper --gradle-version 8.7      # once: the wrapper's jar and scripts are not committed
./gradlew assembleRelease `
    -Pshank1.sdl3Java=<SDL3>/android-project/app/src/main/java `
    -Pshank1.sdl3Dir=<SDL3>
```

`syncSdlJava` copies `org/libsdl/app/*.java` into `app/src/main/java/org/libsdl/app/` (git-ignored) and fails with a
message naming the flag when it is missing. `shank1.sdl3Dir` goes to CMake as `-DSHANK1_SDL3_DIR`, and SDL3 is built as
a subproject and linked into `libmain.so`.

### 3. Renut's shader cache (from your copy)

Renut translates the game's Xenos shaders with DXC, which is a Windows host tool: it cannot run while cross-compiling
to Android, exactly as on the Switch. It needs your `data-360/bootstrap/shaders/*.fxc` and, for the 21 shaders the
game compiles at run time from HLSL text, one PC run through the title screen and a cutscene
([building.md](building.md), step 5):

```powershell
python tools/shank1_dump_shaders.py --game ../extracted --out out/renut_shader_dump
# ... the PC build with -DSHANK1_RENUT_RENDERER=ON, run once: it captures what the cache lacks into
#     out/build/pc-renut/shader_capture/
python tools/renut_switch_shader_cache.py --pc out/build/pc-renut
```

That writes `out/renut_switch/shader_cache_switch.cpp` (+ `spirv_cache.zst`, which the .cpp embeds with `.incbin`),
which the Android build compiles in - the same file the Switch build uses, because it is the renderer's SPIR-V cache
with the DXIL arrays stubbed out. Override the path with `-Pshank1.shaderCache=<file>` if you keep it elsewhere:

```powershell
./gradlew assembleRelease -Pshank1.shaderCache=<that>/shader_cache_switch.cpp
```

Without it the APK builds and starts but draws nothing; the build says so in a warning. The cache is game data: fine in
your own build, not fine in one you distribute.

### 4. The APK

```powershell
./gradlew assembleRelease      # android/app/build/outputs/apk/release/app-release.apk
./gradlew installRelease       # to a device with adb
```

`arm64-v8a` only: the recompiled game is a 64-bit AArch64 binary. `minSdk 26` (Android 8), `targetSdk 34`, Vulkan 1.1
required (the manifest says `0x400003`: 1.1, no optional features).

## First run

1. **Import game data** → pick the folder that holds `default.xex` (or a folder one or two levels above it: the
   launcher finds it). The files are copied into the app's storage and packed into `game_root.nxpack` +
   `game_root.nxidx`, then the loose copy is deleted - one read of ~1.2 GB and every later launch opens one file
   instead of twenty thousand. Roughly 2.5 GB free is needed during the import.
2. **Touch controls** → the layout is already the Xbox 360 pad by position. Drag anything to move it, pinch it to
   resize it, hold a chip in the bar for copy/hide/delete, add elements, fade them, save.
3. **Play**.

The back gesture opens the in-game menu (resume, hide the controls, edit them, quit) - it never leaves the game in one
step, because on a phone that gesture is a stray thumb away at all times.

## The touch controls

A phone has no buttons, so the overlay is not a row of fake keys: it is a pad, and the game reads it through
`XInputGetState` like a real controller.

| Element | What it drives |
| --- | --- |
| Stick | a stick, -1..1, with a dead zone (0.14), a response curve (1.35) and an optional *follow* centre |
| D-pad | up/down/left/right, 8-way |
| Trigger | an analog trigger, 0..1, pulled downwards |
| Button | any of A/B/X/Y/LB/RB/LT/RT/Start/Back/L3/R3 |

Positions are fractions of the safe drawing area (the window minus the notch and the gesture bar) and sizes are
fractions of its height, so a layout made on a phone is the same layout on a tablet and in the other orientation. Two
layouts are saved separately: `filesDir/touch/landscape.json` and `portrait.json`; the defaults are
`assets/touch/default_*.json`. A Bluetooth pad works at the same time: the two are ORed, and the overlay can put
itself away while a pad is connected (Settings → *Hide with a controller*).

The pad state reaches the game through one JNI call per change (not per frame) into a seqlock-protected struct
(`port/src/android/android_pad.cpp`), so a touch cannot stall a frame and nothing is polled.

## Performance

What the port does on top of the console build:

- **Vulkan, not a translation layer.** Renut draws at the D3D9 level the game talks to, as on the Switch; on Android
  the surface is the activity's `ANativeWindow` (`VK_KHR_android_surface`), the backend is `renut_gpu_backend =
  "vulkan"`.
- **Frame budget from the panel.** `PerformanceManager` measures the refresh rate with `Choreographer` and hands the
  period to the engine (`android_frame_pacing.cpp`): 120 Hz gets 8.3 ms, 60 Hz 16.7 ms, and a frame that misses its
  slot is skipped instead of doubled. The display is asked for its fastest mode; 60/90/120/144 can be forced.
- **Sustained performance and game mode.** `setSustainedPerformanceMode` (API 24+) and `Activity.setGameMode`
  (API 31+) so the governor holds its clocks instead of boosting and dropping.
- **Thermal scaling.** `PowerManager`'s thermal status is mirrored into the engine: at MODERATE the render scale drops
  to 0.75x, at CRITICAL to 0.5x, and it comes back when the phone cools. A resolution that steps down looks better
  than a game the governor slows to 20 fps.
- **One asset pack.** `game_root.nxpack` is the SDK's `PackDevice` format (version 2, FNV-1a 64), built on the device
  after the import, so a launch is one `open` instead of thousands.
- **Textures as blocks, not as pixels.** See below: the phone never sees a decompressed texture on the upload path.
- **Nothing per frame on the Kotlin side.** The overlay redraws only when a finger moves a control or a value changes;
  the frame-stats callback fires once a second and only when the counter is on.

## ETC2: what it is for here, and what it is not

The Xenos' DXT formats are BC1/BC2/BC3/BC4/BC5, and Vulkan has exactly those - so the honest answer to "do we need
ETC2" is **only on the phones that do not have them**:

1. If the device advertises `textureCompressionBC` (Tegra, some Adreno, most desktops), the guest's blocks are used
   as they are. Nothing happens: no conversion, no quality loss, no load time.
2. Otherwise (most Adreno and Mali) the sample-on-upload path transcodes each 4x4 block from BC1/BC3/BC4/BC5 to
   ETC2 RGB / ETC2 RGBA / EAC R11 / EAC RG11 - the same block geometry, the same 8 or 16 bytes, a format every
   Vulkan 1.0 implementation must support. `port/src/android/etc2_encode.h` is the transcoder; it decodes the guest
   block, searches the ETC2 mode and modifier tables, and writes the host block. Measured on synthetic tiles
   (`python port/tools/etc2_check.py`, PSNR in dB against the DXT data the guest samples, higher is better):

   | tile | BC1 -> ETC2 RGB | BC3 -> ETC2 RGBA | BC4 -> EAC R11 |
   | --- | --- | --- | --- |
   | flat | 26.2 | 27.4 | exact |
   | gradient | 26.5 | 27.8 | 50.8 |
   | edges | 26.0 | 27.3 | exact |
   | noise | 19.1 | 20.4 | 27.3 |
   | **overall** | **23.9** | **25.2** | **35.7** |

   That is one to three decibels off a dedicated ETC2 encoder and a generation below the console's own DXT, which is
   what any transcoding path costs. The measured ~6-9 µs per 4x4 block is ~0.5 s of one core for a 1024x1024 texture,
   once, spread over a level's load.
3. **Never re-encode the game's assets offline.** The guest looks its textures up by content hash and address: a
   re-packed asset would break that, and the pack is the console's data. Converting on upload is the only place where
   the format is ours to choose.

Settings offers `rgba8` as the escape hatch (no conversion at all, four times the texture memory) for a phone whose
upload path cannot keep up. Effort 0/1 picks the transcoder's search width (0 is faster, about 0.7 dB worse); the
cache (`android_texture_cache`) keeps converted blocks in `filesDir/texture_cache` so later runs skip the work.

## Hooks into the SDK

The SDK (`tools/sa2-nx`, a submodule) owns the app entry point, the file system and the input plumbing. The Android
build needs these handful of points, all one-liners:

| Where | What |
| --- | --- |
| the SDK's entry point | include `<SDL3/SDL_main.h>` and define `main` (SDL3's Java activity calls `SDL_main` in `libmain.so`); call `shank1::android::ParseCommandLine(argc, argv)` before anything opens a file, then hand `--config` to the engine's cvar loader |
| the SDK's host file layer | treat `shank1::android::GameRoot()` as the game's root: a directory, or the `.nxpack` through `PackDevice` (`GameRootIsPack()`) |
| the SDK's `XInputGetState` | OR in `shank1::android::android_pad::Read()` while `Active()`, with sticks scaled to the same -32768..32767 range |
| the SDK's display sync / frame limiter | use `shank1::android::pacing::FrameBudgetNanos()` and call `ReportFrameTime()` once per presented frame |
| the render mode (`src/me/sa2_render_mode.h`) | take the render scale from `shank1::android::pacing::DesiredScaleQuarters()` |
| the Vulkan backend, once the physical device is known | `shank1::android::textures::SetDeviceSupportsBC(bool)` from `VkPhysicalDeviceFeatures::textureCompressionBC` |
| the renderer's present loop | `shank1::android::jni::ReportFrame(nanos)` (declared in `port/src/android/android_jni.cpp`) |

Two things may need a name: `plume::RenderWindow(ANativeWindow*)` in
`port/src/renut_engine/gpu/backend/native_window.cpp`, and plume's ETC2 format names
(`ETC2_R8G8B8_UNORM`, `ETC2_R8G8B8A8_UNORM`, `EAC_R11_UNORM`, `EAC_R11G11_UNORM`) in
`port/src/renut_engine/gpu/render/format.cpp`. Both are marked in the source; if the SDK's plume spells them
differently, those are the only lines to change.

## Known limits

- Not tested on a device in this repository's CI: there is no Android device in it. The Kotlin and the Gradle files are
  written against the framework APIs they name, the native side against the SDK as described above.
- Audio, video (the cutscene movies) and the achievements/overlay menus come from the SDK's own platform layer; the
  Switch build's ATL/VP9 notes in [building.md](building.md) apply unchanged.
- A phone that reports `textureCompressionBC` will look exactly like the console build; one that does not loses a
  generation of texture quality to the transcode, by design.
