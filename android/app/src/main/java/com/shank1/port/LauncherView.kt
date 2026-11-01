package com.shank1.port

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * The launcher's screen, drawn rather than assembled from views: it is the first thing the player sees, so it is where
 * the port looks like the game and not like an installer.
 *
 * Four rows (play, import, controls, settings; about is the title's tap target), a status line for the game data, and
 * a progress bar while an import runs. Everything is one view, so there is nothing to inflate and nothing to keep in
 * sync with the state: the activity calls [setState] and the view redraws.
 */
class LauncherView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Item { PLAY, IMPORT, CONTROLS, SETTINGS, ABOUT }

    var onItem: ((Item) -> Unit)? = null

    class State(
        val installed: Boolean,
        val status: String,
        val busy: Boolean = false,
        val progress: Int = 0,
        val version: String = "",
    )

    private var state = State(installed = false, status = "")

    private val rows = LinkedHashMap<Item, RectF>()
    private val titleRect = RectF()
    private var pressed: Item? = null

    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private val ink = Color.parseColor("#07080A")
    private val ink2 = Color.parseColor("#12151A")
    private val blood = Color.parseColor("#B3121C")
    private val bloodDim = Color.parseColor("#6C0A11")
    private val bone = Color.parseColor("#E9E2D5")
    private val ash = Color.parseColor("#8C8778")

    private val serif = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val light = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

    private fun dp(value: Float) = value * resources.displayMetrics.density

    init {
        isClickable = true
        setWillNotDraw(false)
    }

    fun setState(state: State) {
        this.state = state
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        background.shader = LinearGradient(
            0f, 0f, w * 0.35f, h.toFloat(),
            intArrayOf(bloodDim, ink, ink), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        layoutRows(w, h)
    }

    private fun layoutRows(w: Int, h: Int) {
        rows.clear()
        val rowWidth = min(w * 0.86f, dp(520f))
        val left = (w - rowWidth) / 2f
        val top = h * 0.34f
        val rowHeight = min(dp(76f), h * 0.11f)
        val gap = dp(14f)
        val items = if (state.installed) {
            listOf(Item.PLAY, Item.IMPORT, Item.CONTROLS, Item.SETTINGS)
        } else {
            listOf(Item.IMPORT, Item.CONTROLS, Item.SETTINGS)
        }
        var y = top
        for (item in items) {
            rows[item] = RectF(left, y, left + rowWidth, y + rowHeight)
            y += rowHeight + gap
        }
        titleRect.set(left, top * 0.30f, left + rowWidth, top * 0.30f + rowHeight * 1.6f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        drawSlashes(canvas)
        drawTitle(canvas)
        for ((item, rect) in rows) drawRow(canvas, item, rect)
        drawStatus(canvas)
    }

    private fun drawSlashes(canvas: Canvas) {
        fill.color = Color.argb(26, 179, 18, 28)
        path.reset()
        path.moveTo(width * 0.62f, 0f)
        path.lineTo(width * 0.78f, 0f)
        path.lineTo(width * 0.52f, height.toFloat())
        path.lineTo(width * 0.36f, height.toFloat())
        path.close()
        canvas.drawPath(path, fill)
        fill.color = Color.argb(14, 233, 226, 213)
        path.reset()
        path.moveTo(width * 0.80f, 0f)
        path.lineTo(width * 0.83f, 0f)
        path.lineTo(width * 0.57f, height.toFloat())
        path.lineTo(width * 0.54f, height.toFloat())
        path.close()
        canvas.drawPath(path, fill)
    }

    private fun drawTitle(canvas: Canvas) {
        val cx = titleRect.centerX()
        text.typeface = serif
        text.textAlign = Paint.Align.CENTER
        text.color = bone
        text.textSize = min(titleRect.width() * 0.30f, dp(84f))
        text.letterSpacing = 0.14f
        canvas.drawText(context.getString(R.string.launcher_title), cx, titleRect.top + text.textSize * 0.95f, text)
        text.letterSpacing = 0f
        text.typeface = light
        text.color = ash
        text.textSize = min(titleRect.width() * 0.048f, dp(17f))
        canvas.drawText(context.getString(R.string.launcher_subtitle), cx, titleRect.top + text.textSize * 3.15f, text)
        if (state.version.isNotEmpty()) {
            text.textSize = min(titleRect.width() * 0.040f, dp(14f))
            text.color = Color.argb(160, 140, 135, 120)
            canvas.drawText(
                context.getString(R.string.launcher_version, state.version), cx,
                titleRect.top + text.textSize * 4.4f, text,
            )
        }
    }

    private fun drawRow(canvas: Canvas, item: Item, rect: RectF) {
        val enabled = item != Item.PLAY || state.installed
        val isPressed = pressed == item
        val radius = rect.height() * 0.16f

        fill.color = if (isPressed) Color.argb(235, 179, 18, 28) else Color.argb(190, 18, 21, 26)
        canvas.drawRoundRect(rect, radius, radius, fill)
        stroke.color = if (isPressed) bone else Color.argb(70, 233, 226, 213)
        stroke.strokeWidth = dp(1.2f)
        canvas.drawRoundRect(rect, radius, radius, stroke)
        // The accent bar on the left: the game's own blood red.
        fill.color = if (enabled) blood else Color.argb(90, 108, 10, 17)
        canvas.drawRoundRect(
            RectF(rect.left + dp(10f), rect.top + rect.height() * 0.22f, rect.left + dp(14f),
                rect.bottom - rect.height() * 0.22f),
            dp(2f), dp(2f), fill,
        )

        text.textAlign = Paint.Align.LEFT
        text.typeface = serif
        text.color = if (enabled) bone else Color.argb(120, 233, 226, 213)
        text.textSize = rect.height() * 0.30f
        canvas.drawText(context.getString(titleOf(item)), rect.left + dp(30f), rect.centerY() - rect.height() * 0.04f, text)
        text.typeface = light
        text.color = if (enabled) ash else Color.argb(90, 140, 135, 120)
        text.textSize = rect.height() * 0.17f
        canvas.drawText(
            context.getString(subtitleOf(item)), rect.left + dp(30f),
            rect.centerY() + rect.height() * 0.25f, text,
        )
        // A chevron on the right.
        stroke.color = if (enabled) Color.argb(190, 233, 226, 213) else Color.argb(60, 233, 226, 213)
        stroke.strokeWidth = dp(2f)
        val x = rect.right - dp(26f)
        val y = rect.centerY()
        canvas.drawLine(x - dp(5f), y - dp(6f), x, y, stroke)
        canvas.drawLine(x, y, x - dp(5f), y + dp(6f), stroke)
    }

    private fun drawStatus(canvas: Canvas) {
        val margin = dp(22f)
        val bottom = height - margin
        if (state.busy) {
            val bar = RectF(margin * 2, bottom - dp(46f), width - margin * 2, bottom - dp(38f))
            fill.color = Color.argb(80, 233, 226, 213)
            canvas.drawRoundRect(bar, bar.height() / 2f, bar.height() / 2f, fill)
            fill.color = blood
            val filled = RectF(bar.left, bar.top, bar.left + bar.width() * (state.progress / 100f), bar.bottom)
            canvas.drawRoundRect(filled, bar.height() / 2f, bar.height() / 2f, fill)
        }
        text.typeface = light
        text.textAlign = Paint.Align.LEFT
        text.textSize = min(width * 0.028f, dp(15f))
        text.color = if (state.installed) ash else Color.argb(210, 179, 18, 28)
        drawWrapped(canvas, state.status, margin * 2, bottom - if (state.busy) dp(60f) else dp(26f), width - margin * 4, text)
    }

    private fun drawWrapped(canvas: Canvas, value: String, x: Float, y: Float, maxWidth: Float, paint: Paint) {
        var line = ""
        var lineY = y
        for (word in value.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                canvas.drawText(line, x, lineY, paint)
                lineY += paint.textSize * 1.25f
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) canvas.drawText(line, x, lineY, paint)
    }

    private fun titleOf(item: Item) = when (item) {
        Item.PLAY -> R.string.menu_play
        Item.IMPORT -> R.string.menu_import
        Item.CONTROLS -> R.string.menu_controls
        Item.SETTINGS -> R.string.menu_settings
        Item.ABOUT -> R.string.menu_about
    }

    private fun subtitleOf(item: Item) = when (item) {
        Item.PLAY -> R.string.sub_play
        Item.IMPORT -> R.string.sub_import
        Item.CONTROLS -> R.string.sub_controls
        Item.SETTINGS -> R.string.sub_settings
        Item.ABOUT -> R.string.sub_about
    }

    private fun itemAt(x: Float, y: Float): Item? {
        for ((item, rect) in rows) if (rect.contains(x, y)) return item
        if (titleRect.contains(x, y)) return Item.ABOUT
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (state.busy) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = itemAt(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val item = itemAt(event.x, event.y)
                if (item != pressed) {
                    pressed = item
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val item = pressed ?: return true
                pressed = null
                invalidate()
                if (itemAt(event.x, event.y) == item) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    onItem?.invoke(item)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                invalidate()
            }
        }
        return true
    }
}
