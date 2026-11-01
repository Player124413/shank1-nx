/**
 * @file    android/android_paths.h
 *
 * Where the game's data and options live on an Android device, as handed over by the launcher:
 *
 *     shank1 --game-root=/data/user/0/com.shank1.port/files/game_root.nxpack \
 *            --config=/data/user/0/com.shank1.port/files/shank1.toml
 *
 * The launcher (GameActivity.getArguments) builds that argv; SDL passes it to the native entry point, which calls
 * ParseCommandLine() before it touches the file system. `--game-root` is either the asset pack (`.nxpack`, read
 * through the SDK's PackDevice) or a directory that holds `default.xex`.
 */
#pragma once

#include <string>

namespace shank1::android {

/** Reads `--game-root=` and `--config=` out of argv, remembering the app's own directories. Safe to call twice. */
void ParseCommandLine(int argc, char **argv);

/** The imported game data: the pack's path, or the folder the engine should treat as its root. Empty until parsed. */
const std::string &GameRoot();

/** True when GameRoot() is an asset pack rather than a directory. */
bool GameRootIsPack();

/** `filesDir/shank1.toml`, the cvar file the engine reads. Empty when the launcher did not pass one. */
const std::string &ConfigPath();

/**
 * A value out of the launcher's shank1.toml, for the Android-only options the engine does not know about
 * (`android_texture_format`, `android_etc2_effort`, `android_texture_cache`). Reads the file once, on the first call;
 * a missing file or key answers [fallback], so a build without a launcher still runs.
 */
std::string OptionValue(const char *key, const char *fallback);

/** OptionValue as a bool: `true`/`1`/`on`/`yes` are true, anything else (and a missing key) is [fallback]. */
bool OptionFlag(const char *key, bool fallback);

} // namespace shank1::android
