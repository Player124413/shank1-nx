package com.shank1.port

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.shank1.port.input.LayoutStore

/**
 * The port's settings, in the order a player meets them: picture, speed, textures, touch, storage.
 *
 * Every renderer option is written into filesDir/shank1.toml by [Prefs.writeToml] the moment it changes, so the next
 * launch of the game picks it up; the touch options are read by GameActivity.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val written = contentResolver.openOutputStream(uri)?.use { output ->
                ShaderCaptures.writeZip(this, output)
            } ?: error("the picker gave no file")
            toast(getString(R.string.setting_shaders_exported, written))
        } catch (e: Exception) {
            toast(getString(R.string.setting_shaders_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val root = FrameLayout(this)
        root.setBackgroundColor(COLOR_INK)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(40))
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        window.insetsController?.hide(WindowInsets.Type.statusBars())
        build()
    }

    private fun build() {
        content.removeAllViews()
        header(getString(R.string.settings_title))

        section(R.string.settings_video)
        choice(
            R.string.setting_resolution, R.string.setting_resolution_hint,
            listOf("480p", "720p", "1080p", "1440p", "2160p"), prefs.resolution,
        ) { prefs.resolution = it }
        choice(
            R.string.setting_anisotropy, R.string.setting_anisotropy_hint,
            listOf("Game", "2x", "4x", "8x", "16x"), prefs.anisotropy,
        ) { prefs.anisotropy = it }
        toggle(R.string.setting_vsync, R.string.setting_vsync_hint, prefs.vsync) { prefs.vsync = it }
        toggle(R.string.setting_relaxed_vsync, R.string.setting_relaxed_vsync_hint, prefs.relaxedVsync) {
            prefs.relaxedVsync = it
        }

        section(R.string.settings_speed)
        choice(
            R.string.setting_refresh, R.string.setting_refresh_hint,
            listOf("auto", "60", "90", "120", "144"), prefs.refreshRate,
        ) { prefs.refreshRate = it }
        toggle(R.string.setting_sustained, R.string.setting_sustained_hint, prefs.sustainedPerformance) {
            prefs.sustainedPerformance = it
        }
        toggle(R.string.setting_game_mode, R.string.setting_game_mode_hint, prefs.gameMode) { prefs.gameMode = it }

        section(R.string.settings_textures)
        choice(
            R.string.setting_texture_format, R.string.setting_texture_format_hint,
            listOf("etc2", "rgba8"), prefs.textureFormat,
        ) { prefs.textureFormat = it }
        choice(
            R.string.setting_etc2_effort, R.string.setting_etc2_effort_hint,
            listOf("0", "1"), prefs.etc2Effort.toString(),
        ) { prefs.etc2Effort = it.toInt() }
        toggle(R.string.setting_texture_cache, R.string.setting_texture_cache_hint, prefs.textureCache) {
            prefs.textureCache = it
        }

        section(R.string.settings_touch)
        toggle(R.string.setting_overlay, R.string.setting_overlay_hint, prefs.overlayEnabled) {
            prefs.overlayEnabled = it
        }
        toggle(R.string.setting_hide_gamepad, R.string.setting_hide_gamepad_hint, prefs.hideWithGamepad) {
            prefs.hideWithGamepad = it
        }
        toggle(R.string.setting_haptics, R.string.setting_haptics_hint, prefs.haptics) { prefs.haptics = it }
        slider(R.string.setting_overlay_alpha, (prefs.overlayAlpha * 100).toInt()) {
            prefs.overlayAlpha = it / 100f
        }
        button(R.string.menu_controls) { startActivity(android.content.Intent(this, ControlsActivity::class.java)) }
        button(R.string.setting_reset_layout) {
            AlertDialog.Builder(this)
                .setMessage(R.string.setting_reset_layout_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok) { _, _ ->
                    LayoutStore(this).resetAll()
                    toast(getString(R.string.setting_layout_reset))
                }
                .show()
        }

        section(R.string.settings_shaders)
        shaders()

        section(R.string.settings_storage)
        button(R.string.setting_delete_data) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.setting_delete_data_confirm, GameData.formatBytes(GameData.summary(this)?.bytes ?: 0)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    GameData.deleteAll(this)
                    toast(getString(R.string.setting_data_deleted))
                    build()
                }
                .show()
        }

        footer(getString(R.string.settings_footer))
    }

    /**
     * The shaders this device compiled while playing: the game makes some of its shaders at run time, so a build made
     * from its files alone lacks them. Exporting them here is what lets the next build include the whole set.
     */
    private fun shaders() {
        val captures = ShaderCaptures.count(this)
        row(
            R.string.setting_shaders,
            R.string.setting_shaders_hint,
            valueLabel(
                if (captures > 0) getString(R.string.setting_shaders_count, captures)
                else getString(R.string.setting_shaders_none),
            ),
        )
        if (captures > 0) {
            button(R.string.setting_shaders_export) {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    putExtra(Intent.EXTRA_TITLE, "shank1-shaders-${captures}.zip")
                }
                try {
                    startActivityForResult(intent, REQUEST_EXPORT)
                } catch (e: Exception) {
                    toast(getString(R.string.setting_shaders_failed, e.message ?: "no file picker"))
                }
            }
        }
    }

    // --- Small widgets, built in code so the app needs no AndroidX and no XML per row -----------------------------

    private fun header(title: String) {
        content.addView(
            TextView(this).apply {
                text = title
                setTextColor(COLOR_BONE)
                textSize = 30f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(18))
            },
        )
    }

    private fun section(id: Int) {
        content.addView(
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(18) }
                setBackgroundColor(Color.argb(60, 233, 226, 213))
            },
        )
        content.addView(
            TextView(this).apply {
                text = getString(id).uppercase()
                setTextColor(COLOR_BLOOD)
                textSize = 13f
                letterSpacing = 0.12f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                setPadding(0, dp(14), 0, dp(6))
            },
        )
    }

    private fun row(titleRes: Int, hintRes: Int?, valueView: View, onTap: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(
            TextView(this).apply {
                text = getString(titleRes)
                setTextColor(COLOR_BONE)
                textSize = 17f
            },
        )
        if (hintRes != null) {
            labels.addView(
                TextView(this).apply {
                    text = getString(hintRes)
                    setTextColor(COLOR_ASH)
                    textSize = 12.5f
                },
            )
        }
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(valueView, LinearLayout.LayoutParams(-2, -2))
        if (onTap != null) {
            row.isClickable = true
            row.setOnClickListener { onTap() }
        }
        content.addView(row)
    }

    private fun valueLabel(value: String) = TextView(this).apply {
        text = value
        setTextColor(COLOR_ASH)
        textSize = 16f
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        setPadding(dp(12), 0, dp(4), 0)
    }

    private fun choice(titleRes: Int, hintRes: Int, values: List<String>, current: String, onPick: (String) -> Unit) {
        val label = valueLabel(current)
        row(titleRes, hintRes, label) {
            val checked = values.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setSingleChoiceItems(values.toTypedArray(), checked) { dialog, which ->
                    val value = values[which]
                    label.text = value
                    onPick(value)
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun toggle(titleRes: Int, hintRes: Int, current: Boolean, onChange: (Boolean) -> Unit) {
        val check = CheckBox(this).apply {
            isChecked = current
            buttonTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(COLOR_BLOOD, COLOR_ASH),
            )
        }
        check.setOnCheckedChangeListener { _, value -> onChange(value) }
        row(titleRes, hintRes, check) { check.isChecked = !check.isChecked }
    }

    private fun slider(titleRes: Int, currentPercent: Int, onChange: (Int) -> Unit) {
        val bar = SeekBar(this).apply {
            max = 100
            progress = currentPercent
            progressTintList = android.content.res.ColorStateList.valueOf(COLOR_BLOOD)
            thumbTintList = android.content.res.ColorStateList.valueOf(COLOR_BONE)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        row(titleRes, null, bar)
        (bar.layoutParams as? LinearLayout.LayoutParams)?.width = dp(170)
    }

    private fun button(titleRes: Int, onTap: () -> Unit) {
        content.addView(
            Button(this).apply {
                text = getString(titleRes)
                setTextColor(COLOR_BONE)
                textSize = 16f
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.argb(200, 18, 21, 26))
                    setStroke(dp(1), Color.argb(80, 233, 226, 213))
                    cornerRadius = dp(8)
                }
                setOnClickListener { onTap() }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
            },
        )
    }

    private fun footer(text: String) {
        content.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(COLOR_ASH)
                textSize = 12.5f
                setPadding(0, dp(28), 0, 0)
            },
        )
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_EXPORT = 4101

        private val COLOR_INK = Color.parseColor("#07080A")
        private val COLOR_BONE = Color.parseColor("#E9E2D5")
        private val COLOR_ASH = Color.parseColor("#8C8778")
        private val COLOR_BLOOD = Color.parseColor("#B3121C")
    }
}
