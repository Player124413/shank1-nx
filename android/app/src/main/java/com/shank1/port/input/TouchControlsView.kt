package com.shank1.port.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The touch controls: a gamepad drawn on the screen, with every element the player can move, resize and restyle.
 *
 * Two modes:
 *  - **play**: pointers drive [VirtualPad]. Each pointer belongs to the element it went down on; a stick keeps its
 *    pointer until it lifts, so a thumb that slides off a button releases it and a thumb that slides out of a stick's
 *    ring keeps steering.
 *  - **edit** ([editMode] = true): pointers drag elements instead. A second finger on the selected element pinches it
 *    bigger or smaller. The editor bar (ControlsActivity) drives the rest.
 *
 * The overlay draws nothing of its own on the game's picture outside the controls, and only invalidates when something
 * changed, so it costs nothing while the player is not touching the screen: on a phone the difference between a redraw
 * per frame and none is the battery.
 */
class TouchControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** What the editor calls when the selection changes, and when an element was moved or sized. */
    interface Listener {
        fun onSelectionChanged(element: Element?)
        fun onElementChanged(element: Element)
        fun onEditFinished()
    }

    var listener: Listener? = null

    var layout: TouchLayout = TouchLayout.defaultLandscape()
        set(value) {
            field = value
            releaseAll()
            selected = null
            invalidate()
        }

    var editMode = false
        set(value) {
            field = value
            releaseAll()
            if (!value) {
                commitEdit()
                selected = null
            }
            invalidate()
        }

    /** The whole overlay can be hidden mid-game (the gear button or a hardware pad). */
    var controlsVisible = true
        set(value) {
            field = value
            if (!value) releaseAll()
            invalidate()
        }

    var showGrid = false
        set(value) {
            field = value
            invalidate()
        }

    var selected: Element? = null
        private set(value) {
            field = value
            listener?.onSelectionChanged(value)
            invalidate()
        }

    private class Pointer(
        var element: Element,
        val kind: Element.Kind,
        /** Where the finger went down, in view pixels. */
        var downX: Float = 0f,
        var downY: Float = 0f,
        /** The stick's centre while this pointer holds it (follows the thumb when the stick follows). */
        var centerX: Float = 0f,
        var centerY: Float = 0f,
        var x: Float = 0f,
        var y: Float = 0f,
        /** Edit mode: the element's place when the drag started. */
        var startX: Float = 0f,
        var startY: Float = 0f,
        var startR: Float = 0f,
        var pinching: Boolean = false,
        var pinchStartDistance: Float = 0f,
    )

    private val pointers = HashMap<Int, Pointer>()

    // Drawing: one paint set reused, nothing allocated per frame.
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val captionText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val circle = RectF()
    private val path = Path()

    private val bone = Color.parseColor("#E9E2D5")
    private val blood = Color.parseColor("#B3121C")
    private val ink = Color.parseColor("#07080A")

    /** The safe drawing area in view pixels: the overlay is laid out inside it (cutouts, system bars). */
    private var areaLeft = 0f
    private var areaTop = 0f
    private var areaRight = 0f
    private var areaBottom = 0f

    private val vibrator: Vibrator? by lazy {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        isFocusable = false
        isClickable = true
        setWillNotDraw(false)
    }

    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0

    /** Hands the overlay the window insets so its elements stay off a notch and the gesture bar. Safe to call before
     *  the view is measured: the area is computed again when it is. */
    fun setSafeArea(left: Int, top: Int, right: Int, bottom: Int) {
        insetLeft = left
        insetTop = top
        insetRight = right
        insetBottom = bottom
        updateArea()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateArea()
    }

    private fun updateArea(notify: Boolean = true) {
        if (width == 0 || height == 0) return
        areaLeft = insetLeft.toFloat()
        areaTop = insetTop.toFloat()
        areaRight = (width - insetRight).toFloat().coerceAtLeast(areaLeft + 1f)
        areaBottom = (height - insetBottom).toFloat().coerceAtLeast(areaTop + 1f)
        if (notify) invalidate()
    }

    private fun areaWidth() = (areaRight - areaLeft).coerceAtLeast(1f)
    private fun areaHeight() = (areaBottom - areaTop).coerceAtLeast(1f)

    private fun centerX(element: Element) = areaLeft + element.x * areaWidth()
    private fun centerY(element: Element) = areaTop + element.y * areaHeight()
    private fun radius(element: Element) = element.r * areaHeight()

    private fun dp(value: Float) = value * resources.displayMetrics.density

    // ---------------------------------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (!controlsVisible && !editMode) return
        updateArea(notify = false)
        if (areaRight <= areaLeft) return
        if (showGrid) drawGrid(canvas)
        for (element in layout.elements) {
            if (!element.visible && !editMode) continue
            when (element.kind) {
                Element.Kind.STICK -> drawStick(canvas, element)
                Element.Kind.TRIGGER -> drawTrigger(canvas, element)
                Element.Kind.DPAD -> drawDpad(canvas, element)
                Element.Kind.BUTTON -> drawButton(canvas, element)
            }
            if (editMode) drawEditMarks(canvas, element)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        stroke.color = Color.argb(28, 233, 226, 213)
        stroke.strokeWidth = dp(1f)
        val stepX = areaWidth() / 8f
        val stepY = areaHeight() / 8f
        var x = areaLeft + stepX
        while (x < areaRight) {
            canvas.drawLine(x, areaTop, x, areaBottom, stroke)
            x += stepX
        }
        var y = areaTop + stepY
        while (y < areaBottom) {
            canvas.drawLine(areaLeft, y, areaRight, y, stroke)
            y += stepY
        }
    }

    private fun drawStick(canvas: Canvas, element: Element) {
        val cx = centerX(element)
        val cy = centerY(element)
        val r = radius(element)
        val pointer = pointers.values.firstOrNull { it.element.id == element.id && it.kind == Element.Kind.STICK }
        val knobX = if (pointer != null && element.follow) pointer.centerX + (pointer.x - pointer.centerX) * 0.45f else cx
        val knobY = if (pointer != null && element.follow) pointer.centerY + (pointer.y - pointer.centerY) * 0.45f else cy

        fill.color = withAlpha(ink, element.alpha * 0.55f)
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = withAlpha(bone, if (pointer != null) element.alpha else element.alpha * 0.6f)
        stroke.strokeWidth = dp(2f)
        canvas.drawCircle(cx, cy, r, stroke)
        stroke.color = withAlpha(blood, element.alpha * 0.5f)
        stroke.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, r * element.deadzone, stroke)

        fill.color = withAlpha(bone, element.alpha * if (pointer != null) 0.95f else 0.7f)
        canvas.drawCircle(knobX, knobY, r * 0.34f, fill)
    }

    private fun drawButton(canvas: Canvas, element: Element) {
        val cx = centerX(element)
        val cy = centerY(element)
        val r = radius(element)
        val pressed = pointers.values.any { it.element.id == element.id }
        fill.color = withAlpha(if (pressed) blood else ink, element.alpha * if (pressed) 0.95f else 0.6f)
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = withAlpha(bone, element.alpha)
        stroke.strokeWidth = dp(2f)
        canvas.drawCircle(cx, cy, r, stroke)
        drawGlyph(canvas, element, cx, cy, r)
    }

    private fun drawDpad(canvas: Canvas, element: Element) {
        val cx = centerX(element)
        val cy = centerY(element)
        val r = radius(element)
        val pressed = pointers.values.firstOrNull { it.element.id == element.id }
        fill.color = withAlpha(ink, element.alpha * 0.5f)
        path.reset()
        path.addRoundRect(
            RectF(cx - r, cy - r * 0.36f, cx + r, cy + r * 0.36f), r * 0.2f, r * 0.2f, Path.Direction.CW,
        )
        canvas.drawPath(path, fill)
        path.reset()
        path.addRoundRect(
            RectF(cx - r * 0.36f, cy - r, cx + r * 0.36f, cy + r), r * 0.2f, r * 0.2f, Path.Direction.CW,
        )
        canvas.drawPath(path, fill)
        stroke.color = withAlpha(bone, element.alpha)
        stroke.strokeWidth = dp(1.5f)
        stroke.color = withAlpha(bone, element.alpha * 0.8f)
        canvas.drawCircle(cx, cy, r * 0.22f, fill.apply { color = withAlpha(bone, element.alpha * 0.35f) })
        if (pressed != null) {
            val dx = pressed.x - pressed.centerX
            val dy = pressed.y - pressed.centerY
            if (hypot(dx, dy) > r * 0.25f) {
                fill.color = withAlpha(blood, element.alpha * 0.8f)
                val angle = atan2(dy, dx)
                canvas.drawCircle(
                    cx + cos(angle) * r * 0.62f, cy + sin(angle) * r * 0.62f, r * 0.24f, fill,
                )
            }
        }
        drawGlyph(canvas, element, cx, cy - r * 1.18f, r * 0.30f)
    }

    private fun drawTrigger(canvas: Canvas, element: Element) {
        val cx = centerX(element)
        val cy = centerY(element)
        val halfW = element.r * areaHeight() * 0.5f
        val halfH = element.h * areaHeight()
        val pointer = pointers.values.firstOrNull { it.element.id == element.id }
        val pull = pointer?.let { ((it.y - it.centerY) / (halfH * 2f)).coerceIn(0f, 1f) } ?: 0f
        val box = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        fill.color = withAlpha(ink, element.alpha * 0.55f)
        path.reset()
        path.addRoundRect(box, halfW, halfW, Path.Direction.CW)
        canvas.drawPath(path, fill)
        if (pull > 0f) {
            fill.color = withAlpha(blood, element.alpha * 0.85f)
            path.reset()
            path.addRoundRect(
                RectF(cx - halfW, cy - halfH, cx + halfW, cy - halfH + halfH * 2f * pull),
                halfW, halfW, Path.Direction.CW,
            )
            canvas.drawPath(path, fill)
        }
        stroke.color = withAlpha(bone, element.alpha)
        stroke.strokeWidth = dp(2f)
        path.reset()
        path.addRoundRect(box, halfW, halfW, Path.Direction.CW)
        canvas.drawPath(path, stroke)
        text.color = withAlpha(bone, element.alpha)
        text.textSize = min(halfW * 1.1f, dp(18f))
        canvas.drawText(element.glyph, cx, cy + text.textSize * 0.35f, text)
    }

    private fun drawGlyph(canvas: Canvas, element: Element, cx: Float, cy: Float, r: Float) {
        if (element.glyph.isEmpty()) return
        text.color = withAlpha(bone, min(1f, element.alpha + 0.25f))
        text.textSize = r * 1.05f
        canvas.drawText(element.glyph, cx, cy + text.textSize * 0.34f, text)
        if (element.caption.isNotEmpty()) {
            captionText.color = withAlpha(bone, element.alpha * 0.8f)
            captionText.textSize = r * 0.42f
            canvas.drawText(element.caption, cx, cy + r * 1.55f, captionText)
        }
    }

    private fun drawEditMarks(canvas: Canvas, element: Element) {
        val cx = centerX(element)
        val cy = centerY(element)
        val r = if (element.kind == Element.Kind.TRIGGER) element.h * areaHeight() else radius(element)
        val isSelected = selected?.id == element.id
        stroke.color = withAlpha(if (isSelected) blood else bone, if (isSelected) 0.95f else 0.35f)
        stroke.strokeWidth = dp(if (isSelected) 2.5f else 1f)
        canvas.drawCircle(cx, cy, r, stroke)
        if (isSelected) {
            fill.color = withAlpha(blood, 0.9f)
            canvas.drawCircle(cx + r * 0.7f, cy - r * 0.7f, dp(6f), fill)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    // ---------------------------------------------------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------------------------------------------------

    private fun hitTest(x: Float, y: Float): Element? {
        // Reverse order: the last element added is on top.
        for (element in layout.elements.reversed()) {
            if (!element.visible) continue
            val cx = centerX(element)
            val cy = centerY(element)
            when (element.kind) {
                Element.Kind.TRIGGER -> {
                    val halfW = element.r * areaHeight() * 0.5f
                    val halfH = element.h * areaHeight()
                    if (abs(x - cx) <= halfW * 1.6f && abs(y - cy) <= halfH * 1.1f) return element
                }
                else -> {
                    val r = radius(element) * 1.15f
                    if (hypot(x - cx, y - cy) <= r) return element
                }
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!controlsVisible && !editMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                onPointerDown(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    onPointerMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                onPointerUp(event.getPointerId(index))
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        VirtualPad.setTouchPointers(pointers.size)
        return true
    }

    private fun onPointerDown(id: Int, x: Float, y: Float) {
        val element = hitTest(x, y) ?: return
        if (editMode) {
            selected = element
            val pointer = Pointer(
                element, element.kind, downX = x, downY = y,
                startX = element.x, startY = element.y, startR = element.r,
                centerX = centerX(element), centerY = centerY(element), x = x, y = y,
            )
            // A second finger on the same element pinches it (size in edit mode).
            val other = pointers.values.firstOrNull { it.element.id == element.id }
            if (other != null) {
                pointer.pinching = true
                other.pinching = true
                pointer.pinchStartDistance = hypot(x - other.x, y - other.y).coerceAtLeast(1f)
                other.pinchStartDistance = pointer.pinchStartDistance
                other.startR = element.r
            }
            pointers[id] = pointer
            invalidate()
            return
        }

        val pointer = Pointer(
            element, element.kind, downX = x, downY = y, x = x, y = y,
            centerX = centerX(element), centerY = centerY(element),
        )
        if (element.kind == Element.Kind.STICK && element.follow) {
            // The stick's centre jumps to the thumb, kept inside its own ring so a thumb that lands at the edge still
            // has room to steer the other way.
            val r = radius(element)
            val dx = (x - centerX(element)).coerceIn(-r * 0.6f, r * 0.6f)
            val dy = (y - centerY(element)).coerceIn(-r * 0.6f, r * 0.6f)
            pointer.centerX = centerX(element) + dx
            pointer.centerY = centerY(element) + dy
        }
        pointers[id] = pointer
        applyPointer(pointer)
        haptic(element)
        invalidate()
    }

    private fun onPointerMove(id: Int, x: Float, y: Float) {
        val pointer = pointers[id] ?: return
        pointer.x = x
        pointer.y = y
        if (editMode) {
            if (pointer.pinching) {
                val other = pointers.values.firstOrNull { it !== pointer && it.element.id == pointer.element.id }
                if (other != null) {
                    val distance = hypot(x - other.x, y - other.y)
                    val ratio = distance / pointer.pinchStartDistance
                    pointer.element.r = (pointer.startR * ratio).coerceIn(0.02f, 0.22f)
                    listener?.onElementChanged(pointer.element)
                }
            } else {
                val dx = (x - pointer.downX) / areaWidth()
                val dy = (y - pointer.downY) / areaHeight()
                pointer.element.x = (pointer.startX + dx).coerceIn(0.02f, 0.98f)
                pointer.element.y = (pointer.startY + dy).coerceIn(0.02f, 0.98f)
                listener?.onElementChanged(pointer.element)
            }
            invalidate()
            return
        }
        applyPointer(pointer)
        invalidate()
    }

    private fun onPointerUp(id: Int) {
        val pointer = pointers.remove(id) ?: return
        if (editMode) {
            listener?.onEditFinished()
            invalidate()
            return
        }
        when (pointer.kind) {
            Element.Kind.STICK -> VirtualPad.setStick(pointer.element.output, 0f, 0f)
            Element.Kind.TRIGGER -> VirtualPad.setTrigger(pointer.element.output, 0f)
            Element.Kind.DPAD -> {
                VirtualPad.setButton("up", false)
                VirtualPad.setButton("down", false)
                VirtualPad.setButton("left", false)
                VirtualPad.setButton("right", false)
            }
            Element.Kind.BUTTON -> VirtualPad.setButton(pointer.element.output, false)
        }
        invalidate()
    }

    /** Turns one pointer's position into the pad state for its element. */
    private fun applyPointer(pointer: Pointer) {
        val element = pointer.element
        when (pointer.kind) {
            Element.Kind.STICK -> {
                val r = radius(element).coerceAtLeast(1f)
                var dx = (pointer.x - pointer.centerX) / r
                var dy = (pointer.y - pointer.centerY) / r
                var magnitude = hypot(dx, dy)
                if (magnitude > 1f) {
                    dx /= magnitude
                    dy /= magnitude
                    magnitude = 1f
                }
                // Dead zone, then the response curve: finer control near the centre, full speed at the edge.
                val scaled = if (magnitude <= element.deadzone) {
                    0f
                } else {
                    val t = (magnitude - element.deadzone) / (1f - element.deadzone)
                    t.pow(element.curve)
                }
                if (magnitude > 0.0001f) {
                    dx = dx / magnitude * scaled
                    dy = dy / magnitude * scaled
                } else {
                    dx = 0f
                    dy = 0f
                }
                VirtualPad.setStick(element.output, dx, dy)
            }
            Element.Kind.TRIGGER -> {
                val halfH = (element.h * areaHeight()).coerceAtLeast(1f)
                val pull = ((pointer.y - pointer.centerY) / (halfH * 2f)).coerceIn(0f, 1f)
                VirtualPad.setTrigger(element.output, pull)
            }
            Element.Kind.DPAD -> {
                val r = radius(element).coerceAtLeast(1f)
                val dx = (pointer.x - pointer.centerX) / r
                val dy = (pointer.y - pointer.centerY) / r
                val threshold = 0.28f
                VirtualPad.setButton("left", dx < -threshold)
                VirtualPad.setButton("right", dx > threshold)
                VirtualPad.setButton("up", dy < -threshold)
                VirtualPad.setButton("down", dy > threshold)
            }
            Element.Kind.BUTTON -> VirtualPad.setButton(element.output, true)
        }
    }

    private fun haptic(element: Element) {
        if (!VirtualPad.haptics) return
        val vibrator = vibrator
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(10L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(10L)
            }
        } else {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun releaseAll() {
        if (pointers.isNotEmpty()) {
            pointers.clear()
            VirtualPad.setStick("ls", 0f, 0f)
            VirtualPad.setStick("rs", 0f, 0f)
            VirtualPad.clearButtons()
            VirtualPad.setTrigger("lt", 0f)
            VirtualPad.setTrigger("rt", 0f)
        }
        VirtualPad.setTouchPointers(0)
        invalidate()
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Editing
    // ---------------------------------------------------------------------------------------------------------------

    fun select(element: Element?) {
        selected = element
    }

    fun moveSelectedBy(dx: Float, dy: Float) {
        val element = selected ?: return
        element.x = (element.x + dx).coerceIn(0.02f, 0.98f)
        element.y = (element.y + dy).coerceIn(0.02f, 0.98f)
        listener?.onElementChanged(element)
        invalidate()
    }

    fun scaleSelected(factor: Float) {
        val element = selected ?: return
        element.r = (element.r * factor).coerceIn(0.02f, 0.22f)
        listener?.onElementChanged(element)
        invalidate()
    }

    fun setSelectedAlpha(alpha: Float) {
        val element = selected ?: return
        element.alpha = alpha.coerceIn(0.1f, 1f)
        listener?.onElementChanged(element)
        invalidate()
    }

    /** Ends an edit session: anything still held is released and the layout is what the editor saved. */
    private fun commitEdit() {
        pointers.clear()
        invalidate()
    }
}
