/**
 * @file    android/android_textures.h
 *
 * How the game's textures become Vulkan's on Android.
 *
 * The console's data is DXT (BC1/2/3/4/5), which is exactly what Vulkan calls BC1/2/3/4/5, so on a phone that
 * advertises `textureCompressionBC` nothing needs converting and the blocks go straight up. When it does not - most
 * Adreno and Mali parts - the choice is:
 *   - **ETC2** (the default): the same 4x4 block, 8 or 16 bytes, in a format every Vulkan 1.0 device must support.
 *     The port transcodes the console's block to an ETC2/EAC block as the texture is uploaded (etc2_encode.h), so the
 *     guest's data is never touched, the memory stays at 4 bits a pixel and no asset is re-encoded offline.
 *   - **RGBA8**: no transcoding at all, four times the texture memory. The escape hatch for a device whose upload path
 *     is too slow to keep up with the transcode, at the cost of a lot of megabytes.
 *
 * The options reach this file through the launcher's shank1.toml (android_texture_format, android_etc2_effort,
 * android_texture_cache).
 */
#pragma once

#include <cstdint>

namespace shank1::android::textures {

enum class TargetFormat {
  /** BC1/2/3/4/5 as the guest stored them: the phone compresses nothing. */
  NativeBC,
  /** BC1 -> ETC2 RGB, BC2/3 -> ETC2 RGBA, BC4 -> EAC R11, BC5 -> EAC RG11, on upload. */
  Etc2,
  /** Everything decompressed to RGBA8 (no block format at all). */
  Rgba8,
};

/**
 * The format the renderer should build textures in. It depends on the device as well as on the option: a phone with
 * `textureCompressionBC` keeps the console's blocks even when the option says ETC2, because there is nothing to gain.
 * Called once per texture format lookup, not per texel.
 */
TargetFormat Format();

/** True when [Format] is Etc2: the shortcut the format table tests. */
bool Etc2Enabled();

/** 0 = the quickest search, 1 = the default, 2 = the thorough one. Read as android_etc2_effort. */
int Effort();

/** Keep transcoded blocks in `filesDir/texture_cache` so later runs skip the work (android_texture_cache). */
bool CacheEnabled();

/** The directory the cache lives in (empty when the launcher passed no --game-root, i.e. no app files directory). */
const char *CacheDirectory();

/**
 * Called by the renderer once it knows the physical device (backend.cpp on the Android path): a phone with
 * `textureCompressionBC` keeps the console's blocks, one without transcodes to ETC2.
 */
void SetDeviceSupportsBC(bool supported);

} // namespace shank1::android::textures
