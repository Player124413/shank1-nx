package com.shank1.port

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * The port's options: what the player can change without a controller, and how they reach the engine.
 *
 * Two places, on purpose:
 *  - the touch overlay's own options stay here (SharedPreferences): they belong to the Kotlin side;
 *  - the renderer's options are written into `filesDir/shank1.toml`, the same cvar file the console build reads
 *    (`renut_resolution`, `renut_anisotropic_filtering`, `renut_vsync`, `sa2_vsync_relaxed`, and the Android-only
 *    `android_texture_format`). GameActivity passes it to the game as `--config=`.
 */
class Prefs(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("shank1", Context.MODE_PRIVATE)

    // --- Renderer (mirrored into shank1.toml) ---------------------------------------------------------------------

    var resolution: String
        get() = prefs.getString("resolution", "720p") ?: "720p"
        set(value) { prefs.edit().putString("resolution", value).apply(); writeToml() }

    var anisotropy: String
        get() = prefs.getString("anisotropy", "Game") ?: "Game"
        set(value) { prefs.edit().putString("anisotropy", value).apply(); writeToml() }

    var vsync: Boolean
        get() = prefs.getBoolean("vsync", true)
        set(value) { prefs.edit().putBoolean("vsync", value).apply(); writeToml() }

    /** A frame that missed its refresh is shown at once instead of waiting for the next one. */
    var relaxedVsync: Boolean
        get() = prefs.getBoolean("relaxed_vsync", true)
        set(value) { prefs.edit().putBoolean("relaxed_vsync", value).apply(); writeToml() }

    /** "etc2" (the default: the game's DXT data transcoded as it is uploaded) or "rgba8" (no compression: more
     *  memory, no transcode time — for a device that struggles; docs/android.md). */
    var textureFormat: String
        get() = prefs.getString("texture_format", "etc2") ?: "etc2"
        set(value) { prefs.edit().putString("texture_format", value).apply(); writeToml() }

    /** 0 = off, 1 = the default search, 2 = the thorough one (slower first load, better picture). */
    var etc2Effort: Int
        get() = prefs.getInt("etc2_effort", 1)
        set(value) { prefs.edit().putInt("etc2_effort", value).apply(); writeToml() }

    /** Keep the transcoded blocks on disk: the second and later visits skip the transcode completely. */
    var textureCache: Boolean
        get() = prefs.getBoolean("texture_cache", true)
        set(value) { prefs.edit().putBoolean("texture_cache", value).apply(); writeToml() }

    /** The display mode the game asks the system for: "auto" (the panel's fastest), or a fixed rate. */
    var refreshRate: String
        get() = prefs.getString("refresh_rate", "auto") ?: "auto"
        set(value) { prefs.edit().putString("refresh_rate", value).apply() }

    /** Turn the system's sustained-performance mode on while playing (stops the phone from boosting then dropping). */
    var sustainedPerformance: Boolean
        get() = prefs.getBoolean("sustained", true)
        set(value) { prefs.edit().putBoolean("sustained", value).apply() }

    /** Ask for the game mode (API 31+) so the system keeps the clocks steady. */
    var gameMode: Boolean
        get() = prefs.getBoolean("game_mode", true)
        set(value) { prefs.edit().putBoolean("game_mode", value).apply() }

    var showFps: Boolean
        get() = prefs.getBoolean("show_fps", false)
        set(value) { prefs.edit().putBoolean("show_fps", value).apply() }

    // --- Touch overlay --------------------------------------------------------------------------------------------

    /** The overlay's opacity multiplier on top of each element's own alpha. */
    var overlayAlpha: Float
        get() = prefs.getFloat("overlay_alpha", 1f)
        set(value) { prefs.edit().putFloat("overlay_alpha", value).apply() }

    var overlayEnabled: Boolean
        get() = prefs.getBoolean("overlay", true)
        set(value) { prefs.edit().putBoolean("overlay", value).apply() }

    /** Hide the overlay while a real gamepad is connected. */
    var hideWithGamepad: Boolean
        get() = prefs.getBoolean("hide_gamepad", true)
        set(value) { prefs.edit().putBoolean("hide_gamepad", value).apply() }

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) { prefs.edit().putBoolean("haptics", value).apply() }

    var editLocked: Boolean
        get() = prefs.getBoolean("edit_locked", false)
        set(value) { prefs.edit().putBoolean("edit_locked", value).apply() }

    /** "landscape", "portrait" or "auto": which saved layout the game uses. */
    var layoutProfile: String
        get() = prefs.getString("layout_profile", "auto") ?: "auto"
        set(value) { prefs.edit().putString("layout_profile", value).apply() }

    // --- The cvar file --------------------------------------------------------------------------------------------

    fun tomlFile(): File = File(appContext.filesDir, "shank1.toml")

    /** Writes the renderer's options where the engine reads them. Called on every change: the file is tiny. */
    fun writeToml() {
        tomlFile().writeText(
            buildString {
                appendLine("# Written by the Android launcher (Settings). GameActivity passes it as --config=.")
                appendLine("renut_gpu_backend = \"vulkan\"")
                appendLine("renut_resolution = \"$resolution\"")
                appendLine("renut_anisotropic_filtering = \"$anisotropy\"")
                appendLine("renut_vsync = $vsync")
                appendLine("sa2_vsync_relaxed = $relaxedVsync")
                appendLine("android_texture_format = \"$textureFormat\"")
                appendLine("android_etc2_effort = $etc2Effort")
                appendLine("android_texture_cache = $textureCache")
            },
        )
    }
}
