<div align=center>

<img width="616" height="353" alt="capsule_616x353" src="https://github.com/user-attachments/assets/54108a00-ed00-4292-a191-c0c96868cc35" />

</div>
<h1 align=center>shank1-nx</h1>

A native Nintendo Switch port of **Shank** (Klei Entertainment, Xbox Live Arcade, 2010). It is not an emulator:
the game's PowerPC code is statically recompiled to C++, and it draws through a native renderer on deko3d, the
Switch's own graphics API. It runs at 60 fps.

> [!NOTE]
> You need your own copy of the Xbox Live Arcade game: its content package from an Xbox 360
> (`Content/0000000000000000/58410A62/000D0000/`), or the game extracted from it. This repository and its releases
> contain no game data.

## How to install
<div align=center>
<img width="240" height="224" alt="spider-man-tobey-maguire" src="https://github.com/user-attachments/assets/5fa01c77-f0cd-49c1-a133-7a2308bfc8e1" />
</div>


1. Open the installer page: **https://zhtipu.github.io/shank1-nx-installer/**
2. Choose your game (its package file, the `58410A62` folder, or an extracted folder with `default.xex`) and press
   **Create shank1-nx.zip**. Everything runs in your browser and the game files never leave your computer: the page
   checks your copy, downloads the build and makes the shaders and the game pack from your files.
3. Extract `shank1-nx.zip` into `sdmc:/switch/`.
4. Start it:
   - **NSP (recommended):** install `shank1-nx-x.y.z.nsp` from the [latest release](https://github.com/zhtipu/shank1-nx/releases/latest)
     with your installer of choice and start **Shank** from the HOME Menu. The NSP holds only the program; it reads
     the game and its shaders from `/switch/shank1-nx/`.
   - **NRO:** start `shank1.nro` from the Homebrew Menu in title takeover mode (hold **R** while launching an
     installed game) or with a 39-bit forwarder. The album applet does not give the game enough memory.

Your `shank1-nx` folder:

```text
/switch/shank1-nx/
  shank1.nro
  LICENSES.txt
  deko3d_glsl.bin           (the game's shaders, made from your copy)
  deko3d_shaders.bin        (the shaders compiled for the console; written as you play)
  game_root.nxpack          (the files of your game, in one file)
  game_root.nxidx
  shank1.toml               (optional settings)
```

> [!CAUTION]
> The first time you play, the console compiles each shader the first time it is drawn, so new places can stutter for
> a moment. The compiled shaders are saved in `deko3d_shaders.bin` and later sessions start smoothly.

**Updating:** open the installer page again and press **Create shank1-nx-update.zip** (only the program and the
shaders), extract it over your folder, and install the new NSP if you use it.

## Controls

The Xbox 360 layout on the Switch buttons by position. Stick clicks are L3 / R3. The port's own menus are on
**L + R** with the D-pad (Up: performance overlay, Right: settings, Down: log console, Left: achievements).

## Android

The same port runs as an APK on 64-bit ARM phones and tablets: Vulkan through Renut, a launcher that imports your own
copy of the game with the system's folder picker, and a touch pad you lay out yourself. No game data is in the APK.

- **Touch controls:** a full pad, not a row of keys - left stick (with a follow centre and a response curve), D-pad,
  two analog triggers, A/B/X/Y, bumpers, Start/Back. Drag to move, pinch to resize, hold for copy/hide/delete; saved
  per orientation in `filesDir/touch/`. A Bluetooth controller works at the same time, and can hide the overlay.
- **Performance:** the panel's own refresh rate (60/90/120/144 Hz) drives the frame budget, sustained-performance and
  game mode stop the governor from dropping clocks, the thermal status steps the render scale down before the phone
  throttles itself, and the imported game is packed into one file so a launch opens one file instead of thousands.
- **Textures:** the console's DXT data is used as it is when the phone has `textureCompressionBC`; otherwise each block
  is transcoded to ETC2/EAC as it is uploaded (`port/src/android/etc2_encode.h`), which keeps 4 bits a pixel and never
  re-encodes the game's own files.

Building it needs a PC for the codegen and the shader cache: see [docs/android.md](docs/android.md).

## Known issues
<div align=center>
<img width="273" height="211" alt="giphy" src="https://github.com/user-attachments/assets/43ef00fa-53af-4a22-8761-1eaf5f9054b8" />
</div>
- The cutscene movies' sound can stutter and drift from the picture at the console's stock CPU clock (1020 MHz). They
  play correctly with the CPU at 1785 MHz (for example a sys-clk profile for this title).
- The first visit to a new effect can stutter for a moment while its shader compiles (see above).

## Building

See [docs/building.md](docs/building.md) for the Switch and PC builds, [docs/android.md](docs/android.md) for Android.

## License

GPL-3.0 (`LICENSE`). Third-party code and its licenses: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Shank is a trademark of Klei Entertainment. This project is not affiliated with or endorsed by Klei
Entertainment, Nintendo or Microsoft. Running homebrew requires custom firmware, which is against Nintendo's terms of
service; use it at your own risk.
