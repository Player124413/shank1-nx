package com.shank1.port

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.shank1.port.input.Element
import com.shank1.port.input.LayoutStore
import com.shank1.port.input.TouchLayout
import com.shank1.port.input.TouchControlsView

/**
 * The controls editor: the overlay in edit mode, with the tools under the thumbs instead of in a list.
 *
 * Drag an element to move it, pinch it with two fingers to resize it, tap it to select it; the bar along the bottom
 * sizes it exactly, fades it, renames what it does, adds and removes elements, and saves. Everything is stored per
 * orientation (filesDir/touch/landscape.json and portrait.json) and the game uses whichever matches the screen.
 */
class ControlsActivity : Activity(), TouchControlsView.Listener {

    private lateinit var prefs: Prefs
    private lateinit var store: LayoutStore
    private lateinit var view: TouchControlsView
    private lateinit var bar: LinearLayout
    private lateinit var info: TextView
    private lateinit var statusText: TextView
    private var landscape = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = LayoutStore(this)
        landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        val root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#1B1013"), Color.parseColor("#07080A")),
        )

        view = TouchControlsView(this)
        view.showGrid = true
        view.layout = store.load(landscape)
        view.editMode = true
        view.controlsVisible = true
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }
        info = TextView(this).apply {
            setTextColor(COLOR_BONE)
            textSize = 14f
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        column.addView(info)

        val scroll = HorizontalScrollView(this)
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        scroll.addView(bar)
        column.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(14), dp(10))
        }
        footer.addView(
            smallButton(getString(R.string.controls_add)) { addElement() },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) },
        )
        footer.addView(
            smallButton(getString(R.string.controls_reset)) { confirmReset() },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) },
        )
        footer.addView(smallButton(getString(R.string.controls_save)) { saveAndFinish() })
        column.addView(footer)

        statusText = TextView(this).apply {
            setTextColor(COLOR_ASH)
            textSize = 12f
            setPadding(dp(18), 0, dp(18), dp(6))
        }
        column.addView(statusText)

        root.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            view.setSafeArea(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // The listener goes on last: selecting nothing calls back into the bar, which must exist by then.
        view.listener = this
        buildBar()
        onSelectionChanged(null)
        statusText.text = getString(R.string.controls_hint)
    }

    override fun onPause() {
        super.onPause()
        view.releaseAll()
        save()
    }

    private fun buildBar() {
        bar.removeAllViews()
        for (element in view.layout.elements) {
            val chip = TextView(this).apply {
                text = "${element.glyph.ifEmpty { element.output } }"
                setTextColor(if (view.selected?.id == element.id) COLOR_BONE else COLOR_ASH)
                textSize = 15f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply {
                    setColor(
                        if (view.selected?.id == element.id) Color.argb(230, 179, 18, 28)
                        else Color.argb(200, 18, 21, 26),
                    )
                    setStroke(dp(1), Color.argb(90, 233, 226, 213))
                    cornerRadius = dp(8)
                }
                setOnClickListener { view.select(element) }
                setOnLongClickListener { showElementMenu(element); true }
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
            }
            bar.addView(chip)
        }
    }

    private fun refreshBar() {
        buildBar()
        view.selected?.let { info.text = describe(it) }
    }

    private fun describe(element: Element): String =
        "${element.id}  ·  ${element.output}  ·  ${elementSizePercent(element)}%  ·  α${(element.alpha * 100).toInt()}%"

    private fun elementSizePercent(element: Element): Int =
        ((element.r * 100) / 0.22f).toInt().coerceIn(1, 100)

    private fun addElement() {
        val kinds = arrayOf(
            getString(R.string.kind_button), getString(R.string.kind_trigger),
            getString(R.string.kind_stick), getString(R.string.kind_dpad),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.controls_add)
            .setItems(kinds) { _, which ->
                val outputs = when (which) {
                    2 -> arrayOf("ls", "rs")
                    3 -> arrayOf("dpad")
                    1 -> arrayOf("lt", "rt")
                    else -> arrayOf("a", "b", "x", "y", "lb", "rb", "start", "back", "l3", "r3")
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.controls_add)
                    .setItems(outputs.map { getString(R.string.control_output, it) }.toTypedArray()) { _, index ->
                        val kind = when (which) {
                            1 -> Element.Kind.TRIGGER
                            2 -> Element.Kind.STICK
                            3 -> Element.Kind.DPAD
                            else -> Element.Kind.BUTTON
                        }
                        val element = view.layout.add(kind, outputs[index])
                        view.select(element)
                        refreshBar()
                    }
                    .show()
            }
            .show()
    }

    private fun showElementMenu(element: Element) {
        val actions = arrayOf(
            getString(R.string.controls_copy),
            getString(if (element.visible) R.string.controls_hide else R.string.controls_show),
            getString(R.string.controls_delete),
        )
        AlertDialog.Builder(this)
            .setTitle(element.id)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        val copy = element.duplicate("_" + (view.layout.elements.size + 1))
                        copy.x = (element.x + 0.06f).coerceAtMost(0.97f)
                        copy.y = (element.y + 0.06f).coerceAtMost(0.97f)
                        view.layout.elements.add(copy)
                        view.select(copy)
                    }
                    1 -> element.visible = !element.visible
                    2 -> {
                        view.layout.remove(element)
                        view.select(null)
                    }
                }
                refreshBar()
            }
            .show()
    }

    private fun confirmReset() = AlertDialog.Builder(this)
        .setMessage(R.string.setting_reset_layout_confirm)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.ok) { _, _ ->
            view.layout = if (landscape) TouchLayout.defaultLandscape() else TouchLayout.defaultPortrait()
            view.select(null)
            save()
            refreshBar()
            toast(getString(R.string.setting_layout_reset))
        }
        .show()

    private fun save() {
        val layout = view.layout
        val name = if (landscape) "landscape" else "portrait"
        store.save(layout, name)
        prefs.layoutProfile = name
    }

    private fun saveAndFinish() {
        save()
        finish()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun smallButton(text: String, onTap: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(COLOR_BONE)
        textSize = 14f
        isAllCaps = false
        background = GradientDrawable().apply {
            setColor(Color.argb(210, 18, 21, 26))
            setStroke(dp(1), Color.argb(90, 233, 226, 213))
            cornerRadius = dp(8)
        }
        setOnClickListener { onTap() }
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(16), dp(8), dp(16), dp(8))
    }

    // --- TouchControlsView.Listener -------------------------------------------------------------------------------

    override fun onSelectionChanged(element: Element?) {
        refreshBar()
        if (element == null) {
            info.text = getString(R.string.controls_nothing_selected)
            return
        }
        val extra = arrayListOf<View>()
        extra.add(smallButton(getString(R.string.controls_smaller)) {
            view.scaleSelected(1f / 1.12f)
            refreshBar()
        })
        extra.add(smallButton(getString(R.string.controls_bigger)) {
            view.scaleSelected(1.12f)
            refreshBar()
        })
        for (button in extra) {
            button.layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = dp(8)
                rightMargin = dp(8)
            }
            bar.addView(button)
        }
        val slider = SeekBar(this).apply {
            max = 100
            progress = (element.alpha * 100).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(COLOR_BLOOD)
            thumbTintList = android.content.res.ColorStateList.valueOf(COLOR_BONE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        view.setSelectedAlpha(progress / 100f)
                        info.text = describe(element)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        bar.addView(slider, LinearLayout.LayoutParams(dp(150), -2).apply { leftMargin = dp(10) })
        if (element.kind == Element.Kind.STICK) {
            bar.addView(smallButton(getString(R.string.controls_follow)) {
                element.follow = !element.follow
                toast(getString(if (element.follow) R.string.controls_follow_on else R.string.controls_follow_off))
            })
        }
    }

    override fun onElementChanged(element: Element) {
        // Nothing per-drag: the bar's numbers are refreshed when the drag ends.
    }

    override fun onEditFinished() {
        refreshBar()
    }

    companion object {
        private val COLOR_BONE = Color.parseColor("#E9E2D5")
        private val COLOR_ASH = Color.parseColor("#8C8778")
        private val COLOR_BLOOD = Color.parseColor("#B3121C")
    }
}
